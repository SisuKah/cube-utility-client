//! The launcher's state, the commands the UI calls, pack updates, and running the game.
//! State changes go to the UI as `launcher://state` events, pack details as `pack://info`.

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use cuc_manifest::Manifest;
use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWriteExt, BufReader};

use crate::config;
use crate::error::{Error, Result};
use crate::game::{self, launch, GamePlan};
use crate::net::{Download, Net, Progress};
use crate::paths::Paths;
use crate::sync::{PackSync, Report};

const STATE_EVENT: &str = "launcher://state";
const PACK_EVENT: &str = "pack://info";
/// The log line that means the game has finished loading to the title screen.
const READY_LINE: &str = "Sound engine started";
/// Log lines that mean Fabric refused the mods. It then shows its own error window and waits.
const FAILED_LINES: &[&str] = &["Incompatible mods found!", "Mod discovery failed!", "Mod resolution failed"];

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "phase", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum Phase {
    Checking,
    /// Downloading or applying a pack update.
    Syncing { done_bytes: u64, total_bytes: u64, current: String },
    /// Repair: checking every game file against its hash.
    Verifying { done_files: u64, total_files: u64 },
    NeedsInstall { download_bytes: u64 },
    Installing { done_bytes: u64, total_bytes: u64, current: String },
    Ready,
    Launching,
    Playing { started_at_ms: u64 },
    Error { message: String },
}

/// What the UI shows about the pack.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PackInfo {
    /// A pack server is set. Release builds have none until the GitHub repo exists.
    pub configured: bool,
    pub channel: String,
    pub pack_version: Option<String>,
    pub minecraft: String,
    pub loader_version: String,
    pub java_major: Option<u32>,
    /// Mods installed from the pack.
    pub mod_count: usize,
    pub notes_title: Option<String>,
    pub notes: Option<String>,
    /// The last time the pack server answered (ms since 1970).
    pub checked_at_ms: Option<u64>,
    pub offline: bool,
    pub problem: Option<String>,
    /// A newer pack is waiting for the game to close.
    pub update_ready: bool,
    /// When the launcher last changed the mods while it was open (ms since 1970).
    pub updated_at_ms: Option<u64>,
    /// Jars the player added to the mods folder themselves.
    pub extra_mods: Vec<String>,
    /// Jars moved to mods-disabled because the pack doesn't allow extra mods.
    pub disabled_mods: Vec<String>,
}

impl PackInfo {
    fn describe(&mut self, manifest: Option<&Manifest>) {
        match manifest {
            Some(m) => {
                self.pack_version = Some(m.pack_version.clone());
                self.minecraft = m.minecraft.clone();
                self.loader_version = m.loader.version.clone();
                self.java_major = m.java.as_ref().map(|j| j.major);
                self.notes_title = m.notes.as_ref().map(|n| n.title.clone());
                self.notes = m.notes.as_ref().map(|n| n.markdown.clone());
            }
            None => {
                self.minecraft = config::DEFAULT_MINECRAFT_VERSION.to_string();
                self.loader_version = config::DEFAULT_FABRIC_LOADER_VERSION.to_string();
            }
        }
    }
}

pub struct Launcher {
    paths: Paths,
    net: Net,
    sync: PackSync,
    channel: String,
    phase: Mutex<Phase>,
    pack: Mutex<PackInfo>,
    /// The installed pack. It decides the Minecraft, Fabric and Java versions.
    manifest: Mutex<Option<Arc<Manifest>>>,
    plan: tokio::sync::Mutex<Option<(String, Arc<GamePlan>)>>,
    /// Held while a sync, an install, a repair or a game runs.
    work: tokio::sync::Mutex<()>,
    /// Set from PLAY or Repair until it's done, so a second click doesn't queue another.
    requested: AtomicBool,
    /// True from the moment the game starts until it exits.
    playing: AtomicBool,
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64)
}

impl Launcher {
    pub fn new(paths: Paths, net: Net, app_version: String) -> Self {
        let channel = config::DEFAULT_CHANNEL.to_string();
        let sync = PackSync::new(paths.clone(), net.clone(), app_version);
        // Show the installed pack right away, before the server has answered.
        let manifest = sync.installed(&channel);
        let (checked_at_ms, mod_count) = sync.saved_status();
        let mut pack = PackInfo { configured: config::pack_base_url().is_some(), channel: channel.clone(), checked_at_ms, mod_count, ..Default::default() };
        pack.describe(manifest.as_deref());
        Self {
            paths,
            net,
            sync,
            channel,
            phase: Mutex::new(Phase::Checking),
            pack: Mutex::new(pack),
            manifest: Mutex::new(manifest),
            plan: tokio::sync::Mutex::new(None),
            work: tokio::sync::Mutex::new(()),
            requested: AtomicBool::new(false),
            playing: AtomicBool::new(false),
        }
    }

    fn current(&self) -> Phase {
        lock(&self.phase).clone()
    }

    /// Minecraft is running (started by this launcher).
    pub fn is_playing(&self) -> bool {
        self.playing.load(Ordering::SeqCst)
    }

    fn set(&self, app: &AppHandle, phase: Phase) {
        *lock(&self.phase) = phase.clone();
        let _ = app.emit(STATE_EVENT, phase);
    }

    fn pack_info(&self) -> PackInfo {
        lock(&self.pack).clone()
    }

    fn update_pack(&self, app: &AppHandle, change: impl FnOnce(&mut PackInfo)) {
        let info = {
            let mut pack = lock(&self.pack);
            change(&mut pack);
            pack.clone()
        };
        let _ = app.emit(PACK_EVENT, info);
    }

    fn take_report(&self, app: &AppHandle, report: &Report, problem: Option<String>) {
        if report.manifest.is_some() {
            *lock(&self.manifest) = report.manifest.clone();
        }
        self.update_pack(app, |pack| {
            if report.manifest.is_some() {
                pack.describe(report.manifest.as_deref());
                pack.mod_count = report.mods_installed;
            }
            pack.checked_at_ms = report.checked_at_ms;
            pack.offline = report.offline;
            pack.problem = problem.or_else(|| report.problem.clone());
            pack.extra_mods = report.extras.clone();
            if !report.moved_extras.is_empty() {
                pack.disabled_mods = report.moved_extras.clone();
            }
            if report.changed {
                pack.updated_at_ms = Some(now_ms());
                pack.update_ready = false;
            }
        });
    }

    /// Minecraft, Fabric Loader and Java runtime, from the installed pack.
    fn game_target(&self) -> (String, String, Option<String>) {
        match lock(&self.manifest).as_ref() {
            Some(m) => (m.minecraft.clone(), m.loader.version.clone(), m.java.as_ref().map(|j| j.component.clone())),
            None => (config::DEFAULT_MINECRAFT_VERSION.to_string(), config::DEFAULT_FABRIC_LOADER_VERSION.to_string(), None),
        }
    }

    async fn plan(&self, refresh: bool) -> Result<Arc<GamePlan>> {
        let (minecraft, loader, java) = self.game_target();
        let key = format!("{minecraft}/{loader}/{}", java.as_deref().unwrap_or("-"));
        let mut cached = self.plan.lock().await;
        if let Some((cached_key, plan)) = cached.as_ref() {
            if !refresh && *cached_key == key {
                return Ok(plan.clone());
            }
        }
        let started = Instant::now();
        let plan = Arc::new(game::resolve(&self.net, &self.paths, &minecraft, &loader, java.as_deref()).await?);
        tracing::info!("resolved {} ({} files) in {:.1}s", plan.version.id, plan.downloads.len(), started.elapsed().as_secs_f32());
        *cached = Some((key, plan.clone()));
        Ok(plan)
    }

    /// While the game runs, files can't change: only notes that an update is waiting.
    async fn check_while_playing(&self, app: &AppHandle) {
        if lock(&self.pack).update_ready {
            return;
        }
        if self.sync.peek(&self.channel).await {
            tracing::info!("a pack update is ready; it applies when the game closes");
            self.update_pack(app, |pack| pack.update_ready = true);
        }
    }
}

/// At startup and then every few minutes: apply pack updates, and check the game is installed.
pub fn start(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        refresh(&app, true).await;
        loop {
            tokio::time::sleep(config::PACK_CHECK_INTERVAL).await;
            refresh(&app, false).await;
        }
    });
}

async fn refresh(app: &AppHandle, startup: bool) {
    let launcher = app.state::<Launcher>();
    if launcher.playing.load(Ordering::SeqCst) {
        launcher.check_while_playing(app).await;
        return;
    }
    // Something else is running (PLAY, an install, a repair): it syncs first anyway.
    let Ok(_work) = launcher.work.try_lock() else { return };
    let changed = match sync_pack(app, &launcher, false).await {
        Ok(changed) => changed,
        Err(error) => {
            tracing::error!("pack sync failed: {error}");
            launcher.update_pack(app, |pack| pack.problem = Some(format!("Update failed: {error}")));
            true
        }
    };
    let showing_progress = matches!(launcher.current(), Phase::Checking | Phase::Syncing { .. });
    if startup || changed || showing_progress {
        let phase = game_phase(&launcher, startup).await;
        launcher.set(app, phase);
    }
}

/// Ready, or what still has to be downloaded before the game can start.
async fn game_phase(launcher: &Launcher, refresh: bool) -> Phase {
    match launcher.plan(refresh).await {
        Ok(plan) => {
            let missing = plan.missing();
            if missing.is_empty() {
                Phase::Ready
            } else {
                Phase::NeedsInstall { download_bytes: missing.iter().filter_map(|d| d.size).sum() }
            }
        }
        Err(error) => {
            tracing::error!("checking the game failed: {error}");
            Phase::Error { message: error.to_string() }
        }
    }
}

/// Brings the mods in line with the newest pack, showing progress only when something
/// downloads or changes. If a new pack can't be downloaded, the installed one stays. Returns
/// whether the mods changed.
async fn sync_pack(app: &AppHandle, launcher: &Launcher, repair: bool) -> Result<bool> {
    let mut prepared = launcher.sync.prepare(&launcher.channel, true, repair).await?;
    let mut problem = None;
    if !prepared.downloads.is_empty() {
        let downloads = prepared.downloads.clone();
        let syncing = |done_bytes, total_bytes, current| Phase::Syncing { done_bytes, total_bytes, current };
        if let Err(error) = download_with_progress(app, downloads, 8, syncing).await {
            if !prepared.is_update() {
                return Err(error);
            }
            tracing::warn!("the pack update couldn't download ({error}); keeping the installed pack");
            let offline = prepared.report.offline;
            let checked_at_ms = prepared.report.checked_at_ms;
            prepared = launcher.sync.prepare(&launcher.channel, false, false).await?;
            if !prepared.downloads.is_empty() {
                return Err(error);
            }
            prepared.report.offline = offline;
            prepared.report.checked_at_ms = checked_at_ms;
            problem = Some(format!("Update failed: {error}"));
        }
    }
    if prepared.has_changes() {
        launcher.set(app, Phase::Syncing { done_bytes: 0, total_bytes: 0, current: "Applying the update".into() });
    }
    let report = launcher.sync.finish(prepared).await?;
    launcher.take_report(app, &report, problem);
    Ok(report.changed)
}

/// Downloads with the progress shown as `phase` a few times per second.
async fn download_with_progress(app: &AppHandle, items: Vec<Download>, concurrency: usize, phase: fn(u64, u64, String) -> Phase) -> Result<()> {
    let launcher = app.state::<Launcher>();
    let total: u64 = items.iter().filter_map(|d| d.size).sum();
    let count = items.len();
    let progress = Arc::new(Progress::default());
    progress.total_bytes.store(total, Ordering::Relaxed);
    tracing::info!("downloading {count} files ({:.1} MB)", total as f64 / 1_048_576.0);

    let ticker = {
        let app = app.clone();
        let progress = progress.clone();
        tauri::async_runtime::spawn(async move {
            loop {
                let state = phase(progress.done_bytes.load(Ordering::Relaxed), progress.total_bytes.load(Ordering::Relaxed), progress.current());
                app.state::<Launcher>().set(&app, state);
                tokio::time::sleep(Duration::from_millis(150)).await;
            }
        })
    };
    let started = Instant::now();
    let result = launcher.net.download_all(items, concurrency, progress).await;
    ticker.abort();
    result?;
    tracing::info!("downloaded {count} files in {:.1}s", started.elapsed().as_secs_f32());
    Ok(())
}

#[tauri::command]
pub fn launcher_state(launcher: tauri::State<'_, Launcher>) -> Phase {
    launcher.current()
}

#[tauri::command]
pub fn pack_info(launcher: tauri::State<'_, Launcher>) -> PackInfo {
    launcher.pack_info()
}

/// Checks for a pack update now (the "Retry" after a failed update).
#[tauri::command]
pub fn sync_now(app: AppHandle) {
    tauri::async_runtime::spawn(async move { refresh(&app, false).await });
}

/// Switches an optional mod on or off. It's installed or removed by the next sync, which
/// starts right away unless the game is running.
#[tauri::command]
pub fn set_mod_enabled(app: AppHandle, id: String, enabled: bool) -> Result<()> {
    app.state::<Launcher>().sync.set_choice(&id, enabled)?;
    tauri::async_runtime::spawn(async move { refresh(&app, false).await });
    Ok(())
}

/// PLAY: updates the mods, installs whatever is missing, then starts the game.
#[tauri::command]
pub fn play(app: AppHandle, options: launch::LaunchOptions) -> Result<()> {
    let launcher = app.state::<Launcher>();
    if launcher.requested.swap(true, Ordering::SeqCst) {
        return Err(Error::Message("The game is already starting.".into()));
    }
    tauri::async_runtime::spawn(async move {
        let launcher = app.state::<Launcher>();
        // Waits for a background sync to finish first.
        let work = launcher.work.lock().await;
        let result = run(&app, &launcher, options).await;
        launcher.playing.store(false, Ordering::SeqCst);
        if result.is_ok() && lock(&launcher.pack).update_ready {
            // The update that came out while the game was running.
            if let Err(error) = sync_pack(&app, &launcher, false).await {
                tracing::error!("pack sync failed: {error}");
                launcher.update_pack(&app, |pack| pack.problem = Some(format!("Update failed: {error}")));
            }
        }
        let phase = match result {
            Ok(()) => game_phase(&launcher, false).await,
            Err(error) => {
                tracing::error!("play failed: {error}");
                show_window(&app);
                Phase::Error { message: error.to_string() }
            }
        };
        launcher.set(&app, phase);
        drop(work);
        launcher.requested.store(false, Ordering::SeqCst);
    });
    Ok(())
}

async fn run(app: &AppHandle, launcher: &Launcher, options: launch::LaunchOptions) -> Result<()> {
    launcher.set(app, Phase::Checking);
    // The mods first, so the game never starts with a folder that's halfway through an update.
    sync_pack(app, launcher, false).await?;
    let plan = launcher.plan(false).await?;
    let missing = plan.missing();
    if !missing.is_empty() {
        install(app, &plan, missing).await?;
    }
    start_game(app, launcher, &plan, options).await
}

/// Repair: re-checks every mod and game file against its hash and downloads what's wrong.
#[tauri::command]
pub fn repair(app: AppHandle) -> Result<()> {
    let launcher = app.state::<Launcher>();
    if launcher.playing.load(Ordering::SeqCst) {
        return Err(Error::Message("Close Minecraft first, then repair.".into()));
    }
    if launcher.requested.swap(true, Ordering::SeqCst) {
        return Err(Error::Message("The launcher is busy. Wait a moment and try again.".into()));
    }
    tauri::async_runtime::spawn(async move {
        let launcher = app.state::<Launcher>();
        let work = launcher.work.lock().await;
        let phase = match repair_files(&app, &launcher).await {
            Ok(()) => game_phase(&launcher, false).await,
            Err(error) => {
                tracing::error!("repair failed: {error}");
                Phase::Error { message: error.to_string() }
            }
        };
        launcher.set(&app, phase);
        drop(work);
        launcher.requested.store(false, Ordering::SeqCst);
    });
    Ok(())
}

async fn repair_files(app: &AppHandle, launcher: &Launcher) -> Result<()> {
    launcher.set(app, Phase::Checking);
    sync_pack(app, launcher, true).await?;
    let plan = launcher.plan(true).await?;
    let started = Instant::now();
    let broken = verify_all(app, plan.downloads.clone()).await?;
    tracing::info!("repair: checked {} game files in {:.1}s; {} need downloading again", plan.downloads.len(), started.elapsed().as_secs_f32(), broken.len());
    if !broken.is_empty() {
        install(app, &plan, broken).await?;
    }
    Ok(())
}

/// Hashes every file on all cores, showing progress. Returns the ones that are wrong.
async fn verify_all(app: &AppHandle, files: Vec<Download>) -> Result<Vec<Download>> {
    let total_files = files.len() as u64;
    let done = Arc::new(std::sync::atomic::AtomicU64::new(0));
    let ticker = {
        let app = app.clone();
        let done = done.clone();
        tauri::async_runtime::spawn(async move {
            loop {
                let done_files = done.load(Ordering::Relaxed);
                app.state::<Launcher>().set(&app, Phase::Verifying { done_files, total_files });
                tokio::time::sleep(Duration::from_millis(200)).await;
            }
        })
    };
    let result = tokio::task::spawn_blocking(move || {
        let threads = std::thread::available_parallelism().map_or(4, |n| n.get()).clamp(1, 8);
        let chunk = files.len().div_ceil(threads).max(1);
        std::thread::scope(|scope| {
            let workers: Vec<_> = files
                .chunks(chunk)
                .map(|part| {
                    let done = &done;
                    scope.spawn(move || {
                        part.iter()
                            .filter(|file| {
                                let ok = file.verify();
                                done.fetch_add(1, Ordering::Relaxed);
                                !ok
                            })
                            .cloned()
                            .collect::<Vec<_>>()
                    })
                })
                .collect();
            workers.into_iter().flat_map(|worker| worker.join().unwrap_or_default()).collect::<Vec<_>>()
        })
    })
    .await;
    ticker.abort();
    result.map_err(|e| Error::Message(format!("the file check stopped unexpectedly: {e}")))
}

async fn install(app: &AppHandle, plan: &GamePlan, missing: Vec<Download>) -> Result<()> {
    for dir in &plan.directories {
        tokio::fs::create_dir_all(dir).await?;
    }
    let installing = |done_bytes, total_bytes, current| Phase::Installing { done_bytes, total_bytes, current };
    download_with_progress(app, missing, 16, installing).await
}

fn account() -> Result<launch::Account> {
    #[cfg(debug_assertions)]
    {
        Ok(launch::dev_account())
    }
    #[cfg(not(debug_assertions))]
    {
        Err(Error::Message("Sign-in isn't built yet (milestone 4), so release builds can't start the game.".into()))
    }
}

/// Starts the game and waits until it exits. Ok when it closed normally.
async fn start_game(app: &AppHandle, launcher: &Launcher, plan: &GamePlan, options: launch::LaunchOptions) -> Result<()> {
    let account = account()?;
    launcher.set(app, Phase::Launching);
    for sub in ["java", "jna", "lwjgl", "netty"] {
        tokio::fs::create_dir_all(plan.natives_dir.join(sub)).await?;
    }
    tokio::fs::create_dir_all(launcher.paths.instance()).await?;

    let version = app.package_info().version.to_string();
    let command = launch::build(plan, &launcher.paths, &account, &options, &version);
    tracing::info!("starting {} {}", command.java.display(), launch::describe(&command.args));

    let mut process = tokio::process::Command::new(&command.java);
    process
        .args(&command.args)
        .current_dir(&command.game_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    process.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    let mut child = process.spawn().map_err(|e| Error::Message(format!("couldn't start Java: {e}")))?;
    launcher.playing.store(true, Ordering::SeqCst);
    let launched_at = SystemTime::now();

    // The game's output goes to logs/game.log. READY_LINE means it's up; FAILED_LINES mean
    // Fabric refused the mods and is showing its own error window.
    let log_path = launcher.paths.logs().join("game.log");
    let log = Arc::new(tokio::sync::Mutex::new(tokio::fs::File::create(&log_path).await?));
    let (signal_tx, mut signals) = tokio::sync::mpsc::channel::<GameSignal>(4);
    if let Some(stdout) = child.stdout.take() {
        tauri::async_runtime::spawn(pipe_output(stdout, log.clone(), signal_tx.clone()));
    }
    if let Some(stderr) = child.stderr.take() {
        tauri::async_runtime::spawn(pipe_output(stderr, log.clone(), signal_tx.clone()));
    }
    drop(signal_tx);

    let mut playing = false;
    let mut hidden = false;
    let mut refused: Option<String> = None;
    let status = loop {
        tokio::select! {
            status = child.wait() => break status?,
            Some(signal) = signals.recv() => match signal {
                GameSignal::Ready if !playing && refused.is_none() => {
                    playing = true;
                    hidden = mark_playing(app, launcher, &options);
                }
                GameSignal::Refused(line) => {
                    tracing::warn!("Fabric refused the mods: {line}");
                    refused.get_or_insert(line);
                }
                GameSignal::Ready => {}
            },
            // Some setups never print READY_LINE; after a while, assume the game is up.
            _ = tokio::time::sleep(Duration::from_secs(45)), if !playing && refused.is_none() => {
                playing = true;
                hidden = mark_playing(app, launcher, &options);
            }
        }
    };
    if hidden {
        show_window(app);
    }
    tracing::info!("the game exited with {status}");

    if status.success() {
        return Ok(());
    }
    let code = status.code().map_or_else(|| "unknown".to_string(), |c| c.to_string());
    let mut message = match &refused {
        Some(_) => "Fabric couldn't load the mods, so Minecraft didn't start. Repair files fixes damaged mods.".to_string(),
        None => format!("Minecraft closed with an error (exit code {code})."),
    };
    message.push_str(&format!(" The game log is in {}.", log_path.display()));
    if let Some(report) = new_crash_report(&command.game_dir, launched_at) {
        message.push_str(&format!(" Crash report: {}.", report.display()));
    }
    Err(Error::Message(message))
}

/// What the game's output says about how starting it went.
enum GameSignal {
    Ready,
    /// Fabric refused the mods (the line that said so).
    Refused(String),
}

/// Switches to "in game" and hides the launcher if the player wants that. Returns whether it hid.
fn mark_playing(app: &AppHandle, launcher: &Launcher, options: &launch::LaunchOptions) -> bool {
    launcher.set(app, Phase::Playing { started_at_ms: now_ms() });
    if options.hide_while_playing {
        if let Some(window) = app.get_webview_window("main") {
            return window.hide().is_ok();
        }
    }
    false
}

fn show_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

/// Copies the game's output into the log, line by line, and reports the lines that say how
/// starting went. Bytes that aren't UTF-8 are replaced instead of stopping the copy.
async fn pipe_output<R: AsyncRead + Unpin>(stream: R, log: Arc<tokio::sync::Mutex<tokio::fs::File>>, signals: tokio::sync::mpsc::Sender<GameSignal>) {
    let mut reader = BufReader::new(stream);
    let mut line = Vec::new();
    let (mut ready, mut refused) = (false, false);
    loop {
        line.clear();
        match reader.read_until(b'\n', &mut line).await {
            Ok(0) | Err(_) => break,
            Ok(_) => {
                // Fabric refuses mods before the game is up, so later lines need no reading.
                if !ready {
                    let text = String::from_utf8_lossy(&line);
                    if text.contains(READY_LINE) {
                        ready = true;
                        let _ = signals.try_send(GameSignal::Ready);
                    } else if !refused && FAILED_LINES.iter().any(|failed| text.contains(failed)) {
                        refused = true;
                        let _ = signals.try_send(GameSignal::Refused(text.trim().to_string()));
                    }
                }
                let _ = log.lock().await.write_all(&line).await;
            }
        }
    }
}

fn new_crash_report(game_dir: &Path, since: SystemTime) -> Option<PathBuf> {
    std::fs::read_dir(game_dir.join("crash-reports"))
        .ok()?
        .filter_map(|entry| entry.ok())
        .filter(|entry| entry.metadata().and_then(|m| m.modified()).is_ok_and(|modified| modified >= since))
        .map(|entry| entry.path())
        .max()
}
