//! Making the game folder match the plan, in an order that a crash at any moment can't break:
//!
//! 1. Every new file is copied next to its place under a temp name, which the game ignores.
//!    If that fails, nothing the game sees has changed.
//! 2. The saved state then lists every file that may end up ours, before anything is swapped.
//! 3. New files are renamed into place, and mods that left the pack are deleted.
//! 4. The caller saves the final state last.
//!
//! After a crash in step 3, the next sync finds the listed files, re-checks them and finishes.

use std::collections::{BTreeMap, HashSet};
use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};

use cuc_manifest::{ConfigFile, ConfigPolicy, ManifestFile};
use sha2::{Digest, Sha512};

use super::state::{mtime_ms, part_path, FileRecord, SyncState};
use crate::net::hex;
use crate::paths::safe_join;

/// A file the game folder should have.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Want {
    /// Inside the game folder, like mods/sodium.jar.
    pub path: String,
    pub sha512: String,
    pub size: u64,
    pub url: String,
    /// What the UI shows while it downloads.
    pub label: String,
}

impl Want {
    pub fn mod_file(file: &ManifestFile) -> Self {
        Self {
            path: file.path.clone(),
            sha512: file.sha512.to_ascii_lowercase(),
            size: file.size,
            url: file.url.clone(),
            label: format!("{} {}", file.name, file.version),
        }
    }

    pub fn config(config: &ConfigFile) -> (Self, ConfigPolicy) {
        let want = Self {
            path: config.path.clone(),
            sha512: config.sha512.to_ascii_lowercase(),
            size: config.size,
            url: config.url.clone(),
            label: config.path.clone(),
        };
        (want, config.policy)
    }
}

/// What has to change in the game folder.
#[derive(Debug, Default)]
pub struct Changes {
    /// Mods to write: missing, or not what they should be.
    pub write: Vec<Want>,
    /// Mods that are already right, with fresh records.
    pub keep: BTreeMap<String, FileRecord>,
    /// Mods the launcher installed earlier that the pack doesn't want anymore.
    pub remove: Vec<String>,
    /// Configs to write.
    pub configs: Vec<Want>,
    /// Jars in mods/ that the player added themselves.
    pub extras: Vec<String>,
    /// The pack doesn't allow extra jars, so they move to mods-disabled/.
    pub move_extras: bool,
}

impl Changes {
    pub fn is_empty(&self) -> bool {
        self.write.is_empty() && self.remove.is_empty() && self.configs.is_empty() && !self.move_extras
    }

    /// The files that come from the download cache.
    pub fn blobs(&self) -> impl Iterator<Item = &Want> {
        self.write.iter().chain(&self.configs)
    }
}

/// Compares the game folder with what's wanted. Files whose size and modified time still match
/// the state aren't read again, unless `deep` (Repair) asks to re-hash everything.
pub fn changes(
    game_dir: &Path,
    state: &SyncState,
    mods: &[Want],
    configs: &[(Want, ConfigPolicy)],
    allow_extras: bool,
    deep: bool,
) -> io::Result<Changes> {
    let mut changes = Changes::default();
    for want in mods {
        match current_record(&resolve(game_dir, &want.path)?, state.files.get(&want.path), want, deep)? {
            Some(record) => {
                changes.keep.insert(want.path.clone(), record);
            }
            None => changes.write.push(want.clone()),
        }
    }
    let wanted: HashSet<&str> = mods.iter().map(|want| want.path.as_str()).collect();
    changes.remove = state.files.keys().filter(|path| !wanted.contains(path.as_str())).cloned().collect();
    for (want, policy) in configs {
        let path = resolve(game_dir, &want.path)?;
        let write = match policy {
            // Never overwrite a player's own settings.
            ConfigPolicy::IfMissing => !path.exists(),
            ConfigPolicy::Always => hash_file(&path).ok().as_deref() != Some(want.sha512.as_str()),
        };
        if write {
            changes.configs.push(want.clone());
        }
    }
    changes.extras = extra_jars(game_dir, state, &wanted)?;
    changes.move_extras = !allow_extras && !changes.extras.is_empty();
    Ok(changes)
}

/// The record for a wanted file when it's already right, or None when it has to be written.
fn current_record(path: &Path, record: Option<&FileRecord>, want: &Want, deep: bool) -> io::Result<Option<FileRecord>> {
    let meta = match fs::metadata(path) {
        Ok(meta) if meta.is_file() => meta,
        Ok(_) => return Ok(None),
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e),
    };
    if meta.len() != want.size {
        return Ok(None);
    }
    let fresh = FileRecord { sha512: want.sha512.clone(), size: meta.len(), mtime_ms: mtime_ms(&meta) };
    // Untouched since the launcher wrote it, so it's still right.
    if !deep && fresh.mtime_ms != 0 && record == Some(&fresh) {
        return Ok(Some(fresh));
    }
    Ok((hash_file(path)? == want.sha512).then_some(fresh))
}

/// Jars in mods/ that the launcher didn't put there. Compared without case, like Windows does.
fn extra_jars(game_dir: &Path, state: &SyncState, wanted: &HashSet<&str>) -> io::Result<Vec<String>> {
    let entries = match fs::read_dir(game_dir.join("mods")) {
        Ok(entries) => entries,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(e) => return Err(e),
    };
    let ours: HashSet<String> = state.files.keys().map(|p| p.to_lowercase()).chain(wanted.iter().map(|p| p.to_lowercase())).collect();
    let mut extras = Vec::new();
    for entry in entries {
        let entry = entry?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if !name.to_lowercase().ends_with(".jar") || !entry.file_type()?.is_file() {
            continue;
        }
        let path = format!("mods/{name}");
        if !ours.contains(&path.to_lowercase()) {
            extras.push(path);
        }
    }
    extras.sort();
    Ok(extras)
}

/// Makes the game folder match `changes` and sets `state.files` to what's in place afterwards.
/// The caller saves the state. `fault` is how tests stop it halfway, like a crash would.
/// Returns the names of extra jars moved to mods-disabled/.
pub fn apply(
    game_dir: &Path,
    blob: &dyn Fn(&str) -> PathBuf,
    state_path: &Path,
    state: &mut SyncState,
    changes: &Changes,
    fault: &dyn Fn(&str) -> io::Result<()>,
) -> io::Result<Vec<String>> {
    clean_leftovers(&game_dir.join("mods"));

    // 1. Copy every new file next to its place, under a temp name.
    let mut staged = Vec::new();
    if let Err(error) = stage(game_dir, blob, changes, fault, &mut staged) {
        for (_, _, part) in &staged {
            let _ = fs::remove_file(part);
        }
        return Err(error);
    }

    // 2. Before anything is swapped, the saved state lists every file that may end up ours.
    for want in &changes.write {
        state.files.insert(want.path.clone(), FileRecord { sha512: want.sha512.clone(), size: want.size, mtime_ms: 0 });
    }
    state.save(state_path)?;

    // 3. Swap the new files in, then delete the mods that left the pack.
    for (want, dest, part) in &staged {
        fault(&format!("swap {}", want.path))?;
        fs::rename(part, dest)?;
    }
    for path in &changes.remove {
        fault(&format!("remove {path}"))?;
        match fs::remove_file(resolve(game_dir, path)?) {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::NotFound => {}
            Err(e) => return Err(e),
        }
    }
    let moved = if changes.move_extras { move_extras(game_dir, &changes.extras)? } else { Vec::new() };

    // 4. What's in place now.
    let mut files = changes.keep.clone();
    for want in &changes.write {
        let meta = fs::metadata(resolve(game_dir, &want.path)?)?;
        files.insert(want.path.clone(), FileRecord { sha512: want.sha512.clone(), size: meta.len(), mtime_ms: mtime_ms(&meta) });
    }
    state.files = files;
    Ok(moved)
}

fn stage<'a>(
    game_dir: &Path,
    blob: &dyn Fn(&str) -> PathBuf,
    changes: &'a Changes,
    fault: &dyn Fn(&str) -> io::Result<()>,
    staged: &mut Vec<(&'a Want, PathBuf, PathBuf)>,
) -> io::Result<()> {
    for want in changes.blobs() {
        let dest = resolve(game_dir, &want.path)?;
        if let Some(parent) = dest.parent() {
            fs::create_dir_all(parent)?;
        }
        let part = part_path(&dest);
        fault(&format!("stage {}", want.path))?;
        staged.push((want, dest, part.clone()));
        fs::copy(blob(&want.sha512), &part)?;
    }
    Ok(())
}

fn move_extras(game_dir: &Path, extras: &[String]) -> io::Result<Vec<String>> {
    let disabled = game_dir.join("mods-disabled");
    fs::create_dir_all(&disabled)?;
    let mut moved = Vec::new();
    for path in extras {
        let from = resolve(game_dir, path)?;
        let name = from.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let stem = name.strip_suffix(".jar").unwrap_or(&name).to_string();
        let mut to = disabled.join(&name);
        let mut n = 2;
        while to.exists() {
            to = disabled.join(format!("{stem} ({n}).jar"));
            n += 1;
        }
        fs::rename(&from, &to)?;
        moved.push(name);
    }
    Ok(moved)
}

/// Temp files from a sync that stopped halfway.
fn clean_leftovers(dir: &Path) {
    let Ok(entries) = fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        if entry.file_name().to_string_lossy().ends_with(".cuc-part") {
            let _ = fs::remove_file(entry.path());
        }
    }
}

fn resolve(game_dir: &Path, relative: &str) -> io::Result<PathBuf> {
    safe_join(game_dir, relative).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, format!("unsafe path {relative}")))
}

pub fn hash_file(path: &Path) -> io::Result<String> {
    let mut file = fs::File::open(path)?;
    let mut hasher = Sha512::new();
    let mut buffer = vec![0u8; 1 << 16];
    loop {
        let n = file.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        hasher.update(&buffer[..n]);
    }
    Ok(hex(&hasher.finalize()))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A game folder and a download cache in a temp folder, removed afterwards.
    struct Sandbox {
        root: PathBuf,
    }

    impl Sandbox {
        fn new(name: &str) -> Self {
            let root = std::env::temp_dir().join(format!("cuc-apply-{name}-{}", std::process::id()));
            let _ = fs::remove_dir_all(&root);
            fs::create_dir_all(root.join("game").join("mods")).unwrap();
            fs::create_dir_all(root.join("cache")).unwrap();
            Self { root }
        }

        fn game(&self) -> PathBuf {
            self.root.join("game")
        }

        fn state_path(&self) -> PathBuf {
            self.root.join("state").join("sync-state.json")
        }

        /// A wanted file, with its content already in the cache.
        fn want(&self, path: &str, content: &[u8]) -> Want {
            let sha512 = hex(&Sha512::digest(content));
            fs::write(self.root.join("cache").join(&sha512), content).unwrap();
            Want { path: path.into(), sha512, size: content.len() as u64, url: "https://cdn.modrinth.com/x".into(), label: path.into() }
        }

        fn mods(&self) -> Vec<String> {
            let mut names: Vec<String> = fs::read_dir(self.game().join("mods"))
                .unwrap()
                .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
                .collect();
            names.sort();
            names
        }

        fn read(&self, path: &str) -> Vec<u8> {
            fs::read(self.game().join(path)).unwrap()
        }

        /// One sync, the way the launcher runs it: compare, apply, save the state last.
        fn sync(
            &self,
            state: &mut SyncState,
            mods: &[Want],
            configs: &[(Want, ConfigPolicy)],
            allow_extras: bool,
            fault: &dyn Fn(&str) -> io::Result<()>,
        ) -> io::Result<Vec<String>> {
            let changes = changes(&self.game(), state, mods, configs, allow_extras, false)?;
            let cache = self.root.join("cache");
            let moved = apply(&self.game(), &|sha512| cache.join(sha512), &self.state_path(), state, &changes, fault)?;
            state.save(&self.state_path())?;
            Ok(moved)
        }
    }

    impl Drop for Sandbox {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.root);
        }
    }

    fn no_fault(_: &str) -> io::Result<()> {
        Ok(())
    }

    fn crash_at(step: &'static str) -> impl Fn(&str) -> io::Result<()> {
        move |at| if at == step { Err(io::Error::other(format!("simulated crash at {at}"))) } else { Ok(()) }
    }

    fn keys(state: &SyncState) -> Vec<&str> {
        state.files.keys().map(String::as_str).collect()
    }

    /// Installs a-1 and c, then the player drops in a jar of their own.
    fn installed(sandbox: &Sandbox) -> SyncState {
        let mut state = SyncState::default();
        let old = [sandbox.want("mods/a-1.jar", b"a one"), sandbox.want("mods/c.jar", b"c")];
        sandbox.sync(&mut state, &old, &[], true, &no_fault).unwrap();
        fs::write(sandbox.game().join("mods").join("extra.jar"), b"mine").unwrap();
        state
    }

    fn update(sandbox: &Sandbox) -> [Want; 2] {
        [sandbox.want("mods/a-2.jar", b"a two"), sandbox.want("mods/b.jar", b"b")]
    }

    #[test]
    fn installs_updates_and_removes_mods_but_keeps_the_players_own() {
        let sandbox = Sandbox::new("update");
        let mut state = installed(&sandbox);
        assert_eq!(keys(&state), ["mods/a-1.jar", "mods/c.jar"]);

        let new = update(&sandbox);
        sandbox.sync(&mut state, &new, &[], true, &no_fault).unwrap();
        assert_eq!(sandbox.mods(), ["a-2.jar", "b.jar", "extra.jar"]);
        assert_eq!(sandbox.read("mods/a-2.jar"), b"a two");
        assert_eq!(keys(&state), ["mods/a-2.jar", "mods/b.jar"]);
        assert_eq!(SyncState::load(&sandbox.state_path()), state);

        let again = changes(&sandbox.game(), &state, &new, &[], true, false).unwrap();
        assert!(again.is_empty(), "nothing left to do");
        assert_eq!(again.extras, ["mods/extra.jar"]);
    }

    #[test]
    fn a_crash_while_swapping_is_finished_by_the_next_sync() {
        let sandbox = Sandbox::new("crash-swap");
        let mut state = installed(&sandbox);
        let new = update(&sandbox);

        let crashed = sandbox.sync(&mut state, &new, &[], true, &crash_at("swap mods/b.jar"));
        assert!(crashed.is_err());
        // Half done: a-2 is in, b is still a temp file, the old mods are still there.
        assert_eq!(sandbox.mods(), ["a-1.jar", "a-2.jar", "b.jar.cuc-part", "c.jar", "extra.jar"]);

        // The launcher starts again with only what was saved, which names every file that may be ours.
        let mut state = SyncState::load(&sandbox.state_path());
        assert_eq!(keys(&state), ["mods/a-1.jar", "mods/a-2.jar", "mods/b.jar", "mods/c.jar"]);
        sandbox.sync(&mut state, &new, &[], true, &no_fault).unwrap();
        assert_eq!(sandbox.mods(), ["a-2.jar", "b.jar", "extra.jar"]);
        assert_eq!(keys(&state), ["mods/a-2.jar", "mods/b.jar"]);
        assert_eq!(sandbox.read("mods/b.jar"), b"b");
    }

    #[test]
    fn a_crash_while_deleting_or_before_the_final_save_is_finished_too() {
        let sandbox = Sandbox::new("crash-remove");
        let mut state = installed(&sandbox);
        let new = update(&sandbox);
        assert!(sandbox.sync(&mut state, &new, &[], true, &crash_at("remove mods/c.jar")).is_err());
        assert_eq!(sandbox.mods(), ["a-2.jar", "b.jar", "c.jar", "extra.jar"]);
        let mut state = SyncState::load(&sandbox.state_path());
        sandbox.sync(&mut state, &new, &[], true, &no_fault).unwrap();
        assert_eq!(sandbox.mods(), ["a-2.jar", "b.jar", "extra.jar"]);

        // Everything swapped, but the final state was never saved.
        let sandbox = Sandbox::new("crash-final");
        let mut state = installed(&sandbox);
        let changes = changes(&sandbox.game(), &state, &new_wants(&sandbox), &[], true, false).unwrap();
        let cache = sandbox.root.join("cache");
        apply(&sandbox.game(), &|sha512| cache.join(sha512), &sandbox.state_path(), &mut state, &changes, &no_fault).unwrap();
        let mut state = SyncState::load(&sandbox.state_path());
        sandbox.sync(&mut state, &new_wants(&sandbox), &[], true, &no_fault).unwrap();
        assert_eq!(sandbox.mods(), ["a-2.jar", "b.jar", "extra.jar"]);
        assert_eq!(keys(&state), ["mods/a-2.jar", "mods/b.jar"]);
    }

    fn new_wants(sandbox: &Sandbox) -> Vec<Want> {
        update(sandbox).to_vec()
    }

    #[test]
    fn a_failure_while_copying_changes_nothing() {
        let sandbox = Sandbox::new("crash-stage");
        let mut state = installed(&sandbox);
        let saved = SyncState::load(&sandbox.state_path());
        let new = update(&sandbox);
        assert!(sandbox.sync(&mut state, &new, &[], true, &crash_at("stage mods/b.jar")).is_err());
        assert_eq!(sandbox.mods(), ["a-1.jar", "c.jar", "extra.jar"], "no temp files left, old mods untouched");
        assert_eq!(SyncState::load(&sandbox.state_path()), saved);
    }

    #[test]
    fn repair_finds_damaged_files_that_look_untouched() {
        let sandbox = Sandbox::new("repair");
        let mut state = SyncState::default();
        let want = sandbox.want("mods/a.jar", b"right");
        sandbox.sync(&mut state, std::slice::from_ref(&want), &[], true, &no_fault).unwrap();

        // Same size, same modified time, different bytes.
        let path = sandbox.game().join("mods").join("a.jar");
        let modified = fs::metadata(&path).unwrap().modified().unwrap();
        fs::write(&path, b"wrong").unwrap();
        fs::OpenOptions::new().write(true).open(&path).unwrap().set_modified(modified).unwrap();

        let quick = changes(&sandbox.game(), &state, std::slice::from_ref(&want), &[], true, false).unwrap();
        assert!(quick.is_empty(), "the quick check trusts size and time");
        let deep = changes(&sandbox.game(), &state, std::slice::from_ref(&want), &[], true, true).unwrap();
        assert_eq!(deep.write, std::slice::from_ref(&want));
        sandbox.sync(&mut state, std::slice::from_ref(&want), &[], true, &no_fault).unwrap();

        // A file changed some other way is caught by the quick check too.
        fs::write(&path, b"other content").unwrap();
        let quick = changes(&sandbox.game(), &state, std::slice::from_ref(&want), &[], true, false).unwrap();
        assert_eq!(quick.write, [want]);
    }

    #[test]
    fn extra_jars_move_out_when_the_pack_forbids_them() {
        let sandbox = Sandbox::new("extras");
        let mut state = installed(&sandbox);
        let a = sandbox.want("mods/a-1.jar", b"a one");
        let moved = sandbox.sync(&mut state, std::slice::from_ref(&a), &[], false, &no_fault).unwrap();
        assert_eq!(moved, ["extra.jar"]);
        assert_eq!(sandbox.mods(), ["a-1.jar"]);
        assert!(sandbox.game().join("mods-disabled").join("extra.jar").is_file());
    }

    #[test]
    fn configs_never_overwrite_player_settings_unless_forced() {
        let sandbox = Sandbox::new("configs");
        let mut state = SyncState::default();
        let defaults = sandbox.want("config/sodium-options.json", b"pack default");
        let forced = sandbox.want("options.txt", b"forced");
        fs::create_dir_all(sandbox.game().join("config")).unwrap();
        fs::write(sandbox.game().join("config").join("sodium-options.json"), b"player's").unwrap();
        fs::write(sandbox.game().join("options.txt"), b"old").unwrap();
        let configs = [(defaults, ConfigPolicy::IfMissing), (forced, ConfigPolicy::Always)];

        sandbox.sync(&mut state, &[], &configs, true, &no_fault).unwrap();
        assert_eq!(sandbox.read("config/sodium-options.json"), b"player's");
        assert_eq!(sandbox.read("options.txt"), b"forced");

        fs::remove_file(sandbox.game().join("config").join("sodium-options.json")).unwrap();
        sandbox.sync(&mut state, &[], &configs, true, &no_fault).unwrap();
        assert_eq!(sandbox.read("config/sodium-options.json"), b"pack default");
        assert!(changes(&sandbox.game(), &state, &[], &configs, true, false).unwrap().is_empty());
    }
}
