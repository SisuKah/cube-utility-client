//! Minecraft's own services: logging in with the Xbox token, checking that the player owns
//! Java Edition, and reading their profile (name, UUID and skin).

use reqwest::StatusCode;
use serde::Deserialize;
use serde_json::json;

use super::AuthError;
use crate::net::{ApiResponse, Net};

const LOGIN: &str = "https://api.minecraftservices.com/authentication/login_with_xbox";
const ENTITLEMENTS: &str = "https://api.minecraftservices.com/entitlements/mcstore";
const PROFILE: &str = "https://api.minecraftservices.com/minecraft/profile";

pub struct Login {
    pub access_token: String,
    /// Seconds; usually a day.
    pub expires_in: u64,
}

#[derive(Deserialize)]
struct LoginResponse {
    access_token: String,
    #[serde(default = "a_day")]
    expires_in: u64,
}

fn a_day() -> u64 {
    86_400
}

pub async fn login(net: &Net, uhs: &str, xsts_token: &str) -> Result<Login, AuthError> {
    let body = json!({ "identityToken": format!("XBL3.0 x={uhs};{xsts_token}") });
    let response = net.post_json(LOGIN, &body, None).await?;
    match response.status {
        status if status.is_success() => {
            let parsed: LoginResponse = parse_json(&response, "the Minecraft login")?;
            Ok(Login { access_token: parsed.access_token, expires_in: parsed.expires_in })
        }
        // Mojang hasn't approved this app (client ID) yet.
        StatusCode::FORBIDDEN => Err(AuthError::NotApproved),
        StatusCode::TOO_MANY_REQUESTS => Err(AuthError::Problem("Too many sign-ins in a short time. Wait a minute and try again.".into())),
        status => Err(AuthError::Failed(format!("Minecraft's servers answered {status}"))),
    }
}

pub async fn owns_java(net: &Net, token: &str) -> Result<bool, AuthError> {
    let response = net.get_api(ENTITLEMENTS, token).await?;
    if !response.status.is_success() {
        return Err(AuthError::Failed(format!("the game ownership check answered {}", response.status)));
    }
    Ok(has_java(&response.body))
}

/// Whether the entitlements include Java Edition (bought, or through Game Pass).
pub fn has_java(body: &[u8]) -> bool {
    #[derive(Deserialize)]
    struct Entitlements {
        #[serde(default)]
        items: Vec<Item>,
    }
    #[derive(Deserialize)]
    struct Item {
        name: String,
    }
    serde_json::from_slice::<Entitlements>(body).is_ok_and(|e| e.items.iter().any(|item| matches!(item.name.as_str(), "game_minecraft" | "product_minecraft")))
}

#[derive(Debug, Clone, PartialEq)]
pub struct Profile {
    /// The player's UUID, without dashes.
    pub id: String,
    pub name: String,
    pub skin_url: Option<String>,
    /// Slim ("Alex") arms.
    pub slim: bool,
}

/// None when the account has no Java Edition profile (no name chosen yet).
pub async fn profile(net: &Net, token: &str) -> Result<Option<Profile>, AuthError> {
    let response = net.get_api(PROFILE, token).await?;
    if response.status == StatusCode::NOT_FOUND {
        return Ok(None);
    }
    if !response.status.is_success() {
        return Err(AuthError::Failed(format!("the profile request answered {}", response.status)));
    }
    parse_profile(&response.body).map(Some)
}

pub fn parse_profile(body: &[u8]) -> Result<Profile, AuthError> {
    #[derive(Deserialize)]
    struct Raw {
        id: String,
        name: String,
        #[serde(default)]
        skins: Vec<Skin>,
    }
    #[derive(Deserialize)]
    struct Skin {
        #[serde(default)]
        state: String,
        url: String,
        #[serde(default)]
        variant: String,
    }
    let raw: Raw = serde_json::from_slice(body).map_err(|e| AuthError::Failed(format!("the profile didn't make sense: {e}")))?;
    let skin = raw.skins.iter().find(|s| s.state.eq_ignore_ascii_case("active")).or(raw.skins.first());
    Ok(Profile {
        id: raw.id,
        name: raw.name,
        // Profiles list skins over http; the same address works over https.
        skin_url: skin.map(|s| s.url.replacen("http://", "https://", 1)),
        slim: skin.is_some_and(|s| s.variant.eq_ignore_ascii_case("slim")),
    })
}

fn parse_json<T: serde::de::DeserializeOwned>(response: &ApiResponse, what: &str) -> Result<T, AuthError> {
    serde_json::from_slice(&response.body).map_err(|e| AuthError::Failed(format!("{what} didn't make sense: {e}")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recognizes_java_edition() {
        assert!(has_java(br#"{"items":[{"name":"product_minecraft","signature":"x"},{"name":"game_minecraft","signature":"y"}],"signature":"z","keyId":"1"}"#));
        assert!(!has_java(br#"{"items":[],"signature":"z","keyId":"1"}"#));
        assert!(!has_java(br#"{"items":[{"name":"product_minecraft_bedrock"}]}"#));
        assert!(!has_java(b"not json"));
    }

    #[test]
    fn reads_the_profile_and_active_skin() {
        let profile = parse_profile(
            br#"{"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch",
                "skins":[{"id":"1","state":"INACTIVE","url":"http://textures.minecraft.net/texture/old","variant":"CLASSIC"},
                         {"id":"2","state":"ACTIVE","url":"http://textures.minecraft.net/texture/abc","variant":"SLIM"}],"capes":[]}"#,
        )
        .unwrap();
        assert_eq!(profile.name, "Notch");
        assert_eq!(profile.id, "069a79f444e94726a5befca90e38aaf5");
        assert_eq!(profile.skin_url.as_deref(), Some("https://textures.minecraft.net/texture/abc"));
        assert!(profile.slim);

        let no_skin = parse_profile(br#"{"id":"1","name":"Alex"}"#).unwrap();
        assert_eq!((no_skin.skin_url, no_skin.slim), (None, false));
    }
}
