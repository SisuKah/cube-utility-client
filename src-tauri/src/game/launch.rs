//! Building the command that starts Minecraft.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use serde::Deserialize;

use crate::config;
use crate::paths::Paths;

use super::args;
use super::install::GamePlan;
use super::rules::{Features, Platform};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LaunchTarget {
    /// Joins play.cubecraft.net straight away (Quick Play).
    Cubecraft,
    /// Opens the title screen.
    Title,
}

/// What the player chose on Home and in Settings.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LaunchOptions {
    pub target: LaunchTarget,
    pub memory_gb: u32,
    pub hide_while_playing: bool,
}

/// Who is playing. Milestone 4 fills this from Microsoft sign-in.
#[derive(Debug, Clone)]
pub struct Account {
    pub name: String,
    pub uuid: String,
    pub access_token: String,
    pub xuid: String,
}

/// Development builds only: an offline player, so the game can start before sign-in exists.
/// Offline players can't join online servers, but the title screen and singleplayer work.
#[cfg(debug_assertions)]
pub fn dev_account() -> Account {
    use sha1::{Digest, Sha1};
    let name = "DevPlayer";
    // A stable UUID from the name, with the version and variant bits set.
    let digest = Sha1::digest(format!("OfflinePlayer:{name}").as_bytes());
    let mut bytes = [0u8; 16];
    bytes.copy_from_slice(&digest[..16]);
    bytes[6] = (bytes[6] & 0x0f) | 0x50;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    Account { name: name.to_string(), uuid: crate::net::hex(&bytes), access_token: "0".to_string(), xuid: "0".to_string() }
}

/// The Java program to run and its arguments.
pub struct GameCommand {
    pub java: PathBuf,
    pub args: Vec<String>,
    pub game_dir: PathBuf,
}

pub fn build(plan: &GamePlan, paths: &Paths, account: &Account, options: &LaunchOptions, launcher_version: &str) -> GameCommand {
    let platform = Platform::current();
    let features = Features { quick_play_multiplayer: options.target == LaunchTarget::Cubecraft };
    let separator = if cfg!(windows) { ";" } else { ":" };
    let classpath = plan.classpath.iter().map(|p| text(p)).collect::<Vec<_>>().join(separator);
    let game_dir = paths.instance();

    let vars: HashMap<&str, String> = HashMap::from([
        ("auth_player_name", account.name.clone()),
        ("version_name", plan.version.id.clone()),
        ("game_directory", text(&game_dir)),
        ("assets_root", text(&paths.assets())),
        ("assets_index_name", plan.asset_index_id.clone()),
        ("auth_uuid", account.uuid.clone()),
        ("auth_access_token", account.access_token.clone()),
        ("clientid", "0".to_string()),
        ("auth_xuid", account.xuid.clone()),
        ("user_type", "msa".to_string()),
        ("user_properties", "{}".to_string()),
        ("version_type", plan.version.kind.clone().unwrap_or_else(|| "release".to_string())),
        ("natives_directory", text(&plan.natives_dir)),
        ("launcher_name", "cube-utility-client".to_string()),
        ("launcher_version", launcher_version.to_string()),
        ("classpath", classpath),
        ("classpath_separator", separator.to_string()),
        ("library_directory", text(&paths.libraries())),
        ("quickPlayMultiplayer", config::QUICK_PLAY_SERVER.to_string()),
    ]);

    let memory = options.memory_gb.clamp(2, 16);
    let mut args = vec![format!("-Xmx{memory}G"), format!("-Xms{}G", memory.min(2))];
    args.extend(args::expand(&plan.version.arguments.jvm, &platform, &features, &vars));
    args.push(plan.version.main_class.clone());
    args.extend(args::expand(&plan.version.arguments.game, &platform, &features, &vars));
    GameCommand { java: plan.java.clone(), args, game_dir }
}

fn text(path: &Path) -> String {
    path.to_string_lossy().into_owned()
}

/// The command line for the log, without the access token and with long values shortened.
pub fn describe(args: &[String]) -> String {
    let mut out = Vec::with_capacity(args.len());
    let mut hide_next = false;
    for arg in args {
        if hide_next {
            out.push("<hidden>".to_string());
            hide_next = false;
            continue;
        }
        hide_next = arg == "--accessToken";
        if arg.chars().count() > 160 {
            out.push(format!("{}…", arg.chars().take(160).collect::<String>()));
        } else {
            out.push(arg.clone());
        }
    }
    out.join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::game::version::VersionJson;

    fn plan() -> GamePlan {
        let version: VersionJson = serde_json::from_str(
            r#"{
                "id": "fabric-loader-0.20.1-26.3", "type": "release",
                "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                "arguments": {
                    "game": ["--username", "${auth_player_name}", "--accessToken", "${auth_access_token}",
                        {"rules":[{"action":"allow","features":{"is_quick_play_multiplayer":true}}],"value":["--quickPlayMultiplayer","${quickPlayMultiplayer}"]}],
                    "jvm": ["-Djava.library.path=${natives_directory}/java", "-cp", "${classpath}", "-DFabricMcEmu= net.minecraft.client.main.Main "]
                }
            }"#,
        )
        .unwrap();
        GamePlan {
            version,
            classpath: vec![PathBuf::from("C:/libs/a.jar"), PathBuf::from("C:/versions/26.3/26.3.jar")],
            java: PathBuf::from("C:/java/bin/javaw.exe"),
            natives_dir: PathBuf::from("C:/natives"),
            asset_index_id: "34".to_string(),
            downloads: Vec::new(),
            directories: Vec::new(),
        }
    }

    fn account() -> Account {
        Account { name: "Steve".into(), uuid: "0".repeat(32), access_token: "secret-token".into(), xuid: "0".into() }
    }

    fn options(target: LaunchTarget) -> LaunchOptions {
        LaunchOptions { target, memory_gb: 6, hide_while_playing: true }
    }

    #[test]
    fn builds_the_command_line_in_order() {
        let paths = Paths::new(PathBuf::from("C:/data"));
        let cmd = build(&plan(), &paths, &account(), &options(LaunchTarget::Title), "1.0.0");
        assert_eq!(cmd.args[0], "-Xmx6G");
        assert_eq!(cmd.args[1], "-Xms2G");
        assert!(cmd.args.contains(&"-Djava.library.path=C:/natives/java".to_string()));
        let cp = cmd.args.iter().position(|a| a == "-cp").unwrap();
        assert!(cmd.args[cp + 1].ends_with("26.3.jar"));
        assert!(cmd.args.contains(&"-DFabricMcEmu= net.minecraft.client.main.Main ".to_string()));
        let main = cmd.args.iter().position(|a| a == "net.fabricmc.loader.impl.launch.knot.KnotClient").unwrap();
        assert_eq!(cmd.args[main + 1..], ["--username", "Steve", "--accessToken", "secret-token"]);
    }

    #[test]
    fn quick_play_joins_cubecraft() {
        let paths = Paths::new(PathBuf::from("C:/data"));
        let cmd = build(&plan(), &paths, &account(), &options(LaunchTarget::Cubecraft), "1.0.0");
        let quick = cmd.args.iter().position(|a| a == "--quickPlayMultiplayer").unwrap();
        assert_eq!(cmd.args[quick + 1], "play.cubecraft.net");
    }

    #[test]
    fn memory_is_kept_within_limits() {
        let paths = Paths::new(PathBuf::from("C:/data"));
        let mut opts = options(LaunchTarget::Title);
        opts.memory_gb = 64;
        assert_eq!(build(&plan(), &paths, &account(), &opts, "1.0.0").args[0], "-Xmx16G");
        opts.memory_gb = 1;
        assert_eq!(build(&plan(), &paths, &account(), &opts, "1.0.0").args[0], "-Xmx2G");
    }

    #[test]
    fn logs_never_show_the_access_token() {
        let line = describe(&["--accessToken".to_string(), "secret-token".to_string(), "--username".to_string(), "Steve".to_string()]);
        assert!(!line.contains("secret-token"));
        assert!(line.contains("<hidden>"));
    }

    #[test]
    fn dev_account_has_a_valid_uuid() {
        let account = dev_account();
        assert_eq!(account.uuid.len(), 32);
        assert_eq!(&account.uuid[12..13], "5");
        assert_eq!(account.uuid, dev_account().uuid);
    }
}
