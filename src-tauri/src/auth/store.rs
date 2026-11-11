//! What sign-in keeps on the PC: the Microsoft refresh token in the Windows Credential Manager
//! (never in a file), and the player's name and UUID in state/accounts.json, so the launcher can
//! show who's signed in and still start singleplayer when it's offline.

use std::path::PathBuf;

use serde::{Deserialize, Serialize};

use crate::paths::Paths;

const SERVICE: &str = "dev.cubeutility.client";
const TOKEN_ENTRY: &str = "microsoft-refresh-token";
/// The Credential Manager holds at most 2560 bytes per entry, so long tokens are split.
const CHUNK: usize = 2000;
const MAX_CHUNKS: usize = 8;

fn entry(index: usize) -> Result<keyring::v1::Entry, String> {
    let user = if index == 0 { TOKEN_ENTRY.to_string() } else { format!("{TOKEN_ENTRY}.{index}") };
    keyring::v1::Entry::new(SERVICE, &user).map_err(|e| format!("the Windows Credential Manager isn't available: {e}"))
}

pub fn save_refresh_token(token: &str) -> Result<(), String> {
    if token.len() > CHUNK * MAX_CHUNKS {
        return Err("Microsoft's sign-in token is too long to store".into());
    }
    delete_refresh_token();
    for (index, chunk) in token.as_bytes().chunks(CHUNK).enumerate() {
        entry(index)?.set_secret(chunk).map_err(|e| format!("couldn't save the sign-in: {e}"))?;
    }
    Ok(())
}

pub fn load_refresh_token() -> Option<String> {
    let mut bytes = Vec::new();
    for index in 0..MAX_CHUNKS {
        match entry(index).ok()?.get_secret() {
            Ok(chunk) => bytes.extend(chunk),
            Err(_) => break,
        }
    }
    String::from_utf8(bytes).ok().filter(|token| !token.is_empty())
}

pub fn delete_refresh_token() {
    for index in 0..MAX_CHUNKS {
        if let Ok(entry) = entry(index) {
            let _ = entry.delete_credential();
        }
    }
}

/// Names and UUIDs only: no tokens.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SavedAccount {
    pub uuid: String,
    pub name: String,
}

#[derive(Default, Serialize, Deserialize)]
struct AccountsFile {
    #[serde(default)]
    accounts: Vec<SavedAccount>,
}

fn accounts_file(paths: &Paths) -> PathBuf {
    paths.state().join("accounts.json")
}

pub fn saved_account(paths: &Paths) -> Option<SavedAccount> {
    let bytes = std::fs::read(accounts_file(paths)).ok()?;
    serde_json::from_slice::<AccountsFile>(&bytes).ok()?.accounts.into_iter().next()
}

pub fn save_account(paths: &Paths, account: &SavedAccount) {
    let file = AccountsFile { accounts: vec![account.clone()] };
    if let Ok(bytes) = serde_json::to_vec_pretty(&file) {
        if let Err(error) = crate::sync::write_atomic(&accounts_file(paths), &bytes) {
            tracing::warn!("couldn't save accounts.json: {error}");
        }
    }
}

pub fn forget_account(paths: &Paths) {
    if let Some(account) = saved_account(paths) {
        let _ = std::fs::remove_file(skin_file(paths, &account.uuid));
    }
    let _ = std::fs::remove_file(accounts_file(paths));
}

fn skin_file(paths: &Paths, uuid: &str) -> PathBuf {
    let safe: String = uuid.chars().filter(char::is_ascii_alphanumeric).collect();
    paths.state().join("skins").join(format!("{safe}.png"))
}

pub fn cached_skin(paths: &Paths, uuid: &str) -> Option<Vec<u8>> {
    std::fs::read(skin_file(paths, uuid)).ok()
}

pub fn save_skin(paths: &Paths, uuid: &str, png: &[u8]) {
    if let Err(error) = crate::sync::write_atomic(&skin_file(paths, uuid), png) {
        tracing::warn!("couldn't keep the skin: {error}");
    }
}
