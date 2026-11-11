//! The pack tool: turns pack/pack.toml into a signed manifest that every launcher syncs to.
//!
//!   pack keys [--force]                   create the signing key pair
//!   pack build [--channel beta] [--files-url URL] [--serial N] [--force]
//!   pack add <jar> [--notes "text"]       put a new build of one of my mods in the pack
//!   pack serve [--port 8799]              serve pack/out to test launchers locally
//!   pack rollback <packVersion> [--channel beta]

mod build;
mod changelog;
mod jar;
mod modrinth;
mod packfile;
mod serve;
mod time;

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use cuc_manifest::{Manifest, Notes};

pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;

const USAGE: &str = "usage:
  pack keys [--force]
  pack build [--channel beta] [--files-url URL] [--serial N] [--force]
  pack add <jar> [--notes \"text\"]
  pack serve [--port 8799]
  pack rollback <packVersion> [--channel beta]";

/// Positional arguments, and `--name value` flags (`--force` style flags get an empty value).
pub struct Args {
    pub positional: Vec<String>,
    pub flags: HashMap<String, String>,
}

impl Args {
    fn parse(raw: &[String]) -> Self {
        let mut positional = Vec::new();
        let mut flags = HashMap::new();
        let mut i = 0;
        while i < raw.len() {
            if let Some(name) = raw[i].strip_prefix("--") {
                let value = raw.get(i + 1).filter(|v| !v.starts_with("--")).cloned();
                i += if value.is_some() { 2 } else { 1 };
                flags.insert(name.to_string(), value.unwrap_or_default());
            } else {
                positional.push(raw[i].clone());
                i += 1;
            }
        }
        Self { positional, flags }
    }

    pub fn flag(&self, name: &str) -> Option<&str> {
        self.flags.get(name).map(String::as_str)
    }
}

fn main() -> ExitCode {
    let raw: Vec<String> = std::env::args().skip(1).collect();
    let Some(command) = raw.first() else {
        eprintln!("{USAGE}");
        return ExitCode::FAILURE;
    };
    let args = Args::parse(&raw[1..]);
    let root = PathBuf::from(".");
    let result = match command.as_str() {
        "keys" => keys(&root, args.flags.contains_key("force")),
        "build" => build::run(&root, &args),
        "add" => add(&root, &args),
        "serve" => serve::run(&pack_dir(&root).join("out"), args.flag("port").and_then(|p| p.parse().ok()).unwrap_or(8799)),
        "rollback" => rollback(&root, &args),
        _ => Err(USAGE.into()),
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("error: {error}");
            ExitCode::FAILURE
        }
    }
}

pub fn pack_dir(root: &Path) -> PathBuf {
    root.join("pack")
}

/// The signing key: the PACK_SIGNING_KEY environment variable (in CI), or pack/signing-key.secret.
pub fn signing_key(root: &Path) -> Result<String> {
    if let Ok(key) = std::env::var("PACK_SIGNING_KEY") {
        if !key.trim().is_empty() {
            return Ok(key.trim().to_string());
        }
    }
    fs::read_to_string(pack_dir(root).join("signing-key.secret"))
        .map(|key| key.trim().to_string())
        .map_err(|_| "no signing key: set PACK_SIGNING_KEY or run `npm run keys:generate`".into())
}

fn keys(root: &Path, force: bool) -> Result<()> {
    let path = pack_dir(root).join("signing-key.secret");
    if path.exists() && !force {
        return Err("pack/signing-key.secret already exists. Use --force to replace it; launchers then need a new build with the new public key.".into());
    }
    let mut seed = [0u8; 32];
    getrandom::fill(&mut seed).map_err(|e| format!("couldn't get random bytes: {e}"))?;
    let secret = cuc_manifest::encode_hex(&seed);
    let public = cuc_manifest::public_key(&secret)?;
    fs::create_dir_all(pack_dir(root))?;
    fs::write(&path, &secret)?;
    println!("Saved the secret key to pack/signing-key.secret. It's in .gitignore: never commit it.");
    println!("When the GitHub repo exists, save it as the PACK_SIGNING_KEY secret too.");

    let config = root.join("src-tauri").join("src").join("config.rs");
    let text = fs::read_to_string(&config).unwrap_or_default();
    let updated: Vec<String> = text
        .lines()
        .map(|line| match line.find("PACK_SIGNING_PUBLIC_KEY: &str = \"") {
            Some(at) => format!("{}PACK_SIGNING_PUBLIC_KEY: &str = \"{public}\";", &line[..at]),
            None => line.to_string(),
        })
        .collect();
    if text.contains("PACK_SIGNING_PUBLIC_KEY: &str = \"") {
        fs::write(&config, updated.join("\n") + "\n")?;
        println!("Put the public key in src-tauri/src/config.rs: {public}");
    } else {
        println!("Public key (put it in src-tauri/src/config.rs as PACK_SIGNING_PUBLIC_KEY): {public}");
    }
    Ok(())
}

fn add(root: &Path, args: &Args) -> Result<()> {
    let jar_path = PathBuf::from(args.positional.first().ok_or("usage: pack add <jar> [--notes \"text\"]")?);
    let new_mod = jar::read_jar(&jar_path)?;
    let dir = pack_dir(root);
    let toml_path = dir.join("pack.toml");
    let text = fs::read_to_string(&toml_path).map_err(|e| format!("can't read pack/pack.toml: {e}"))?;
    let pack: packfile::PackFile = toml::from_str(&text).map_err(|e| format!("pack/pack.toml: {e}"))?;
    let name = new_mod.name.clone().unwrap_or_else(|| new_mod.id.clone());
    if jar::accepts(&new_mod.depends, "minecraft", &pack.minecraft) == Some(false) {
        return Err(format!("{name} {} doesn't accept Minecraft {} (it asks for {})", new_mod.version, pack.minecraft, new_mod.depends["minecraft"]).into());
    }

    let file_name = jar_path.file_name().and_then(|n| n.to_str()).ok_or("that path has no file name")?.to_string();
    let new_relative = format!("jars/{file_name}");
    let existing = pack
        .mods
        .iter()
        .filter_map(|m| m.file.as_deref())
        .find(|relative| jar::read_jar(&dir.join(relative)).is_ok_and(|m| m.id == new_mod.id))
        .map(str::to_string);

    fs::create_dir_all(dir.join("jars"))?;
    let destination = dir.join(&new_relative);
    if fs::canonicalize(&jar_path).ok() != fs::canonicalize(&destination).ok() {
        fs::copy(&jar_path, &destination)?;
    }
    let updated = match &existing {
        Some(old) if *old == new_relative => text,
        Some(old) => {
            fs::remove_file(dir.join(old))?;
            text.replace(&format!("\"{old}\""), &format!("\"{new_relative}\""))
        }
        None => insert_mod(&text, &new_relative)?,
    };
    fs::write(&toml_path, updated)?;

    let title = format!("{name} {}", new_mod.version);
    let notes = args.flag("notes").filter(|n| !n.trim().is_empty()).map(str::to_string);
    let body = notes.unwrap_or_else(|| format!("- {name} updated to {}.", new_mod.version));
    let changelog_path = dir.join("changelog.md");
    let changelog = fs::read_to_string(&changelog_path).unwrap_or_default();
    fs::write(&changelog_path, changelog::prepend(&changelog, &title, &body))?;

    match existing {
        Some(old) if old != new_relative => println!("Replaced {old} with {new_relative} and added \"{title}\" to the changelog."),
        Some(_) => println!("{new_relative} was already in the pack. Added \"{title}\" to the changelog."),
        None => println!("Added {new_relative} as a new mod and \"{title}\" to the changelog."),
    }
    println!("Next: npm run pack:build");
    Ok(())
}

/// Adds a line for a new jar to the `mods` list, after the last of my jars.
fn insert_mod(text: &str, relative: &str) -> Result<String> {
    let entry = format!("{{ file = \"{relative}\", group = \"own\" }},");
    let lines: Vec<&str> = text.lines().collect();
    let after_jar = lines.iter().rposition(|line| line.contains("file = \"jars/"));
    let anchor = after_jar.or_else(|| lines.iter().position(|line| line.trim_start().starts_with("mods = [")));
    let Some(anchor) = anchor else {
        return Err(format!("couldn't find the mods list in pack/pack.toml. Add this to it: {entry}").into());
    };
    let indent = match after_jar {
        Some(_) => &lines[anchor][..lines[anchor].len() - lines[anchor].trim_start().len()],
        None => "  ",
    };
    let mut out: Vec<String> = lines.iter().map(|line| line.to_string()).collect();
    out.insert(anchor + 1, format!("{indent}{entry}"));
    let mut joined = out.join("\n");
    if text.ends_with('\n') {
        joined.push('\n');
    }
    toml::from_str::<packfile::PackFile>(&joined)
        .map_err(|e| format!("couldn't add it to pack/pack.toml automatically ({e}). Add this by hand: {entry}"))?;
    Ok(joined)
}

fn rollback(root: &Path, args: &Args) -> Result<()> {
    let target = args.positional.first().ok_or("usage: pack rollback <packVersion>")?;
    let channel = args.flag("channel").filter(|c| !c.is_empty()).unwrap_or("stable");
    let out = pack_dir(root).join("out").join(channel);
    let current = Manifest::parse(&fs::read(out.join("manifest.json")).map_err(|_| format!("nothing published on {channel} yet"))?)?;
    let saved = fs::read(out.join("history").join(format!("{target}.json"))).map_err(|_| format!("there's no saved release {target}"))?;
    let mut manifest = Manifest::parse(&saved)?;
    manifest.serial = current.serial + 1;
    manifest.pack_version = time::next_pack_version(Some(&current.pack_version));
    manifest.released_at = time::now_rfc3339();
    manifest.notes = Some(Notes { title: format!("Back to release {target}"), markdown: format!("The pack is back to the mods of release {target}.") });
    build::write_signed(root, &out, &manifest)?;
    println!("Published {} (serial {}) with the files of {target}.", manifest.pack_version, manifest.serial);
    println!("pack.toml still lists the newer mods, so the next `pack build` publishes them again. Change it first.");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const PACK: &str = r#"name = "Test"
channel = "stable"
minecraft = "26.3"
loader = { type = "fabric", version = "0.20.1" }

mods = [
  # My mods
  { id = "cubeutility", file = "jars/cube-utility-1.7.0.jar", group = "own" },
  { id = "armorhud",    file = "jars/armor-dura-hud-1.0.0.jar", group = "own" },

  { modrinth = "sodium", version = "mc26.3-0.9.2-fabric", group = "performance" },
]
"#;

    #[test]
    fn inserts_new_jars_after_mine() {
        let updated = insert_mod(PACK, "jars/new-mod-1.0.0.jar").unwrap();
        let pack: packfile::PackFile = toml::from_str(&updated).unwrap();
        assert_eq!(pack.mods.len(), 4);
        assert_eq!(pack.mods[2].file.as_deref(), Some("jars/new-mod-1.0.0.jar"));
        assert!(updated.contains("\n  { file = \"jars/new-mod-1.0.0.jar\", group = \"own\" },\n\n  { modrinth"));
    }

    #[test]
    fn parses_flags() {
        let raw: Vec<String> = ["a.jar", "--notes", "- Fixed it", "--force"].iter().map(|s| s.to_string()).collect();
        let args = Args::parse(&raw);
        assert_eq!(args.positional, ["a.jar"]);
        assert_eq!(args.flag("notes"), Some("- Fixed it"));
        assert_eq!(args.flag("force"), Some(""));
    }
}
