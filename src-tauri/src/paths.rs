use std::path::{Path, PathBuf};

/// Where everything lives on the player's PC, under %APPDATA%\.cubeutilityclient.
#[derive(Debug, Clone)]
pub struct Paths {
    root: PathBuf,
}

impl Paths {
    pub fn new(root: PathBuf) -> Self {
        Self { root }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    /// Game files every instance shares: versions, libraries, assets and Java.
    pub fn shared(&self) -> PathBuf {
        self.root.join("shared")
    }

    pub fn versions(&self) -> PathBuf {
        self.shared().join("versions")
    }

    pub fn libraries(&self) -> PathBuf {
        self.shared().join("libraries")
    }

    pub fn assets(&self) -> PathBuf {
        self.shared().join("assets")
    }

    pub fn runtimes(&self) -> PathBuf {
        self.shared().join("runtimes")
    }

    pub fn natives(&self, version_id: &str) -> PathBuf {
        self.versions().join(version_id).join("natives")
    }

    /// The game directory: mods, config, saves and screenshots.
    pub fn instance(&self) -> PathBuf {
        self.root.join("instance")
    }

    /// Content-addressed download cache for pack files: cache/sha512/<first 2>/<hash>. A file
    /// is only stored here after its hash was checked, so rollbacks never download twice.
    pub fn cache(&self) -> PathBuf {
        self.root.join("cache")
    }

    pub fn cached_blob(&self, sha512: &str) -> PathBuf {
        let sha512 = sha512.to_ascii_lowercase();
        let prefix = sha512.get(..2).unwrap_or("00").to_string();
        self.cache().join("sha512").join(prefix).join(sha512)
    }

    /// Launcher state: sync-state.json, the installed manifest and the player's mod choices.
    pub fn state(&self) -> PathBuf {
        self.root.join("state")
    }

    pub fn logs(&self) -> PathBuf {
        self.root.join("logs")
    }

    pub fn ensure(&self) -> std::io::Result<()> {
        for dir in [self.shared(), self.instance(), self.cache(), self.state(), self.logs()] {
            std::fs::create_dir_all(dir)?;
        }
        Ok(())
    }
}

/// The real location of `path`, with junctions and Windows' per-app folder redirection
/// resolved. Java and Fabric resolve paths this way too, and Fabric breaks if the
/// classpath it was given and the paths it resolves don't match.
pub fn real_path(path: &Path) -> PathBuf {
    match std::fs::canonicalize(path) {
        Ok(real) => strip_verbatim(real),
        Err(_) => path.to_path_buf(),
    }
}

/// Turns `\\?\C:\x` into `C:\x` and `\\?\UNC\server\share` into `\\server\share`, the forms
/// Java expects.
fn strip_verbatim(path: PathBuf) -> PathBuf {
    let text = path.to_string_lossy();
    if let Some(rest) = text.strip_prefix(r"\\?\UNC\") {
        return PathBuf::from(format!(r"\\{rest}"));
    }
    if let Some(rest) = text.strip_prefix(r"\\?\") {
        if rest.as_bytes().get(1) == Some(&b':') {
            return PathBuf::from(rest);
        }
    }
    path
}

/// Joins a relative path taken from downloaded metadata onto `base`, refusing anything
/// that could escape it (`..` or a drive letter).
pub fn safe_join(base: &Path, relative: &str) -> Option<PathBuf> {
    let mut out = base.to_path_buf();
    for part in relative.split(['/', '\\']) {
        match part {
            "" | "." => continue,
            ".." => return None,
            p if p.contains(':') => return None,
            p => out.push(p),
        }
    }
    (out != base).then_some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn joins_normal_paths() {
        let base = Path::new("C:/data");
        assert_eq!(safe_join(base, "org/lwjgl/lwjgl.jar"), Some(base.join("org").join("lwjgl").join("lwjgl.jar")));
    }

    #[test]
    fn strips_verbatim_prefixes() {
        assert_eq!(strip_verbatim(PathBuf::from(r"\\?\C:\Users\x")), PathBuf::from(r"C:\Users\x"));
        assert_eq!(strip_verbatim(PathBuf::from(r"\\?\UNC\server\share\x")), PathBuf::from(r"\\server\share\x"));
        assert_eq!(strip_verbatim(PathBuf::from(r"C:\plain")), PathBuf::from(r"C:\plain"));
    }

    #[test]
    fn real_path_resolves_existing_folders() {
        let here = real_path(Path::new("."));
        assert!(here.is_absolute());
        assert!(!here.to_string_lossy().starts_with(r"\\?\"));
    }

    #[test]
    fn refuses_escapes() {
        let base = Path::new("C:/data");
        assert_eq!(safe_join(base, "../outside.jar"), None);
        assert_eq!(safe_join(base, "a/../../b"), None);
        assert_eq!(safe_join(base, "C:/Windows/evil.dll"), None);
        assert_eq!(safe_join(base, ""), None);
    }
}
