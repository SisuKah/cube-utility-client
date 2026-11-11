//! Which mods the game folder should have. No files or network here, so it's easy to test.

use std::collections::{BTreeMap, HashSet};

use cuc_manifest::{Manifest, ManifestFile};

/// The player's own on/off choices for optional mods, by mod id.
pub type Choices = BTreeMap<String, bool>;

/// Whether a mod players can switch is on: their choice, or the pack's default.
fn switched_on(file: &ManifestFile, choices: &Choices) -> bool {
    choices.get(&file.id).copied().unwrap_or(file.default_enabled)
}

/// Optional mods players can switch on or off (libraries follow the mods that need them).
pub fn is_switchable(file: &ManifestFile) -> bool {
    !file.required && file.required_by.is_empty()
}

/// Every required mod, the optional mods that are on, and the libraries those need.
pub fn wanted<'a>(manifest: &'a Manifest, choices: &Choices) -> Vec<&'a ManifestFile> {
    let on: HashSet<&str> = manifest
        .files
        .iter()
        .filter(|file| file.required || (is_switchable(file) && switched_on(file, choices)))
        .map(|file| file.id.as_str())
        .collect();
    manifest
        .files
        .iter()
        .filter(|file| on.contains(file.id.as_str()) || file.required_by.iter().any(|id| on.contains(id.as_str())))
        .collect()
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use cuc_manifest::{Group, Loader, SCHEMA};

    pub fn file(id: &str, required: bool, default_enabled: bool, required_by: &[&str]) -> ManifestFile {
        ManifestFile {
            id: id.into(),
            name: id.into(),
            version: "1.0.0".into(),
            description: None,
            group: if required_by.is_empty() { Group::Qol } else { Group::Library },
            path: format!("mods/{id}.jar"),
            url: format!("https://cdn.modrinth.com/data/x/versions/y/{id}.jar"),
            sha512: "a".repeat(128),
            size: 10,
            required,
            default_enabled,
            required_by: required_by.iter().map(|s| s.to_string()).collect(),
            modrinth: None,
        }
    }

    pub fn manifest(files: Vec<ManifestFile>) -> Manifest {
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
            files,
            configs: vec![],
        }
    }

    fn ids(files: Vec<&ManifestFile>) -> Vec<&str> {
        files.iter().map(|f| f.id.as_str()).collect()
    }

    fn sample() -> Manifest {
        manifest(vec![
            file("sodium", true, true, &[]),
            file("zoomify", false, true, &[]),
            file("freelook", false, false, &[]),
            file("yacl", false, false, &["zoomify"]),
            file("kotlin", false, false, &["zoomify", "freelook"]),
        ])
    }

    #[test]
    fn defaults_install_required_mods_and_the_ones_on_by_default() {
        let pack = sample();
        assert_eq!(ids(wanted(&pack, &Choices::new())), ["sodium", "zoomify", "yacl", "kotlin"]);
    }

    #[test]
    fn libraries_follow_the_mods_that_need_them() {
        let pack = sample();
        let zoomify_off = Choices::from([("zoomify".to_string(), false)]);
        assert_eq!(ids(wanted(&pack, &zoomify_off)), ["sodium"]);

        let freelook_only = Choices::from([("zoomify".to_string(), false), ("freelook".to_string(), true)]);
        assert_eq!(ids(wanted(&pack, &freelook_only)), ["sodium", "freelook", "kotlin"]);
    }

    #[test]
    fn players_cant_switch_off_required_mods_or_libraries() {
        let pack = sample();
        let choices = Choices::from([("sodium".to_string(), false), ("yacl".to_string(), false), ("kotlin".to_string(), true)]);
        assert_eq!(ids(wanted(&pack, &choices)), ["sodium", "zoomify", "yacl", "kotlin"]);
        assert!(!is_switchable(&pack.files[0]));
        assert!(is_switchable(&pack.files[1]));
        assert!(!is_switchable(&pack.files[3]));
    }

    #[test]
    fn choices_for_mods_that_left_the_pack_are_ignored() {
        let pack = sample();
        let choices = Choices::from([("removed-mod".to_string(), true)]);
        assert_eq!(wanted(&pack, &choices).len(), 4);
    }
}
