//! Xbox Live: the Microsoft token becomes an Xbox user token, then XSTS tokens for Minecraft
//! (and for Xbox itself, which also says the player's gamertag and Xbox user ID).

use serde::Deserialize;
use serde_json::json;

use super::AuthError;
use crate::net::{ApiResponse, Net};

const USER_AUTH: &str = "https://user.auth.xboxlive.com/user/authenticate";
const XSTS: &str = "https://xsts.auth.xboxlive.com/xsts/authorize";
pub const MINECRAFT: &str = "rp://api.minecraftservices.com/";
pub const XBOX: &str = "http://xboxlive.com";

#[derive(Debug, Clone)]
pub struct XboxToken {
    pub token: String,
    /// The user hash, needed next to the token.
    pub uhs: String,
    pub gamertag: Option<String>,
    pub xuid: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct XboxResponse {
    token: String,
    display_claims: DisplayClaims,
}

#[derive(Deserialize)]
struct DisplayClaims {
    xui: Vec<Claim>,
}

#[derive(Deserialize)]
struct Claim {
    uhs: String,
    #[serde(default)]
    gtg: Option<String>,
    #[serde(default)]
    xid: Option<String>,
}

#[derive(Deserialize)]
struct XboxError {
    #[serde(rename = "XErr")]
    code: Option<u64>,
}

pub async fn user_token(net: &Net, microsoft_token: &str) -> Result<XboxToken, AuthError> {
    let body = json!({
        "Properties": {
            "AuthMethod": "RPS",
            "SiteName": "user.auth.xboxlive.com",
            "RpsTicket": format!("d={microsoft_token}")
        },
        "RelyingParty": "http://auth.xboxlive.com",
        "TokenType": "JWT"
    });
    parse(net.post_json(USER_AUTH, &body, None).await?)
}

pub async fn xsts(net: &Net, user_token: &str, relying_party: &str) -> Result<XboxToken, AuthError> {
    let body = json!({
        "Properties": { "SandboxId": "RETAIL", "UserTokens": [user_token] },
        "RelyingParty": relying_party,
        "TokenType": "JWT"
    });
    parse(net.post_json(XSTS, &body, None).await?)
}

fn parse(response: ApiResponse) -> Result<XboxToken, AuthError> {
    if response.status.is_success() {
        let parsed: XboxResponse =
            serde_json::from_slice(&response.body).map_err(|e| AuthError::Failed(format!("Xbox Live's answer didn't make sense: {e}")))?;
        let claim = parsed.display_claims.xui.into_iter().next().ok_or_else(|| AuthError::Failed("Xbox Live sent no user".into()))?;
        return Ok(XboxToken { token: parsed.token, uhs: claim.uhs, gamertag: claim.gtg, xuid: claim.xid });
    }
    match serde_json::from_slice::<XboxError>(&response.body) {
        Ok(XboxError { code: Some(code) }) => Err(refusal(code)),
        _ => Err(AuthError::Failed(format!("Xbox Live answered {}", response.status))),
    }
}

/// Xbox Live's reasons for refusing an account, in words players understand.
pub fn refusal(code: u64) -> AuthError {
    let message = match code {
        2148916233 => "This Microsoft account has no Xbox profile yet. Sign in once at xbox.com to create one, then try again.".to_string(),
        2148916235 => "Xbox Live isn't available in your country, so this account can't sign in to Minecraft.".to_string(),
        2148916236 | 2148916237 => "This account needs adult verification on xbox.com before it can play.".to_string(),
        2148916238 => "This is a child account. An adult has to add it to a Microsoft family group before it can play Minecraft.".to_string(),
        other => format!("Xbox Live refused the sign-in (error {other})."),
    };
    AuthError::Problem(message)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn response(status: u16, body: &str) -> ApiResponse {
        ApiResponse { status: reqwest::StatusCode::from_u16(status).unwrap(), body: body.as_bytes().to_vec() }
    }

    #[test]
    fn reads_tokens_and_claims() {
        let token = parse(response(200, r#"{"IssueInstant":"x","Token":"tok","DisplayClaims":{"xui":[{"gtg":"Steve","xid":"2535","uhs":"123"}]}}"#)).unwrap();
        assert_eq!(token.token, "tok");
        assert_eq!(token.uhs, "123");
        assert_eq!(token.gamertag.as_deref(), Some("Steve"));
        assert_eq!(token.xuid.as_deref(), Some("2535"));
    }

    #[test]
    fn explains_refusals() {
        let child = parse(response(401, r#"{"Identity":"0","XErr":2148916238,"Message":"","Redirect":"https://start.ui.xboxlive.com/AddChildToFamily"}"#));
        assert!(matches!(child, Err(AuthError::Problem(message)) if message.contains("child account")));
        assert!(matches!(parse(response(401, r#"{"XErr":2148916233}"#)), Err(AuthError::Problem(message)) if message.contains("no Xbox profile")));
        assert!(matches!(parse(response(500, "oops")), Err(AuthError::Failed(_))));
    }
}
