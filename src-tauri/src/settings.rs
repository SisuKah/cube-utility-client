//! The player's settings, kept on the Rust side in state/settings.json, where PLAY and the
//! pack sync read them.

use std::path::PathBuf;
use std::sync::{Mutex, MutexGuard};

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};

use crate::error::{Error, Result};
use crate::game::launch::LaunchTarget;
use crate::paths::Paths;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub memory_gb: u32,
    pub hide_while_playing: bool,
    pub beta_updates: bool,
    pub launch_target: LaunchTarget,
}

impl Default for Settings {
    fn default() -> Self {
        Self { memory_gb: 4, hide_while_playing: true, beta_updates: false, launch_target: LaunchTarget::Cubecraft }
    }
}

/// How much memory Minecraft may get on this PC (spec 3.3).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MemoryLimits {
    pub total_gb: u32,
    /// 16 GB or half the PC's memory, whichever is lower.
    pub max_gb: u32,
    /// 4 GB, or 3 GB on PCs with less than 8 GB.
    pub default_gb: u32,
}

impl MemoryLimits {
    pub fn for_total(total_gb: u32) -> Self {
        let max_gb = (total_gb / 2).clamp(2, 16);
        let default_gb = if total_gb < 8 { 3 } else { 4 }.min(max_gb);
        Self { total_gb, max_gb, default_gb }
    }
}

/// A change from the UI; missing fields stay as they are.
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsChange {
    pub memory_gb: Option<u32>,
    pub hide_while_playing: Option<bool>,
    pub beta_updates: Option<bool>,
    pub launch_target: Option<LaunchTarget>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsView {
    #[serde(flatten)]
    pub settings: Settings,
    pub memory: MemoryLimits,
    /// No settings were saved yet (the UI moves its old browser-stored ones over).
    pub fresh: bool,
}

pub struct SettingsStore {
    file: PathBuf,
    limits: MemoryLimits,
    settings: Mutex<Settings>,
    fresh: Mutex<bool>,
}

impl SettingsStore {
    pub fn load(paths: &Paths, limits: MemoryLimits) -> Self {
        let file = paths.state().join("settings.json");
        let saved = std::fs::read(&file).ok().and_then(|bytes| serde_json::from_slice::<Settings>(&bytes).ok());
        let fresh = saved.is_none();
        let mut settings = saved.unwrap_or_else(|| Settings { memory_gb: limits.default_gb, ..Settings::default() });
        settings.memory_gb = settings.memory_gb.clamp(2, limits.max_gb);
        Self { file, limits, settings: Mutex::new(settings), fresh: Mutex::new(fresh) }
    }

    fn lock(&self) -> MutexGuard<'_, Settings> {
        self.settings.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn get(&self) -> Settings {
        self.lock().clone()
    }

    pub fn view(&self) -> SettingsView {
        SettingsView { settings: self.get(), memory: self.limits, fresh: *self.fresh.lock().unwrap_or_else(|e| e.into_inner()) }
    }

    pub fn update(&self, change: SettingsChange) -> Result<Settings> {
        let mut settings = self.lock();
        if let Some(memory_gb) = change.memory_gb {
            settings.memory_gb = memory_gb.clamp(2, self.limits.max_gb);
        }
        if let Some(hide) = change.hide_while_playing {
            settings.hide_while_playing = hide;
        }
        if let Some(beta) = change.beta_updates {
            settings.beta_updates = beta;
        }
        if let Some(target) = change.launch_target {
            settings.launch_target = target;
        }
        let bytes = serde_json::to_vec_pretty(&*settings).map_err(|e| Error::Message(e.to_string()))?;
        crate::sync::write_atomic(&self.file, &bytes)?;
        *self.fresh.lock().unwrap_or_else(|e| e.into_inner()) = false;
        Ok(settings.clone())
    }
}

#[tauri::command]
pub fn get_settings(store: tauri::State<'_, SettingsStore>) -> SettingsView {
    store.view()
}

#[tauri::command]
pub fn update_settings(app: AppHandle, change: SettingsChange) -> Result<SettingsView> {
    let store = app.state::<SettingsStore>();
    let before = store.get();
    let after = store.update(change)?;
    // Beta updates on or off: sync from the other channel now.
    if before.beta_updates != after.beta_updates {
        crate::launcher::sync_soon(&app);
    }
    Ok(store.view())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn memory_limits_follow_the_spec() {
        assert_eq!(MemoryLimits::for_total(32), MemoryLimits { total_gb: 32, max_gb: 16, default_gb: 4 });
        assert_eq!(MemoryLimits::for_total(64).max_gb, 16);
        assert_eq!(MemoryLimits::for_total(8), MemoryLimits { total_gb: 8, max_gb: 4, default_gb: 4 });
        assert_eq!(MemoryLimits::for_total(6), MemoryLimits { total_gb: 6, max_gb: 3, default_gb: 3 });
        assert_eq!(MemoryLimits::for_total(4), MemoryLimits { total_gb: 4, max_gb: 2, default_gb: 2 });
    }

    #[test]
    fn settings_save_and_load_within_limits() {
        let dir = std::env::temp_dir().join(format!("cuc-settings-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let paths = Paths::new(dir.clone());
        let limits = MemoryLimits::for_total(16);

        let store = SettingsStore::load(&paths, limits);
        assert!(store.view().fresh);
        assert_eq!(store.get().memory_gb, 4);
        let saved = store
            .update(SettingsChange { memory_gb: Some(64), beta_updates: Some(true), launch_target: Some(LaunchTarget::Title), ..Default::default() })
            .unwrap();
        assert_eq!(saved.memory_gb, 8, "clamped to half of 16 GB");

        let again = SettingsStore::load(&paths, limits);
        assert!(!again.view().fresh);
        assert_eq!(again.get(), Settings { memory_gb: 8, hide_while_playing: true, beta_updates: true, launch_target: LaunchTarget::Title });
        let json = serde_json::to_value(again.view()).unwrap();
        assert_eq!(json["launchTarget"], "title");
        assert_eq!(json["memory"]["maxGb"], 8);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
