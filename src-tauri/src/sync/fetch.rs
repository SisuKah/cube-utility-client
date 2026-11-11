//! Getting the manifest from the pack server, and deciding whether to trust it.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use cuc_manifest::Manifest;

use crate::error::Result;
use crate::net::{self, Fetched, Net};

const TIMEOUT: Duration = Duration::from_secs(20);

/// What the server sent.
pub enum Response {
    NotModified,
    /// Nothing is published on this channel yet.
    NotFound,
    Body { bytes: Vec<u8>, signature: String, etag: Option<String> },
}

/// Fetches manifest.json and, when it changed, manifest.json.sig. The query string makes CDNs
/// pass both requests through, so a new manifest is never paired with an old cached signature.
pub async fn fetch(net: &Net, url: &str, etag: Option<&str>) -> Result<Response> {
    let fresh = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis());
    match net.get_if_changed(&format!("{url}?t={fresh}"), etag, TIMEOUT).await? {
        Fetched::NotModified => Ok(Response::NotModified),
        Fetched::NotFound => Ok(Response::NotFound),
        Fetched::Body { bytes, etag } => {
            // A missing signature fails the check below, like a wrong one.
            let signature = match net.get_if_changed(&format!("{url}.sig?t={fresh}"), None, TIMEOUT).await? {
                Fetched::Body { bytes, .. } => String::from_utf8_lossy(&bytes).trim().to_string(),
                Fetched::NotModified | Fetched::NotFound => String::new(),
            };
            Ok(Response::Body { bytes, signature, etag })
        }
    }
}

#[derive(Debug)]
pub enum Verdict {
    /// The same pack that's installed.
    Unchanged,
    /// A newer pack: signed, checked and safe to install.
    Newer(Box<Manifest>),
}

#[derive(Debug, PartialEq, Eq, thiserror::Error)]
pub enum TrustError {
    #[error("The pack update isn't signed with this launcher's key, so it was ignored.")]
    Signature,
    #[error("The pack update is broken ({0}), so it was ignored.")]
    Invalid(String),
    #[error("The server sent the {found} pack instead of the {wanted} one, so it was ignored.")]
    WrongChannel { found: String, wanted: String },
    #[error("The pack update downloads from {0}, which isn't allowed, so it was ignored.")]
    HostNotAllowed(String),
    #[error("The server sent an older pack (release #{found}) than the one installed (#{installed}), so it was ignored.")]
    Older { found: u64, installed: u64 },
    #[error("The server changed pack release #{0} without giving it a new number, so it was ignored.")]
    SameSerial(u64),
    #[error("This pack update needs launcher {0} or newer.")]
    LauncherTooOld(String),
}

/// Everything a manifest has to pass before the launcher acts on it: the signature over the
/// exact bytes first, then the format, the channel, every download host, and a serial that
/// isn't lower than the installed one (so an old manifest can't be replayed).
pub fn trust(
    bytes: &[u8],
    signature: &str,
    public_key: &str,
    channel: &str,
    installed_serial: Option<u64>,
    installed_bytes: Option<&[u8]>,
    app_version: &str,
) -> std::result::Result<Verdict, TrustError> {
    cuc_manifest::verify(bytes, signature, public_key).map_err(|_| TrustError::Signature)?;
    let manifest = Manifest::parse(bytes).map_err(|e| TrustError::Invalid(e.to_string()))?;
    if manifest.channel != channel {
        return Err(TrustError::WrongChannel { found: manifest.channel, wanted: channel.to_string() });
    }
    let urls = manifest.files.iter().map(|f| f.url.as_str()).chain(manifest.configs.iter().map(|c| c.url.as_str()));
    if let Some(url) = urls.into_iter().find(|url| !net::is_allowed_str(url)) {
        let host = reqwest::Url::parse(url).ok().and_then(|u| u.host_str().map(str::to_string));
        return Err(TrustError::HostNotAllowed(host.unwrap_or_else(|| url.to_string())));
    }
    if let Some(installed) = installed_serial {
        if manifest.serial < installed {
            return Err(TrustError::Older { found: manifest.serial, installed });
        }
        if manifest.serial == installed {
            return match installed_bytes {
                Some(saved) if saved == bytes => Ok(Verdict::Unchanged),
                Some(_) => Err(TrustError::SameSerial(installed)),
                // The saved copy is gone. This one is just as new, so it's installed again.
                None => Ok(Verdict::Newer(Box::new(manifest))),
            };
        }
    }
    if is_newer(&manifest.min_launcher_version, app_version) {
        return Err(TrustError::LauncherTooOld(manifest.min_launcher_version));
    }
    Ok(Verdict::Newer(Box::new(manifest)))
}

/// Whether dotted version `a` (like 1.2.0) is newer than `b`.
pub fn is_newer(a: &str, b: &str) -> bool {
    let parse = |v: &str| v.split(['.', '-', '+']).map(|part| part.parse::<u64>().unwrap_or(0)).collect::<Vec<_>>();
    let (a, b) = (parse(a), parse(b));
    for i in 0..a.len().max(b.len()) {
        let (x, y) = (a.get(i).copied().unwrap_or(0), b.get(i).copied().unwrap_or(0));
        if x != y {
            return x > y;
        }
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sync::plan::tests::{file, manifest};

    const SECRET: &str = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";

    fn signed(m: &Manifest) -> (Vec<u8>, String) {
        let bytes = m.to_bytes().unwrap();
        let signature = cuc_manifest::sign(&bytes, SECRET).unwrap();
        (bytes, signature)
    }

    fn key() -> String {
        cuc_manifest::public_key(SECRET).unwrap()
    }

    fn pack(serial: u64) -> Manifest {
        let mut m = manifest(vec![file("sodium", true, true, &[])]);
        m.serial = serial;
        m
    }

    fn check(m: &Manifest, installed_serial: Option<u64>, installed_bytes: Option<&[u8]>) -> std::result::Result<Verdict, TrustError> {
        let (bytes, signature) = signed(m);
        trust(&bytes, &signature, &key(), "stable", installed_serial, installed_bytes, "1.0.0")
    }

    #[test]
    fn accepts_a_newer_signed_pack() {
        assert!(matches!(check(&pack(1), None, None), Ok(Verdict::Newer(m)) if m.serial == 1));
        assert!(matches!(check(&pack(7), Some(6), Some(b"old")), Ok(Verdict::Newer(_))));
    }

    #[test]
    fn refuses_bad_or_missing_signatures() {
        let (mut bytes, signature) = signed(&pack(2));
        assert!(trust(&bytes, &signature, &key(), "stable", None, None, "1.0.0").is_ok());
        // One changed byte (here: the serial), and the signature no longer matches.
        let at = bytes.windows(9).position(|w| w == b"\"serial\":").unwrap() + 10;
        bytes[at] = b'9';
        assert_eq!(trust(&bytes, &signature, &key(), "stable", None, None, "1.0.0").unwrap_err(), TrustError::Signature);
        let (bytes, _) = signed(&pack(2));
        assert_eq!(trust(&bytes, "", &key(), "stable", None, None, "1.0.0").unwrap_err(), TrustError::Signature);
        let other_key = cuc_manifest::public_key(&"11".repeat(32)).unwrap();
        let (bytes, signature) = signed(&pack(2));
        assert_eq!(trust(&bytes, &signature, &other_key, "stable", None, None, "1.0.0").unwrap_err(), TrustError::Signature);
    }

    #[test]
    fn refuses_older_and_replayed_packs() {
        assert_eq!(check(&pack(5), Some(6), None).unwrap_err(), TrustError::Older { found: 5, installed: 6 });
        let (bytes, _) = signed(&pack(6));
        assert!(matches!(check(&pack(6), Some(6), Some(&bytes)), Ok(Verdict::Unchanged)));
        assert_eq!(check(&pack(6), Some(6), Some(b"something else")).unwrap_err(), TrustError::SameSerial(6));
        assert!(matches!(check(&pack(6), Some(6), None), Ok(Verdict::Newer(_))));
    }

    #[test]
    fn refuses_the_wrong_channel_and_unknown_hosts() {
        let mut beta = pack(3);
        beta.channel = "beta".into();
        assert!(matches!(check(&beta, None, None), Err(TrustError::WrongChannel { .. })));

        let mut evil = pack(3);
        evil.files[0].url = "https://evil.example.com/sodium.jar".into();
        assert_eq!(check(&evil, None, None).unwrap_err(), TrustError::HostNotAllowed("evil.example.com".into()));
    }

    #[test]
    fn refuses_unknown_formats_and_packs_for_newer_launchers() {
        let mut future = pack(3);
        future.schema = 2;
        assert!(matches!(check(&future, None, None), Err(TrustError::Invalid(_))));

        let mut needs_update = pack(3);
        needs_update.min_launcher_version = "1.1.0".into();
        assert_eq!(check(&needs_update, None, None).unwrap_err(), TrustError::LauncherTooOld("1.1.0".into()));
    }

    #[test]
    fn compares_versions() {
        assert!(is_newer("1.1.0", "1.0.9"));
        assert!(is_newer("2", "1.9.9"));
        assert!(!is_newer("1.0.0", "1.0.0"));
        assert!(!is_newer("1.0", "1.0.0"));
        assert!(!is_newer("0.9.0", "1.0.0"));
    }
}
