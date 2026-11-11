//! Brand- and deployment-specific values for the Rust side, kept in one place.
//! Values marked TODO get filled in by later milestones.

// Some values are only used by later milestones.
#![allow(dead_code)]

use std::time::Duration;

/// Folder under %APPDATA% that holds the game, the download cache and launcher state.
pub const DATA_FOLDER: &str = ".cubeutilityclient";

/// The game to install while no pack is installed yet (or no pack server is set). Once a pack
/// is installed, its manifest decides the Minecraft, Fabric and Java versions.
pub const DEFAULT_MINECRAFT_VERSION: &str = "26.3";
pub const DEFAULT_FABRIC_LOADER_VERSION: &str = "0.20.1";

/// Where the pack is published (GitHub Pages): `<base>/<channel>/manifest.json`, next to
/// `manifest.json.sig`. Release builds sync from here.
pub const PACK_BASE_URL: &str = "https://sisukah.github.io/cube-utility-client";

/// Debug builds sync from `npm run pack:serve` on this PC, or from CUC_PACK_URL when it's set.
pub const DEV_PACK_BASE_URL: &str = "http://127.0.0.1:8787";

pub const DEFAULT_CHANNEL: &str = "stable";

/// The ed25519 public key from `npm run keys:generate`. Launchers only trust manifests signed
/// with the matching secret key.
pub const PACK_SIGNING_PUBLIC_KEY: &str = "85260080024878bf1e5a0443ddbe4a5b573e070ad2c696fe4c09b73466490628";

/// How often the launcher looks for a pack update while it's open.
pub const PACK_CHECK_INTERVAL: Duration = if cfg!(debug_assertions) { Duration::from_secs(30) } else { Duration::from_secs(600) };

/// TODO (milestone 4): client ID of the Azure app, once Mojang approves it.
pub const AZURE_CLIENT_ID: &str = "";

pub const QUICK_PLAY_SERVER: &str = "play.cubecraft.net";

/// The pack server's address, without a trailing slash. None when there is none.
pub fn pack_base_url() -> Option<String> {
    let base = if cfg!(debug_assertions) {
        std::env::var("CUC_PACK_URL").ok().filter(|url| !url.trim().is_empty()).unwrap_or_else(|| DEV_PACK_BASE_URL.to_string())
    } else {
        PACK_BASE_URL.to_string()
    };
    let base = base.trim().trim_end_matches('/').to_string();
    (!base.is_empty()).then_some(base)
}

pub fn manifest_url(channel: &str) -> Option<String> {
    pack_base_url().map(|base| format!("{base}/{channel}/manifest.json"))
}
