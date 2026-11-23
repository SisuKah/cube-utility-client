//! Microsoft sign-in (spec 3.4), for as many accounts as the player likes. Microsoft's page opens
//! in the player's own browser; then the launcher goes Xbox Live → XSTS → Minecraft, checks the
//! player owns Java Edition, and reads their profile and skin. Each account's refresh token is
//! kept in the Windows Credential Manager; Minecraft logins live in memory only.
//!
//! One account plays: PLAY starts the game with it. The game switches to another without closing
//! through its Accounts screen, which asks the launcher for a login over the bridge (bridge.rs).
//! Until Mojang approves the Azure app, Minecraft answers 403, shown as "waiting for approval".

mod encoding;
mod minecraft;
mod oauth;
mod store;
mod xbox;

use std::collections::HashMap;
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, Instant};

use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_opener::OpenerExt;

use crate::config;
use crate::game::launch::Account;
use crate::net::Net;
use crate::paths::Paths;
use store::{SavedAccount, Slot};

const STATE_EVENT: &str = "account://state";
/// How long the player has to finish Microsoft's page.
const SIGN_IN_TIMEOUT: Duration = Duration::from_secs(300);
/// How long a switch waits for a sign-in or refresh that's already running.
const BUSY_WAIT: Duration = Duration::from_secs(15);

#[derive(Debug, Clone, PartialEq, thiserror::Error)]
pub enum AuthError {
    #[error("Sign-in was cancelled.")]
    Cancelled,
    #[error("The sign-in page wasn't finished within 5 minutes.")]
    TimedOut,
    #[error("Your sign-in expired. Sign in again.")]
    Expired,
    #[error("Mojang hasn't approved this launcher's sign-in yet, so Minecraft can't start with your account. It works as soon as they approve.")]
    NotApproved,
    #[error("A sign-in is open in your browser. Finish it or cancel it first.")]
    Busy,
    /// Something about the account, explained for the player.
    #[error("{0}")]
    Problem(String),
    #[error("Microsoft couldn't sign you in: {0}")]
    Microsoft(String),
    #[error("Can't reach the sign-in servers. Check your internet connection.")]
    Offline,
    #[error("Sign-in failed: {0}")]
    Failed(String),
}

impl From<crate::error::Error> for AuthError {
    fn from(error: crate::error::Error) -> Self {
        match error {
            crate::error::Error::Http(_) | crate::error::Error::Offline => AuthError::Offline,
            other => AuthError::Failed(other.to_string()),
        }
    }
}

impl From<std::io::Error> for AuthError {
    fn from(error: std::io::Error) -> Self {
        AuthError::Failed(error.to_string())
    }
}

/// How the account that plays is doing, for the UI.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "state", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum AccountState {
    SignedOut { message: Option<String> },
    /// Checking the saved sign-in at startup.
    Checking,
    /// Microsoft's page is open in the browser.
    SigningIn,
    SignedIn { name: String, uuid: String, skin: Option<String>, slim: bool },
    /// Microsoft and Xbox accepted the account, but Minecraft doesn't accept this launcher yet.
    AwaitingApproval { gamertag: Option<String> },
    /// Something about the account that the player has to fix.
    Problem { message: String },
    /// The servers can't be reached; the last account can still play singleplayer.
    Offline { name: String, uuid: String, skin: Option<String> },
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountInfo {
    #[serde(flatten)]
    pub state: AccountState,
    /// Release builds only start the game for a signed-in player.
    pub sign_in_required: bool,
    /// Every account signed in on this PC, in the order they were added.
    pub accounts: Vec<AccountSummary>,
    /// Why the last switch or added account didn't work, while the account that plays is fine.
    pub notice: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountSummary {
    pub uuid: String,
    pub name: String,
    /// The skin, as a data: URL (the UI loads nothing from other sites).
    pub skin: Option<String>,
    /// PLAY starts with this one.
    pub active: bool,
}

/// What the game's Accounts screen lists (bridge.rs).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GameAccounts {
    /// The account the launcher starts the game with; the game may play as another.
    pub active: Option<String>,
    pub accounts: Vec<GameAccount>,
    /// A sign-in is open in the browser.
    pub signing_in: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GameAccount {
    pub uuid: String,
    pub name: String,
    /// The skin PNG, in base64.
    pub skin: Option<String>,
    pub slim: bool,
}

/// What Minecraft said about a Microsoft account.
enum Outcome {
    /// Signed in: the account is on the list and its Minecraft login is live.
    Ready(SavedAccount),
    /// Microsoft and Xbox said yes; Minecraft doesn't accept this launcher yet.
    AwaitingApproval { gamertag: Option<String> },
}

/// A live Minecraft login, never written anywhere.
struct Session {
    name: String,
    uuid: String,
    access_token: String,
    xuid: String,
    expires_at: Instant,
}

pub struct Auth {
    paths: Paths,
    net: Net,
    /// How the account that plays is doing.
    state: Mutex<AccountState>,
    notice: Mutex<Option<String>>,
    accounts: Mutex<store::Accounts>,
    /// Live Minecraft logins, by UUID.
    sessions: Mutex<HashMap<String, Session>>,
    /// One sign-in or refresh at a time.
    flow: tokio::sync::Mutex<()>,
    cancel: Mutex<Option<tokio::sync::oneshot::Sender<()>>>,
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

impl Auth {
    pub fn new(paths: Paths, net: Net) -> Self {
        let accounts = store::load_accounts(&paths);
        Self {
            paths,
            net,
            state: Mutex::new(AccountState::Checking),
            notice: Mutex::new(None),
            accounts: Mutex::new(accounts),
            sessions: Mutex::new(HashMap::new()),
            flow: tokio::sync::Mutex::new(()),
            cancel: Mutex::new(None),
        }
    }

    pub fn info(&self) -> AccountInfo {
        let accounts = lock(&self.accounts).clone();
        let summaries = accounts
            .accounts
            .iter()
            .map(|a| AccountSummary {
                uuid: a.uuid.clone(),
                name: a.name.clone(),
                skin: self.skin_url(&a.uuid),
                active: accounts.active.as_deref() == Some(a.uuid.as_str()),
            })
            .collect();
        AccountInfo {
            state: lock(&self.state).clone(),
            sign_in_required: !cfg!(debug_assertions),
            accounts: summaries,
            notice: lock(&self.notice).clone(),
        }
    }

    /// The accounts for the game's Accounts screen, with their skins.
    pub fn game_accounts(&self) -> GameAccounts {
        let accounts = lock(&self.accounts).clone();
        GameAccounts {
            active: accounts.active.clone(),
            accounts: accounts
                .accounts
                .iter()
                .map(|a| GameAccount {
                    uuid: a.uuid.clone(),
                    name: a.name.clone(),
                    skin: store::cached_skin(&self.paths, &a.uuid).map(|png| encoding::base64(&png)),
                    slim: a.slim,
                })
                .collect(),
            signing_in: lock(&self.cancel).is_some(),
        }
    }

    fn emit(&self, app: &AppHandle) {
        let _ = app.emit(STATE_EVENT, self.info());
    }

    fn set(&self, app: &AppHandle, state: AccountState) {
        *lock(&self.state) = state;
        self.emit(app);
    }

    fn note(&self, notice: Option<String>) {
        *lock(&self.notice) = notice;
    }

    fn signed_in(&self, account: &SavedAccount) -> AccountState {
        AccountState::SignedIn { name: account.name.clone(), uuid: account.uuid.clone(), skin: self.skin_url(&account.uuid), slim: account.slim }
    }

    /// PLAY starts with this account from now on.
    fn make_active(&self, uuid: &str) {
        let mut accounts = lock(&self.accounts);
        if accounts.active.as_deref() != Some(uuid) {
            accounts.active = Some(uuid.to_string());
            store::save_accounts(&self.paths, &accounts);
        }
    }

    /// Everything kept about the account goes: its token, its place on the list and its skin.
    fn forget(&self, uuid: &str) {
        store::delete_refresh_token(Slot::Account(uuid));
        store::forget_skin(&self.paths, uuid);
        lock(&self.sessions).remove(uuid);
        let mut accounts = lock(&self.accounts);
        if accounts.remove(uuid) {
            store::save_accounts(&self.paths, &accounts);
        }
    }

    /// Signs in again with a saved refresh token. Microsoft hands out a new one each time, which is
    /// saved before anything else can go wrong.
    async fn refresh(&self, slot: Slot<'_>) -> Result<Outcome, AuthError> {
        let token = store::load_refresh_token(slot).ok_or(AuthError::Expired)?;
        let tokens = oauth::refresh(&self.net, config::AZURE_CLIENT_ID, &token).await?;
        let refresh_token = match tokens.refresh_token {
            Some(newer) => {
                store::save_refresh_token(slot, &newer).map_err(AuthError::Failed)?;
                newer
            }
            None => token,
        };
        self.finish(&tokens.access_token, &refresh_token, Some(slot)).await
    }

    /// From a Microsoft access token to a Minecraft login, the profile and the skin. When it all
    /// works, the refresh token is filed under the account, which goes on the list (or is updated
    /// there). `from` is where the refresh token was kept until now; a new sign-in has none.
    async fn finish(&self, microsoft_token: &str, refresh_token: &str, from: Option<Slot<'_>>) -> Result<Outcome, AuthError> {
        let user = xbox::user_token(&self.net, microsoft_token).await?;
        let for_minecraft = xbox::xsts(&self.net, &user.token, xbox::MINECRAFT).await?;
        // The gamertag and Xbox user ID; nice to have, so a failure here doesn't matter.
        let identity = xbox::xsts(&self.net, &user.token, xbox::XBOX).await.ok();
        let gamertag = identity.as_ref().and_then(|i| i.gamertag.clone());
        let login = match minecraft::login(&self.net, &for_minecraft.uhs, &for_minecraft.token).await {
            Ok(login) => login,
            Err(AuthError::NotApproved) => {
                tracing::info!("Microsoft and Xbox sign-in worked; Minecraft answered 403 (the Azure app isn't approved yet)");
                // Kept for the day Mojang says yes. There's no UUID to file it under yet.
                if from.is_none() {
                    store::save_refresh_token(Slot::Pending, refresh_token).map_err(AuthError::Failed)?;
                }
                return Ok(Outcome::AwaitingApproval { gamertag });
            }
            Err(error) => return Err(error),
        };
        if !minecraft::owns_java(&self.net, &login.access_token).await? {
            return Err(AuthError::Problem("This Microsoft account doesn't own Minecraft: Java Edition.".into()));
        }
        let Some(profile) = minecraft::profile(&self.net, &login.access_token).await? else {
            return Err(AuthError::Problem(
                "Your account owns Minecraft but has no Java Edition profile yet. Open the official Minecraft Launcher once to choose a name, then sign in again.".into(),
            ));
        };

        store::save_refresh_token(Slot::Account(&profile.id), refresh_token).map_err(AuthError::Failed)?;
        let mut misfiled = None;
        match from {
            Some(Slot::Pending) => store::delete_refresh_token(Slot::Pending),
            // Moved over from the one-account launcher under the wrong account.
            Some(Slot::Account(other)) if other != profile.id => {
                store::delete_refresh_token(Slot::Account(other));
                misfiled = Some(other.to_string());
            }
            _ => {}
        }
        self.download_skin(&profile).await;
        let saved = SavedAccount { uuid: profile.id.clone(), name: profile.name.clone(), slim: profile.slim };
        {
            let mut accounts = lock(&self.accounts);
            if let Some(other) = &misfiled {
                accounts.remove(other);
            }
            accounts.upsert(saved.clone());
            store::save_accounts(&self.paths, &accounts);
        }
        let lifetime = Duration::from_secs(login.expires_in.saturating_sub(300).max(60));
        lock(&self.sessions).insert(
            profile.id.clone(),
            Session {
                name: profile.name.clone(),
                uuid: profile.id.clone(),
                access_token: login.access_token,
                xuid: identity.and_then(|i| i.xuid).unwrap_or_else(|| "0".to_string()),
                expires_at: Instant::now() + lifetime,
            },
        );
        tracing::info!("signed in as {}", profile.name);
        Ok(Outcome::Ready(saved))
    }

    /// Keeps the skin for the UI and the game's Accounts screen, and for offline starts.
    async fn download_skin(&self, profile: &minecraft::Profile) {
        let Some(url) = &profile.skin_url else { return };
        match self.net.get_bytes(url, None).await {
            Ok(png) if png.starts_with(b"\x89PNG") => store::save_skin(&self.paths, &profile.id, &png),
            Ok(_) => tracing::warn!("the skin isn't a PNG"),
            Err(error) => tracing::warn!("couldn't download the skin: {error}"),
        }
    }

    fn skin_url(&self, uuid: &str) -> Option<String> {
        store::cached_skin(&self.paths, uuid).map(|png| format!("data:image/png;base64,{}", encoding::base64(&png)))
    }

    /// What to show after signing the account that plays in again failed.
    fn state_after(&self, error: &AuthError, account: Option<&SavedAccount>) -> AccountState {
        match (error, account) {
            (AuthError::Expired, Some(account)) => {
                self.forget(&account.uuid);
                AccountState::SignedOut { message: Some(format!("{}'s sign-in expired. Sign in again.", account.name)) }
            }
            (AuthError::Expired, None) => {
                store::delete_refresh_token(Slot::Pending);
                AccountState::SignedOut { message: Some(error.to_string()) }
            }
            (AuthError::Offline, Some(account)) => {
                AccountState::Offline { name: account.name.clone(), uuid: account.uuid.clone(), skin: self.skin_url(&account.uuid) }
            }
            (other, _) => AccountState::Problem { message: other.to_string() },
        }
    }

    /// Signs the account that plays in again (or the pending sign-in, when no account plays yet)
    /// and says how that went. Call with `flow` held.
    async fn refresh_active(&self) -> AccountState {
        let active = lock(&self.accounts).active().cloned();
        let slot = active.as_ref().map_or(Slot::Pending, |a| Slot::Account(&a.uuid));
        if store::load_refresh_token(slot).is_none() {
            // Listed, but its token is gone (removed from Windows' Credential Manager, say).
            if let Some(account) = &active {
                self.forget(&account.uuid);
                return AccountState::SignedOut { message: Some(format!("Sign in again to play as {}.", account.name)) };
            }
            return AccountState::SignedOut { message: None };
        }
        match self.refresh(slot).await {
            Ok(Outcome::Ready(account)) => {
                self.make_active(&account.uuid);
                self.signed_in(&account)
            }
            Ok(Outcome::AwaitingApproval { gamertag }) => AccountState::AwaitingApproval { gamertag },
            Err(error) => {
                tracing::warn!("couldn't restore the sign-in: {error}");
                self.state_after(&error, active.as_ref())
            }
        }
    }

    async fn sign_in(&self, app: &AppHandle, cancelled: tokio::sync::oneshot::Receiver<()>) -> Result<Outcome, AuthError> {
        let pkce = oauth::Pkce::new()?;
        let state = oauth::random_state()?;
        let loopback = oauth::Loopback::bind().await?;
        let redirect_uri = loopback.redirect_uri();
        let url = oauth::authorize_url(config::AZURE_CLIENT_ID, &redirect_uri, &state, &pkce.challenge);
        app.opener().open_url(url, None::<&str>).map_err(|e| AuthError::Failed(format!("couldn't open your browser: {e}")))?;
        let code = tokio::select! {
            code = loopback.wait_for_code(&state) => code?,
            _ = cancelled => return Err(AuthError::Cancelled),
            _ = tokio::time::sleep(SIGN_IN_TIMEOUT) => return Err(AuthError::TimedOut),
        };
        let tokens = oauth::redeem_code(&self.net, config::AZURE_CLIENT_ID, &code, &redirect_uri, &pkce.verifier).await?;
        let refresh_token = tokens.refresh_token.ok_or_else(|| AuthError::Failed("Microsoft sent no refresh token".into()))?;
        self.finish(&tokens.access_token, &refresh_token, None).await
    }

    /// Waits for a refresh that's running, but not for a sign-in open in the browser: that can
    /// take minutes.
    async fn wait_for_flow(&self) -> Result<tokio::sync::MutexGuard<'_, ()>, AuthError> {
        if lock(&self.cancel).is_some() {
            return Err(AuthError::Busy);
        }
        tokio::time::timeout(BUSY_WAIT, self.flow.lock()).await.map_err(|_| AuthError::Busy)
    }

    /// Opens Microsoft's sign-in page in the browser. The account that signs in is added (or
    /// signed in again, if it was there already) and plays from now on.
    pub async fn add_account(&self, app: &AppHandle) -> Result<Account, AuthError> {
        let _flow = self.wait_for_flow().await?;
        let before = lock(&self.state).clone();
        self.note(None);
        self.set(app, AccountState::SigningIn);
        let (cancel, cancelled) = tokio::sync::oneshot::channel();
        *lock(&self.cancel) = Some(cancel);
        let result = self.sign_in(app, cancelled).await;
        *lock(&self.cancel) = None;
        match result {
            Ok(Outcome::Ready(account)) => {
                self.make_active(&account.uuid);
                self.set(app, self.signed_in(&account));
                self.live_account(&account.uuid).ok_or_else(|| AuthError::Failed("the new login went missing".into()))
            }
            Ok(Outcome::AwaitingApproval { gamertag }) => {
                self.set(app, AccountState::AwaitingApproval { gamertag });
                Err(AuthError::NotApproved)
            }
            Err(error) => {
                let playing = matches!(before, AccountState::SignedIn { .. } | AccountState::Offline { .. } | AccountState::AwaitingApproval { .. });
                let state = match &error {
                    // Back to how it was, with a note when nobody was signed in.
                    AuthError::Cancelled | AuthError::TimedOut => match before {
                        AccountState::SignedOut { .. } | AccountState::Checking | AccountState::SigningIn => AccountState::SignedOut { message: Some(error.to_string()) },
                        other => other,
                    },
                    // The account that plays is fine: it keeps playing, and the reason shows.
                    _ if playing => {
                        tracing::warn!("couldn't add the account: {error}");
                        self.note(Some(error.to_string()));
                        before
                    }
                    _ => {
                        tracing::warn!("sign-in failed: {error}");
                        self.state_after(&error, None)
                    }
                };
                self.set(app, state);
                Err(error)
            }
        }
    }

    /// The account plays from now on: PLAY starts with it, and the game (when it asked) switches
    /// to it. It's signed in again first when its Minecraft login isn't live. If that fails, the
    /// account that played keeps playing and the reason shows as a notice.
    pub async fn use_account(&self, app: &AppHandle, uuid: &str) -> Result<Account, AuthError> {
        let _flow = self.wait_for_flow().await?;
        let account = lock(&self.accounts).get(uuid).cloned().ok_or_else(|| AuthError::Problem("That account isn't signed in on this PC any more.".into()))?;
        let login = match self.live_account(uuid) {
            Some(login) => login,
            None => match self.refresh(Slot::Account(uuid)).await {
                Ok(Outcome::Ready(_)) => self.live_account(uuid).ok_or_else(|| AuthError::Failed("the new login went missing".into()))?,
                Ok(Outcome::AwaitingApproval { .. }) => return Err(self.refuse(app, &account, AuthError::NotApproved)),
                Err(error) => return Err(self.refuse(app, &account, error)),
            },
        };
        self.make_active(uuid);
        let account = lock(&self.accounts).get(uuid).cloned().unwrap_or(account);
        self.note(None);
        self.set(app, self.signed_in(&account));
        tracing::info!("{} plays now", account.name);
        Ok(login)
    }

    /// A switch that didn't work: says why, and forgets an account whose sign-in expired.
    fn refuse(&self, app: &AppHandle, account: &SavedAccount, error: AuthError) -> AuthError {
        tracing::warn!("couldn't switch to {}: {error}", account.name);
        let message = match error {
            AuthError::Expired => {
                self.forget(&account.uuid);
                format!("{}'s sign-in expired. Add the account again to play as them.", account.name)
            }
            other => format!("Couldn't switch to {}. {other}", account.name),
        };
        self.note(Some(message.clone()));
        self.emit(app);
        AuthError::Problem(message)
    }

    /// Signs the account out of this PC: its token, name and skin go. When it's the one that
    /// plays, the next account on the list plays instead.
    pub async fn remove(&self, app: &AppHandle, uuid: &str) -> Result<(), AuthError> {
        let flow = self.wait_for_flow().await?;
        let (account, was_active) = {
            let accounts = lock(&self.accounts);
            (accounts.get(uuid).cloned(), accounts.active.as_deref() == Some(uuid))
        };
        let Some(account) = account else { return Ok(()) };
        self.forget(uuid);
        self.note(None);
        tracing::info!("signed out {}", account.name);
        if !was_active {
            self.emit(app);
            return Ok(());
        }
        let next = lock(&self.accounts).accounts.first().cloned();
        match next {
            None => self.set(app, AccountState::SignedOut { message: None }),
            Some(next) => {
                self.make_active(&next.uuid);
                if self.live_account(&next.uuid).is_some() {
                    self.set(app, self.signed_in(&next));
                } else {
                    self.set(app, AccountState::Checking);
                    drop(flow);
                    restore(app.clone());
                }
            }
        }
        Ok(())
    }

    /// The account to start the game with. Refreshes an expired login first. Offline, the last
    /// account still starts (singleplayer only). None when nobody has signed in.
    pub async fn launch_account(&self, app: &AppHandle) -> Result<Option<Account>, AuthError> {
        if let Some(account) = self.active_live_account() {
            return Ok(Some(account));
        }
        let _flow = self.flow.lock().await;
        if let Some(account) = self.active_live_account() {
            return Ok(Some(account));
        }
        let state = self.refresh_active().await;
        self.set(app, state.clone());
        match state {
            AccountState::SignedIn { .. } => Ok(self.active_live_account()),
            AccountState::AwaitingApproval { .. } => Err(AuthError::NotApproved),
            AccountState::Offline { name, uuid, .. } => Ok(Some(Account { name, uuid, access_token: "0".to_string(), xuid: "0".to_string() })),
            AccountState::Problem { message } | AccountState::SignedOut { message: Some(message) } => Err(AuthError::Problem(message)),
            _ => Ok(None),
        }
    }

    fn active_live_account(&self) -> Option<Account> {
        let active = lock(&self.accounts).active.clone()?;
        self.live_account(&active)
    }

    fn live_account(&self, uuid: &str) -> Option<Account> {
        let sessions = lock(&self.sessions);
        let session = sessions.get(uuid).filter(|s| s.expires_at > Instant::now())?;
        Some(Account {
            name: session.name.clone(),
            uuid: session.uuid.clone(),
            access_token: session.access_token.clone(),
            xuid: session.xuid.clone(),
        })
    }

    pub fn cancel_sign_in(&self) {
        if let Some(cancel) = lock(&self.cancel).take() {
            let _ = cancel.send(());
        }
    }
}

/// Signs the account that plays in again, in the background.
fn restore(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        let auth = app.state::<Auth>();
        let _flow = auth.flow.lock().await;
        let state = auth.refresh_active().await;
        auth.set(&app, state);
    });
}

/// At startup: the account that plays signs in again silently. A token kept by the one-account
/// launcher moves to that account's own entry first.
pub fn start(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        let auth = app.state::<Auth>();
        let _flow = auth.flow.lock().await;
        let accounts = lock(&auth.accounts).clone();
        store::adopt_pending_token(&accounts);
        let state = auth.refresh_active().await;
        auth.set(&app, state);
    });
}

#[tauri::command]
pub fn account_state(auth: tauri::State<'_, Auth>) -> AccountInfo {
    auth.info()
}

/// Opens Microsoft's sign-in page in the browser and adds the account when it comes back.
#[tauri::command]
pub fn sign_in(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        let auth = app.state::<Auth>();
        if let Err(error) = auth.add_account(&app).await {
            tracing::info!("no account added: {error}");
        }
    });
}

#[tauri::command]
pub fn cancel_sign_in(auth: tauri::State<'_, Auth>) {
    auth.cancel_sign_in();
}

/// PLAY starts with this account from now on.
#[tauri::command]
pub async fn switch_account(app: AppHandle, uuid: String) -> Result<(), String> {
    let auth = app.state::<Auth>();
    auth.use_account(&app, &uuid).await.map(|_| ()).map_err(|e| e.to_string())
}

/// Forgets an account: its token in the Credential Manager, its name and its skin.
#[tauri::command]
pub async fn remove_account(app: AppHandle, uuid: String) -> Result<(), String> {
    let auth = app.state::<Auth>();
    auth.remove(&app, &uuid).await.map_err(|e| e.to_string())
}

/// Signs out the account that plays (Settings). The next one on the list plays instead.
#[tauri::command]
pub async fn sign_out(app: AppHandle) -> Result<(), String> {
    let auth = app.state::<Auth>();
    let active = lock(&auth.accounts).active.clone();
    match active {
        Some(uuid) => auth.remove(&app, &uuid).await.map_err(|e| e.to_string()),
        None => {
            // Only a sign-in that's waiting for Mojang.
            store::delete_refresh_token(Slot::Pending);
            auth.note(None);
            auth.set(&app, AccountState::SignedOut { message: None });
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn account_states_serialize_for_the_ui() {
        let info = AccountInfo {
            state: AccountState::SignedIn { name: "Steve".into(), uuid: "abc".into(), skin: None, slim: true },
            sign_in_required: true,
            accounts: vec![
                AccountSummary { uuid: "abc".into(), name: "Steve".into(), skin: None, active: true },
                AccountSummary { uuid: "def".into(), name: "Alex".into(), skin: Some("data:image/png;base64,AA==".into()), active: false },
            ],
            notice: Some("Couldn't switch to Alex.".into()),
        };
        let json = serde_json::to_value(&info).unwrap();
        assert_eq!(json["state"], "signedIn");
        assert_eq!(json["name"], "Steve");
        assert_eq!(json["slim"], true);
        assert_eq!(json["signInRequired"], true);
        assert_eq!(json["accounts"][1]["name"], "Alex");
        assert_eq!(json["accounts"][0]["active"], true);
        assert_eq!(json["notice"], "Couldn't switch to Alex.");

        let waiting = serde_json::to_value(AccountState::AwaitingApproval { gamertag: Some("Steve".into()) }).unwrap();
        assert_eq!(waiting["state"], "awaitingApproval");
        assert_eq!(waiting["gamertag"], "Steve");
    }

    #[test]
    fn the_game_gets_skins_as_plain_base64() {
        let accounts = GameAccounts {
            active: Some("abc".into()),
            accounts: vec![GameAccount { uuid: "abc".into(), name: "Steve".into(), skin: Some("iVBORw==".into()), slim: false }],
            signing_in: false,
        };
        let json = serde_json::to_value(&accounts).unwrap();
        assert_eq!(json["active"], "abc");
        assert_eq!(json["signingIn"], false);
        assert_eq!(json["accounts"][0]["skin"], "iVBORw==");
    }

    #[test]
    fn network_failures_count_as_offline() {
        let error = AuthError::from(crate::error::Error::Offline);
        assert_eq!(error, AuthError::Offline);
        assert!(error.to_string().contains("Check your internet connection"));
    }
}
