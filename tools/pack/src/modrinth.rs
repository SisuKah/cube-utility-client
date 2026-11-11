//! The bits of Modrinth's API the pack tool needs: projects, their versions and dependencies.

use std::time::Duration;

use reqwest::blocking::Client;
use reqwest::StatusCode;
use serde::de::DeserializeOwned;
use serde::Deserialize;

use crate::Result;

const API: &str = "https://api.modrinth.com/v2";

#[derive(Debug, Clone, Deserialize)]
pub struct Project {
    pub id: String,
    pub slug: String,
    pub title: String,
    #[serde(default)]
    pub description: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Version {
    pub id: String,
    pub project_id: String,
    pub version_number: String,
    pub version_type: String,
    pub date_published: String,
    #[serde(default)]
    pub changelog: Option<String>,
    pub files: Vec<VersionFile>,
    #[serde(default)]
    pub dependencies: Vec<Dependency>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct VersionFile {
    pub url: String,
    pub filename: String,
    pub primary: bool,
    pub size: u64,
    pub hashes: Hashes,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Hashes {
    pub sha512: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Dependency {
    pub project_id: Option<String>,
    pub version_id: Option<String>,
    /// "required", "optional", "incompatible" or "embedded".
    pub dependency_type: String,
}

impl Version {
    /// The main download of this version.
    pub fn primary_file(&self) -> Option<&VersionFile> {
        self.files.iter().find(|f| f.primary).or_else(|| self.files.first())
    }
}

pub struct Modrinth {
    client: Client,
}

impl Modrinth {
    pub fn new() -> Result<Self> {
        let client = Client::builder()
            .user_agent(concat!("cube-utility-client-pack-tool/", env!("CARGO_PKG_VERSION")))
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(120))
            .build()?;
        Ok(Self { client })
    }

    /// None when there's no such project.
    pub fn project(&self, id_or_slug: &str) -> Result<Option<Project>> {
        self.get(&format!("{API}/project/{}", encode(id_or_slug)), &format!("Modrinth project '{id_or_slug}'"))
    }

    pub fn version(&self, id: &str) -> Result<Option<Version>> {
        self.get(&format!("{API}/version/{}", encode(id)), &format!("Modrinth version '{id}'"))
    }

    /// The project's versions for this game version and loader, newest first.
    pub fn versions(&self, project: &str, minecraft: &str, loader: &str) -> Result<Vec<Version>> {
        let url = format!(
            "{API}/project/{}/version?loaders={}&game_versions={}",
            encode(project),
            encode(&format!("[\"{loader}\"]")),
            encode(&format!("[\"{minecraft}\"]")),
        );
        let mut versions: Vec<Version> = self.get(&url, &format!("versions of '{project}'"))?.unwrap_or_default();
        versions.sort_by(|a, b| b.date_published.cmp(&a.date_published));
        Ok(versions)
    }

    pub fn download(&self, url: &str) -> Result<Vec<u8>> {
        let mut last_error = String::new();
        for attempt in 0..3u64 {
            if attempt > 0 {
                std::thread::sleep(Duration::from_secs(attempt * 2));
            }
            match self.client.get(url).send().and_then(|r| r.error_for_status()).and_then(|r| r.bytes()) {
                Ok(bytes) => return Ok(bytes.to_vec()),
                Err(e) => last_error = e.to_string(),
            }
        }
        Err(format!("couldn't download {url}: {last_error}").into())
    }

    fn get<T: DeserializeOwned>(&self, url: &str, what: &str) -> Result<Option<T>> {
        let mut last_error = String::new();
        for attempt in 0..3u64 {
            if attempt > 0 {
                std::thread::sleep(Duration::from_secs(attempt * 2));
            }
            let response = match self.client.get(url).send() {
                Ok(response) => response,
                Err(e) => {
                    last_error = e.to_string();
                    continue;
                }
            };
            let status = response.status();
            if status == StatusCode::NOT_FOUND {
                return Ok(None);
            }
            if status.is_server_error() || status == StatusCode::TOO_MANY_REQUESTS {
                last_error = format!("Modrinth answered {status}");
                continue;
            }
            if !status.is_success() {
                return Err(format!("{what}: Modrinth answered {status}").into());
            }
            let bytes = response.bytes()?;
            return serde_json::from_slice(&bytes).map(Some).map_err(|e| format!("{what}: unexpected answer from Modrinth: {e}").into());
        }
        Err(format!("{what}: {last_error}").into())
    }
}

/// Picks the pinned version, or for "latest" the newest release (or the newest version of
/// any kind when there's no release).
pub fn choose<'a>(versions: &'a [Version], wanted: &str) -> Option<&'a Version> {
    if wanted == "latest" {
        versions.iter().find(|v| v.version_type == "release").or_else(|| versions.first())
    } else {
        versions.iter().find(|v| v.version_number == wanted)
    }
}

/// Percent-encodes everything except unreserved characters.
fn encode(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for byte in text.bytes() {
        if byte.is_ascii_alphanumeric() || b"-_.~".contains(&byte) {
            out.push(byte as char);
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn version(number: &str, kind: &str) -> Version {
        Version {
            id: number.into(),
            project_id: "p".into(),
            version_number: number.into(),
            version_type: kind.into(),
            date_published: String::new(),
            changelog: None,
            files: vec![],
            dependencies: vec![],
        }
    }

    #[test]
    fn chooses_pinned_or_newest_release() {
        let versions = [version("0.9.3-beta.4", "beta"), version("0.9.2", "release"), version("0.9.1", "release")];
        assert_eq!(choose(&versions, "latest").unwrap().version_number, "0.9.2");
        assert_eq!(choose(&versions, "0.9.1").unwrap().version_number, "0.9.1");
        assert!(choose(&versions, "0.8.0").is_none());
        assert_eq!(choose(&[version("1.0-beta", "beta")], "latest").unwrap().version_number, "1.0-beta");
    }

    #[test]
    fn encodes_query_values() {
        assert_eq!(encode("[\"fabric\"]"), "%5B%22fabric%22%5D");
        assert_eq!(encode("2.17.0+mc26.3"), "2.17.0%2Bmc26.3");
    }
}
