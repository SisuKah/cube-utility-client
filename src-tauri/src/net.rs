//! The download engine: an allow-list of hosts, parallel downloads, hashes checked while
//! streaming, and files written to a temp name and renamed into place.

use std::io::Read;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use reqwest::header::{ACCEPT, CONTENT_TYPE, ETAG, IF_NONE_MATCH};
use reqwest::{redirect, Client, StatusCode, Url};
use sha1::{Digest, Sha1};
use sha2::Sha512;
use tokio::io::AsyncWriteExt;
use tokio::sync::Semaphore;
use tokio::task::JoinSet;

use crate::config;
use crate::error::{Error, Result};

/// The only hosts the launcher downloads from, plus the pack server. Every redirect is checked
/// against them too.
const ALLOWED_HOSTS: &[&str] = &[
    // Minecraft, its libraries and Java
    "piston-meta.mojang.com",
    "piston-data.mojang.com",
    "launchermeta.mojang.com",
    "launcher.mojang.com",
    "libraries.minecraft.net",
    "resources.download.minecraft.net",
    // Fabric
    "meta.fabricmc.net",
    "maven.fabricmc.net",
    // Pack mods: Modrinth, and my jars as GitHub release assets (which redirect to these)
    "cdn.modrinth.com",
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    // Sign-in: Microsoft, Xbox Live and Minecraft, plus the player's skin
    "login.microsoftonline.com",
    "user.auth.xboxlive.com",
    "xsts.auth.xboxlive.com",
    "api.minecraftservices.com",
    "textures.minecraft.net",
];

/// How long a sign-in or other API request may take.
const API_TIMEOUT: Duration = Duration::from_secs(30);

pub fn is_allowed(url: &Url) -> bool {
    let Some(host) = url.host_str() else { return false };
    let listed = url.scheme() == "https" && ALLOWED_HOSTS.contains(&host);
    let pack_server = pack_origin().is_some_and(|origin| {
        origin.scheme() == url.scheme() && origin.host_str() == Some(host) && origin.port_or_known_default() == url.port_or_known_default()
    });
    // Debug builds also take a pack server on this PC (`npm run pack:serve`).
    let dev_server = cfg!(debug_assertions) && url.scheme() == "http" && matches!(host, "127.0.0.1" | "localhost");
    listed || pack_server || dev_server
}

pub fn is_allowed_str(url: &str) -> bool {
    Url::parse(url).is_ok_and(|url| is_allowed(&url))
}

/// The pack server, from config. Release builds only accept it over HTTPS.
fn pack_origin() -> Option<&'static Url> {
    static ORIGIN: OnceLock<Option<Url>> = OnceLock::new();
    ORIGIN
        .get_or_init(|| {
            config::pack_base_url()
                .and_then(|base| Url::parse(&base).ok())
                .filter(|url| url.scheme() == "https" || cfg!(debug_assertions))
        })
        .as_ref()
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Hash {
    Sha1(String),
    Sha512(String),
}

/// An API's answer, whatever its status: sign-in has to read the bodies of 401s and 403s.
pub struct ApiResponse {
    pub status: StatusCode,
    pub body: Vec<u8>,
}

/// The answer to a request for a small file that may not have changed.
pub enum Fetched {
    NotModified,
    NotFound,
    Body { bytes: Vec<u8>, etag: Option<String> },
}

/// One file to fetch.
#[derive(Debug, Clone)]
pub struct Download {
    pub url: String,
    pub dest: PathBuf,
    pub hash: Option<Hash>,
    pub size: Option<u64>,
    /// What the UI shows while this file downloads.
    pub label: String,
}

impl Download {
    /// A quick check that needs no hashing: the file exists and has the expected size.
    /// Every file is hash-checked when it's downloaded.
    pub fn is_present(&self) -> bool {
        match std::fs::metadata(&self.dest) {
            Ok(meta) => meta.is_file() && self.size.map_or(meta.len() > 0, |size| size == meta.len()),
            Err(_) => false,
        }
    }

    /// The full check: the file's size and hash match. Slow; Repair uses it.
    pub fn verify(&self) -> bool {
        let Ok(file) = std::fs::File::open(&self.dest) else { return false };
        let mut reader = std::io::BufReader::with_capacity(1 << 16, file);
        let mut hasher = Hasher::for_hash(self.hash.as_ref());
        let mut buffer = vec![0u8; 1 << 16];
        let mut size = 0u64;
        loop {
            match reader.read(&mut buffer) {
                Ok(0) => break,
                Ok(n) => {
                    hasher.update(&buffer[..n]);
                    size += n as u64;
                }
                Err(_) => return false,
            }
        }
        self.size.is_none_or(|expected| expected == size) && hasher.matches(self.hash.as_ref())
    }
}

/// Counters the UI reads while a batch downloads.
#[derive(Debug, Default)]
pub struct Progress {
    pub done_bytes: AtomicU64,
    pub total_bytes: AtomicU64,
    current: Mutex<String>,
}

impl Progress {
    pub fn current(&self) -> String {
        self.current.lock().map(|c| c.clone()).unwrap_or_default()
    }

    fn set_current(&self, label: &str) {
        if let Ok(mut current) = self.current.lock() {
            if *current != label {
                *current = label.to_string();
            }
        }
    }
}

#[derive(Clone)]
pub struct Net {
    client: Client,
}

impl Net {
    pub fn new() -> Result<Self> {
        let policy = redirect::Policy::custom(|attempt| {
            if attempt.previous().len() >= 5 {
                attempt.error("too many redirects")
            } else if is_allowed(attempt.url()) {
                attempt.follow()
            } else {
                attempt.error("redirected to a host that isn't allowed")
            }
        });
        let client = Client::builder()
            .user_agent(concat!("CubeUtilityClient/", env!("CARGO_PKG_VERSION")))
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(600))
            .redirect(policy)
            .build()?;
        Ok(Self { client })
    }

    fn checked(url: &str) -> Result<Url> {
        let parsed = Url::parse(url).map_err(|_| Error::Message(format!("not a valid address: {url}")))?;
        if is_allowed(&parsed) {
            Ok(parsed)
        } else {
            Err(Error::HostNotAllowed(parsed.host_str().unwrap_or(url).to_string()))
        }
    }

    /// Fetches a small file into memory, optionally checking its SHA-1.
    pub async fn get_bytes(&self, url: &str, sha1: Option<&str>) -> Result<Vec<u8>> {
        let response = self.client.get(Self::checked(url)?).send().await?.error_for_status()?;
        let bytes = response.bytes().await?.to_vec();
        if let Some(expected) = sha1 {
            if !hex(&Sha1::digest(&bytes)).eq_ignore_ascii_case(expected) {
                return Err(Error::Checksum(url.to_string()));
            }
        }
        Ok(bytes)
    }

    pub async fn get_text(&self, url: &str) -> Result<String> {
        Ok(self.client.get(Self::checked(url)?).send().await?.error_for_status()?.text().await?)
    }

    /// POSTs a form (application/x-www-form-urlencoded).
    pub async fn post_form(&self, url: &str, fields: &[(&str, &str)]) -> Result<ApiResponse> {
        let request = self.client.post(Self::checked(url)?).header(CONTENT_TYPE, "application/x-www-form-urlencoded").body(form_encode(fields));
        Self::api(request).await
    }

    /// POSTs JSON, with a bearer token when there is one.
    pub async fn post_json(&self, url: &str, body: &serde_json::Value, bearer: Option<&str>) -> Result<ApiResponse> {
        let mut request = self.client.post(Self::checked(url)?).header(CONTENT_TYPE, "application/json").body(body.to_string());
        if let Some(token) = bearer {
            request = request.bearer_auth(token);
        }
        Self::api(request).await
    }

    /// GETs an API with a bearer token.
    pub async fn get_api(&self, url: &str, bearer: &str) -> Result<ApiResponse> {
        Self::api(self.client.get(Self::checked(url)?).bearer_auth(bearer)).await
    }

    async fn api(request: reqwest::RequestBuilder) -> Result<ApiResponse> {
        let response = request.header(ACCEPT, "application/json").timeout(API_TIMEOUT).send().await?;
        let status = response.status();
        Ok(ApiResponse { status, body: response.bytes().await?.to_vec() })
    }

    /// Fetches a small file, or just learns it hasn't changed when it still has `etag`.
    pub async fn get_if_changed(&self, url: &str, etag: Option<&str>, timeout: Duration) -> Result<Fetched> {
        let mut request = self.client.get(Self::checked(url)?).timeout(timeout);
        if let Some(etag) = etag {
            request = request.header(IF_NONE_MATCH, etag);
        }
        let response = request.send().await?;
        match response.status() {
            StatusCode::NOT_MODIFIED => return Ok(Fetched::NotModified),
            StatusCode::NOT_FOUND => return Ok(Fetched::NotFound),
            _ => {}
        }
        let response = response.error_for_status()?;
        let etag = response.headers().get(ETAG).and_then(|v| v.to_str().ok()).map(str::to_string);
        Ok(Fetched::Body { bytes: response.bytes().await?.to_vec(), etag })
    }

    /// Downloads every item, `concurrency` at a time. Stops at the first file that keeps failing.
    pub async fn download_all(&self, items: Vec<Download>, concurrency: usize, progress: Arc<Progress>) -> Result<()> {
        let slots = Arc::new(Semaphore::new(concurrency.max(1)));
        let mut tasks = JoinSet::new();
        for item in items {
            let slot = slots
                .clone()
                .acquire_owned()
                .await
                .map_err(|_| Error::Message("the download queue closed".into()))?;
            let net = self.clone();
            let progress = progress.clone();
            tasks.spawn(async move {
                let _slot = slot;
                net.download_with_retries(&item, &progress).await
            });
            while let Some(finished) = tasks.try_join_next() {
                finished.map_err(task_error)??;
            }
        }
        while let Some(finished) = tasks.join_next().await {
            finished.map_err(task_error)??;
        }
        Ok(())
    }

    async fn download_with_retries(&self, item: &Download, progress: &Progress) -> Result<()> {
        let mut attempt = 0u32;
        loop {
            match self.download_one(item, progress).await {
                Ok(()) => return Ok(()),
                Err(error) if attempt < 3 && error.is_retryable() => {
                    attempt += 1;
                    tracing::warn!("retrying {} (attempt {attempt}): {error}", item.url);
                    tokio::time::sleep(Duration::from_millis(500 * 3u64.pow(attempt - 1))).await;
                }
                Err(error) => return Err(error),
            }
        }
    }

    async fn download_one(&self, item: &Download, progress: &Progress) -> Result<()> {
        let url = Self::checked(&item.url)?;
        progress.set_current(&item.label);
        if let Some(parent) = item.dest.parent() {
            tokio::fs::create_dir_all(parent).await?;
        }
        let file_name = item.dest.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let partial = item.dest.with_file_name(format!("{file_name}.part"));

        let mut hasher = Hasher::for_hash(item.hash.as_ref());
        let mut written = 0u64;
        let streamed: Result<()> = async {
            let mut response = self.client.get(url).send().await?.error_for_status()?;
            let mut file = tokio::fs::File::create(&partial).await?;
            while let Some(chunk) = response.chunk().await? {
                file.write_all(&chunk).await?;
                hasher.update(&chunk);
                written += chunk.len() as u64;
                progress.done_bytes.fetch_add(chunk.len() as u64, Ordering::Relaxed);
            }
            file.flush().await?;
            Ok(())
        }
        .await;

        let verified = streamed.and_then(|()| {
            let size_ok = item.size.is_none_or(|size| size == written);
            if size_ok && hasher.matches(item.hash.as_ref()) {
                Ok(())
            } else {
                Err(Error::Checksum(file_name.clone()))
            }
        });
        match verified {
            Ok(()) => {
                tokio::fs::rename(&partial, &item.dest).await?;
                Ok(())
            }
            Err(error) => {
                progress.done_bytes.fetch_sub(written, Ordering::Relaxed);
                let _ = tokio::fs::remove_file(&partial).await;
                Err(error)
            }
        }
    }
}

/// `a=1&b=x+y`, the way HTML forms send fields.
pub fn form_encode(fields: &[(&str, &str)]) -> String {
    let mut url = Url::parse("http://form.invalid/").expect("a constant, valid URL");
    url.query_pairs_mut().extend_pairs(fields);
    url.query().unwrap_or_default().to_string()
}

fn task_error(error: tokio::task::JoinError) -> Error {
    Error::Message(format!("a download stopped unexpectedly: {error}"))
}

enum Hasher {
    Sha1(Sha1),
    Sha512(Box<Sha512>),
    Unchecked,
}

impl Hasher {
    fn for_hash(hash: Option<&Hash>) -> Self {
        match hash {
            Some(Hash::Sha1(_)) => Self::Sha1(Sha1::new()),
            Some(Hash::Sha512(_)) => Self::Sha512(Box::new(Sha512::new())),
            None => Self::Unchecked,
        }
    }

    fn update(&mut self, data: &[u8]) {
        match self {
            Self::Sha1(h) => h.update(data),
            Self::Sha512(h) => h.update(data),
            Self::Unchecked => {}
        }
    }

    fn matches(self, expected: Option<&Hash>) -> bool {
        match (self, expected) {
            (Self::Sha1(h), Some(Hash::Sha1(want))) => hex(&h.finalize()).eq_ignore_ascii_case(want),
            (Self::Sha512(h), Some(Hash::Sha512(want))) => {
                let h = *h;
                hex(&h.finalize()).eq_ignore_ascii_case(want)
            }
            (Self::Unchecked, None) => true,
            _ => false,
        }
    }
}

/// Lowercase hex, as Mojang and Modrinth write their hashes.
pub fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(DIGITS[(byte >> 4) as usize] as char);
        out.push(DIGITS[(byte & 0x0f) as usize] as char);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allows_only_https_on_listed_hosts() {
        assert!(is_allowed(&Url::parse("https://piston-data.mojang.com/v1/objects/abc/client.jar").unwrap()));
        assert!(is_allowed(&Url::parse("https://maven.fabricmc.net/net/fabricmc/x.jar").unwrap()));
        assert!(is_allowed_str("https://cdn.modrinth.com/data/AANobbMI/versions/x/sodium.jar"));
        assert!(is_allowed_str("https://github.com/x/cube-utility-client/releases/download/pack-1/a.jar"));
        assert!(!is_allowed(&Url::parse("http://piston-data.mojang.com/x").unwrap()));
        assert!(!is_allowed(&Url::parse("https://evil.example.com/x").unwrap()));
        assert!(!is_allowed(&Url::parse("https://piston-data.mojang.com.evil.example/x").unwrap()));
        assert!(!is_allowed_str("http://cdn.modrinth.com/x.jar"));
        assert!(!is_allowed_str("file:///C:/Windows/x.jar"));
        assert!(!is_allowed_str("not a url"));
    }

    #[test]
    fn sign_in_hosts_are_allowed() {
        for url in [
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
            "https://user.auth.xboxlive.com/user/authenticate",
            "https://xsts.auth.xboxlive.com/xsts/authorize",
            "https://api.minecraftservices.com/minecraft/profile",
            "https://textures.minecraft.net/texture/abc",
        ] {
            assert!(is_allowed_str(url), "{url}");
        }
        assert!(!is_allowed_str("http://textures.minecraft.net/texture/abc"));
    }

    #[test]
    fn encodes_forms() {
        assert_eq!(form_encode(&[("scope", "XboxLive.signin offline_access"), ("redirect_uri", "http://localhost:5000")]), "scope=XboxLive.signin+offline_access&redirect_uri=http%3A%2F%2Flocalhost%3A5000");
    }

    #[test]
    fn debug_builds_take_a_local_pack_server() {
        // Tests are debug builds: `npm run pack:serve` on this PC is allowed, other http isn't.
        assert!(is_allowed_str("http://127.0.0.1:8787/stable/manifest.json"));
        assert!(!is_allowed_str("http://192.168.1.10:8787/stable/manifest.json"));
    }

    #[test]
    fn hashes_match_known_values() {
        assert_eq!(hex(&Sha1::digest(b"abc")), "a9993e364706816aba3e25717850c26c9cd0d89d");
        let mut h = Hasher::for_hash(Some(&Hash::Sha1("A9993E364706816ABA3E25717850C26C9CD0D89D".into())));
        h.update(b"ab");
        h.update(b"c");
        assert!(h.matches(Some(&Hash::Sha1("A9993E364706816ABA3E25717850C26C9CD0D89D".into()))));
        let mut wrong = Hasher::for_hash(Some(&Hash::Sha1("0".repeat(40))));
        wrong.update(b"abc");
        assert!(!wrong.matches(Some(&Hash::Sha1("0".repeat(40)))));
    }
}
