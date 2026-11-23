//! Servers' resource packs (CubeCraft's), ready before the game starts. Minecraft keeps the
//! packs servers send in `downloads/<id>/<sha1>`, and the pack's Server Resource Pack Checker
//! mod lists each of those files as a resource pack called `cache/<sha1>`. So the launcher
//! downloads the pack there ahead of time and switches it on in options.txt: it loads with the
//! game, and when CubeCraft sends the same pack on joining, Minecraft finds it downloaded and
//! already loaded, and joining doesn't stop for it. When CubeCraft changes its pack, joining
//! downloads the new one the usual way.

use std::sync::Arc;

use cuc_manifest::ServerPack;

use super::options::{read_record, write_record, Options};
use crate::net::{Download, Hash, Net, Progress};
use crate::paths::Paths;
use crate::sync::write_atomic;

/// The packs this player has had switched on once, one id per line (in the launcher's state
/// folder). A pack the player switches off in the game then stays off.
const GIVEN: &str = "server-packs-given.txt";

/// The prefix the Server Resource Pack Checker mod gives the packs it lists.
const CACHED: &str = "cache/";

fn pack_id(pack: &ServerPack) -> String {
    format!("{CACHED}{}", pack.sha1)
}

/// The packs that aren't downloaded yet.
pub fn missing(paths: &Paths, packs: &[ServerPack]) -> Vec<Download> {
    packs
        .iter()
        .map(|pack| Download {
            url: pack.url.clone(),
            dest: paths.instance().join(pack.path()),
            hash: Some(Hash::Sha1(pack.sha1.clone())),
            size: Some(pack.size),
            label: format!("{}'s resource pack", pack.server),
        })
        .filter(|download| !download.is_present())
        .collect()
}

/// Downloads the packs that aren't there yet. A pack that can't be downloaded only means the
/// server sends it when the player joins, the way it would without this, so a failure is
/// logged and nothing more.
pub async fn fetch(net: &Net, paths: &Paths, packs: &[ServerPack]) {
    let missing = missing(paths, packs);
    if missing.is_empty() {
        return;
    }
    let count = missing.len();
    match net.download_all(missing, 2, Arc::new(Progress::default())).await {
        Ok(()) => tracing::info!("downloaded {count} server resource pack(s) ahead of time"),
        Err(error) => tracing::warn!("couldn't download a server's resource pack ahead of time ({error}); it downloads when joining instead"),
    }
}

/// Switches each downloaded pack on in options.txt, once per pack. A player who already has
/// one of the cached server packs on (a newer CubeCraft pack than the launcher's, say) keeps
/// theirs. Returns the packs it switched on.
pub fn switch_on(paths: &Paths, packs: &[ServerPack]) -> std::io::Result<Vec<String>> {
    let file = paths.instance().join("options.txt");
    let Ok(text) = std::fs::read_to_string(&file) else { return Ok(Vec::new()) };
    let mut options = Options::parse(&text);
    let record = paths.state().join(GIVEN);
    let mut given = read_record(&record);
    let before = given.len();

    // Minecraft's own default: just the game's resources.
    let mut selected: Vec<String> = options.get("resourcePacks").and_then(|list| serde_json::from_str(list).ok()).unwrap_or_else(|| vec!["vanilla".to_string()]);
    let has_cached = selected.iter().any(|id| id.starts_with(CACHED));
    let mut added = Vec::new();
    for pack in packs {
        let id = pack_id(pack);
        if given.contains(&id) || !is_downloaded(paths, pack) {
            continue;
        }
        // Decided once, either way.
        given.insert(id.clone());
        if has_cached || selected.contains(&id) {
            continue;
        }
        selected.push(id.clone());
        added.push(id);
    }

    if !added.is_empty() {
        let list = serde_json::to_string(&selected).map_err(std::io::Error::other)?;
        options.set("resourcePacks", &list);
        write_atomic(&file, options.to_text().as_bytes())?;
    }
    if given.len() != before {
        write_record(&record, &given)?;
    }
    Ok(added)
}

fn is_downloaded(paths: &Paths, pack: &ServerPack) -> bool {
    std::fs::metadata(paths.instance().join(pack.path())).is_ok_and(|meta| meta.is_file() && meta.len() == pack.size)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    struct TempDir(PathBuf);

    impl TempDir {
        fn new(name: &str) -> Self {
            let dir = std::env::temp_dir().join(format!("cuc-server-packs-{name}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).unwrap();
            Self(dir)
        }

        fn paths(&self) -> Paths {
            let paths = Paths::new(self.0.join("data"));
            paths.ensure().unwrap();
            paths
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    const SHA1: &str = "de57854a9220e95cdbf04515d431b23f1849617b";

    fn cubecraft() -> ServerPack {
        ServerPack {
            server: "play.cubecraft.net".into(),
            id: "c05f8844-0b30-39f9-b032-ac20410d2575".into(),
            url: "https://files.cubecraftcdn.com/assets/cubecraft/pack.zip".into(),
            sha1: SHA1.into(),
            size: 4,
        }
    }

    fn download(paths: &Paths, pack: &ServerPack) {
        let file = paths.instance().join(pack.path());
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(file, b"PK..").unwrap();
    }

    fn options(paths: &Paths, text: &str) {
        std::fs::write(paths.instance().join("options.txt"), text).unwrap();
    }

    fn read_options(paths: &Paths) -> String {
        std::fs::read_to_string(paths.instance().join("options.txt")).unwrap()
    }

    #[test]
    fn switches_a_downloaded_pack_on_once() {
        let dir = TempDir::new("once");
        let paths = dir.paths();
        let pack = cubecraft();
        options(&paths, "version:5023\nonboardAccessibility:false\n");

        assert!(switch_on(&paths, std::slice::from_ref(&pack)).unwrap().is_empty(), "not downloaded yet");
        assert_eq!(missing(&paths, std::slice::from_ref(&pack)).len(), 1);

        download(&paths, &pack);
        assert!(missing(&paths, std::slice::from_ref(&pack)).is_empty());
        assert_eq!(switch_on(&paths, std::slice::from_ref(&pack)).unwrap(), vec![format!("cache/{SHA1}")]);
        assert!(read_options(&paths).contains(&format!("resourcePacks:[\"vanilla\",\"cache/{SHA1}\"]\n")));

        // The player switches it off in the game: it stays off.
        options(&paths, "version:5023\nresourcePacks:[\"vanilla\"]\n");
        assert!(switch_on(&paths, &[pack]).unwrap().is_empty());
        assert_eq!(read_options(&paths), "version:5023\nresourcePacks:[\"vanilla\"]\n");
    }

    #[test]
    fn keeps_the_players_packs_and_their_own_server_pack() {
        let dir = TempDir::new("keeps");
        let paths = dir.paths();
        let pack = cubecraft();
        download(&paths, &pack);

        options(&paths, "version:5023\nresourcePacks:[\"vanilla\",\"file/ocd.zip\"]\nlang:en_us\n");
        switch_on(&paths, std::slice::from_ref(&pack)).unwrap();
        assert_eq!(read_options(&paths), format!("version:5023\nresourcePacks:[\"vanilla\",\"file/ocd.zip\",\"cache/{SHA1}\"]\nlang:en_us\n"));

        // Someone whose game already has a newer CubeCraft pack on keeps just that one.
        let dir = TempDir::new("newer");
        let paths = dir.paths();
        download(&paths, &pack);
        let theirs = "version:5023\nresourcePacks:[\"vanilla\",\"cache/0123456789012345678901234567890123456789\"]\n";
        options(&paths, theirs);
        assert!(switch_on(&paths, &[pack]).unwrap().is_empty());
        assert_eq!(read_options(&paths), theirs);
    }

    #[test]
    fn already_on_is_left_alone() {
        let dir = TempDir::new("already");
        let paths = dir.paths();
        let pack = cubecraft();
        download(&paths, &pack);
        let text = format!("version:5023\nresourcePacks:[\"vanilla\",\"cache/{SHA1}\"]\n");
        options(&paths, &text);
        assert!(switch_on(&paths, &[pack]).unwrap().is_empty());
        assert_eq!(read_options(&paths), text);
    }
}
