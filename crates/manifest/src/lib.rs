//! The pack manifest: the signed list of files every launcher syncs to.
//! The pack tool writes and signs it; the launcher verifies it before trusting anything in it.

use std::collections::HashSet;

use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use serde::{Deserialize, Serialize};

/// The manifest format version this code understands.
pub const SCHEMA: u32 = 1;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("the manifest's signature doesn't match")]
    BadSignature,
    #[error("invalid {0}")]
    BadKey(&'static str),
    #[error("this launcher doesn't understand manifest schema {0}")]
    Schema(u32),
    #[error("invalid manifest: {0}")]
    Invalid(String),
    #[error("couldn't read the manifest: {0}")]
    Json(#[from] serde_json::Error),
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    pub schema: u32,
    /// Goes up by one with every release. Launchers refuse a lower serial than the last one
    /// they applied, so an old manifest can't be replayed.
    pub serial: u64,
    pub pack_version: String,
    pub channel: String,
    /// RFC 3339, UTC.
    pub released_at: String,
    pub min_launcher_version: String,
    pub minecraft: String,
    pub loader: Loader,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub java: Option<JavaRequirement>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub quick_play: Option<String>,
    #[serde(default = "yes")]
    pub allow_extra_mods: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub notes: Option<Notes>,
    pub files: Vec<ManifestFile>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub configs: Vec<ConfigFile>,
    /// Servers' resource packs, put in the game's download cache ahead of time. Launchers older
    /// than 1.2 don't know this field and skip it.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub server_packs: Vec<ServerPack>,
}

fn yes() -> bool {
    true
}

/// A server's resource pack (CubeCraft's), downloaded ahead of time to where Minecraft keeps the
/// packs servers send: `downloads/<id>/<sha1>` in the game folder. When the server sends that
/// pack, Minecraft finds it already there, and the game starts with it switched on, so joining
/// doesn't stop to download and load it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ServerPack {
    /// The server that sends it, like play.cubecraft.net. Only for people reading the manifest.
    pub server: String,
    /// The pack's UUID, as the server sends it.
    pub id: String,
    pub url: String,
    /// Minecraft names the downloaded file after its SHA-1.
    pub sha1: String,
    pub size: u64,
}

impl ServerPack {
    /// Where the pack goes, relative to the game folder.
    pub fn path(&self) -> String {
        format!("downloads/{}/{}", self.id, self.sha1)
    }
}

/// A UUID in its usual text form, the way Minecraft names a download folder.
fn is_uuid(text: &str) -> bool {
    text.len() == 36
        && text.char_indices().all(|(i, c)| match i {
            8 | 13 | 18 | 23 => c == '-',
            _ => c.is_ascii_hexdigit() && !c.is_ascii_uppercase(),
        })
}

fn is_sha1(text: &str) -> bool {
    text.len() == 40 && text.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Loader {
    #[serde(rename = "type")]
    pub kind: String,
    pub version: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct JavaRequirement {
    pub component: String,
    pub major: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Notes {
    pub title: String,
    pub markdown: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Group {
    /// The pack owner's own mods.
    Own,
    Performance,
    Library,
    /// Optional quality-of-life mods players can switch on or off.
    Qol,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestFile {
    pub id: String,
    pub name: String,
    pub version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub group: Group,
    /// Relative to the game folder, always under `mods/`.
    pub path: String,
    pub url: String,
    pub sha512: String,
    pub size: u64,
    pub required: bool,
    #[serde(default)]
    pub default_enabled: bool,
    /// For optional libraries: the mods that need them. A library is installed while any of
    /// these is switched on.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub required_by: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub modrinth: Option<ModrinthRef>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModrinthRef {
    pub project_id: String,
    pub version_id: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ConfigPolicy {
    /// Only written when the player doesn't have the file yet. Never overwrites their settings.
    IfMissing,
    /// Always kept identical to the pack's copy.
    Always,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ConfigFile {
    pub path: String,
    pub url: String,
    pub sha512: String,
    pub size: u64,
    pub policy: ConfigPolicy,
}

impl Manifest {
    /// Parses and validates. Check the signature with [`verify`] first.
    pub fn parse(bytes: &[u8]) -> Result<Self, Error> {
        let manifest: Manifest = serde_json::from_slice(bytes)?;
        manifest.validate()?;
        Ok(manifest)
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut bytes = serde_json::to_vec_pretty(self)?;
        bytes.push(b'\n');
        Ok(bytes)
    }

    pub fn validate(&self) -> Result<(), Error> {
        let invalid = |message: String| Err(Error::Invalid(message));
        if self.schema != SCHEMA {
            return Err(Error::Schema(self.schema));
        }
        if self.minecraft.is_empty() || self.loader.version.is_empty() || self.pack_version.is_empty() {
            return invalid("missing the Minecraft, loader or pack version".into());
        }
        let mut ids = HashSet::new();
        let mut paths = HashSet::new();
        for file in &self.files {
            if file.id.is_empty() || !ids.insert(file.id.as_str()) {
                return invalid(format!("duplicate or empty mod id '{}'", file.id));
            }
            if !is_safe_relative_path(&file.path) || !file.path.starts_with("mods/") || !file.path.ends_with(".jar") {
                return invalid(format!("unsafe mod path '{}'", file.path));
            }
            if !paths.insert(file.path.to_ascii_lowercase()) {
                return invalid(format!("two mods use the path '{}'", file.path));
            }
            check_file(&file.url, &file.sha512, file.size, &file.id)?;
            if file.required && !file.required_by.is_empty() {
                return invalid(format!("'{}' is required, so it can't depend on other mods being on", file.id));
            }
        }
        for file in &self.files {
            if let Some(missing) = file.required_by.iter().find(|id| !ids.contains(id.as_str())) {
                return invalid(format!("'{}' says '{missing}' needs it, but there's no such mod", file.id));
            }
        }
        for config in &self.configs {
            let allowed_place = config.path.starts_with("config/") || config.path == "options.txt";
            if !is_safe_relative_path(&config.path) || !allowed_place {
                return invalid(format!("unsafe config path '{}'", config.path));
            }
            check_file(&config.url, &config.sha512, config.size, &config.path)?;
        }
        let mut pack_ids = HashSet::new();
        for pack in &self.server_packs {
            if !is_uuid(&pack.id) || !pack_ids.insert(pack.id.as_str()) {
                return invalid(format!("server pack for '{}' has a duplicate or invalid id '{}'", pack.server, pack.id));
            }
            if !is_sha1(&pack.sha1) {
                return invalid(format!("server pack for '{}' has an invalid SHA-1 hash", pack.server));
            }
            if !(pack.url.starts_with("https://") || pack.url.starts_with("http://")) || pack.size == 0 {
                return invalid(format!("server pack for '{}' has an invalid download address or size", pack.server));
            }
        }
        Ok(())
    }
}

fn check_file(url: &str, sha512: &str, size: u64, what: &str) -> Result<(), Error> {
    if !(url.starts_with("https://") || url.starts_with("http://")) {
        return Err(Error::Invalid(format!("'{what}' has an invalid download address")));
    }
    if sha512.len() != 128 || !sha512.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(Error::Invalid(format!("'{what}' has an invalid SHA-512 hash")));
    }
    if size == 0 {
        return Err(Error::Invalid(format!("'{what}' has a size of 0")));
    }
    Ok(())
}

/// A forward-slash path that stays inside its folder: no `..`, no drive letters, no backslashes.
pub fn is_safe_relative_path(path: &str) -> bool {
    !path.is_empty()
        && !path.starts_with('/')
        && !path.contains('\\')
        && !path.contains(':')
        && path.split('/').all(|part| !part.is_empty() && part != "." && part != "..")
}

/// Checks the ed25519 signature over the exact manifest bytes.
pub fn verify(bytes: &[u8], signature_hex: &str, public_key_hex: &str) -> Result<(), Error> {
    let key: [u8; 32] = decode_hex(public_key_hex.trim()).and_then(|b| b.try_into().ok()).ok_or(Error::BadKey("public key"))?;
    let signature: [u8; 64] = decode_hex(signature_hex.trim()).and_then(|b| b.try_into().ok()).ok_or(Error::BadKey("signature"))?;
    let key = VerifyingKey::from_bytes(&key).map_err(|_| Error::BadKey("public key"))?;
    key.verify_strict(bytes, &Signature::from_bytes(&signature)).map_err(|_| Error::BadSignature)
}

/// Signs the exact manifest bytes. Returns the signature as hex.
pub fn sign(bytes: &[u8], secret_key_hex: &str) -> Result<String, Error> {
    Ok(encode_hex(&signing_key(secret_key_hex)?.sign(bytes).to_bytes()))
}

/// The public key (hex) that belongs to a secret key (hex).
pub fn public_key(secret_key_hex: &str) -> Result<String, Error> {
    Ok(encode_hex(&signing_key(secret_key_hex)?.verifying_key().to_bytes()))
}

fn signing_key(secret_key_hex: &str) -> Result<SigningKey, Error> {
    let seed: [u8; 32] = decode_hex(secret_key_hex.trim()).and_then(|b| b.try_into().ok()).ok_or(Error::BadKey("secret key"))?;
    Ok(SigningKey::from_bytes(&seed))
}

pub fn encode_hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(DIGITS[(byte >> 4) as usize] as char);
        out.push(DIGITS[(byte & 0x0f) as usize] as char);
    }
    out
}

pub fn decode_hex(text: &str) -> Option<Vec<u8>> {
    if !text.len().is_multiple_of(2) {
        return None;
    }
    (0..text.len()).step_by(2).map(|i| u8::from_str_radix(text.get(i..i + 2)?, 16).ok()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET: &str = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";

    fn sample() -> Manifest {
        let hash = "a".repeat(128);
        Manifest {
            schema: SCHEMA,
            serial: 42,
            pack_version: "2026.11.10-1".into(),
            channel: "stable".into(),
            released_at: "2026-11-10T18:00:00Z".into(),
            min_launcher_version: "1.0.0".into(),
            minecraft: "26.3".into(),
            loader: Loader { kind: "fabric".into(), version: "0.20.1".into() },
            java: Some(JavaRequirement { component: "java-runtime-epsilon".into(), major: 25 }),
            quick_play: Some("play.cubecraft.net".into()),
            allow_extra_mods: true,
            notes: Some(Notes { title: "Cube Utility 1.7.0".into(), markdown: "- New".into() }),
            files: vec![
                ManifestFile {
                    id: "cubeutility".into(),
                    name: "Cube Utility".into(),
                    version: "1.7.0".into(),
                    description: None,
                    group: Group::Own,
                    path: "mods/cube-utility-1.7.0.jar".into(),
                    url: "https://github.com/x/y/releases/download/pack-1/cube-utility-1.7.0.jar".into(),
                    sha512: hash.clone(),
                    size: 675_840,
                    required: true,
                    default_enabled: true,
                    required_by: vec![],
                    modrinth: None,
                },
                ManifestFile {
                    id: "zoomify".into(),
                    name: "Zoomify".into(),
                    version: "2.17.0".into(),
                    description: None,
                    group: Group::Qol,
                    path: "mods/zoomify.jar".into(),
                    url: "https://cdn.modrinth.com/data/w7ThoJFB/versions/x/zoomify.jar".into(),
                    sha512: hash.clone(),
                    size: 10,
                    required: false,
                    default_enabled: true,
                    required_by: vec![],
                    modrinth: Some(ModrinthRef { project_id: "w7ThoJFB".into(), version_id: "x".into() }),
                },
                ManifestFile {
                    id: "yacl".into(),
                    name: "YetAnotherConfigLib".into(),
                    version: "3.9.7".into(),
                    description: None,
                    group: Group::Library,
                    path: "mods/yacl.jar".into(),
                    url: "https://cdn.modrinth.com/data/1eAoo2KR/versions/y/yacl.jar".into(),
                    sha512: hash,
                    size: 10,
                    required: false,
                    default_enabled: false,
                    required_by: vec!["zoomify".into()],
                    modrinth: None,
                },
            ],
            configs: vec![],
            server_packs: vec![ServerPack {
                server: "play.cubecraft.net".into(),
                id: "c05f8844-0b30-39f9-b032-ac20410d2575".into(),
                url: "https://files.cubecraftcdn.com/assets/cubecraft/pack.zip".into(),
                sha1: "de57854a9220e95cdbf04515d431b23f1849617b".into(),
                size: 3_494_564,
            }],
        }
    }

    #[test]
    fn round_trips_and_validates() {
        let manifest = sample();
        let bytes = manifest.to_bytes().unwrap();
        assert_eq!(Manifest::parse(&bytes).unwrap(), manifest);
        // The JSON uses the camelCase names from the spec.
        let text = String::from_utf8(bytes).unwrap();
        assert!(text.contains("\"packVersion\"") && text.contains("\"requiredBy\"") && text.contains("\"type\": \"fabric\""));
        assert!(text.contains("\"serverPacks\""));
        assert_eq!(manifest.server_packs[0].path(), "downloads/c05f8844-0b30-39f9-b032-ac20410d2575/de57854a9220e95cdbf04515d431b23f1849617b");
    }

    /// Launchers already out there read manifests from newer pack tools: fields they don't know
    /// (like `serverPacks` for launcher 1.1) have to be skipped, not refused.
    #[test]
    fn skips_fields_it_does_not_know() {
        let mut json: serde_json::Value = serde_json::from_slice(&sample().to_bytes().unwrap()).unwrap();
        json["somethingNew"] = serde_json::json!({ "from": "a later pack tool" });
        json["serverPacks"][0]["alsoNew"] = serde_json::json!(true);
        let parsed = Manifest::parse(&serde_json::to_vec(&json).unwrap()).unwrap();
        assert_eq!(parsed, sample());

        let mut without: serde_json::Value = serde_json::from_slice(&sample().to_bytes().unwrap()).unwrap();
        without.as_object_mut().unwrap().remove("serverPacks");
        assert!(Manifest::parse(&serde_json::to_vec(&without).unwrap()).unwrap().server_packs.is_empty());
    }

    #[test]
    fn refuses_broken_server_packs() {
        for (id, sha1) in [
            ("c05f8844-0b30-39f9-b032-ac20410d2575/../..", "de57854a9220e95cdbf04515d431b23f1849617b"),
            ("C05F8844-0B30-39F9-B032-AC20410D2575", "de57854a9220e95cdbf04515d431b23f1849617b"),
            ("c05f8844-0b30-39f9-b032-ac20410d2575", "de57854a9220e95cdbf04515d431b23f184961"),
            ("c05f8844-0b30-39f9-b032-ac20410d2575", "../57854a9220e95cdbf04515d431b23f1849617b"),
        ] {
            let mut m = sample();
            m.server_packs[0].id = id.into();
            m.server_packs[0].sha1 = sha1.into();
            assert!(m.validate().is_err(), "{id} {sha1}");
        }
        let mut m = sample();
        m.server_packs.push(m.server_packs[0].clone());
        assert!(m.validate().is_err(), "the same pack twice");
    }

    #[test]
    fn signatures_only_match_the_exact_bytes_and_key() {
        let bytes = sample().to_bytes().unwrap();
        let signature = sign(&bytes, SECRET).unwrap();
        let public = public_key(SECRET).unwrap();
        assert!(verify(&bytes, &signature, &public).is_ok());

        let mut tampered = bytes.clone();
        tampered[10] ^= 1;
        assert!(matches!(verify(&tampered, &signature, &public), Err(Error::BadSignature)));

        let other = public_key(&"11".repeat(32)).unwrap();
        assert!(matches!(verify(&bytes, &signature, &other), Err(Error::BadSignature)));
        assert!(matches!(verify(&bytes, "abc", &public), Err(Error::BadKey(_))));
    }

    #[test]
    fn refuses_unsafe_or_broken_manifests() {
        let mut m = sample();
        m.files[0].path = "mods/../../evil.jar".into();
        assert!(m.validate().is_err());

        let mut m = sample();
        m.files[0].path = "C:/Windows/evil.jar".into();
        assert!(m.validate().is_err());

        let mut m = sample();
        m.files[1].id = "cubeutility".into();
        assert!(m.validate().is_err(), "duplicate ids");

        let mut m = sample();
        m.files[0].sha512 = "1234".into();
        assert!(m.validate().is_err(), "bad hash");

        let mut m = sample();
        m.files[2].required_by = vec!["nothing".into()];
        assert!(m.validate().is_err(), "unknown requiredBy");

        let mut m = sample();
        m.schema = 2;
        assert!(matches!(m.validate(), Err(Error::Schema(2))));
    }

    #[test]
    fn safe_paths() {
        assert!(is_safe_relative_path("mods/a.jar"));
        assert!(is_safe_relative_path("config/sodium-options.json"));
        for bad in ["", "/mods/a.jar", "mods/../a.jar", "mods//a.jar", "mods\\a.jar", "C:/a.jar", "./a.jar"] {
            assert!(!is_safe_relative_path(bad), "{bad}");
        }
    }

    #[test]
    fn hex_round_trips() {
        assert_eq!(decode_hex("00ff10"), Some(vec![0, 255, 16]));
        assert_eq!(encode_hex(&[0, 255, 16]), "00ff10");
        assert_eq!(decode_hex("abc"), None);
        assert_eq!(decode_hex("zz"), None);
    }
}
