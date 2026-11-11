//! What the launcher remembers between syncs: state/sync-state.json.

use std::collections::BTreeMap;
use std::fs;
use std::io::{self, Write};
use std::path::Path;
use std::time::UNIX_EPOCH;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncState {
    /// Per manifest address (so per channel, and per server): the serial of the manifest last
    /// applied. A lower one is refused. Keyed by address, so serials from a test server never
    /// block the real one.
    #[serde(default)]
    pub serials: BTreeMap<String, u64>,
    /// Per manifest address: the server's ETag for that manifest.
    #[serde(default)]
    pub etags: BTreeMap<String, String>,
    /// The last time the pack server answered (ms since 1970).
    #[serde(default)]
    pub checked_at_ms: Option<u64>,
    /// Files the launcher put in the game folder: path inside it → what was written.
    #[serde(default)]
    pub files: BTreeMap<String, FileRecord>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileRecord {
    pub sha512: String,
    pub size: u64,
    /// When the file was last modified (ms since 1970); 0 means unknown, so it gets re-hashed.
    /// A file whose size and time still match isn't hashed again.
    pub mtime_ms: u64,
}

impl SyncState {
    pub fn load(path: &Path) -> Self {
        match fs::read(path) {
            Ok(bytes) => serde_json::from_slice(&bytes).unwrap_or_else(|error| {
                tracing::warn!("{} is damaged ({error}); starting from scratch", path.display());
                Self::default()
            }),
            Err(_) => Self::default(),
        }
    }

    pub fn save(&self, path: &Path) -> io::Result<()> {
        let bytes = serde_json::to_vec_pretty(self).map_err(io::Error::other)?;
        write_atomic(path, &bytes)
    }
}

/// Writes to a temp file, flushes it to disk and renames it over the old one, so a crash
/// leaves either the old file or the new one, never half of one.
pub fn write_atomic(path: &Path, bytes: &[u8]) -> io::Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let part = part_path(path);
    let mut file = fs::File::create(&part)?;
    file.write_all(bytes)?;
    file.sync_all()?;
    drop(file);
    fs::rename(&part, path)
}

/// Where a file is written before it's renamed into place. Fabric ignores it: it isn't a .jar.
pub fn part_path(path: &Path) -> std::path::PathBuf {
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    path.with_file_name(format!("{name}.cuc-part"))
}

pub fn mtime_ms(meta: &fs::Metadata) -> u64 {
    meta.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map_or(0, |d| d.as_millis() as u64)
}
