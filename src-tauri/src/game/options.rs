//! Minecraft's options.txt. Before every launch the launcher marks the game's welcome screen
//! (the narrator, accessibility and language questions) as done, so Minecraft opens on its
//! title screen. On a player's first launch it also brings over the settings of the Minecraft
//! they already play: keybinds, video, sound and language.

use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::paths::Paths;
use crate::sync::write_atomic;

/// While this isn't `false`, Minecraft opens on its welcome screen.
const WELCOME_KEY: &str = "onboardAccessibility";

/// Resource pack choices name pack files in the other install's own folder, which aren't here.
const NOT_COPIED: &[&str] = &["resourcePacks", "incompatibleResourcePacks"];

/// options.txt as ordered `key:value` lines. Values can hold colons too (`lastServer`), so a
/// line splits at its first one, the way Minecraft reads it.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Options {
    lines: Vec<(String, String)>,
}

impl Options {
    pub fn parse(text: &str) -> Self {
        let lines = text
            .lines()
            .filter_map(|line| line.split_once(':'))
            .filter(|(key, _)| !key.is_empty())
            .map(|(key, value)| (key.to_string(), value.to_string()))
            .collect();
        Self { lines }
    }

    pub fn get(&self, key: &str) -> Option<&str> {
        self.lines.iter().find(|(k, _)| k == key).map(|(_, value)| value.as_str())
    }

    pub fn set(&mut self, key: &str, value: &str) {
        match self.lines.iter_mut().find(|(k, _)| k == key) {
            Some((_, old)) => *old = value.to_string(),
            None => self.lines.push((key.to_string(), value.to_string())),
        }
    }

    pub fn remove(&mut self, key: &str) {
        self.lines.retain(|(k, _)| k != key);
    }

    pub fn to_text(&self) -> String {
        self.lines.iter().map(|(key, value)| format!("{key}:{value}\n")).collect()
    }
}

/// The options.txt of another Minecraft install on this PC.
#[derive(Debug, Clone, PartialEq)]
pub struct Found {
    pub path: PathBuf,
    /// Where it's from, like "Minecraft Launcher" or "Modrinth App · Fabulously Optimized".
    pub label: String,
    pub modified: SystemTime,
}

/// The settings files of the player's other Minecraft installs, newest first: the Minecraft
/// Launcher (its own folder and its profiles' game folders), Modrinth App, Prism Launcher,
/// CurseForge and Lunar Client. Mods keep files called options.txt in their config folders
/// too, so only game folders are looked at.
pub fn find_others(appdata: &Path, home: &Path) -> Vec<Found> {
    let mut found: Vec<Found> = Vec::new();
    let mut add = |dir: PathBuf, label: String| {
        let path = dir.join("options.txt");
        let Ok(meta) = std::fs::metadata(&path) else { return };
        if meta.is_file() && !found.iter().any(|other| same_file(&other.path, &path)) {
            found.push(Found { path, label, modified: meta.modified().unwrap_or(UNIX_EPOCH) });
        }
    };

    let minecraft = appdata.join(".minecraft");
    add(minecraft.clone(), "Minecraft Launcher".to_string());
    for (name, dir) in launcher_profiles(&minecraft) {
        add(dir, format!("Minecraft Launcher · {name}"));
    }
    for app in ["ModrinthApp", "com.modrinth.theseus"] {
        for (name, dir) in folders(&appdata.join(app).join("profiles")) {
            add(dir, format!("Modrinth App · {name}"));
        }
    }
    for (name, dir) in folders(&appdata.join("PrismLauncher").join("instances")) {
        add(dir.join(".minecraft"), format!("Prism Launcher · {name}"));
        add(dir.join("minecraft"), format!("Prism Launcher · {name}"));
    }
    for (name, dir) in folders(&home.join("curseforge").join("minecraft").join("Instances")) {
        add(dir, format!("CurseForge · {name}"));
    }
    for (name, dir) in folders(&home.join(".lunarclient").join("profiles")) {
        add(dir, format!("Lunar Client · {name}"));
    }

    found.sort_by_key(|other| std::cmp::Reverse(other.modified));
    found
}

fn same_file(a: &Path, b: &Path) -> bool {
    a == b || matches!((std::fs::canonicalize(a), std::fs::canonicalize(b)), (Ok(x), Ok(y)) if x == y)
}

/// The folders directly inside `root`, with their names.
fn folders(root: &Path) -> Vec<(String, PathBuf)> {
    let Ok(entries) = std::fs::read_dir(root) else { return Vec::new() };
    entries
        .filter_map(|entry| entry.ok())
        .filter(|entry| entry.file_type().is_ok_and(|kind| kind.is_dir()))
        .map(|entry| (entry.file_name().to_string_lossy().into_owned(), entry.path()))
        .collect()
}

/// The Minecraft Launcher's profiles that have a game folder of their own.
fn launcher_profiles(minecraft: &Path) -> Vec<(String, PathBuf)> {
    let Some(json) = std::fs::read(minecraft.join("launcher_profiles.json")).ok().and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
    else {
        return Vec::new();
    };
    let Some(profiles) = json.get("profiles").and_then(|profiles| profiles.as_object()) else { return Vec::new() };
    profiles
        .values()
        .filter_map(|profile| {
            let dir = profile.get("gameDir")?.as_str()?.trim();
            let name = [profile.get("name"), profile.get("lastVersionId")]
                .into_iter()
                .flatten()
                .filter_map(|value| value.as_str())
                .find(|name| !name.trim().is_empty())
                .unwrap_or("profile");
            (!dir.is_empty()).then(|| (name.to_string(), PathBuf::from(dir)))
        })
        .collect()
}

/// What `prepare` did.
#[derive(Debug, PartialEq)]
pub enum Prepared {
    /// The player has used this game folder before: nothing to change.
    Unchanged,
    /// The welcome screen is marked as done; everything else is Minecraft's default.
    SkippedWelcome,
    /// The player's settings came over from another install (its label).
    Imported(String),
}

/// Makes Minecraft open on its title screen. While the game folder is unused (no options.txt
/// yet, or one still waiting on the welcome screen), it first takes the newest of the player's
/// other settings files, and keeps the file it replaces in the launcher's state folder.
pub fn prepare(paths: &Paths, others: &[Found]) -> std::io::Result<Prepared> {
    let file = paths.instance().join("options.txt");
    let existing = std::fs::read_to_string(&file).ok();
    let mut options = existing.as_deref().map(Options::parse).unwrap_or_default();
    if options.get(WELCOME_KEY) == Some("false") {
        return Ok(Prepared::Unchanged);
    }

    let theirs = others.iter().find_map(|other| {
        let parsed = Options::parse(&std::fs::read_to_string(&other.path).ok()?);
        // A real options.txt always says which Minecraft wrote it. Minecraft brings an older
        // one up to date by itself.
        parsed.get("version")?;
        Some((parsed, other.label.clone()))
    });
    let result = match theirs {
        Some((parsed, label)) => {
            if let Some(text) = &existing {
                write_atomic(&paths.state().join("options-before-import.txt"), text.as_bytes())?;
            }
            options = parsed;
            for key in NOT_COPIED {
                options.remove(key);
            }
            Prepared::Imported(label)
        }
        None => Prepared::SkippedWelcome,
    };
    options.set(WELCOME_KEY, "false");
    write_atomic(&file, options.to_text().as_bytes())?;
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    /// A fresh folder under the system temp folder, removed again when dropped.
    struct TempDir(PathBuf);

    impl TempDir {
        fn new(name: &str) -> Self {
            let dir = std::env::temp_dir().join(format!("cuc-options-{name}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).unwrap();
            Self(dir)
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn write(path: &Path, text: &str) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, text).unwrap();
    }

    fn found(path: PathBuf, label: &str) -> Found {
        Found { path, label: label.to_string(), modified: UNIX_EPOCH }
    }

    const THEIRS: &str = "version:5023\nlang:de_de\nkey_key.jump:key.keyboard.space\nlastServer:play.cubecraft.net:25565\nresourcePacks:[\"vanilla\",\"file/ocd.zip\"]\nincompatibleResourcePacks:[]\nonboardAccessibility:false\n";

    #[test]
    fn keeps_every_line_and_colons_in_values() {
        let options = Options::parse(THEIRS);
        assert_eq!(options.to_text(), THEIRS);
        assert_eq!(options.get("lastServer"), Some("play.cubecraft.net:25565"));
        assert_eq!(Options::parse("a:1\r\nb:2\r\n").to_text(), "a:1\nb:2\n");
    }

    #[test]
    fn set_replaces_in_place_or_adds_at_the_end() {
        let mut options = Options::parse("a:1\nb:2\n");
        options.set("a", "3");
        options.set("c", "4");
        options.remove("b");
        assert_eq!(options.to_text(), "a:3\nc:4\n");
    }

    #[test]
    fn first_launch_takes_the_players_settings() {
        let dir = TempDir::new("import");
        let paths = Paths::new(dir.0.join("data"));
        let theirs = dir.0.join("minecraft").join("options.txt");
        write(&theirs, THEIRS);
        let not_options = dir.0.join("empty").join("options.txt");
        write(&not_options, "hello\n");

        let result = prepare(&paths, &[found(not_options, "Mod config"), found(theirs, "Minecraft Launcher")]).unwrap();
        assert_eq!(result, Prepared::Imported("Minecraft Launcher".to_string()));
        let ours = Options::parse(&std::fs::read_to_string(paths.instance().join("options.txt")).unwrap());
        assert_eq!(ours.get("lang"), Some("de_de"));
        assert_eq!(ours.get("key_key.jump"), Some("key.keyboard.space"));
        assert_eq!(ours.get("version"), Some("5023"));
        assert_eq!(ours.get(WELCOME_KEY), Some("false"));
        assert_eq!(ours.get("resourcePacks"), None);
        assert!(!paths.state().join("options-before-import.txt").exists());

        // From then on the player's own changes stay.
        assert_eq!(prepare(&paths, &[found(dir.0.join("minecraft").join("options.txt"), "Minecraft Launcher")]).unwrap(), Prepared::Unchanged);
    }

    #[test]
    fn a_folder_still_on_the_welcome_screen_counts_as_unused() {
        let dir = TempDir::new("welcome");
        let paths = Paths::new(dir.0.join("data"));
        let untouched = "version:5023\nlang:en_us\nonboardAccessibility:true\n";
        write(&paths.instance().join("options.txt"), untouched);
        let theirs = dir.0.join("minecraft").join("options.txt");
        write(&theirs, THEIRS);

        assert_eq!(prepare(&paths, &[found(theirs, "Minecraft Launcher")]).unwrap(), Prepared::Imported("Minecraft Launcher".to_string()));
        assert_eq!(std::fs::read_to_string(paths.state().join("options-before-import.txt")).unwrap(), untouched);
    }

    #[test]
    fn without_other_installs_only_the_welcome_screen_goes() {
        let dir = TempDir::new("defaults");
        let paths = Paths::new(dir.0.join("data"));
        assert_eq!(prepare(&paths, &[]).unwrap(), Prepared::SkippedWelcome);
        assert_eq!(std::fs::read_to_string(paths.instance().join("options.txt")).unwrap(), "onboardAccessibility:false\n");

        write(&paths.instance().join("options.txt"), "version:5023\nfov:0.5\nonboardAccessibility:true\n");
        assert_eq!(prepare(&paths, &[]).unwrap(), Prepared::SkippedWelcome);
        assert_eq!(std::fs::read_to_string(paths.instance().join("options.txt")).unwrap(), "version:5023\nfov:0.5\nonboardAccessibility:false\n");
    }

    #[test]
    fn a_played_folder_is_left_alone() {
        let dir = TempDir::new("played");
        let paths = Paths::new(dir.0.join("data"));
        let played = "version:5023\nfov:0.75\nonboardAccessibility:false\n";
        write(&paths.instance().join("options.txt"), played);
        let theirs = dir.0.join("minecraft").join("options.txt");
        write(&theirs, THEIRS);
        assert_eq!(prepare(&paths, &[found(theirs, "Minecraft Launcher")]).unwrap(), Prepared::Unchanged);
        assert_eq!(std::fs::read_to_string(paths.instance().join("options.txt")).unwrap(), played);
    }

    #[test]
    fn finds_game_folders_of_known_launchers_newest_first() {
        let dir = TempDir::new("find");
        let (appdata, home) = (dir.0.join("Roaming"), dir.0.join("home"));
        let old = SystemTime::now() - Duration::from_secs(3600);
        let place = |path: PathBuf, modified: SystemTime| {
            write(&path, "version:5023\n");
            std::fs::File::options().write(true).open(&path).unwrap().set_modified(modified).unwrap();
        };
        place(appdata.join(".minecraft").join("options.txt"), old);
        place(appdata.join("ModrinthApp").join("profiles").join("CubeCraft").join("options.txt"), SystemTime::now());
        place(appdata.join("PrismLauncher").join("instances").join("Skyblock").join(".minecraft").join("options.txt"), old - Duration::from_secs(60));
        place(home.join(".lunarclient").join("profiles").join("1.21").join("options.txt"), old - Duration::from_secs(120));
        // A mod's own config file with the same name isn't a game folder.
        place(appdata.join("ModrinthApp").join("profiles").join("CubeCraft").join("config").join("fancymenu").join("options.txt"), SystemTime::now());
        // A Minecraft Launcher profile with its own game folder, and one that uses the default.
        let profile_dir = dir.0.join("games").join("26.3");
        place(profile_dir.join("options.txt"), old - Duration::from_secs(30));
        let profiles = serde_json::json!({ "profiles": {
            "a": { "name": "Fabric 26.3", "gameDir": profile_dir.to_string_lossy() },
            "b": { "name": "", "lastVersionId": "latest-release" }
        }});
        write(&appdata.join(".minecraft").join("launcher_profiles.json"), &profiles.to_string());

        let labels: Vec<String> = find_others(&appdata, &home).into_iter().map(|f| f.label).collect();
        assert_eq!(labels, ["Modrinth App · CubeCraft", "Minecraft Launcher", "Minecraft Launcher · Fabric 26.3", "Prism Launcher · Skyblock", "Lunar Client · 1.21"]);
    }
}
