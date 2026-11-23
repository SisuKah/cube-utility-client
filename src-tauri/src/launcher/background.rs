//! Ready in the background: while the launcher is open and the game is up to date, Minecraft
//! starts ahead of PLAY, hidden: no window, no sound, low priority, and only a few frames a
//! second once it's loaded. PLAY then just shows it. The Cube Utility Client mod does the
//! hiding inside the game (mod/.../background) when it sees `-Dcubeutilityclient.background=true`,
//! and the launcher tells it about PLAY on the game's standard input: `show`, or `join <address>`
//! for CubeCraft.
//!
//! A waiting game is only shown if it's exactly what PLAY would start now. A pack update, a mod
//! added or removed, other memory or another player stops it, and PLAY starts one the usual way;
//! the launcher starts a new one in the background when it can. Closing the launcher stops it.

use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use tauri::{AppHandle, Manager};
use tokio::io::AsyncWriteExt;
use tokio::sync::{oneshot, Notify};

use super::{lock, GameProcess, GameSignal, Launcher, Phase};
use crate::config;
use crate::game::launch::{Account, LaunchOptions, LaunchTarget};
use crate::game::options::Options;
use crate::settings::SettingsStore;

/// How often the launcher looks at whether a game should be waiting.
const CHECK_EVERY: Duration = Duration::from_secs(20);
/// After a game started in the background fails, PLAY starts games the usual way for this long.
const RETRY_AFTER: Duration = Duration::from_secs(30 * 60);
/// Free memory needed first: a loaded game at the title screen takes about 2.5 GB, and the rest
/// of the PC keeps some room.
const NEEDS_FREE_GB: f64 = 4.0;
/// Only a Cube Utility Client jar that can start hidden has this class.
const MOD_MARKER: &[u8] = b"dev/cubeutilityclient/background/LauncherLink";
/// The pack's mod id for it.
const MOD_ID: &str = "cubeutilityclient";

#[cfg(windows)]
pub(super) const BELOW_NORMAL_PRIORITY_CLASS: u32 = 0x0000_4000;
#[cfg(windows)]
const NORMAL_PRIORITY_CLASS: u32 = 0x0000_0020;

#[cfg(windows)]
#[link(name = "kernel32")]
extern "system" {
    fn SetPriorityClass(process: *mut std::ffi::c_void, priority_class: u32) -> i32;
}

#[cfg(windows)]
#[link(name = "user32")]
extern "system" {
    fn AllowSetForegroundWindow(process_id: u32) -> i32;
}

#[derive(Default)]
pub struct Background {
    waiting: tokio::sync::Mutex<Option<Waiting>>,
    failed_at: Mutex<Option<Instant>>,
    next_id: AtomicU64,
    wake: Notify,
    /// Why the last check didn't start a game, so each reason is logged once.
    last_reason: Mutex<String>,
    /// The installed Cube Utility Client jar (path and size), and whether it can start hidden.
    mod_check: Mutex<Option<(PathBuf, u64, bool)>>,
}

/// What a waiting game was started for. PLAY only shows it for the same player and memory.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Key {
    uuid: String,
    access_token: String,
    memory_gb: u32,
}

impl Key {
    fn new(account: &Account, memory_gb: u32) -> Self {
        Self { uuid: account.uuid.clone(), access_token: account.access_token.clone(), memory_gb }
    }
}

/// The game waiting for PLAY, as the rest of the launcher sees it.
struct Waiting {
    id: u64,
    key: Key,
    /// PLAY sends where to hand the game over to.
    claim: oneshot::Sender<oneshot::Sender<Handover>>,
    /// Stops the game; the answer comes once it's gone.
    stop: oneshot::Sender<oneshot::Sender<()>>,
}

/// A waiting game, handed over to PLAY.
pub(super) struct Handover {
    game: GameProcess,
    /// It has finished loading.
    up: bool,
}

/// Looks every little while (and whenever something changes) at whether a game should be
/// waiting, and starts or stops one.
pub fn begin(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        loop {
            {
                let background = app.state::<Background>();
                tokio::select! {
                    _ = tokio::time::sleep(CHECK_EVERY) => {}
                    _ = background.wake.notified() => {}
                }
            }
            check(&app).await;
        }
    });
}

/// Something changed (the game became ready, a setting): look again now.
pub fn nudge(app: &AppHandle) {
    app.state::<Background>().wake.notify_one();
}

async fn check(app: &AppHandle) {
    let settings = app.state::<SettingsStore>().get();
    let background = app.state::<Background>();
    let stale = background.waiting.lock().await.as_ref().map(|w| !settings.background_start || w.key.memory_gb != settings.memory_gb);
    match stale {
        Some(true) => stop(app, "the settings changed").await,
        Some(false) => return,
        None => {}
    }
    let reason = start(app).await.err().unwrap_or_default();
    let mut last = lock(&background.last_reason);
    if *last != reason {
        if !reason.is_empty() {
            tracing::info!("not starting Minecraft in the background: {reason}");
        }
        *last = reason;
    }
}

async fn start(app: &AppHandle) -> Result<(), String> {
    let settings = app.state::<SettingsStore>().get();
    if !settings.background_start {
        return Err("it's switched off in Settings".into());
    }
    let launcher = app.state::<Launcher>();
    let background = app.state::<Background>();
    if background.waiting.lock().await.is_some() {
        return Ok(());
    }
    if launcher.requested.load(Ordering::SeqCst) || launcher.is_playing() || !matches!(launcher.current(), Phase::Ready) {
        return Err("the launcher isn't on Ready to play".into());
    }
    if lock(&background.failed_at).is_some_and(|at| at.elapsed() < RETRY_AFTER) {
        return Err("it didn't work a moment ago".into());
    }
    if starts_fullscreen(&launcher) {
        return Err("Minecraft is set to full screen".into());
    }
    if !background.mod_can_start_hidden(&launcher) {
        return Err("the installed Cube Utility Client mod can't start hidden".into());
    }
    match available_memory_gb() {
        Some(free) if free < NEEDS_FREE_GB => return Err(format!("only {free:.1} GB of memory is free")),
        None => return Err("couldn't read the free memory".into()),
        Some(_) => {}
    }
    // Nothing else may change the game folder while the game starts. A sync or PLAY in the
    // middle of something: the next check tries again.
    let Ok(_work) = launcher.work.try_lock() else { return Err("the launcher is busy".into()) };
    if launcher.requested.load(Ordering::SeqCst) || launcher.is_playing() || !matches!(launcher.current(), Phase::Ready) {
        return Err("the launcher isn't on Ready to play".into());
    }
    let account = super::account(app).await.map_err(|error| format!("no player to start it for ({error})"))?;
    let plan = launcher.plan(false).await.map_err(|error| error.to_string())?;
    if !plan.missing().is_empty() {
        return Err("game files are missing".into());
    }
    let options = LaunchOptions { target: LaunchTarget::Title, memory_gb: settings.memory_gb, hide_while_playing: settings.hide_while_playing, background: true };
    let game = super::spawn_game(app, &launcher, &plan, &account, &options).await.map_err(|error| error.to_string())?;

    let id = background.next_id.fetch_add(1, Ordering::SeqCst);
    let (claim, claims) = oneshot::channel();
    let (stop, stops) = oneshot::channel();
    *background.waiting.lock().await = Some(Waiting { id, key: Key::new(&account, settings.memory_gb), claim, stop });
    tauri::async_runtime::spawn(wait_for_play(app.clone(), id, game, claims, stops));
    Ok(())
}

/// Holds the hidden game until PLAY takes it, or until it has to go.
async fn wait_for_play(
    app: AppHandle,
    id: u64,
    mut game: GameProcess,
    mut claims: oneshot::Receiver<oneshot::Sender<Handover>>,
    mut stops: oneshot::Receiver<oneshot::Sender<()>>,
) {
    enum Event {
        Exited(std::io::Result<std::process::ExitStatus>),
        Signal(GameSignal),
        Claimed(Option<oneshot::Sender<Handover>>),
        Stopped(Option<oneshot::Sender<()>>),
    }
    let mut up = false;
    loop {
        let event = tokio::select! {
            status = game.child.wait() => Event::Exited(status),
            Some(signal) = game.signals.recv() => Event::Signal(signal),
            claim = &mut claims => Event::Claimed(claim.ok()),
            stop = &mut stops => Event::Stopped(stop.ok()),
        };
        match event {
            Event::Signal(GameSignal::Ready) => {
                if !up {
                    up = true;
                    tracing::info!("Minecraft has loaded in the background and waits for PLAY");
                }
            }
            Event::Signal(GameSignal::Refused(line)) => {
                // Fabric shows its own error window over this; PLAY shows the problem properly.
                tracing::warn!("Fabric refused the mods in the background ({line}); PLAY starts the game the usual way");
                failed(&app);
                let _ = game.child.kill().await;
                forget(&app, id).await;
                return;
            }
            Event::Exited(status) => {
                let how = status.map_or_else(|error| error.to_string(), |status| status.to_string());
                tracing::warn!("the game waiting in the background closed by itself ({how}); PLAY starts the game the usual way");
                failed(&app);
                forget(&app, id).await;
                return;
            }
            Event::Claimed(Some(reply)) => {
                if let Err(unwanted) = reply.send(Handover { game, up }) {
                    // PLAY stopped waiting for it, so nobody will ever see this game.
                    let mut game = unwanted.game;
                    let _ = game.child.kill().await;
                }
                return;
            }
            Event::Claimed(None) | Event::Stopped(None) => {
                let _ = game.child.kill().await;
                forget(&app, id).await;
                return;
            }
            Event::Stopped(Some(done)) => {
                let _ = game.child.kill().await;
                drop(game);
                let _ = done.send(());
                return;
            }
        }
    }
}

/// The waiting game is gone: clears it, unless another one took its place.
async fn forget(app: &AppHandle, id: u64) {
    let background = app.state::<Background>();
    let mut waiting = background.waiting.lock().await;
    if waiting.as_ref().is_some_and(|w| w.id == id) {
        *waiting = None;
    }
}

fn failed(app: &AppHandle) {
    *lock(&app.state::<Background>().failed_at) = Some(Instant::now());
}

/// PLAY: the waiting game, if it was started for this player and memory. Anything else waiting
/// is stopped, so PLAY starts a game the usual way.
pub(super) async fn claim(app: &AppHandle, account: &Account, memory_gb: u32) -> Option<Handover> {
    let waiting = app.state::<Background>().waiting.lock().await.take()?;
    if waiting.key != Key::new(account, memory_gb) {
        tracing::info!("the game waiting in the background was for another player or memory setting");
        stop_waiting(waiting).await;
        return None;
    }
    let Waiting { claim, stop, .. } = waiting;
    let (reply, handover) = oneshot::channel();
    claim.send(reply).ok()?;
    let handover = tokio::time::timeout(Duration::from_secs(10), handover).await.ok()?.ok();
    drop(stop);
    handover
}

/// Shows a game that waited in the background, and follows it like any other (Ok when it
/// closed normally).
pub(super) async fn play(app: &AppHandle, launcher: &Launcher, handover: Handover, options: &LaunchOptions) -> crate::error::Result<()> {
    let Handover { mut game, up } = handover;
    launcher.set(app, Phase::Launching);
    launcher.playing.store(true, Ordering::SeqCst);
    bring_forward(&game);
    let command = match options.target {
        LaunchTarget::Cubecraft => format!("join {}\n", config::QUICK_PLAY_SERVER),
        LaunchTarget::Title => "show\n".to_string(),
    };
    tracing::info!("PLAY: showing the game that started in the background{}", if up { "" } else { " (still loading)" });
    if let Some(stdin) = game.stdin.as_mut() {
        let sent = async {
            stdin.write_all(command.as_bytes()).await?;
            stdin.flush().await
        };
        if let Err(error) = sent.await {
            tracing::warn!("couldn't tell the game about PLAY ({error})");
        }
    }
    super::watch_game(app, launcher, game, options, up).await
}

/// Stops the game waiting in the background, if there is one, and waits until it's gone.
pub async fn stop(app: &AppHandle, why: &str) {
    let Some(waiting) = app.state::<Background>().waiting.lock().await.take() else { return };
    tracing::info!("stopping the game waiting in the background: {why}");
    stop_waiting(waiting).await;
}

async fn stop_waiting(waiting: Waiting) {
    let (done, gone) = oneshot::channel();
    if waiting.stop.send(done).is_ok() {
        let _ = tokio::time::timeout(Duration::from_secs(10), gone).await;
    }
}

/// The launcher is closing: a game nobody has played yet goes with it. (The game also quits by
/// itself when the launcher's end of its input closes.)
pub fn stop_on_exit(app: &AppHandle) {
    tauri::async_runtime::block_on(stop(app, "the launcher is closing"));
}

/// The game becomes an ordinary one: normal priority, and allowed to come to the front (the
/// launcher has the foreground, since the player just pressed PLAY in it).
#[cfg(windows)]
fn bring_forward(game: &GameProcess) {
    if let Some(pid) = game.child.id() {
        // SAFETY: plain Win32 calls with a process id and the child's own open handle.
        unsafe { AllowSetForegroundWindow(pid) };
    }
    if let Some(handle) = game.child.raw_handle() {
        // SAFETY: the handle belongs to the running child and stays open while `game` lives.
        unsafe { SetPriorityClass(handle, NORMAL_PRIORITY_CLASS) };
    }
}

#[cfg(not(windows))]
fn bring_forward(_game: &GameProcess) {}

/// A hidden game can't take over the screen, so players who play in full screen get the usual
/// start.
fn starts_fullscreen(launcher: &Launcher) -> bool {
    std::fs::read_to_string(launcher.paths.instance().join("options.txt")).is_ok_and(|text| Options::parse(&text).get("fullscreen") == Some("true"))
}

fn available_memory_gb() -> Option<f64> {
    let mut system = sysinfo::System::new().ok()?;
    system.refresh_memory();
    Some(system.available_memory() as f64 / 1024_f64.powi(3))
}

impl Background {
    /// Without the mod's part, a game started "in the background" would open its window right
    /// away, so only a jar that has it qualifies.
    fn mod_can_start_hidden(&self, launcher: &Launcher) -> bool {
        let Some(manifest) = launcher.installed_manifest() else { return false };
        let Some(file) = manifest.files.iter().find(|file| file.id == MOD_ID) else { return false };
        let path = launcher.paths.instance().join(&file.path);
        let Ok(size) = std::fs::metadata(&path).map(|meta| meta.len()) else { return false };
        let mut checked = lock(&self.mod_check);
        if let Some((known, known_size, answer)) = checked.as_ref() {
            if *known == path && *known_size == size {
                return *answer;
            }
        }
        let answer = std::fs::read(&path).is_ok_and(|bytes| contains(&bytes, MOD_MARKER));
        *checked = Some((path, size, answer));
        answer
    }
}

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    !needle.is_empty() && haystack.windows(needle.len()).any(|window| window == needle)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn account(uuid: &str, token: &str) -> Account {
        Account { name: "Steve".into(), uuid: uuid.into(), access_token: token.into(), xuid: "0".into() }
    }

    #[test]
    fn only_the_same_player_and_memory_get_the_waiting_game() {
        let key = Key::new(&account("a", "token"), 4);
        assert_eq!(key, Key::new(&account("a", "token"), 4));
        assert_ne!(key, Key::new(&account("b", "token"), 4), "another player");
        assert_ne!(key, Key::new(&account("a", "refreshed"), 4), "a newer sign-in");
        assert_ne!(key, Key::new(&account("a", "token"), 6), "other memory");
    }

    #[test]
    fn finds_the_mod_marker_in_a_jar() {
        let mut jar = b"PK\x03\x04 stuff ".to_vec();
        assert!(!contains(&jar, MOD_MARKER));
        jar.extend_from_slice(b"dev/cubeutilityclient/background/LauncherLink.class more");
        assert!(contains(&jar, MOD_MARKER));
        assert!(!contains(b"short", MOD_MARKER));
    }
}
