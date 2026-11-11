//! Mojang's official Java runtimes, the same ones the vanilla launcher installs.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use serde::Deserialize;

use crate::error::{parse_json, Error, Result};
use crate::net::{Download, Hash, Net};
use crate::paths::safe_join;

use super::install::{read_if_sha1, write_file};

const RUNTIME_INDEX: &str =
    "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

#[derive(Deserialize)]
struct Entry {
    manifest: ManifestRef,
    version: RuntimeVersion,
}

#[derive(Deserialize)]
struct ManifestRef {
    sha1: String,
    url: String,
}

#[derive(Deserialize)]
struct RuntimeVersion {
    name: String,
}

#[derive(Deserialize)]
struct Manifest {
    files: HashMap<String, RuntimeFile>,
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
enum RuntimeFile {
    File { downloads: FileDownloads },
    Directory {},
    Link {},
}

#[derive(Deserialize)]
struct FileDownloads {
    raw: RawFile,
}

#[derive(Deserialize)]
struct RawFile {
    sha1: String,
    size: u64,
    url: String,
}

/// A Java runtime ready to download: its files, its folders and where `javaw.exe` ends up.
pub struct JavaRuntime {
    pub java: PathBuf,
    pub downloads: Vec<Download>,
    pub directories: Vec<PathBuf>,
}

fn platform_key() -> Result<&'static str> {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("windows", "x86_64") => Ok("windows-x64"),
        ("windows", "aarch64") => Ok("windows-arm64"),
        ("windows", "x86") => Ok("windows-x86"),
        (os, arch) => Err(Error::Message(format!("no Java runtime is available for {os} {arch}"))),
    }
}

/// Works out the runtime's files. Uses the saved manifest when the servers can't be reached.
pub async fn resolve(net: &Net, runtimes: &Path, component: &str) -> Result<JavaRuntime> {
    let platform = platform_key()?;
    let manifest_path = runtimes.join(format!("{component}.manifest.json"));

    let latest = match net.get_bytes(RUNTIME_INDEX, None).await {
        Ok(bytes) => {
            let mut index: HashMap<String, HashMap<String, Vec<Entry>>> = parse_json(&bytes, "the Java runtime list")?;
            let entry = index
                .get_mut(platform)
                .and_then(|components| components.remove(component))
                .and_then(|entries| entries.into_iter().next())
                .ok_or_else(|| Error::Message(format!("Mojang doesn't offer the Java runtime {component} for {platform}")))?;
            Some(entry)
        }
        Err(error) => {
            tracing::warn!("Java runtime list unavailable ({error}); using the saved copy");
            None
        }
    };

    let manifest_bytes = match &latest {
        Some(entry) => match read_if_sha1(&manifest_path, &entry.manifest.sha1).await {
            Some(bytes) => bytes,
            None => {
                let bytes = net.get_bytes(&entry.manifest.url, Some(&entry.manifest.sha1)).await?;
                write_file(&manifest_path, &bytes).await?;
                bytes
            }
        },
        None => tokio::fs::read(&manifest_path).await.map_err(|_| Error::Offline)?,
    };
    let manifest: Manifest = parse_json(&manifest_bytes, "the Java runtime manifest")?;
    let label = match &latest {
        Some(entry) => format!("Java {}", entry.version.name),
        None => "Java".to_string(),
    };

    let home = runtimes.join(component);
    let mut downloads = Vec::new();
    let mut directories = Vec::new();
    for (path, file) in manifest.files {
        let Some(dest) = safe_join(&home, &path) else {
            tracing::warn!("skipping unsafe Java runtime path {path}");
            continue;
        };
        match file {
            RuntimeFile::File { downloads: d } => downloads.push(Download {
                url: d.raw.url,
                dest,
                hash: Some(Hash::Sha1(d.raw.sha1)),
                size: Some(d.raw.size),
                label: label.clone(),
            }),
            RuntimeFile::Directory {} => directories.push(dest),
            RuntimeFile::Link {} => {}
        }
    }
    let executable = if cfg!(windows) { "javaw.exe" } else { "java" };
    Ok(JavaRuntime { java: home.join("bin").join(executable), downloads, directories })
}
