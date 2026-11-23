//! Settings in mods' own config files that the pack decides. The pack writes a mod's config
//! file only for players who don't have it yet ("if-missing" in pack.toml), so a player who
//! already had the file would never get a setting the pack decides later. Each one here is set
//! in their file once (recorded in the launcher's state folder); a player who changes it
//! afterwards keeps their choice.

use serde_json::{Map, Value};

use super::options::{read_record, write_record};
use crate::paths::Paths;
use crate::sync::write_atomic;

/// A config file in the game folder, the setting's place in its JSON (keys joined by `/`) and
/// the pack's value, as JSON.
const PACK_DECIDES: &[(&str, &str, &str)] = &[
    // Dynamic FPS never lowers the frame rate. Its Idle state (10 FPS after five minutes
    // without input) is off, and it keeps full speed under the mouse (60 FPS on its defaults)
    // and behind other windows (1 FPS), as the pack's copy of the file has it.
    ("config/dynamic_fps.json", "idle/timeout", "0"),
    ("config/dynamic_fps.json", "states/hovered/frame_rate_target", "-1"),
    ("config/dynamic_fps.json", "states/unfocused/frame_rate_target", "-1"),
];

/// The settings this player has been given, one `file setting` per line.
const GIVEN: &str = "pack-mod-settings-given.txt";

/// Sets each setting the pack decides in a mod's config file, once. A file that isn't there yet
/// waits: the pack's own copy, which has the setting, comes with the next sync. So does a file
/// that isn't a JSON object. Returns the settings it changed.
pub fn give_pack_settings(paths: &Paths) -> std::io::Result<Vec<String>> {
    let record = paths.state().join(GIVEN);
    let mut given = read_record(&record);
    let before = given.len();
    let mut changed = Vec::new();
    for (file, setting, value) in PACK_DECIDES {
        let name = format!("{file} {setting}");
        if given.contains(&name) {
            continue;
        }
        let path = paths.instance().join(file);
        let Some(mut json) = std::fs::read_to_string(&path).ok().and_then(|text| serde_json::from_str::<Value>(&text).ok()).filter(Value::is_object) else {
            continue;
        };
        let value: Value = serde_json::from_str(value).expect("the pack's values are JSON");
        if set(&mut json, setting, value) {
            let text = serde_json::to_string_pretty(&json).map_err(std::io::Error::other)?;
            write_atomic(&path, text.as_bytes())?;
            changed.push(name.clone());
        }
        given.insert(name);
    }
    if given.len() != before {
        write_record(&record, &given)?;
    }
    Ok(changed)
}

/// Puts `value` at `setting` (keys joined by `/`), making the objects on the way. False when it
/// already had that value, or something that isn't an object is in the way.
fn set(json: &mut Value, setting: &str, value: Value) -> bool {
    let (parents, key) = setting.rsplit_once('/').map_or(("", setting), |(parents, key)| (parents, key));
    let mut here = json;
    for parent in parents.split('/').filter(|parent| !parent.is_empty()) {
        let Some(object) = here.as_object_mut() else { return false };
        here = object.entry(parent).or_insert_with(|| Value::Object(Map::new()));
    }
    let Some(object) = here.as_object_mut() else { return false };
    if object.get(key) == Some(&value) {
        return false;
    }
    object.insert(key.to_string(), value);
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    /// A fresh folder under the system temp folder, removed again when dropped.
    struct TempDir(PathBuf);

    impl TempDir {
        fn new(name: &str) -> Self {
            let dir = std::env::temp_dir().join(format!("cuc-mod-configs-{name}-{}", std::process::id()));
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

    const DYNAMIC_FPS: &str = "config/dynamic_fps.json";

    fn write_config(paths: &Paths, text: &str) {
        let path = paths.instance().join(DYNAMIC_FPS);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, text).unwrap();
    }

    fn read_config(paths: &Paths) -> Value {
        serde_json::from_str(&std::fs::read_to_string(paths.instance().join(DYNAMIC_FPS)).unwrap()).unwrap()
    }

    #[test]
    fn dynamic_fps_on_its_defaults_stops_lowering_the_frame_rate_once() {
        let dir = TempDir::new("defaults");
        let paths = Paths::new(dir.0.join("data"));
        // What Dynamic FPS writes when it starts without a file: everything on its defaults.
        write_config(&paths, "{}");

        let changed = give_pack_settings(&paths).unwrap();
        assert_eq!(changed.len(), 3);
        let json = read_config(&paths);
        assert_eq!(json.pointer("/idle/timeout"), Some(&Value::from(0)));
        assert_eq!(json.pointer("/states/hovered/frame_rate_target"), Some(&Value::from(-1)));
        assert_eq!(json.pointer("/states/unfocused/frame_rate_target"), Some(&Value::from(-1)));

        // Switching idling back on in Background frame rate is their choice now.
        write_config(&paths, r#"{"idle":{"timeout":600}}"#);
        assert!(give_pack_settings(&paths).unwrap().is_empty());
        assert_eq!(read_config(&paths).pointer("/idle/timeout"), Some(&Value::from(600)));
    }

    #[test]
    fn the_rest_of_the_players_file_stays() {
        let dir = TempDir::new("keeps");
        let paths = Paths::new(dir.0.join("data"));
        // The pack's older copy, plus a change of their own.
        write_config(&paths, r#"{"states":{"hovered":{"frame_rate_target":-1},"unfocused":{"frame_rate_target":-1,"volume_multipliers":{"master":0.5}}}}"#);

        assert_eq!(give_pack_settings(&paths).unwrap(), ["config/dynamic_fps.json idle/timeout"]);
        let json = read_config(&paths);
        assert_eq!(json.pointer("/idle/timeout"), Some(&Value::from(0)));
        assert_eq!(json.pointer("/states/unfocused/volume_multipliers/master"), Some(&Value::from(0.5)));
    }

    #[test]
    fn a_missing_or_broken_file_waits() {
        let dir = TempDir::new("missing");
        let paths = Paths::new(dir.0.join("data"));
        assert!(give_pack_settings(&paths).unwrap().is_empty());
        write_config(&paths, "not json");
        assert!(give_pack_settings(&paths).unwrap().is_empty());
        assert_eq!(std::fs::read_to_string(paths.instance().join(DYNAMIC_FPS)).unwrap(), "not json");
        assert!(!paths.state().join(GIVEN).exists());

        // The pack's own copy arrives with everything already set: nothing to change.
        write_config(&paths, &std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../pack/configs/config/dynamic_fps.json")).unwrap());
        assert!(give_pack_settings(&paths).unwrap().is_empty());
        assert!(paths.state().join(GIVEN).exists());
    }

    #[test]
    fn set_makes_the_objects_on_the_way() {
        let mut json = serde_json::json!({ "states": { "hovered": 5 } });
        assert!(set(&mut json, "idle/timeout", Value::from(0)));
        assert!(!set(&mut json, "idle/timeout", Value::from(0)), "already set");
        assert!(!set(&mut json, "states/hovered/frame_rate_target", Value::from(-1)), "a number is in the way");
        assert!(set(&mut json, "top", Value::from(true)));
        assert_eq!(json, serde_json::json!({ "idle": { "timeout": 0 }, "states": { "hovered": 5 }, "top": true }));
    }
}
