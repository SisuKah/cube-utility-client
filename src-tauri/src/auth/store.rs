//! What sign-in keeps on the PC: each account's Microsoft refresh token in the Windows Credential
//! Manager (never in a file), and the accounts' names and UUIDs, plus which one plays, in
//! state/accounts.json, so the launcher can list them and still start singleplayer offline.

use std::path::PathBuf;

use serde::{Deserialize, Serialize};

use crate::paths::Paths;

const SERVICE: &str = "dev.cubeutility.client";
/// Launchers before the alt manager kept their one account's token under this name. It now holds
/// a sign-in whose Minecraft profile isn't known yet: Microsoft and Xbox said yes, but Mojang
/// hadn't approved the launcher, so there's no UUID to file it under.
const PENDING: &str = "microsoft-refresh-token";
/// The Credential Manager holds at most 2560 bytes per entry, so long tokens are split.
const CHUNK: usize = 2000;
const MAX_CHUNKS: usize = 8;

/// Whose token: an account's (by its UUID) or the pending sign-in's.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Slot<'a> {
    Pending,
    Account(&'a str),
}

fn entry_name(slot: Slot<'_>) -> String {
    match slot {
        Slot::Pending => PENDING.to_string(),
        Slot::Account(uuid) => format!("{PENDING}:{}", safe(uuid)),
    }
}

fn entry(name: &str, index: usize) -> Result<keyring::v1::Entry, String> {
    let user = if index == 0 { name.to_string() } else { format!("{name}.{index}") };
    keyring::v1::Entry::new(SERVICE, &user).map_err(|e| format!("the Windows Credential Manager isn't available: {e}"))
}

pub fn save_refresh_token(slot: Slot<'_>, token: &str) -> Result<(), String> {
    if token.len() > CHUNK * MAX_CHUNKS {
        return Err("Microsoft's sign-in token is too long to store".into());
    }
    delete_refresh_token(slot);
    let name = entry_name(slot);
    for (index, chunk) in token.as_bytes().chunks(CHUNK).enumerate() {
        entry(&name, index)?.set_secret(chunk).map_err(|e| format!("couldn't save the sign-in: {e}"))?;
    }
    Ok(())
}

pub fn load_refresh_token(slot: Slot<'_>) -> Option<String> {
    let name = entry_name(slot);
    let mut bytes = Vec::new();
    for index in 0..MAX_CHUNKS {
        match entry(&name, index).ok()?.get_secret() {
            Ok(chunk) => bytes.extend(chunk),
            Err(_) => break,
        }
    }
    String::from_utf8(bytes).ok().filter(|token| !token.is_empty())
}

pub fn delete_refresh_token(slot: Slot<'_>) {
    let name = entry_name(slot);
    for index in 0..MAX_CHUNKS {
        if let Ok(entry) = entry(&name, index) {
            let _ = entry.delete_credential();
        }
    }
}

/// Names and UUIDs only: no tokens.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SavedAccount {
    pub uuid: String,
    pub name: String,
    /// The skin has slim ("Alex") arms.
    #[serde(default)]
    pub slim: bool,
}

/// Every account signed in on this PC, in the order they were added, and the one that plays.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Accounts {
    /// The UUID of the account PLAY starts with. Files from before the alt manager have none:
    /// their one account plays.
    #[serde(default)]
    pub active: Option<String>,
    #[serde(default)]
    pub accounts: Vec<SavedAccount>,
}

impl Accounts {
    pub fn get(&self, uuid: &str) -> Option<&SavedAccount> {
        self.accounts.iter().find(|a| a.uuid == uuid)
    }

    pub fn active(&self) -> Option<&SavedAccount> {
        self.active.as_deref().and_then(|uuid| self.get(uuid))
    }

    /// Adds the account, or updates its name and skin type if it's there already (both change).
    pub fn upsert(&mut self, account: SavedAccount) {
        match self.accounts.iter_mut().find(|a| a.uuid == account.uuid) {
            Some(existing) => *existing = account,
            None => self.accounts.push(account),
        }
    }

    /// Removes the account. If it was the one that plays, none does until the player picks one.
    pub fn remove(&mut self, uuid: &str) -> bool {
        let before = self.accounts.len();
        self.accounts.retain(|a| a.uuid != uuid);
        if self.active.as_deref() == Some(uuid) {
            self.active = None;
        }
        self.accounts.len() != before
    }

    /// Older files name no active account: the first one plays, as it always did.
    fn upgraded(mut self) -> Self {
        let known = self.active.as_deref().is_some_and(|uuid| self.get(uuid).is_some());
        if !known {
            self.active = self.accounts.first().map(|a| a.uuid.clone());
        }
        self
    }
}

fn accounts_file(paths: &Paths) -> PathBuf {
    paths.state().join("accounts.json")
}

pub fn load_accounts(paths: &Paths) -> Accounts {
    std::fs::read(accounts_file(paths))
        .ok()
        .and_then(|bytes| serde_json::from_slice::<Accounts>(&bytes).ok())
        .unwrap_or_default()
        .upgraded()
}

pub fn save_accounts(paths: &Paths, accounts: &Accounts) {
    if let Ok(bytes) = serde_json::to_vec_pretty(accounts) {
        if let Err(error) = crate::sync::write_atomic(&accounts_file(paths), &bytes) {
            tracing::warn!("couldn't save accounts.json: {error}");
        }
    }
}

/// From the one-account launcher: its token sat under the pending name. When accounts.json knows
/// that account and it has no token of its own yet, the token is filed under its UUID. (If it
/// turns out to be someone else's, the next sign-in with it files it under the right UUID.)
pub fn adopt_pending_token(accounts: &Accounts) {
    let Some(account) = accounts.active().or(accounts.accounts.first()) else { return };
    if load_refresh_token(Slot::Account(&account.uuid)).is_some() {
        return;
    }
    if let Some(token) = load_refresh_token(Slot::Pending) {
        match save_refresh_token(Slot::Account(&account.uuid), &token) {
            Ok(()) => {
                delete_refresh_token(Slot::Pending);
                tracing::info!("moved the saved sign-in to {}'s own entry", account.name);
            }
            Err(error) => tracing::warn!("couldn't move the saved sign-in: {error}"),
        }
    }
}

/// UUIDs come from Mojang, but they also name files and Credential Manager entries.
fn safe(uuid: &str) -> String {
    uuid.chars().filter(char::is_ascii_alphanumeric).collect()
}

fn skin_file(paths: &Paths, uuid: &str) -> PathBuf {
    paths.state().join("skins").join(format!("{}.png", safe(uuid)))
}

pub fn cached_skin(paths: &Paths, uuid: &str) -> Option<Vec<u8>> {
    std::fs::read(skin_file(paths, uuid)).ok()
}

pub fn save_skin(paths: &Paths, uuid: &str, png: &[u8]) {
    if let Err(error) = crate::sync::write_atomic(&skin_file(paths, uuid), png) {
        tracing::warn!("couldn't keep the skin: {error}");
    }
}

pub fn forget_skin(paths: &Paths, uuid: &str) {
    let _ = std::fs::remove_file(skin_file(paths, uuid));
}

#[cfg(test)]
mod tests {
    use super::*;

    fn account(uuid: &str, name: &str) -> SavedAccount {
        SavedAccount { uuid: uuid.into(), name: name.into(), slim: false }
    }

    #[test]
    fn reads_the_one_account_file_from_before() {
        let old: Accounts = serde_json::from_str(r#"{"accounts":[{"uuid":"aaa","name":"Steve"}]}"#).unwrap();
        let upgraded = old.upgraded();
        assert_eq!(upgraded.active.as_deref(), Some("aaa"));
        assert_eq!(upgraded.active().unwrap().name, "Steve");
        assert_eq!(Accounts::default().upgraded().active, None);
    }

    #[test]
    fn an_unknown_active_account_falls_back_to_the_first() {
        let file = Accounts { active: Some("gone".into()), accounts: vec![account("aaa", "Steve"), account("bbb", "Alex")] };
        assert_eq!(file.upgraded().active.as_deref(), Some("aaa"));
    }

    #[test]
    fn adds_renames_and_removes_accounts() {
        let mut accounts = Accounts::default();
        accounts.upsert(account("aaa", "Steve"));
        accounts.upsert(account("bbb", "Alex"));
        accounts.upsert(account("aaa", "Steve2"));
        assert_eq!(accounts.accounts, vec![account("aaa", "Steve2"), account("bbb", "Alex")]);

        accounts.active = Some("bbb".into());
        assert!(accounts.remove("aaa"));
        assert_eq!(accounts.active.as_deref(), Some("bbb"));
        assert!(accounts.remove("bbb"));
        assert_eq!(accounts.active, None);
        assert!(!accounts.remove("bbb"));
    }

    #[test]
    fn saves_the_active_account() {
        let accounts = Accounts { active: Some("bbb".into()), accounts: vec![account("aaa", "Steve"), account("bbb", "Alex")] };
        let json = serde_json::to_string(&accounts).unwrap();
        assert!(json.contains(r#""active":"bbb""#));
        assert_eq!(serde_json::from_str::<Accounts>(&json).unwrap(), accounts);
    }

    #[test]
    fn credential_entries_are_named_by_account() {
        assert_eq!(entry_name(Slot::Pending), "microsoft-refresh-token");
        assert_eq!(entry_name(Slot::Account("069a79f4-44e9")), "microsoft-refresh-token:069a79f444e9");
    }
}
