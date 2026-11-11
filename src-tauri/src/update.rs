//! Launcher self-update. Release builds look for a newer launcher on GitHub Releases at start
//! and every 30 minutes, and download it in the background (Tauri's updater checks it against
//! the updater key). Then the player chooses: "Restart" installs it now; otherwise it installs
//! when the launcher quits. The updater never installs an older version.

use std::sync::{Mutex, MutexGuard};
use std::time::Duration;

use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_updater::{Update, UpdaterExt};

use crate::error::{Error, Result};
use crate::launcher::Launcher;

const READY_EVENT: &str = "update://ready";
/// Gives the first pack sync a head start.
const FIRST_CHECK_DELAY: Duration = Duration::from_secs(20);
const CHECK_INTERVAL: Duration = Duration::from_secs(30 * 60);

/// A downloaded launcher update, waiting to be installed.
struct Ready {
    update: Update,
    bytes: Vec<u8>,
}

#[derive(Default)]
pub struct SelfUpdate {
    ready: Mutex<Option<Ready>>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    pub version: String,
}

fn lock(state: &SelfUpdate) -> MutexGuard<'_, Option<Ready>> {
    state.ready.lock().unwrap_or_else(|e| e.into_inner())
}

/// Only release builds update themselves: a dev build would replace itself with a release.
pub fn enabled() -> bool {
    !cfg!(debug_assertions)
}

pub fn start(app: AppHandle) {
    if !enabled() {
        return;
    }
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(FIRST_CHECK_DELAY).await;
        loop {
            check(&app).await;
            tokio::time::sleep(CHECK_INTERVAL).await;
        }
    });
}

async fn check(app: &AppHandle) {
    let state = app.state::<SelfUpdate>();
    if lock(&state).is_some() {
        return;
    }
    let update = match app.updater().map_err(|e| e.to_string()) {
        Ok(updater) => match updater.check().await {
            Ok(Some(update)) => update,
            Ok(None) => {
                tracing::info!("the launcher is up to date");
                return;
            }
            Err(error) => {
                tracing::warn!("couldn't check for a launcher update: {error}");
                return;
            }
        },
        Err(error) => {
            tracing::warn!("the updater isn't available: {error}");
            return;
        }
    };
    tracing::info!("launcher {} is out; downloading it", update.version);
    // download() checks the signature before returning the bytes.
    match update.download(|_, _| {}, || {}).await {
        Ok(bytes) => {
            let info = UpdateInfo { version: update.version.clone() };
            *lock(&state) = Some(Ready { update, bytes });
            tracing::info!("launcher {} is ready to install", info.version);
            let _ = app.emit(READY_EVENT, info);
        }
        Err(error) => tracing::warn!("the launcher update didn't download: {error}"),
    }
}

/// The downloaded update waiting to be installed, if there is one.
#[tauri::command]
pub fn update_ready(state: tauri::State<'_, SelfUpdate>) -> Option<UpdateInfo> {
    lock(&state).as_ref().map(|ready| UpdateInfo { version: ready.update.version.clone() })
}

/// "Restart": installs the update now. On Windows the installer closes the launcher and opens
/// the new version when it's done.
#[tauri::command]
pub fn restart_to_update(app: AppHandle) -> Result<()> {
    if app.state::<Launcher>().is_playing() {
        return Err(Error::Message("Close Minecraft first, then restart the launcher.".into()));
    }
    let state = app.state::<SelfUpdate>();
    let ready = lock(&state).take().ok_or_else(|| Error::Message("There's no launcher update to install.".into()))?;
    tracing::info!("installing launcher {} and restarting", ready.update.version);
    if let Err(error) = ready.update.install(&ready.bytes) {
        let message = format!("Couldn't install the launcher update: {error}");
        *lock(&state) = Some(ready);
        return Err(Error::Message(message));
    }
    // Windows never gets here: the installer has taken over. Other systems restart now.
    app.restart();
}

/// When the launcher quits with an update waiting, it's installed then, without reopening.
pub fn install_on_quit(app: &AppHandle) {
    let Some(state) = app.try_state::<SelfUpdate>() else { return };
    let Some(ready) = lock(&state).take() else { return };
    tracing::info!("installing launcher {} as the launcher quits", ready.update.version);
    let update = ready.update.restart_after_install(false);
    if let Err(error) = update.install(&ready.bytes) {
        tracing::warn!("couldn't install the launcher update: {error}");
    }
}
