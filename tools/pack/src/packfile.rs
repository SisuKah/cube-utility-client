//! pack/pack.toml: the modpack's source of truth, edited by hand.

use cuc_manifest::{ConfigPolicy, Group};
use serde::Deserialize;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PackFile {
    pub name: String,
    pub channel: String,
    pub minecraft: String,
    pub loader: Loader,
    #[serde(default)]
    pub java: Option<Java>,
    #[serde(default)]
    pub quick_play: Option<String>,
    #[serde(default = "yes")]
    pub allow_extra_mods: bool,
    #[serde(default = "first_launcher")]
    pub min_launcher_version: String,
    #[serde(default)]
    pub mods: Vec<ModEntry>,
    #[serde(default)]
    pub configs: Vec<ConfigEntry>,
}

fn yes() -> bool {
    true
}

fn first_launcher() -> String {
    "1.0.0".to_string()
}

fn if_missing() -> ConfigPolicy {
    ConfigPolicy::IfMissing
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Loader {
    #[serde(rename = "type")]
    pub kind: String,
    pub version: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Java {
    pub component: String,
    pub major: u32,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ModEntry {
    /// The id launchers know the mod by. Defaults to the jar's mod id or the Modrinth slug.
    #[serde(default)]
    pub id: Option<String>,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    /// One of my jars that isn't on Modrinth, relative to pack/ (like "jars/x.jar").
    #[serde(default)]
    pub file: Option<String>,
    /// A Modrinth project slug.
    #[serde(default)]
    pub modrinth: Option<String>,
    /// For Modrinth mods: an exact version number, or "latest" to follow the newest release.
    #[serde(default)]
    pub version: Option<String>,
    pub group: Group,
    /// Players can switch it on or off.
    #[serde(default)]
    pub optional: bool,
    /// For optional mods: whether it starts switched on.
    #[serde(default, rename = "default")]
    pub default_state: Option<Toggle>,
}

impl ModEntry {
    /// How to name this entry in error messages.
    pub fn label(&self) -> &str {
        self.id.as_deref().or(self.modrinth.as_deref()).or(self.file.as_deref()).unwrap_or("a mod entry")
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Toggle {
    On,
    Off,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ConfigEntry {
    /// The file in pack/, like "configs/sodium-options.json".
    pub file: String,
    /// Where it goes in the game folder, like "config/sodium-options.json".
    pub path: String,
    #[serde(default = "if_missing")]
    pub policy: ConfigPolicy,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_pack() {
        let pack: PackFile = toml::from_str(
            r#"
            name = "Test"
            channel = "stable"
            minecraft = "26.3"
            loader = { type = "fabric", version = "0.20.1" }
            quickPlay = "play.cubecraft.net"

            mods = [
              # comments and trailing commas are fine
              { id = "cubeutility", file = "jars/cube-utility-1.7.0.jar", group = "own" },
              { modrinth = "zoomify", version = "2.17.0+mc26.3", group = "qol", optional = true, default = "on" },
            ]

            [[configs]]
            file = "configs/sodium-options.json"
            path = "config/sodium-options.json"
            "#,
        )
        .unwrap();
        assert_eq!(pack.mods.len(), 2);
        assert!(pack.allow_extra_mods);
        assert_eq!(pack.quick_play.as_deref(), Some("play.cubecraft.net"));
        assert_eq!(pack.min_launcher_version, "1.0.0");
        assert_eq!(pack.mods[1].group, Group::Qol);
        assert_eq!(pack.mods[1].default_state, Some(Toggle::On));
        assert_eq!(pack.configs[0].policy, ConfigPolicy::IfMissing);
    }

    #[test]
    fn refuses_typos() {
        let typo = r#"
            name = "Test"
            channel = "stable"
            minecraft = "26.3"
            loader = { type = "fabric", version = "0.20.1" }
            mods = [{ modrinth = "zoomify", version = "latest", group = "qol", optinal = true }]
        "#;
        assert!(toml::from_str::<PackFile>(typo).is_err());
    }
}
