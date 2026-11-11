//! Working out every file the game needs. The metadata is cached, so a game that's already
//! installed still starts when Mojang's servers can't be reached.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};

use serde::Deserialize;
use sha1::{Digest, Sha1};

use crate::error::{parse_json, Error, Result};
use crate::net::{hex, Download, Hash, Net};
use crate::paths::{safe_join, Paths};

use super::java;
use super::rules::{self, Features, Platform};
use super::version::{AssetIndexRef, Coordinates, Library, VersionJson};

const VERSION_MANIFEST: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const ASSET_SERVER: &str = "https://resources.download.minecraft.net";
const DEFAULT_LIBRARY_SERVER: &str = "https://libraries.minecraft.net";

/// Everything needed to install and start one game version.
pub struct GamePlan {
    pub version: VersionJson,
    /// In launch order: libraries first, the game jar last.
    pub classpath: Vec<PathBuf>,
    pub java: PathBuf,
    pub natives_dir: PathBuf,
    pub asset_index_id: String,
    pub downloads: Vec<Download>,
    pub directories: Vec<PathBuf>,
}

impl GamePlan {
    /// Files that are missing or the wrong size.
    pub fn missing(&self) -> Vec<Download> {
        self.downloads.iter().filter(|d| !d.is_present()).cloned().collect()
    }
}

#[derive(Deserialize)]
struct VersionList {
    versions: Vec<VersionEntry>,
}

#[derive(Deserialize)]
struct VersionEntry {
    id: String,
    url: String,
    sha1: String,
}

#[derive(Deserialize)]
struct AssetIndex {
    objects: HashMap<String, AssetObject>,
}

#[derive(Deserialize)]
struct AssetObject {
    hash: String,
    size: u64,
}

/// Resolves Minecraft `minecraft` with Fabric Loader `loader` into a full plan. `java` is the
/// pack's Java runtime component, when its manifest names one.
pub async fn resolve(net: &Net, paths: &Paths, minecraft: &str, loader: &str, java: Option<&str>) -> Result<GamePlan> {
    let platform = Platform::current();
    let features = Features::default();

    let vanilla = vanilla_version(net, paths, minecraft).await?;
    let fabric = fabric_profile(net, paths, minecraft, loader).await?;
    if fabric.inherits_from.as_deref() != Some(minecraft) {
        return Err(Error::Message(format!(
            "the Fabric profile is for {}, not Minecraft {minecraft}",
            fabric.inherits_from.as_deref().unwrap_or("an unknown version")
        )));
    }
    let version = fabric.inherit(vanilla);

    let mut downloads = Vec::new();
    let mut classpath = Vec::new();

    for library in &version.libraries {
        if !rules::allowed(library.rules.as_deref(), &platform, &features) {
            continue;
        }
        if library.natives.is_some() {
            tracing::warn!("{} uses the old natives format, which this launcher doesn't unpack", library.name);
        }
        let download = library_download(net, paths, library).await?;
        classpath.push(download.dest.clone());
        downloads.push(download);
    }

    let client = version
        .downloads
        .as_ref()
        .ok_or_else(|| Error::Message(format!("Minecraft {minecraft} has no game download")))?
        .client
        .clone();
    let client_jar = paths.versions().join(minecraft).join(format!("{minecraft}.jar"));
    downloads.push(Download {
        url: client.url,
        dest: client_jar.clone(),
        hash: Some(Hash::Sha1(client.sha1)),
        size: Some(client.size),
        label: format!("Minecraft {minecraft}"),
    });
    classpath.push(client_jar);

    let index = version
        .asset_index
        .clone()
        .ok_or_else(|| Error::Message(format!("Minecraft {minecraft} has no asset list")))?;
    downloads.extend(asset_objects(net, paths, &index).await?);

    let (mut component, major) = version
        .java_version
        .as_ref()
        .map(|j| (j.component.clone(), j.major_version))
        .unwrap_or_else(|| ("java-runtime-epsilon".to_string(), 25));
    if let Some(pack_java) = java.filter(|j| *j != component) {
        tracing::warn!("the pack asks for Java {pack_java}, Minecraft {minecraft} for {component}; using the pack's");
        component = pack_java.to_string();
    }
    tracing::info!("Minecraft {minecraft} runs on Java {major} ({component})");
    let runtime = java::resolve(net, &paths.runtimes(), &component).await?;
    downloads.extend(runtime.downloads);

    Ok(GamePlan {
        natives_dir: paths.natives(&version.id),
        classpath,
        java: runtime.java,
        asset_index_id: index.id,
        downloads,
        directories: runtime.directories,
        version,
    })
}

async fn vanilla_version(net: &Net, paths: &Paths, id: &str) -> Result<VersionJson> {
    let path = paths.versions().join(id).join(format!("{id}.json"));
    let entry = match net.get_bytes(VERSION_MANIFEST, None).await {
        Ok(bytes) => {
            let list: VersionList = parse_json(&bytes, "Mojang's version list")?;
            Some(
                list.versions
                    .into_iter()
                    .find(|v| v.id == id)
                    .ok_or_else(|| Error::Message(format!("Mojang doesn't list Minecraft {id}")))?,
            )
        }
        Err(error) => {
            tracing::warn!("version list unavailable ({error}); using the saved copy");
            None
        }
    };
    let bytes = match entry {
        Some(entry) => match read_if_sha1(&path, &entry.sha1).await {
            Some(bytes) => bytes,
            None => {
                let bytes = net.get_bytes(&entry.url, Some(&entry.sha1)).await?;
                write_file(&path, &bytes).await?;
                bytes
            }
        },
        None => tokio::fs::read(&path).await.map_err(|_| Error::Offline)?,
    };
    parse_json(&bytes, "the Minecraft version file")
}

async fn fabric_profile(net: &Net, paths: &Paths, minecraft: &str, loader: &str) -> Result<VersionJson> {
    let id = format!("fabric-loader-{loader}-{minecraft}");
    let path = paths.versions().join(&id).join(format!("{id}.json"));
    let url = format!("https://meta.fabricmc.net/v2/versions/loader/{minecraft}/{loader}/profile/json");
    let bytes = match net.get_bytes(&url, None).await {
        Ok(bytes) => {
            write_file(&path, &bytes).await?;
            bytes
        }
        Err(error) => {
            tracing::warn!("Fabric profile unavailable ({error}); using the saved copy");
            tokio::fs::read(&path).await.map_err(|_| Error::Offline)?
        }
    };
    parse_json(&bytes, "the Fabric profile")
}

async fn library_download(net: &Net, paths: &Paths, library: &Library) -> Result<Download> {
    let root = paths.libraries();
    let label = if library.name.starts_with("net.fabricmc:") { "Fabric" } else { "Minecraft libraries" }.to_string();
    let unsafe_path = |path: &str| Error::Message(format!("unsafe library path {path}"));

    // Mojang's libraries say exactly where to download from.
    if let Some(artifact) = library.downloads.as_ref().and_then(|d| d.artifact.as_ref()) {
        let relative = match &artifact.path {
            Some(path) => path.clone(),
            None => coordinates(library)?.path(),
        };
        let dest = safe_join(&root, &relative).ok_or_else(|| unsafe_path(&relative))?;
        return Ok(Download {
            url: artifact.url.clone(),
            dest,
            hash: Some(Hash::Sha1(artifact.sha1.clone())),
            size: Some(artifact.size),
            label,
        });
    }

    // Fabric's libraries point at a Maven repository instead.
    let relative = coordinates(library)?.path();
    let dest = safe_join(&root, &relative).ok_or_else(|| unsafe_path(&relative))?;
    let base = library.url.as_deref().unwrap_or(DEFAULT_LIBRARY_SERVER);
    let url = format!("{}/{}", base.trim_end_matches('/'), relative);
    let hash = match &library.sha1 {
        Some(sha1) => Some(Hash::Sha1(sha1.to_ascii_lowercase())),
        // Fabric's profile has no hash for the loader itself; Maven publishes one beside it.
        None => match net.get_text(&format!("{url}.sha1")).await {
            Ok(text) => {
                let sha1 = text
                    .split_whitespace()
                    .next()
                    .filter(|h| h.len() == 40 && h.chars().all(|c| c.is_ascii_hexdigit()))
                    .ok_or_else(|| Error::Message(format!("no valid checksum for {}", library.name)))?;
                Some(Hash::Sha1(sha1.to_ascii_lowercase()))
            }
            Err(error) if dest.is_file() => {
                tracing::warn!("checksum for {} unavailable ({error}); using the installed copy", library.name);
                None
            }
            Err(error) => return Err(error),
        },
    };
    Ok(Download { url, dest, hash, size: library.size, label })
}

fn coordinates(library: &Library) -> Result<Coordinates> {
    Coordinates::parse(&library.name).ok_or_else(|| Error::Message(format!("unrecognized library name {}", library.name)))
}

async fn asset_objects(net: &Net, paths: &Paths, index: &AssetIndexRef) -> Result<Vec<Download>> {
    let path = paths.assets().join("indexes").join(format!("{}.json", index.id));
    let bytes = match read_if_sha1(&path, &index.sha1).await {
        Some(bytes) => bytes,
        None => {
            let bytes = net.get_bytes(&index.url, Some(&index.sha1)).await?;
            write_file(&path, &bytes).await?;
            bytes
        }
    };
    let parsed: AssetIndex = parse_json(&bytes, "the asset list")?;
    let objects = paths.assets().join("objects");
    let mut seen = HashSet::new();
    Ok(parsed
        .objects
        .into_values()
        .filter(|o| o.hash.len() == 40 && o.hash.chars().all(|c| c.is_ascii_hexdigit()))
        .filter(|o| seen.insert(o.hash.clone()))
        .map(|o| {
            let prefix = &o.hash[..2];
            Download {
                url: format!("{ASSET_SERVER}/{prefix}/{}", o.hash),
                dest: objects.join(prefix).join(&o.hash),
                hash: Some(Hash::Sha1(o.hash.clone())),
                size: Some(o.size),
                label: "Minecraft sounds and textures".to_string(),
            }
        })
        .collect())
}

/// Reads a cached file, but only if it still has the expected SHA-1.
pub async fn read_if_sha1(path: &Path, sha1: &str) -> Option<Vec<u8>> {
    let bytes = tokio::fs::read(path).await.ok()?;
    hex(&Sha1::digest(&bytes)).eq_ignore_ascii_case(sha1).then_some(bytes)
}

pub async fn write_file(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    tokio::fs::write(path, bytes).await?;
    Ok(())
}
