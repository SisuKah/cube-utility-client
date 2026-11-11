//! Pack sync: keeps the game folder's mods in line with the newest signed manifest.
//!
//! `prepare` asks the server for a newer pack and works out what has to change and what the
//! cache is missing; the launcher downloads that (showing progress), then `finish` applies the
//! changes and saves the state. If the downloads fail, the launcher prepares again for the
//! installed pack instead.

mod apply;
mod fetch;
mod plan;
mod state;

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use cuc_manifest::Manifest;

use crate::config;
use crate::error::{Error, Result};
use crate::net::{Download, Hash, Net};
use crate::paths::Paths;

use apply::{Changes, Want};
use fetch::{Response, TrustError, Verdict};
pub use plan::Choices;
use state::SyncState;

/// A manifest that passed the checks, with the exact bytes its signature covers.
#[derive(Clone)]
struct Signed {
    manifest: Arc<Manifest>,
    bytes: Vec<u8>,
    signature: String,
    etag: Option<String>,
}

enum Fetch {
    /// No pack server is set.
    NoServer,
    Offline(String),
    NothingPublished,
    /// The installed pack is the newest; maybe with a new ETag for it.
    Unchanged(Option<String>),
    Newer(Signed),
    Rejected(TrustError),
}

/// What a sync found and did, for the UI.
#[derive(Debug, Clone, Default)]
pub struct Report {
    /// The pack now installed.
    pub manifest: Option<Arc<Manifest>>,
    /// The server couldn't be reached.
    pub offline: bool,
    /// Something to tell the player that didn't stop the sync, like a manifest that was ignored.
    pub problem: Option<String>,
    /// The game folder changed.
    pub changed: bool,
    pub mods_installed: usize,
    /// Jars the player added to mods/ themselves.
    pub extras: Vec<String>,
    /// Extra jars moved to mods-disabled/ because the pack doesn't allow them.
    pub moved_extras: Vec<String>,
    /// The last time the server answered (ms since 1970).
    pub checked_at_ms: Option<u64>,
}

/// A sync that's worked out, waiting for its downloads and the apply step.
pub struct Prepared {
    channel: String,
    /// Where serials and ETags are kept in the state: the manifest's address.
    key: String,
    target: Option<Signed>,
    /// The target is newer than the installed pack, so it gets saved once applied.
    is_update: bool,
    state: SyncState,
    changes: Changes,
    pub downloads: Vec<Download>,
    pub report: Report,
}

impl Prepared {
    pub fn is_update(&self) -> bool {
        self.is_update
    }

    pub fn has_changes(&self) -> bool {
        !self.changes.is_empty()
    }
}

pub struct PackSync {
    paths: Paths,
    net: Net,
    app_version: String,
}

impl PackSync {
    pub fn new(paths: Paths, net: Net, app_version: String) -> Self {
        Self { paths, net, app_version }
    }

    fn state_file(&self) -> PathBuf {
        self.paths.state().join("sync-state.json")
    }

    fn saved_manifest(&self, channel: &str) -> PathBuf {
        self.paths.state().join(format!("pack-{channel}.json"))
    }

    fn choices_file(&self) -> PathBuf {
        self.paths.state().join("mod-choices.json")
    }

    /// Serials and ETags belong to one manifest address: one channel on one server.
    fn state_key(channel: &str) -> String {
        config::manifest_url(channel).unwrap_or_else(|| channel.to_string())
    }

    /// The pack last installed on this channel, checked again.
    pub fn installed(&self, channel: &str) -> Option<Arc<Manifest>> {
        self.installed_signed(channel).map(|signed| signed.manifest)
    }

    fn installed_signed(&self, channel: &str) -> Option<Signed> {
        let path = self.saved_manifest(channel);
        let bytes = std::fs::read(&path).ok()?;
        let signature = std::fs::read_to_string(signature_path(&path)).ok()?.trim().to_string();
        let checked = cuc_manifest::verify(&bytes, &signature, config::PACK_SIGNING_PUBLIC_KEY)
            .and_then(|()| Manifest::parse(&bytes))
            .map_err(|e| e.to_string());
        match checked {
            Ok(manifest) => Some(Signed { manifest: Arc::new(manifest), bytes, signature, etag: None }),
            Err(error) => {
                tracing::warn!("the saved {channel} pack doesn't check out ({error}); ignoring it");
                None
            }
        }
    }

    /// What the last sync left: when the server last answered, and how many mods are installed.
    pub fn saved_status(&self) -> (Option<u64>, usize) {
        let state = SyncState::load(&self.state_file());
        (state.checked_at_ms, state.files.len())
    }

    /// The player's on/off choices for optional mods.
    pub fn choices(&self) -> Choices {
        std::fs::read(self.choices_file()).ok().and_then(|bytes| serde_json::from_slice(&bytes).ok()).unwrap_or_default()
    }

    pub fn set_choice(&self, id: &str, enabled: bool) -> Result<()> {
        let mut choices = self.choices();
        choices.insert(id.to_string(), enabled);
        let bytes = serde_json::to_vec_pretty(&choices).map_err(|e| Error::Message(e.to_string()))?;
        state::write_atomic(&self.choices_file(), &bytes)?;
        Ok(())
    }

    /// Asks the server whether a newer pack is out, without changing anything.
    pub async fn peek(&self, channel: &str) -> bool {
        let state = SyncState::load(&self.state_file());
        let installed = self.installed_signed(channel);
        matches!(self.fetch_trusted(channel, &state, installed.as_ref()).await, Fetch::Newer(_))
    }

    /// Asks the server for a newer pack (when `ask_server`), then works out what has to change
    /// in the game folder. `repair` re-checks every file instead of trusting sizes and dates.
    pub async fn prepare(&self, channel: &str, ask_server: bool, repair: bool) -> Result<Prepared> {
        let mut state = SyncState::load(&self.state_file());
        let key = Self::state_key(channel);
        let installed = self.installed_signed(channel);
        let mut report = Report::default();
        let mut newer = None;
        if ask_server {
            let answered = match self.fetch_trusted(channel, &state, installed.as_ref()).await {
                Fetch::NoServer => false,
                Fetch::Offline(reason) => {
                    tracing::info!("the pack server can't be reached: {reason}");
                    report.offline = true;
                    false
                }
                Fetch::NothingPublished => {
                    tracing::info!("nothing is published on the {channel} channel yet");
                    true
                }
                Fetch::Unchanged(etag) => {
                    if let Some(etag) = etag {
                        state.etags.insert(key.clone(), etag);
                    }
                    true
                }
                Fetch::Newer(signed) => {
                    tracing::info!("pack {} (#{}) is out", signed.manifest.pack_version, signed.manifest.serial);
                    newer = Some(signed);
                    true
                }
                Fetch::Rejected(error) => {
                    tracing::warn!("{error}");
                    report.problem = Some(error.to_string());
                    true
                }
            };
            if answered {
                state.checked_at_ms = Some(now_ms());
            }
        }
        report.checked_at_ms = state.checked_at_ms;

        let is_update = newer.is_some();
        let Some(target) = newer.or(installed) else {
            return Ok(Prepared {
                channel: channel.to_string(),
                key,
                target: None,
                is_update,
                state,
                changes: Changes::default(),
                downloads: Vec::new(),
                report,
            });
        };
        let choices = self.choices();
        let mods: Vec<Want> = plan::wanted(&target.manifest, &choices).into_iter().map(Want::mod_file).collect();
        let configs: Vec<_> = target.manifest.configs.iter().map(Want::config).collect();
        let allow_extras = target.manifest.allow_extra_mods;
        report.mods_installed = mods.len();
        report.manifest = Some(target.manifest.clone());

        let paths = self.paths.clone();
        let snapshot = state.clone();
        let (changes, downloads) = tokio::task::spawn_blocking(move || -> std::io::Result<(Changes, Vec<Download>)> {
            let changes = apply::changes(&paths.instance(), &snapshot, &mods, &configs, allow_extras, repair)?;
            let mut seen = HashSet::new();
            let downloads = changes
                .blobs()
                .filter(|want| seen.insert(want.sha512.clone()) && !blob_ok(&paths, want, repair))
                .map(|want| Download {
                    url: want.url.clone(),
                    dest: paths.cached_blob(&want.sha512),
                    hash: Some(Hash::Sha512(want.sha512.clone())),
                    size: Some(want.size),
                    label: want.label.clone(),
                })
                .collect();
            Ok((changes, downloads))
        })
        .await
        .map_err(join_error)??;
        report.extras = changes.extras.clone();
        Ok(Prepared { channel: channel.to_string(), key, target: Some(target), is_update, state, changes, downloads, report })
    }

    /// Applies the changes, then saves the new manifest, then the state that names its serial.
    pub async fn finish(&self, prepared: Prepared) -> Result<Report> {
        let Prepared { channel, key, target, is_update, mut state, changes, mut report, .. } = prepared;
        let paths = self.paths.clone();
        let state_file = self.state_file();
        let manifest_file = self.saved_manifest(&channel);
        let (changed, moved) = tokio::task::spawn_blocking(move || -> std::io::Result<(bool, Vec<String>)> {
            let changed = !changes.is_empty();
            let mut moved = Vec::new();
            if changed {
                let blob = |sha512: &str| paths.cached_blob(sha512);
                moved = apply::apply(&paths.instance(), &blob, &state_file, &mut state, &changes, &|_| Ok(()))?;
            }
            if let (Some(target), true) = (&target, is_update) {
                // The manifest goes to disk before the state that names its serial: if the
                // launcher stops in between, the next check simply installs it again.
                state::write_atomic(&manifest_file, &target.bytes)?;
                state::write_atomic(&signature_path(&manifest_file), target.signature.as_bytes())?;
                state.serials.insert(key.clone(), target.manifest.serial);
                match &target.etag {
                    Some(etag) => state.etags.insert(key.clone(), etag.clone()),
                    None => state.etags.remove(&key),
                };
            }
            state.save(&state_file)?;
            Ok((changed, moved))
        })
        .await
        .map_err(join_error)??;
        if changed {
            tracing::info!("the mods folder is up to date ({} mods)", report.mods_installed);
        }
        report.changed = changed;
        if !moved.is_empty() {
            report.extras.clear();
            report.moved_extras = moved;
        }
        Ok(report)
    }

    async fn fetch_trusted(&self, channel: &str, state: &SyncState, installed: Option<&Signed>) -> Fetch {
        let Some(url) = config::manifest_url(channel) else { return Fetch::NoServer };
        // "Has it changed?" only makes sense when the installed copy is what the ETag belongs to.
        let etag = installed.and(state.etags.get(&url)).map(String::as_str);
        let mut retried = false;
        loop {
            let (bytes, signature, etag) = match fetch::fetch(&self.net, &url, etag).await {
                Err(error) => return Fetch::Offline(error.to_string()),
                Ok(Response::NotModified) => return Fetch::Unchanged(None),
                Ok(Response::NotFound) => return Fetch::NothingPublished,
                Ok(Response::Body { bytes, signature, etag }) => (bytes, signature, etag),
            };
            let verdict = fetch::trust(
                &bytes,
                &signature,
                config::PACK_SIGNING_PUBLIC_KEY,
                channel,
                state.serials.get(&url).copied(),
                installed.map(|signed| signed.bytes.as_slice()),
                &self.app_version,
            );
            match verdict {
                Ok(Verdict::Unchanged) => return Fetch::Unchanged(etag),
                Ok(Verdict::Newer(manifest)) => {
                    return Fetch::Newer(Signed { manifest: Arc::new(*manifest), bytes, signature, etag });
                }
                // Right after a release, the manifest and its signature can be out of step for
                // a moment. One more try.
                Err(TrustError::Signature) if !retried => {
                    retried = true;
                    tokio::time::sleep(Duration::from_secs(2)).await;
                }
                Err(error) => return Fetch::Rejected(error),
            }
        }
    }
}

/// Whether the cache already has this file. Repair re-hashes it too.
fn blob_ok(paths: &Paths, want: &Want, deep: bool) -> bool {
    let path = paths.cached_blob(&want.sha512);
    match std::fs::metadata(&path) {
        Ok(meta) if meta.is_file() && meta.len() == want.size => !deep || apply::hash_file(&path).is_ok_and(|hash| hash == want.sha512),
        _ => false,
    }
}

fn signature_path(manifest: &Path) -> PathBuf {
    let name = manifest.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    manifest.with_file_name(format!("{name}.sig"))
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64)
}

fn join_error(error: tokio::task::JoinError) -> Error {
    Error::Message(format!("a sync step stopped unexpectedly: {error}"))
}
