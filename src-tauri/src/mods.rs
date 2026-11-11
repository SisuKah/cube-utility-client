//! What the Mods screen shows, worked out from the installed pack (spec section 5).

use std::collections::BTreeMap;

use cuc_manifest::{Group, Manifest};
use serde::Serialize;

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
    /// "Your extra mods": jars the player added to the mods folder.
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

pub fn build(
    manifest: Option<&Manifest>,
    choices: &Choices,
    updated_from: &BTreeMap<String, String>,
    player_jars: Vec<String>,
    disabled_jars: Vec<String>,
) -> ModsView {
    let Some(manifest) = manifest else { return ModsView { player_jars, disabled_jars, allow_extra_mods: true, ..Default::default() } };
    let mut view = ModsView { installed: true, allow_extra_mods: manifest.allow_extra_mods, player_jars, disabled_jars, ..Default::default() };
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
/// "1.7.0+mc26.1-26.3" → "1.7.0".
pub fn display_version(version: &str) -> String {
    let mut rest = version.trim();
    if let Some((minecraft, after)) = rest.strip_prefix("mc").and_then(|r| r.split_once('-')) {
        if minecraft.chars().all(|c| c.is_ascii_digit() || c == '.') {
            rest = after;
        }
    }
    let rest = rest.split('+').next().unwrap_or(rest);
    let rest = rest.trim_end_matches("-fabric").trim_end_matches("-quilt");
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
        let view = build(Some(&manifest()), &choices, &updated, vec!["mods/mine.jar".into()], vec![]);
        assert!(view.installed);
        assert_eq!(view.own.len(), 1);
        assert_eq!(view.own[0].version, "1.7.0");
        assert_eq!(view.own[0].updated_from.as_deref(), Some("1.5.3"));
        let extras: Vec<(&str, bool, &[String])> = view.extras.iter().map(|e| (e.id.as_str(), e.enabled, e.needs.as_slice())).collect();
        assert_eq!(extras, [("zoomify", true, &["YACL".to_string()][..]), ("freelook", true, &[][..])]);
        assert_eq!(view.always, ["SODIUM", "FABRIC-API"], "performance first, then libraries; tied libraries listed under their mod");
        assert_eq!(view.player_jars, ["mods/mine.jar"]);
        assert!(!build(None, &choices, &updated, vec![], vec![]).installed);
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
        ] {
            assert_eq!(display_version(raw), shown, "{raw}");
        }
    }
}
