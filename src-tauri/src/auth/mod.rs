//! Microsoft sign-in (spec 3.4). Microsoft's page opens in the player's own browser; then the
//! launcher goes Xbox Live → XSTS → Minecraft, checks the player owns Java Edition, and reads
//! their profile and skin. Only the refresh token is kept, in the Windows Credential Manager;
//! the rest lives in memory. Until Mojang approves the Azure app, Minecraft answers 403, shown
//! as "waiting for approval".

mod encoding;
mod minecraft;
mod oauth;
mod store;
mod xbox;

use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, Instant};

use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_opener::OpenerExt;

use crate::config;
use crate::game::launch::Account;
use crate::net::Net;
use crate::paths::Paths;

const STATE_EVENT: &str = "account://state";
/// How long the player has to finish Microsoft's page.
const SIGN_IN_TIMEOUT: Duration = Duration::from_secs(300);

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

/// Who's signed in, for the UI.
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
}

/// The live Minecraft login, never written anywhere.
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
    state: Mutex<AccountState>,
    session: Mutex<Option<Session>>,
    /// One sign-in or refresh at a time.
    flow: tokio::sync::Mutex<()>,
    cancel: Mutex<Option<tokio::sync::oneshot::Sender<()>>>,
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

impl Auth {
    pub fn new(paths: Paths, net: Net) -> Self {
        Self {
            paths,
            net,
            state: Mutex::new(AccountState::Checking),
            session: Mutex::new(None),
            flow: tokio::sync::Mutex::new(()),
            cancel: Mutex::new(None),
        }
    }

    pub fn info(&self) -> AccountInfo {
        AccountInfo { state: lock(&self.state).clone(), sign_in_required: !cfg!(debug_assertions) }
    }

    fn set(&self, app: &AppHandle, state: AccountState) {
        *lock(&self.state) = state;
        let _ = app.emit(STATE_EVENT, self.info());
    }

    /// Signs in again with the saved refresh token. Microsoft hands out a new one each time.
    async fn refresh(&self, refresh_token: &str) -> Result<AccountState, AuthError> {
        let tokens = oauth::refresh(&self.net, config::AZURE_CLIENT_ID, refresh_token).await?;
        if let Some(newer) = &tokens.refresh_token {
            store::save_refresh_token(newer).map_err(AuthError::Failed)?;
        }
        self.finish(&tokens.access_token).await
    }

    /// From a Microsoft access token to a Minecraft login, the profile and the skin.
    async fn finish(&self, microsoft_token: &str) -> Result<AccountState, AuthError> {
        let user = xbox::user_token(&self.net, microsoft_token).await?;
        let for_minecraft = xbox::xsts(&self.net, &user.token, xbox::MINECRAFT).await?;
        // The gamertag and Xbox user ID; nice to have, so a failure here doesn't matter.
        let identity = xbox::xsts(&self.net, &user.token, xbox::XBOX).await.ok();
        let gamertag = identity.as_ref().and_then(|i| i.gamertag.clone());
        let login = match minecraft::login(&self.net, &for_minecraft.uhs, &for_minecraft.token).await {
            Ok(login) => login,
            Err(AuthError::NotApproved) => {
                tracing::info!("Microsoft and Xbox sign-in worked; Minecraft answered 403 (the Azure app isn't approved yet)");
                return Ok(AccountState::AwaitingApproval { gamertag });
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
        let skin = self.skin(&profile).await;
        store::save_account(&self.paths, &store::SavedAccount { uuid: profile.id.clone(), name: profile.name.clone() });
        let lifetime = Duration::from_secs(login.expires_in.saturating_sub(300).max(60));
        *lock(&self.session) = Some(Session {
            name: profile.name.clone(),
            uuid: profile.id.clone(),
            access_token: login.access_token,
            xuid: identity.and_then(|i| i.xuid).unwrap_or_else(|| "0".to_string()),
            expires_at: Instant::now() + lifetime,
        });
        tracing::info!("signed in as {}", profile.name);
        Ok(AccountState::SignedIn { name: profile.name, uuid: profile.id, skin, slim: profile.slim })
    }

    /// The skin as a data: URL (the UI loads nothing from other sites), kept for offline starts.
    async fn skin(&self, profile: &minecraft::Profile) -> Option<String> {
        if let Some(url) = &profile.skin_url {
            match self.net.get_bytes(url, None).await {
                Ok(png) if png.starts_with(b"\x89PNG") => store::save_skin(&self.paths, &profile.id, &png),
                Ok(_) => tracing::warn!("the skin isn't a PNG"),
                Err(error) => tracing::warn!("couldn't download the skin: {error}"),
            }
        }
        self.cached_skin(&profile.id)
    }

    fn cached_skin(&self, uuid: &str) -> Option<String> {
        store::cached_skin(&self.paths, uuid).map(|png| format!("data:image/png;base64,{}", encoding::base64(&png)))
    }

    /// What to show after a sign-in or refresh failed.
    fn state_after(&self, error: &AuthError) -> AccountState {
        match error {
            AuthError::Expired => {
                store::delete_refresh_token();
                AccountState::SignedOut { message: Some(error.to_string()) }
            }
            AuthError::Offline => match store::saved_account(&self.paths) {
                Some(saved) => AccountState::Offline { skin: self.cached_skin(&saved.uuid), name: saved.name, uuid: saved.uuid },
                None => AccountState::Problem { message: error.to_string() },
            },
            other => AccountState::Problem { message: other.to_string() },
        }
    }

    async fn sign_in(&self, app: &AppHandle, cancelled: tokio::sync::oneshot::Receiver<()>) -> Result<AccountState, AuthError> {
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
        store::save_refresh_token(&refresh_token).map_err(AuthError::Failed)?;
        self.finish(&tokens.access_token).await
    }

    /// The account to start the game with. Refreshes an expired login first. Offline, the last
    /// account still starts (singleplayer only). None when nobody has signed in.
    pub async fn launch_account(&self, app: &AppHandle) -> Result<Option<Account>, AuthError> {
        if let Some(account) = self.live_account() {
            return Ok(Some(account));
        }
        let _flow = self.flow.lock().await;
        if let Some(account) = self.live_account() {
            return Ok(Some(account));
        }
        let Some(refresh_token) = store::load_refresh_token() else { return Ok(None) };
        match self.refresh(&refresh_token).await {
            Ok(state) => {
                let approved = matches!(state, AccountState::SignedIn { .. });
                self.set(app, state);
                if approved {
                    Ok(self.live_account())
                } else {
                    Err(AuthError::NotApproved)
                }
            }
            Err(AuthError::Offline) => {
                self.set(app, self.state_after(&AuthError::Offline));
                Ok(store::saved_account(&self.paths).map(|saved| Account {
                    name: saved.name,
                    uuid: saved.uuid,
                    access_token: "0".to_string(),
                    xuid: "0".to_string(),
                }))
            }
            Err(error) => {
                self.set(app, self.state_after(&error));
                Err(error)
            }
        }
    }

    fn live_account(&self) -> Option<Account> {
        let session = lock(&self.session);
        let session = session.as_ref().filter(|s| s.expires_at > Instant::now())?;
        Some(Account {
            name: session.name.clone(),
            uuid: session.uuid.clone(),
            access_token: session.access_token.clone(),
            xuid: session.xuid.clone(),
        })
    }
}

/// At startup: signs in again silently with the saved token.
pub fn start(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        let auth = app.state::<Auth>();
        let _flow = auth.flow.lock().await;
        let state = match store::load_refresh_token() {
            None => AccountState::SignedOut { message: None },
            Some(token) => match auth.refresh(&token).await {
                Ok(state) => state,
                Err(error) => {
                    tracing::warn!("couldn't restore the sign-in: {error}");
                    auth.state_after(&error)
                }
            },
        };
        auth.set(&app, state);
    });
}

#[tauri::command]
pub fn account_state(auth: tauri::State<'_, Auth>) -> AccountInfo {
    auth.info()
}

/// Opens Microsoft's sign-in page in the browser and finishes signing in when it comes back.
#[tauri::command]
pub fn sign_in(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        let auth = app.state::<Auth>();
        let Ok(_flow) = auth.flow.try_lock() else { return };
        let before = lock(&auth.state).clone();
        auth.set(&app, AccountState::SigningIn);
        let (cancel, cancelled) = tokio::sync::oneshot::channel();
        *lock(&auth.cancel) = Some(cancel);
        let result = auth.sign_in(&app, cancelled).await;
        *lock(&auth.cancel) = None;
        let state = match result {
            Ok(state) => state,
            // Back to how it was, with a note when nobody was signed in.
            Err(error @ (AuthError::Cancelled | AuthError::TimedOut)) => match before {
                AccountState::SignedOut { .. } | AccountState::Checking | AccountState::SigningIn => {
                    AccountState::SignedOut { message: Some(error.to_string()) }
                }
                other => other,
            },
            Err(error) => {
                tracing::warn!("sign-in failed: {error}");
                auth.state_after(&error)
            }
        };
        auth.set(&app, state);
    });
}

#[tauri::command]
pub fn cancel_sign_in(auth: tauri::State<'_, Auth>) {
    if let Some(cancel) = lock(&auth.cancel).take() {
        let _ = cancel.send(());
    }
}

/// Forgets the account: the token in the Credential Manager, the name and the skin.
#[tauri::command]
pub fn sign_out(app: AppHandle) {
    let auth = app.state::<Auth>();
    store::delete_refresh_token();
    store::forget_account(&auth.paths);
    *lock(&auth.session) = None;
    tracing::info!("signed out");
    auth.set(&app, AccountState::SignedOut { message: None });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn account_states_serialize_for_the_ui() {
        let info = AccountInfo {
            state: AccountState::SignedIn { name: "Steve".into(), uuid: "abc".into(), skin: None, slim: true },
            sign_in_required: true,
        };
        let json = serde_json::to_value(&info).unwrap();
        assert_eq!(json["state"], "signedIn");
        assert_eq!(json["name"], "Steve");
        assert_eq!(json["slim"], true);
        assert_eq!(json["signInRequired"], true);

        let waiting = serde_json::to_value(AccountState::AwaitingApproval { gamertag: Some("Steve".into()) }).unwrap();
        assert_eq!(waiting["state"], "awaitingApproval");
        assert_eq!(waiting["gamertag"], "Steve");
    }

    #[test]
    fn network_failures_count_as_offline() {
        let error = AuthError::from(crate::error::Error::Offline);
        assert_eq!(error, AuthError::Offline);
        assert!(error.to_string().contains("Check your internet connection"));
    }
}
