//! What the Mods screen shows, worked out from the installed pack (spec section 5).

use std::collections::BTreeMap;

use cuc_manifest::{Group, Manifest};
use serde::Serialize;

use crate::modrinth::InstalledMod;
use crate::sync::{is_switchable, Choices};

#[derive(Debug, Clone, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModsView {
    /// A pack is installed.
    pub installed: bool,
    /// "Cube Utility mods": the pack owner's own mods.
    pub own: Vec<ModCard>,
    /// "Extras · you choose": the mods players switch on or off.
    pub extras: Vec<ExtraCard>,
    /// "Performance and libraries": always installed.
    pub always: Vec<String>,
    /// Mods the player installed from Modrinth, and the libraries that came with them.
    pub modrinth: Vec<ModrinthCard>,
    /// "Your extra mods": jars the player added to the mods folder by hand.
    pub player_jars: Vec<String>,
    /// Extra jars moved to mods-disabled because the pack doesn't allow them.
    pub disabled_jars: Vec<String>,
    pub allow_extra_mods: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModCard {
    pub id: String,
    pub name: String,
    pub version: String,
    pub description: Option<String>,
    /// The version before the latest update changed it.
    pub updated_from: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExtraCard {
    pub id: String,
    pub name: String,
    pub version: String,
    pub description: Option<String>,
    pub enabled: bool,
    /// Libraries that come with it while it's on.
    pub needs: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModrinthCard {
    pub project_id: String,
    pub name: String,
    pub version: String,
    pub icon_url: Option<String>,
    /// The player chose it. Libraries that came with a mod they chose aren't.
    pub picked: bool,
    /// For libraries: the mods they came with.
    pub needed_by: Vec<String>,
}

pub fn build(
    manifest: Option<&Manifest>,
    choices: &Choices,
    updated_from: &BTreeMap<String, String>,
    player_jars: Vec<String>,
    disabled_jars: Vec<String>,
    from_modrinth: &[InstalledMod],
) -> ModsView {
    // Jars from Modrinth have their own cards; the rest the player added by hand.
    let player_jars = player_jars.into_iter().filter(|jar| !from_modrinth.iter().any(|m| m.path.eq_ignore_ascii_case(jar))).collect();
    let modrinth = from_modrinth
        .iter()
        .map(|m| ModrinthCard {
            project_id: m.project_id.clone(),
            name: m.title.clone(),
            version: display_version(&m.version),
            icon_url: m.icon_url.clone(),
            picked: m.picked,
            needed_by: m.needed_by.iter().filter_map(|id| from_modrinth.iter().find(|o| &o.project_id == id)).map(|o| o.title.clone()).collect(),
        })
        .collect();
    let Some(manifest) = manifest else { return ModsView { modrinth, player_jars, disabled_jars, allow_extra_mods: true, ..Default::default() } };
    let mut view = ModsView { installed: true, allow_extra_mods: manifest.allow_extra_mods, modrinth, player_jars, disabled_jars, ..Default::default() };
    for file in &manifest.files {
        let version = display_version(&file.version);
        if file.group == Group::Own {
            view.own.push(ModCard {
                id: file.id.clone(),
                name: file.name.clone(),
                version,
                description: file.description.clone(),
                updated_from: updated_from.get(&file.id).map(|old| display_version(old)),
            });
        } else if is_switchable(file) {
            let needs = manifest.files.iter().filter(|lib| lib.required_by.contains(&file.id)).map(|lib| lib.name.clone()).collect();
            view.extras.push(ExtraCard {
                id: file.id.clone(),
                name: file.name.clone(),
                version,
                description: file.description.clone(),
                enabled: choices.get(&file.id).copied().unwrap_or(file.default_enabled),
                needs,
            });
        } else if file.required {
            view.always.push(file.name.clone());
        }
    }
    // Performance mods first, then libraries, each by name.
    let group_of = |name: &String| manifest.files.iter().find(|f| &f.name == name).map(|f| f.group == Group::Library);
    view.always.sort_by(|a, b| group_of(a).cmp(&group_of(b)).then(a.to_lowercase().cmp(&b.to_lowercase())));
    view
}

/// Versions without the Minecraft and loader parts: "mc26.3-0.9.2-fabric" → "0.9.2",
/// "1.7.0+mc26.1-26.3" → "1.7.0", "3.3.12-fabric-26.3" → "3.3.12".
pub fn display_version(version: &str) -> String {
    let mut rest = version.trim();
    if let Some((minecraft, after)) = rest.strip_prefix("mc").and_then(|r| r.split_once('-')) {
        if minecraft.chars().all(|c| c.is_ascii_digit() || c == '.') {
            rest = after;
        }
    }
    let mut rest = rest.split('+').next().unwrap_or(rest);
    // A loader or Minecraft part after a dash ends the mod's own version.
    let lower = rest.to_ascii_lowercase();
    let tail = ["-fabric", "-quilt", "-neoforge", "-forge"]
        .iter()
        .filter_map(|loader| lower.find(loader))
        .chain(lower.match_indices("-mc").filter(|(at, _)| lower[at + 3..].starts_with(|c: char| c.is_ascii_digit())).map(|(at, _)| at))
        .min();
    if let Some(at) = tail {
        rest = &rest[..at];
    }
    if rest.is_empty() { version.to_string() } else { rest.to_string() }
}

#[cfg(test)]
mod tests {
    use super::*;
    use cuc_manifest::{Loader, ManifestFile, SCHEMA};

    fn file(id: &str, group: Group, required: bool, default_enabled: bool, required_by: &[&str], version: &str) -> ManifestFile {
        ManifestFile {
            id: id.into(),
            name: id.to_uppercase(),
            version: version.into(),
            description: Some(format!("about {id}")),
            group,
            path: format!("mods/{id}.jar"),
            url: format!("https://cdn.modrinth.com/{id}.jar"),
            sha512: "a".repeat(128),
            size: 1,
            required,
            default_enabled,
            required_by: required_by.iter().map(|s| s.to_string()).collect(),
            modrinth: None,
        }
    }

    fn manifest() -> Manifest {
        Manifest {
            schema: SCHEMA,
            serial: 1,
            pack_version: "2026.11.11-1".into(),
            channel: "stable".into(),
            released_at: "2026-11-11T08:00:00Z".into(),
            min_launcher_version: "1.0.0".into(),
            minecraft: "26.3".into(),
            loader: Loader { kind: "fabric".into(), version: "0.20.1".into() },
            java: None,
            quick_play: None,
            allow_extra_mods: true,
            notes: None,
            files: vec![
                file("cubeutility", Group::Own, true, true, &[], "1.7.0+mc26.1-26.3"),
                file("sodium", Group::Performance, true, true, &[], "mc26.3-0.9.2-fabric"),
                file("fabric-api", Group::Library, true, true, &[], "0.166.0+26.3"),
                file("zoomify", Group::Qol, false, true, &[], "2.17.0+mc26.3"),
                file("freelook", Group::Qol, false, false, &[], "1.4.4"),
                file("yacl", Group::Library, false, false, &["zoomify"], "3.9.7+26.3-fabric"),
            ],
            configs: vec![],
        }
    }

    #[test]
    fn sorts_the_pack_into_the_screens_sections() {
        let choices = Choices::from([("freelook".to_string(), true)]);
        let updated = BTreeMap::from([("cubeutility".to_string(), "1.5.3+mc26.1-26.3".to_string())]);
        let view = build(Some(&manifest()), &choices, &updated, vec!["mods/mine.jar".into()], vec![], &[]);
        assert!(view.installed);
        assert_eq!(view.own.len(), 1);
        assert_eq!(view.own[0].version, "1.7.0");
        assert_eq!(view.own[0].updated_from.as_deref(), Some("1.5.3"));
        let extras: Vec<(&str, bool, &[String])> = view.extras.iter().map(|e| (e.id.as_str(), e.enabled, e.needs.as_slice())).collect();
        assert_eq!(extras, [("zoomify", true, &["YACL".to_string()][..]), ("freelook", true, &[][..])]);
        assert_eq!(view.always, ["SODIUM", "FABRIC-API"], "performance first, then libraries; tied libraries listed under their mod");
        assert_eq!(view.player_jars, ["mods/mine.jar"]);
        assert!(!build(None, &choices, &updated, vec![], vec![], &[]).installed);
    }

    #[test]
    fn mods_from_modrinth_get_their_own_cards() {
        let from_modrinth = |id: &str, title: &str, picked: bool, needed_by: &[&str]| InstalledMod {
            project_id: id.into(),
            version_id: "v".into(),
            title: title.into(),
            version: "1.11.7+26.3-fabric".into(),
            icon_url: None,
            path: format!("mods/{id}.jar"),
            sha512: "a".repeat(128),
            minecraft: "26.3".into(),
            picked,
            needed_by: needed_by.iter().map(|s| s.to_string()).collect(),
        };
        let installed = [from_modrinth("iris", "Iris Shaders", true, &[]), from_modrinth("lib", "Some Library", false, &["iris"])];
        let jars = vec!["mods/iris.jar".into(), "mods/LIB.jar".into(), "mods/mine.jar".into()];
        let view = build(Some(&manifest()), &Choices::new(), &BTreeMap::new(), jars, vec![], &installed);
        assert_eq!(view.player_jars, ["mods/mine.jar"], "jars from Modrinth aren't listed as unknown jars");
        assert_eq!(view.modrinth.len(), 2);
        assert_eq!((view.modrinth[0].name.as_str(), view.modrinth[0].version.as_str(), view.modrinth[0].picked), ("Iris Shaders", "1.11.7", true));
        assert_eq!(view.modrinth[1].needed_by, ["Iris Shaders"]);
    }

    #[test]
    fn tidies_versions_for_display() {
        for (raw, shown) in [
            ("1.7.0+mc26.1-26.3", "1.7.0"),
            ("mc26.3-0.9.2-fabric", "0.9.2"),
            ("mc26.3-0.26.3-fabric", "0.26.3"),
            ("mc26.3-2.2.5+fabric", "2.2.5"),
            ("26.3.159+fabric", "26.3.159"),
            ("1.14.2+kotlin.2.4.21", "1.14.2"),
            ("9.0.0-fabric", "9.0.0"),
            ("V5", "V5"),
            ("1.11.3", "1.11.3"),
            // Modrinth version numbers of mods installed from the Mods screen
            ("3.3.12-fabric-26.3", "3.3.12"),
            ("7.2.7-mc26.3-Fabric", "7.2.7"),
            ("3.0.10+mc26.3", "3.0.10"),
            ("26.3.4", "26.3.4"),
            ("0.5.0-beta.2", "0.5.0-beta.2"),
            ("1.0.0-mcmod", "1.0.0-mcmod"),
        ] {
            assert_eq!(display_version(raw), shown, "{raw}");
        }
    }
}
