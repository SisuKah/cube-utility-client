//! Modrinth in the Mods screen: search for mods that run on the pack's Minecraft version and
//! loader, install one (with the libraries it needs) into the mods folder, and remove it again.
//!
//! These are the player's own extra mods, like jars they drop into the folder by hand: the pack
//! sync leaves them alone while the pack allows extra mods, and doesn't update them. What came
//! from here is listed in state/modrinth-mods.json, so the screen can show and remove it. Files
//! come from Modrinth's CDN with the SHA-512 its API lists, through `net`'s host allow-list, like
//! every other download.

use std::collections::{HashSet, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use cuc_manifest::{Manifest, ManifestFile};
use reqwest::{StatusCode, Url};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};

use crate::config;
use crate::error::{parse_json, Error, Result};
use crate::launcher::Launcher;
use crate::net::{self, Download, Hash, Net, Progress};
use crate::paths::{safe_join, Paths};

const API: &str = "https://api.modrinth.com/v2";
const PAGE_SIZE: u32 = 20;
/// No mod comes close; this stops a bad answer from filling the disk.
const MAX_FILE_BYTES: u64 = 200 * 1024 * 1024;
/// A mod plus the libraries it needs. Real mods need two or three.
const MAX_PROJECTS: usize = 12;
const STORE_FILE: &str = "modrinth-mods.json";
/// Icons shown in the launcher only come from here (the window's content policy allows it).
const ICON_ORIGIN: &str = "https://cdn.modrinth.com/";

/// Installs and removals take turns, so the list in state/ never loses an entry.
static BUSY: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

// ------------------------------------------------------------------ what the UI gets

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchPage {
    pub hits: Vec<Hit>,
    /// How many mods match in all.
    pub total: u32,
    pub offset: u32,
    /// Where the next page starts. Not always offset + hits: the popular list leaves out mods
    /// the pack already has.
    pub next_offset: u32,
    /// The Minecraft version the results are for.
    pub minecraft: String,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Hit {
    pub project_id: String,
    pub title: String,
    pub author: String,
    pub description: String,
    pub icon_url: Option<String>,
    pub downloads: u64,
    pub status: Status,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Status {
    Available,
    /// Installed from here (picked, or as a library another mod needed).
    Installed,
    /// The pack already has it.
    InPack,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallOutcome {
    /// What was downloaded: the mod first, then the libraries it needed.
    pub installed: Vec<String>,
    /// Minecraft is running, so the mod loads the next time it starts.
    pub next_launch: bool,
}

// ------------------------------------------------------------------ Modrinth's API

#[derive(Debug, Deserialize)]
struct ApiSearch {
    hits: Vec<ApiHit>,
    total_hits: u32,
    offset: u32,
}

#[derive(Debug, Deserialize)]
struct ApiHit {
    project_id: String,
    title: String,
    #[serde(default)]
    author: String,
    #[serde(default)]
    description: String,
    icon_url: Option<String>,
    #[serde(default)]
    downloads: u64,
}

#[derive(Debug, Clone, Deserialize)]
struct ApiProject {
    title: String,
    icon_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
struct ApiVersion {
    id: String,
    project_id: String,
    version_number: String,
    version_type: String,
    #[serde(default)]
    date_published: String,
    #[serde(default)]
    game_versions: Vec<String>,
    #[serde(default)]
    loaders: Vec<String>,
    #[serde(default)]
    dependencies: Vec<ApiDependency>,
    #[serde(default)]
    files: Vec<ApiFile>,
}

#[derive(Debug, Clone, Deserialize)]
struct ApiDependency {
    version_id: Option<String>,
    project_id: Option<String>,
    dependency_type: String,
}

#[derive(Debug, Clone, Deserialize)]
struct ApiFile {
    url: String,
    filename: String,
    #[serde(default)]
    primary: bool,
    size: u64,
    hashes: ApiHashes,
}

#[derive(Debug, Clone, Deserialize)]
struct ApiHashes {
    sha512: Option<String>,
}

fn api_url(path: &str) -> Url {
    Url::parse(&format!("{API}/{path}")).expect("API paths are built from checked IDs")
}

/// Mods for this Minecraft version and loader that run on the player's side, the most popular
/// first until the player types something.
fn search_url(query: &str, offset: u32, minecraft: &str, loader: &str) -> Url {
    let facets = serde_json::json!([
        ["project_type:mod"],
        [format!("categories:{loader}")],
        [format!("versions:{minecraft}")],
        ["client_side:required", "client_side:optional"]
    ]);
    let query = query.trim();
    let mut url = api_url("search");
    {
        let mut pairs = url.query_pairs_mut();
        if !query.is_empty() {
            pairs.append_pair("query", query);
        }
        pairs.append_pair("facets", &facets.to_string());
        pairs.append_pair("index", if query.is_empty() { "downloads" } else { "relevance" });
        pairs.append_pair("limit", &PAGE_SIZE.to_string());
        pairs.append_pair("offset", &offset.to_string());
    }
    url
}

fn versions_url(project_id: &str, minecraft: &str, loader: &str) -> Url {
    let mut url = api_url(&format!("project/{project_id}/version"));
    url.query_pairs_mut()
        .append_pair("loaders", &serde_json::json!([loader]).to_string())
        .append_pair("game_versions", &serde_json::json!([minecraft]).to_string());
    url
}

/// Modrinth's IDs are short base-62 strings. Anything else never reaches a URL.
fn is_id(id: &str) -> bool {
    (1..=32).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric())
}

async fn get<T: DeserializeOwned>(net: &Net, url: &Url, what: &'static str) -> Result<T> {
    let response = net.get_public(url.as_str()).await.map_err(unreachable)?;
    match response.status {
        status if status.is_success() => parse_json(&response.body, what),
        StatusCode::NOT_FOUND => Err(Error::Message("Modrinth doesn't have that mod anymore.".into())),
        StatusCode::TOO_MANY_REQUESTS => Err(Error::Message("Modrinth is busy right now. Try again in a minute.".into())),
        status => Err(Error::Message(format!("Modrinth answered with an error ({status}). Try again in a moment."))),
    }
}

fn unreachable(error: Error) -> Error {
    match error {
        Error::Http(_) => Error::Message("Can't reach Modrinth. Check your internet connection and try again.".into()),
        other => other,
    }
}

fn icon(url: Option<String>) -> Option<String> {
    url.filter(|u| u.starts_with(ICON_ORIGIN))
}

// ------------------------------------------------------------------ what's installed

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstalledMod {
    pub project_id: String,
    pub version_id: String,
    pub title: String,
    pub version: String,
    pub icon_url: Option<String>,
    /// Inside the game folder: mods/<file>.
    pub path: String,
    pub sha512: String,
    /// The Minecraft version it was picked for.
    pub minecraft: String,
    /// The player chose it, rather than it coming along as a library.
    pub picked: bool,
    /// The picked mods that brought it in as a library.
    #[serde(default)]
    pub needed_by: Vec<String>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct Store {
    #[serde(default)]
    mods: Vec<InstalledMod>,
}

impl Store {
    fn file(paths: &Paths) -> PathBuf {
        paths.state().join(STORE_FILE)
    }

    /// What's installed, leaving out entries whose jar is gone (the player deleted it).
    fn load(paths: &Paths) -> Self {
        let mut store: Store = std::fs::read(Self::file(paths)).ok().and_then(|bytes| serde_json::from_slice(&bytes).ok()).unwrap_or_default();
        let game = paths.instance();
        store.mods.retain(|m| safe_join(&game, &m.path).is_some_and(|path| path.is_file()));
        store
    }

    fn save(&self, paths: &Paths) -> Result<()> {
        let bytes = serde_json::to_vec_pretty(self).map_err(|source| Error::Json { what: "the list of mods from Modrinth", source })?;
        crate::sync::write_atomic(&Self::file(paths), &bytes)?;
        Ok(())
    }

    fn get(&self, project_id: &str) -> Option<&InstalledMod> {
        self.mods.iter().find(|m| m.project_id == project_id)
    }
}

/// The mods installed from Modrinth, for the Mods screen.
pub fn installed(paths: &Paths) -> Vec<InstalledMod> {
    Store::load(paths).mods
}

fn pack_file<'a>(manifest: &'a Manifest, project_id: &str) -> Option<&'a ManifestFile> {
    manifest.files.iter().find(|f| f.modrinth.as_ref().is_some_and(|m| m.project_id == project_id))
}

fn status(project_id: &str, manifest: Option<&Manifest>, store: &Store) -> Status {
    if manifest.is_some_and(|m| pack_file(m, project_id).is_some()) {
        Status::InPack
    } else if store.get(project_id).is_some() {
        Status::Installed
    } else {
        Status::Available
    }
}

// ------------------------------------------------------------------ choosing files

/// The version to install: the newest full release for this game, else the newest beta, else
/// the newest alpha.
fn pick_version<'a>(versions: &'a [ApiVersion], minecraft: &str, loader: &str) -> Option<&'a ApiVersion> {
    let rank = |kind: &str| match kind {
        "release" => 0,
        "beta" => 1,
        _ => 2,
    };
    versions
        .iter()
        .filter(|v| v.game_versions.iter().any(|g| g == minecraft) && v.loaders.iter().any(|l| l == loader) && jar_file(v).is_some())
        // Modrinth writes every date the same way, so the text sorts like the time.
        .min_by(|a, b| rank(&a.version_type).cmp(&rank(&b.version_type)).then_with(|| b.date_published.cmp(&a.date_published)))
}

/// A version's jar: its primary file, or else its first jar.
fn jar_file(version: &ApiVersion) -> Option<&ApiFile> {
    let jars = || version.files.iter().filter(|f| is_jar_name(&f.filename));
    jars().find(|f| f.primary).or_else(|| jars().next())
}

/// A plain .jar name for mods/: nothing that points elsewhere, and nothing Windows refuses.
fn is_jar_name(name: &str) -> bool {
    const RESERVED: &[&str] = &["con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"];
    let lower = name.to_ascii_lowercase();
    let stem = lower.split('.').next().unwrap_or_default();
    (5..=150).contains(&name.len())
        && lower.ends_with(".jar")
        && !name.starts_with(['.', ' '])
        && !name.chars().any(|c| c.is_control() || "<>:\"/\\|?*".contains(c))
        && !RESERVED.contains(&stem)
}

fn is_sha512(hash: &str) -> bool {
    hash.len() == 128 && hash.bytes().all(|b| b.is_ascii_hexdigit())
}

/// What removing a picked mod takes out: the mod, and the libraries only it needed. A library
/// another mod still needs stays, and so does a picked mod that another mod needs (it just
/// stops counting as picked). Returns what stays and what goes.
fn plan_removal(mods: &[InstalledMod], project_id: &str) -> (Vec<InstalledMod>, Vec<InstalledMod>) {
    let mut keep = Vec::new();
    let mut remove = Vec::new();
    for installed in mods {
        let mut installed = installed.clone();
        installed.needed_by.retain(|id| id != project_id);
        if installed.project_id == project_id {
            installed.picked = false;
        }
        if installed.picked || !installed.needed_by.is_empty() {
            keep.push(installed);
        } else {
            remove.push(installed);
        }
    }
    (keep, remove)
}

/// Mods installed from here that the pack now has itself. The pack's copy wins: the player's
/// copy goes, unless it's the very file the pack uses. Returns each one with whether its jar
/// has to be deleted.
fn pack_duplicates<'a>(store: &'a Store, manifest: &Manifest) -> Vec<(&'a InstalledMod, bool)> {
    store
        .mods
        .iter()
        .filter_map(|m| pack_file(manifest, &m.project_id).map(|file| (m, !file.path.eq_ignore_ascii_case(&m.path))))
        .collect()
}

// ------------------------------------------------------------------ installing

/// A file the install will download.
struct Planned {
    project_id: String,
    title: String,
    icon_url: Option<String>,
    version: ApiVersion,
    file: ApiFile,
    sha512: String,
}

/// Downloads a mod and the libraries it needs into mods/, then lists them in state/.
/// Returns the new files.
async fn install(net: &Net, paths: &Paths, manifest: &Manifest, project_id: &str) -> Result<(Vec<String>, Vec<String>)> {
    let _turn = BUSY.lock().await;
    if !is_id(project_id) {
        return Err(Error::Message("That isn't a mod from Modrinth.".into()));
    }
    if !manifest.allow_extra_mods {
        return Err(no_extra_mods());
    }
    if let Some(file) = pack_file(manifest, project_id) {
        return Err(Error::Message(format!("{} is already part of the pack.", file.name)));
    }
    let mut store = Store::load(paths);
    if let Some(existing) = store.mods.iter_mut().find(|m| m.project_id == project_id) {
        // It came in as a library before; now the player has picked it too.
        if !existing.picked {
            existing.picked = true;
            store.save(paths)?;
        }
        return Ok((Vec::new(), Vec::new()));
    }

    let (minecraft, loader) = (manifest.minecraft.as_str(), manifest.loader.kind.as_str());
    let game = paths.instance();
    let mut planned: Vec<Planned> = Vec::new();
    // Libraries it needs that an earlier install already brought in.
    let mut shared: Vec<String> = Vec::new();
    let mut queue = VecDeque::from([project_id.to_string()]);
    let mut seen = HashSet::from([project_id.to_string()]);
    while let Some(id) = queue.pop_front() {
        let project: ApiProject = get(net, &api_url(&format!("project/{id}")), "Modrinth's mod details").await?;
        let versions: Vec<ApiVersion> = get(net, &versions_url(&id, minecraft, loader), "Modrinth's versions").await?;
        let Some(version) = pick_version(&versions, minecraft, loader).cloned() else {
            let mod_name = planned.first().map(|p| p.title.as_str());
            return Err(Error::Message(match mod_name {
                None => format!("{} has no version for Minecraft {minecraft} with Fabric yet.", project.title),
                Some(name) => format!("{name} needs {}, which has no version for Minecraft {minecraft} with Fabric yet.", project.title),
            }));
        };
        for dependency in &version.dependencies {
            let other = match (&dependency.project_id, &dependency.version_id) {
                (Some(other), _) => other.clone(),
                (None, Some(version_id)) if is_id(version_id) => {
                    let other: ApiVersion = get(net, &api_url(&format!("version/{version_id}")), "a Modrinth version").await?;
                    other.project_id
                }
                _ => continue,
            };
            if !is_id(&other) {
                continue;
            }
            match dependency.dependency_type.as_str() {
                "required" => {
                    if let Some(file) = pack_file(manifest, &other) {
                        check_pack_library(manifest, file, &game, &project.title)?;
                    } else if store.get(&other).is_some() {
                        shared.push(other);
                    } else if seen.insert(other.clone()) {
                        queue.push_back(other);
                    }
                }
                "incompatible" => {
                    let clash = pack_file(manifest, &other).filter(|f| is_in_place(&game, f)).map(|f| f.name.clone()).or_else(|| store.get(&other).map(|m| m.title.clone()));
                    if let Some(clash) = clash {
                        return Err(Error::Message(format!("{} doesn't work together with {clash}, which you already have.", project.title)));
                    }
                }
                _ => {}
            }
        }
        if seen.len() > MAX_PROJECTS {
            return Err(Error::Message(format!("{} needs too many other mods to install from here.", project.title)));
        }
        let file = jar_file(&version).cloned().ok_or_else(|| unusable(&project.title))?;
        let sha512 = file.hashes.sha512.clone().filter(|h| is_sha512(h)).ok_or_else(|| unusable(&project.title))?;
        if !is_jar_name(&file.filename) || file.size == 0 || file.size > MAX_FILE_BYTES || !net::is_allowed_str(&file.url) {
            return Err(unusable(&project.title));
        }
        let taken = game.join("mods").join(&file.filename).exists() || planned.iter().any(|p| p.file.filename.eq_ignore_ascii_case(&file.filename));
        if taken {
            return Err(Error::Message(format!("There's already a file called {} in your mods folder.", file.filename)));
        }
        planned.push(Planned { project_id: id, title: project.title, icon_url: icon(project.icon_url), version, file, sha512 });
    }

    let downloads: Vec<Download> = planned
        .iter()
        .map(|p| Download {
            url: p.file.url.clone(),
            dest: game.join("mods").join(&p.file.filename),
            hash: Some(Hash::Sha512(p.sha512.clone())),
            size: Some(p.file.size),
            label: p.title.clone(),
        })
        .collect();
    if let Err(error) = net.download_all(downloads.clone(), 4, Arc::new(Progress::default())).await {
        // Don't leave half an install behind: these files didn't exist before.
        for download in &downloads {
            let _ = std::fs::remove_file(&download.dest);
        }
        return Err(unreachable(error));
    }

    for (i, p) in planned.iter().enumerate() {
        store.mods.push(InstalledMod {
            project_id: p.project_id.clone(),
            version_id: p.version.id.clone(),
            title: p.title.clone(),
            version: p.version.version_number.clone(),
            icon_url: p.icon_url.clone(),
            path: format!("mods/{}", p.file.filename),
            sha512: p.sha512.clone(),
            minecraft: minecraft.to_string(),
            picked: i == 0,
            needed_by: if i == 0 { Vec::new() } else { vec![project_id.to_string()] },
        });
    }
    for library in shared {
        if let Some(installed) = store.mods.iter_mut().find(|m| m.project_id == library) {
            if !installed.needed_by.iter().any(|id| id == project_id) {
                installed.needed_by.push(project_id.to_string());
            }
        }
    }
    store.save(paths)?;
    tracing::info!("installed from Modrinth: {}", planned.iter().map(|p| format!("{} {}", p.title, p.version.version_number)).collect::<Vec<_>>().join(", "));
    Ok((planned.iter().map(|p| p.title.clone()).collect(), planned.iter().map(|p| format!("mods/{}", p.file.filename)).collect()))
}

fn unusable(title: &str) -> Error {
    Error::Message(format!("Modrinth's file for {title} can't be installed from here."))
}

fn no_extra_mods() -> Error {
    Error::Message("This pack doesn't allow extra mods.".into())
}

fn is_in_place(game: &Path, file: &ManifestFile) -> bool {
    safe_join(game, &file.path).is_some_and(|path| path.is_file())
}

/// A library the mod needs is part of the pack: fine while the pack has it installed. Optional
/// pack mods (and the libraries that come with them) can be switched off.
fn check_pack_library(manifest: &Manifest, file: &ManifestFile, game: &Path, mod_title: &str) -> Result<()> {
    if is_in_place(game, file) {
        return Ok(());
    }
    let with: Vec<&str> = manifest.files.iter().filter(|f| file.required_by.contains(&f.id)).map(|f| f.name.as_str()).collect();
    Err(Error::Message(if !with.is_empty() {
        format!("{mod_title} needs {}, which comes with {}. Switch that on under Extras first.", file.name, with.join(" or "))
    } else if crate::sync::is_switchable(file) {
        format!("{mod_title} needs {}. Switch it on under Extras first.", file.name)
    } else {
        format!("{mod_title} needs {}, which the launcher is still installing. Try again in a moment.", file.name)
    }))
}

/// Removes a mod the player picked, and the libraries that only it needed. Returns the removed files.
async fn remove(paths: &Paths, project_id: &str) -> Result<Vec<String>> {
    let _turn = BUSY.lock().await;
    let store = Store::load(paths);
    let Some(target) = store.get(project_id) else {
        return Err(Error::Message("That mod isn't installed from Modrinth.".into()));
    };
    if !target.picked {
        let by: Vec<&str> = target.needed_by.iter().filter_map(|id| store.get(id)).map(|m| m.title.as_str()).collect();
        return Err(Error::Message(format!("{} came with {}. Remove that instead.", target.title, by.join(" and "))));
    }
    let (keep, gone) = plan_removal(&store.mods, project_id);
    let game = paths.instance();
    let mut removed = Vec::new();
    for installed in &gone {
        let Some(path) = safe_join(&game, &installed.path) else { continue };
        match std::fs::remove_file(&path) {
            Ok(()) => removed.push(installed.path.clone()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            // A jar Java still has open can't be deleted on Windows. What's gone is gone; the
            // list drops it on the next load.
            Err(_) => return Err(Error::Message(format!("Couldn't remove {}. Close Minecraft and try again.", installed.title))),
        }
    }
    Store { mods: keep }.save(paths)?;
    tracing::info!("removed from Modrinth installs: {}", gone.iter().map(|m| m.title.as_str()).collect::<Vec<_>>().join(", "));
    Ok(removed)
}

/// After a sync: drops the player's copies of mods the pack now has itself. Returns the jars
/// deleted. Only called while the game isn't running.
pub async fn drop_pack_duplicates(paths: &Paths, manifest: &Manifest) -> Vec<String> {
    let _turn = BUSY.lock().await;
    let mut store = Store::load(paths);
    let duplicates: Vec<(String, String, bool)> = pack_duplicates(&store, manifest).into_iter().map(|(m, delete)| (m.project_id.clone(), m.path.clone(), delete)).collect();
    if duplicates.is_empty() {
        return Vec::new();
    }
    let game = paths.instance();
    let mut deleted = Vec::new();
    for (project_id, path, delete) in duplicates {
        if delete {
            match safe_join(&game, &path).map(std::fs::remove_file) {
                Some(Ok(())) => deleted.push(path.clone()),
                Some(Err(error)) if error.kind() != std::io::ErrorKind::NotFound => {
                    tracing::warn!("couldn't remove {path}, which the pack now includes: {error}");
                    continue;
                }
                _ => {}
            }
        }
        tracing::info!("the pack now includes {path} ({project_id}), so the copy from Modrinth goes");
        store.mods.retain(|m| m.project_id != project_id);
    }
    if let Err(error) = store.save(paths) {
        tracing::warn!("couldn't save the list of mods from Modrinth: {error}");
    }
    deleted
}

// ------------------------------------------------------------------ commands

/// The Minecraft version and loader to search for: the installed pack's.
fn target(manifest: Option<&Manifest>) -> (String, String) {
    match manifest {
        Some(m) => (m.minecraft.clone(), m.loader.kind.clone()),
        None => (config::DEFAULT_MINECRAFT_VERSION.to_string(), "fabric".to_string()),
    }
}

/// One page of mods that work with the pack.
#[tauri::command]
pub async fn modrinth_search(app: AppHandle, query: String, offset: u32) -> Result<SearchPage> {
    let launcher = app.state::<Launcher>();
    let manifest = launcher.installed_manifest();
    if manifest.as_ref().is_some_and(|m| !m.allow_extra_mods) {
        return Err(no_extra_mods());
    }
    let (minecraft, loader) = target(manifest.as_deref());
    let query: String = query.chars().take(100).collect();
    let net = launcher.net().clone();
    let page: ApiSearch = get(&net, &search_url(&query, offset.min(10_000), &minecraft, &loader), "Modrinth's search results").await?;
    let store = Store::load(launcher.paths());
    let next_offset = page.offset + page.hits.len() as u32;
    let browsing = query.trim().is_empty();
    let hits = page
        .hits
        .into_iter()
        .filter(|hit| is_id(&hit.project_id))
        .map(|hit| Hit {
            status: status(&hit.project_id, manifest.as_deref(), &store),
            project_id: hit.project_id,
            title: hit.title,
            author: hit.author,
            description: hit.description,
            icon_url: icon(hit.icon_url),
            downloads: hit.downloads,
        })
        // The most popular mods are mostly the pack's own. Searching still finds them.
        .filter(|hit| !(browsing && hit.status == Status::InPack))
        .collect();
    Ok(SearchPage { hits, total: page.total_hits, offset: page.offset, next_offset, minecraft })
}

/// Installs a mod from Modrinth, with the libraries it needs.
#[tauri::command]
pub async fn modrinth_install(app: AppHandle, project_id: String) -> Result<InstallOutcome> {
    let launcher = app.state::<Launcher>();
    let Some(manifest) = launcher.installed_manifest() else {
        return Err(Error::Message("Wait until the launcher has installed the pack, then try again.".into()));
    };
    let (net, paths) = (launcher.net().clone(), launcher.paths().clone());
    let (installed, files) = install(&net, &paths, &manifest, &project_id).await?;
    launcher.note_extras(&app, &files, &[]);
    Ok(InstallOutcome { installed, next_launch: launcher.is_playing() })
}

/// Removes a mod installed from Modrinth, with the libraries only it needed.
#[tauri::command]
pub async fn modrinth_remove(app: AppHandle, project_id: String) -> Result<()> {
    let launcher = app.state::<Launcher>();
    if launcher.is_playing() {
        return Err(Error::Message("Close Minecraft first, then remove it.".into()));
    }
    let paths = launcher.paths().clone();
    let removed = remove(&paths, &project_id).await?;
    launcher.note_extras(&app, &[], &removed);
    Ok(())
}

/// Opens a mod's page on modrinth.com in the player's browser.
#[tauri::command]
pub fn open_modrinth_page(app: AppHandle, project_id: String) -> Result<()> {
    use tauri_plugin_opener::OpenerExt;
    if !is_id(&project_id) {
        return Err(Error::Message("That isn't a mod from Modrinth.".into()));
    }
    app.opener().open_url(format!("https://modrinth.com/mod/{project_id}"), None::<&str>).map_err(|error| Error::Message(format!("Couldn't open your browser: {error}")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use cuc_manifest::{Group, Loader, ModrinthRef, SCHEMA};

    fn version(id: &str, kind: &str, date: &str, game: &str, files: &[(&str, bool)]) -> ApiVersion {
        ApiVersion {
            id: id.into(),
            project_id: "PROJ".into(),
            version_number: id.into(),
            version_type: kind.into(),
            date_published: date.into(),
            game_versions: vec![game.into()],
            loaders: vec!["fabric".into()],
            dependencies: vec![],
            files: files
                .iter()
                .map(|(name, primary)| ApiFile { url: format!("https://cdn.modrinth.com/data/x/{name}"), filename: (*name).into(), primary: *primary, size: 1, hashes: ApiHashes { sha512: None } })
                .collect(),
        }
    }

    fn installed(id: &str, picked: bool, needed_by: &[&str]) -> InstalledMod {
        InstalledMod {
            project_id: id.into(),
            version_id: format!("{id}v"),
            title: id.to_uppercase(),
            version: "1.0".into(),
            icon_url: None,
            path: format!("mods/{id}.jar"),
            sha512: "a".repeat(128),
            minecraft: "26.3".into(),
            picked,
            needed_by: needed_by.iter().map(|s| s.to_string()).collect(),
        }
    }

    fn manifest(files: &[(&str, &str, &str)]) -> Manifest {
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
            files: files
                .iter()
                .map(|(id, project, path)| ManifestFile {
                    id: (*id).into(),
                    name: id.to_uppercase(),
                    version: "1".into(),
                    description: None,
                    group: Group::Library,
                    path: (*path).into(),
                    url: "https://cdn.modrinth.com/x.jar".into(),
                    sha512: "a".repeat(128),
                    size: 1,
                    required: true,
                    default_enabled: true,
                    required_by: vec![],
                    modrinth: Some(ModrinthRef { project_id: (*project).into(), version_id: "v".into() }),
                })
                .collect(),
            configs: vec![],
        }
    }

    #[test]
    fn searches_client_mods_for_the_packs_game() {
        let url = search_url("  zoom ", 40, "26.3", "fabric");
        let pairs: Vec<(String, String)> = url.query_pairs().map(|(k, v)| (k.into_owned(), v.into_owned())).collect();
        let get = |key: &str| pairs.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str());
        assert_eq!(url.host_str(), Some("api.modrinth.com"));
        assert_eq!(get("query"), Some("zoom"));
        assert_eq!(get("index"), Some("relevance"));
        assert_eq!(get("offset"), Some("40"));
        assert_eq!(get("facets"), Some(r#"[["project_type:mod"],["categories:fabric"],["versions:26.3"],["client_side:required","client_side:optional"]]"#));
        let popular = search_url("", 0, "26.3", "fabric");
        assert!(popular.query_pairs().all(|(k, _)| k != "query"));
        assert!(popular.query_pairs().any(|(k, v)| k == "index" && v == "downloads"));
        assert!(net::is_allowed(&url), "api.modrinth.com must be on the allow-list");
    }

    #[test]
    fn asks_for_versions_of_the_packs_game_and_loader() {
        let url = versions_url("AANobbMI", "26.3", "fabric");
        assert_eq!(url.path(), "/v2/project/AANobbMI/version");
        assert!(url.query_pairs().any(|(k, v)| k == "game_versions" && v == r#"["26.3"]"#));
        assert!(url.query_pairs().any(|(k, v)| k == "loaders" && v == r#"["fabric"]"#));
    }

    #[test]
    fn picks_the_newest_release_before_betas() {
        let versions = [
            version("beta-new", "beta", "2026-10-02T10:00:00Z", "26.3", &[("a.jar", true)]),
            version("release-old", "release", "2026-08-01T10:00:00Z", "26.3", &[("a.jar", true)]),
            version("release-new", "release", "2026-09-01T10:00:00Z", "26.3", &[("a.jar", true)]),
            version("other-game", "release", "2026-10-05T10:00:00Z", "26.2", &[("a.jar", true)]),
            version("no-jar", "release", "2026-10-06T10:00:00Z", "26.3", &[("a.zip", true)]),
        ];
        assert_eq!(pick_version(&versions, "26.3", "fabric").map(|v| v.id.as_str()), Some("release-new"));
        assert_eq!(pick_version(&versions[..1], "26.3", "fabric").map(|v| v.id.as_str()), Some("beta-new"), "a beta when there's no release");
        assert!(pick_version(&versions, "26.3", "quilt").is_none());
        assert!(pick_version(&versions[3..], "26.3", "fabric").is_none());
    }

    #[test]
    fn takes_the_primary_jar() {
        let both = version("v", "release", "", "26.3", &[("sources.jar", false), ("mod.jar", true)]);
        assert_eq!(jar_file(&both).map(|f| f.filename.as_str()), Some("mod.jar"));
        let no_primary = version("v", "release", "", "26.3", &[("readme.txt", true), ("mod.jar", false)]);
        assert_eq!(jar_file(&no_primary).map(|f| f.filename.as_str()), Some("mod.jar"));
    }

    #[test]
    fn only_plain_jar_names_reach_the_mods_folder() {
        for good in ["iris-fabric-1.11.7+mc26.3.jar", "appleskin-fabric-mc26.3-3.0.10.jar", "Mod Name 1.0.JAR", "console.jar"] {
            assert!(is_jar_name(good), "{good}");
        }
        for bad in ["../evil.jar", "a/b.jar", "a\\b.jar", "C:x.jar", "x.zip", ".hidden.jar", "CON.jar", "nul.jar", "x.jar\n", "x?.jar", ".jar", ""] {
            assert!(!is_jar_name(bad), "{bad:?}");
        }
        assert!(is_sha512(&"ab12".repeat(32)));
        assert!(!is_sha512("ab12"));
    }

    #[test]
    fn checks_ids_before_they_reach_a_url() {
        assert!(is_id("AANobbMI"));
        assert!(!is_id("../search"));
        assert!(!is_id("a b"));
        assert!(!is_id(""));
    }

    #[test]
    fn removing_a_mod_takes_its_own_libraries_with_it() {
        let mods = [installed("iris", true, &[]), installed("lib", false, &["iris"]), installed("shared", false, &["iris", "other"]), installed("other", true, &[])];
        let (keep, gone) = plan_removal(&mods, "iris");
        assert_eq!(gone.iter().map(|m| m.project_id.as_str()).collect::<Vec<_>>(), ["iris", "lib"]);
        assert_eq!(keep.iter().map(|m| m.project_id.as_str()).collect::<Vec<_>>(), ["shared", "other"]);
        assert_eq!(keep[0].needed_by, ["other"]);
    }

    #[test]
    fn a_picked_mod_another_mod_needs_stays_as_a_library() {
        let mods = [installed("lib", true, &["other"]), installed("other", true, &[])];
        let (keep, gone) = plan_removal(&mods, "lib");
        assert!(gone.is_empty());
        assert!(!keep[0].picked);
        assert_eq!(keep[0].needed_by, ["other"]);
    }

    #[test]
    fn the_packs_copy_wins_over_the_players() {
        let store = Store { mods: vec![installed("apple", true, &[]), installed("same", true, &[]), installed("mine", true, &[])] };
        let pack = manifest(&[("appleskin", "apple", "mods/appleskin-3.0.jar"), ("same", "same", "mods/same.jar")]);
        let found: Vec<(&str, bool)> = pack_duplicates(&store, &pack).into_iter().map(|(m, delete)| (m.project_id.as_str(), delete)).collect();
        assert_eq!(found, [("apple", true), ("same", false)], "the pack's own file is never deleted");
    }

    #[test]
    fn marks_what_the_pack_or_the_player_already_has() {
        let pack = manifest(&[("sodium", "AANobbMI", "mods/sodium.jar")]);
        let store = Store { mods: vec![installed("YL57xq9U", true, &[])] };
        assert_eq!(status("AANobbMI", Some(&pack), &store), Status::InPack);
        assert_eq!(status("YL57xq9U", Some(&pack), &store), Status::Installed);
        assert_eq!(status("EsAfCjCV", Some(&pack), &store), Status::Available);
        assert_eq!(status("AANobbMI", None, &store), Status::Available);
    }

    #[test]
    fn forgets_mods_whose_jar_is_gone() {
        let root = std::env::temp_dir().join(format!("cuc-modrinth-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let paths = Paths::new(root.clone());
        std::fs::create_dir_all(paths.instance().join("mods")).unwrap();
        std::fs::write(paths.instance().join("mods").join("kept.jar"), b"jar").unwrap();
        Store { mods: vec![installed("kept", true, &[]), installed("deleted", true, &[])] }.save(&paths).unwrap();
        let loaded = Store::load(&paths);
        assert_eq!(loaded.mods.iter().map(|m| m.project_id.as_str()).collect::<Vec<_>>(), ["kept"]);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn a_library_from_the_pack_must_be_switched_on() {
        let game = std::env::temp_dir().join(format!("cuc-modrinth-lib-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&game);
        std::fs::create_dir_all(game.join("mods")).unwrap();
        let mut pack = manifest(&[("zoomify", "w7ThoJFB", "mods/zoomify.jar"), ("yacl", "1eAoo2KR", "mods/yacl.jar")]);
        pack.files[1].required = false;
        pack.files[1].required_by = vec!["zoomify".into()];
        let yacl = pack.files[1].clone();
        let error = check_pack_library(&pack, &yacl, &game, "Some Mod").unwrap_err().to_string();
        assert_eq!(error, "Some Mod needs YACL, which comes with ZOOMIFY. Switch that on under Extras first.");
        std::fs::write(game.join("mods").join("yacl.jar"), b"jar").unwrap();
        assert!(check_pack_library(&pack, &yacl, &game, "Some Mod").is_ok(), "fine while the pack has it installed");
        let _ = std::fs::remove_dir_all(&game);
    }

    #[test]
    fn only_modrinths_cdn_serves_icons() {
        assert_eq!(icon(Some("https://cdn.modrinth.com/data/x/icon.png".into())).as_deref(), Some("https://cdn.modrinth.com/data/x/icon.png"));
        assert_eq!(icon(Some("https://example.com/icon.png".into())), None);
        assert_eq!(icon(None), None);
    }
}
