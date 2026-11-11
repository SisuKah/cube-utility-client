//! Reading a mod jar's fabric.mod.json (and those of the jars nested inside it), and checking
//! the version rules in its `depends`.

use std::cmp::Ordering;
use std::collections::HashMap;
use std::io::{Cursor, Read, Seek};
use std::path::Path;

use serde::Deserialize;
use serde_json::Value;

use crate::Result;

#[derive(Debug, Deserialize)]
struct FabricModJson {
    id: String,
    version: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    description: Option<String>,
    #[serde(default)]
    depends: HashMap<String, Value>,
    #[serde(default)]
    provides: Vec<String>,
    #[serde(default)]
    jars: Vec<NestedJar>,
}

#[derive(Debug, Deserialize)]
struct NestedJar {
    file: String,
}

/// What the pack tool needs to know about a mod jar.
#[derive(Debug, Clone)]
pub struct ModInfo {
    pub id: String,
    pub version: String,
    pub name: Option<String>,
    pub description: Option<String>,
    pub depends: HashMap<String, Value>,
    /// Every mod id the jar brings: its own, its `provides`, and those of its nested jars.
    pub provides: Vec<String>,
}

pub fn read_jar(path: &Path) -> Result<ModInfo> {
    let bytes = std::fs::read(path).map_err(|e| format!("can't open {}: {e}", path.display()))?;
    read_jar_bytes(&bytes).map_err(|e| format!("{}: {e}", path.display()).into())
}

pub fn read_jar_bytes(bytes: &[u8]) -> Result<ModInfo> {
    read_nested(bytes, 0)
}

fn read_nested(bytes: &[u8], depth: u32) -> Result<ModInfo> {
    let mut archive = zip::ZipArchive::new(Cursor::new(bytes))?;
    let raw = read_entry(&mut archive, "fabric.mod.json")?.ok_or("it has no fabric.mod.json, so it isn't a Fabric mod")?;
    let text = String::from_utf8_lossy(&raw);
    let json: FabricModJson =
        serde_json::from_str(text.trim_start_matches('\u{feff}')).map_err(|e| format!("its fabric.mod.json is broken: {e}"))?;

    let mut provides = vec![json.id.clone()];
    provides.extend(json.provides.iter().cloned());
    if depth < 4 {
        for nested in &json.jars {
            let Some(inner) = read_entry(&mut archive, &nested.file)? else { continue };
            // Plain Java libraries bundled in a mod have no fabric.mod.json and provide no mod ids.
            if let Ok(inner) = read_nested(&inner, depth + 1) {
                provides.extend(inner.provides);
            }
        }
    }
    provides.sort();
    provides.dedup();
    Ok(ModInfo {
        id: json.id,
        version: json.version,
        name: json.name,
        description: json.description,
        depends: json.depends,
        provides,
    })
}

fn read_entry<R: Read + Seek>(archive: &mut zip::ZipArchive<R>, name: &str) -> Result<Option<Vec<u8>>> {
    let mut entry = match archive.by_name(name) {
        Ok(entry) => entry,
        Err(zip::result::ZipError::FileNotFound) => return Ok(None),
        Err(e) => return Err(e.into()),
    };
    let mut data = Vec::new();
    entry.read_to_end(&mut data)?;
    Ok(Some(data))
}

/// Whether the mod's `depends` rule for `key` (like "minecraft") accepts `version`.
/// Some(true) when there's no rule; None when the rule is too unusual to judge.
pub fn accepts(depends: &HashMap<String, Value>, key: &str, version: &str) -> Option<bool> {
    match depends.get(key) {
        None => Some(true),
        Some(Value::String(rule)) => matches(rule, version),
        // A list means "any of these".
        Some(Value::Array(rules)) => {
            let mut any = false;
            for rule in rules {
                any |= matches(rule.as_str()?, version)?;
            }
            Some(any)
        }
        Some(_) => None,
    }
}

/// Space-separated comparisons that must all hold, like `>=26.1 <26.4-`.
fn matches(rule: &str, version: &str) -> Option<bool> {
    let mut all = true;
    for part in rule.split_whitespace() {
        all &= compare_one(part, version)?;
    }
    Some(all)
}

fn compare_one(part: &str, version: &str) -> Option<bool> {
    if part == "*" {
        return Some(true);
    }
    let (op, wanted) = ["<=", ">=", "<", ">", "=", "~", "^"]
        .iter()
        .find_map(|op| part.strip_prefix(op).map(|rest| (*op, rest)))
        .unwrap_or(("=", part));
    let have = numbers(version)?;
    // A trailing "-" means "including pre-releases"; for releases it's the same version.
    let want = numbers(wanted.trim_end_matches('-'))?;
    let order = compare(&have, &want);
    Some(match op {
        "<=" => order != Ordering::Greater,
        ">=" => order != Ordering::Less,
        "<" => order == Ordering::Less,
        ">" => order == Ordering::Greater,
        // Same major and minor version.
        "~" => order != Ordering::Less && have.first() == want.first() && have.get(1) == want.get(1),
        // Same major version.
        "^" => order != Ordering::Less && have.first() == want.first(),
        _ => order == Ordering::Equal,
    })
}

/// Version numbers like 26.3 or 1.21.11; `x` or `*` (None) matches anything from there on.
fn numbers(version: &str) -> Option<Vec<Option<u64>>> {
    version
        .split('.')
        .map(|n| if n.eq_ignore_ascii_case("x") || n == "*" { Some(None) } else { n.parse().ok().map(Some) })
        .collect()
}

fn compare(a: &[Option<u64>], b: &[Option<u64>]) -> Ordering {
    for i in 0..a.len().max(b.len()) {
        let (x, y) = (a.get(i).copied(), b.get(i).copied());
        if x == Some(None) || y == Some(None) {
            return Ordering::Equal;
        }
        // Missing parts count as 0, so 26.3 == 26.3.0.
        let order = x.flatten().unwrap_or(0).cmp(&y.flatten().unwrap_or(0));
        if order != Ordering::Equal {
            return order;
        }
    }
    Ordering::Equal
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn depends(json: &str) -> HashMap<String, Value> {
        serde_json::from_str(json).unwrap()
    }

    #[test]
    fn version_rules() {
        let check = |rule: &str, version: &str| accepts(&depends(&format!(r#"{{"minecraft": {rule}}}"#)), "minecraft", version);
        assert_eq!(check(r#"">=26.1 <26.4-""#, "26.3"), Some(true));
        assert_eq!(check(r#"">=26.1 <26.4-""#, "26.4"), Some(false));
        assert_eq!(check(r#""~26.3""#, "26.3"), Some(true));
        assert_eq!(check(r#""~26.3""#, "26.3.1"), Some(true));
        assert_eq!(check(r#""~26.3""#, "26.4"), Some(false));
        assert_eq!(check(r#"["~26.1", "~26.2", "~26.3"]"#, "26.3"), Some(true));
        assert_eq!(check(r#""1.21.11""#, "26.3"), Some(false));
        assert_eq!(check(r#"">=1.21""#, "26.3"), Some(true));
        assert_eq!(check(r#""1.21.x""#, "1.21.4"), Some(true));
        assert_eq!(check(r#""*""#, "26.3"), Some(true));
        assert_eq!(check(r#"">=26.1-alpha.3""#, "26.3"), None);
        assert_eq!(accepts(&depends("{}"), "minecraft", "26.3"), Some(true));
        assert_eq!(accepts(&depends(r#"{"fabricloader": ">=0.16.10"}"#), "fabricloader", "0.20.1"), Some(true));
        assert_eq!(accepts(&depends(r#"{"fabricloader": ">=0.21.0"}"#), "fabricloader", "0.20.1"), Some(false));
        assert_eq!(accepts(&depends(r#"{"java": ">=21"}"#), "java", "25"), Some(true));
    }

    fn jar(files: &[(&str, &[u8])]) -> Vec<u8> {
        let mut buffer = Cursor::new(Vec::new());
        let mut writer = zip::ZipWriter::new(&mut buffer);
        for (name, data) in files {
            writer.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            writer.write_all(data).unwrap();
        }
        writer.finish().unwrap();
        buffer.into_inner()
    }

    #[test]
    fn reads_ids_from_nested_jars() {
        let inner = jar(&[("fabric.mod.json", br#"{"id": "fabric-api-base", "version": "1.0.0"}"#)]);
        let outer = jar(&[
            (
                "fabric.mod.json",
                br#"{"schemaVersion": 1, "id": "fabric-api", "version": "0.166.0+26.3", "provides": ["fabric"],
                     "depends": {"fabricloader": ">=0.16"}, "jars": [{"file": "META-INF/jars/base.jar"}]}"#,
            ),
            ("META-INF/jars/base.jar", &inner),
        ]);
        let info = read_jar_bytes(&outer).unwrap();
        assert_eq!(info.id, "fabric-api");
        assert_eq!(info.provides, ["fabric", "fabric-api", "fabric-api-base"]);
        assert!(info.depends.contains_key("fabricloader"));
        assert!(read_jar_bytes(&jar(&[("readme.txt", b"hi")])).is_err());
    }
}
