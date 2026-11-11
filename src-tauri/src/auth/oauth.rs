//! Microsoft sign-in: the authorization code flow with PKCE, in the player's own browser. The
//! browser comes back to a one-time listener on this PC, so the launcher never sees passwords.

use std::collections::HashMap;
use std::net::{Ipv4Addr, Ipv6Addr};
use std::time::Duration;

use serde::Deserialize;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

use super::encoding::base64url;
use super::AuthError;
use crate::net::{ApiResponse, Net};

const AUTHORIZE: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
const TOKEN: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
const SCOPE: &str = "XboxLive.signin offline_access";

/// PKCE (RFC 7636): proves the code is redeemed by the app that asked for it.
pub struct Pkce {
    pub verifier: String,
    pub challenge: String,
}

impl Pkce {
    pub fn new() -> Result<Self, AuthError> {
        Ok(Self::from_verifier(base64url(&random::<32>()?)))
    }

    fn from_verifier(verifier: String) -> Self {
        let challenge = base64url(&Sha256::digest(verifier.as_bytes()));
        Self { verifier, challenge }
    }
}

/// Ties the browser's answer to this sign-in.
pub fn random_state() -> Result<String, AuthError> {
    Ok(base64url(&random::<16>()?))
}

fn random<const N: usize>() -> Result<[u8; N], AuthError> {
    let mut bytes = [0u8; N];
    getrandom::fill(&mut bytes).map_err(|e| AuthError::Failed(format!("couldn't get random numbers: {e}")))?;
    Ok(bytes)
}

pub fn authorize_url(client_id: &str, redirect_uri: &str, state: &str, challenge: &str) -> String {
    let mut url = reqwest::Url::parse(AUTHORIZE).expect("a constant, valid URL");
    url.query_pairs_mut()
        .append_pair("client_id", client_id)
        .append_pair("response_type", "code")
        .append_pair("redirect_uri", redirect_uri)
        .append_pair("response_mode", "query")
        .append_pair("scope", SCOPE)
        .append_pair("state", state)
        .append_pair("code_challenge", challenge)
        .append_pair("code_challenge_method", "S256")
        .append_pair("prompt", "select_account");
    url.to_string()
}

/// A one-time web server on this PC for the browser to come back to.
pub struct Loopback {
    v4: TcpListener,
    v6: Option<TcpListener>,
    port: u16,
}

impl Loopback {
    pub async fn bind() -> Result<Self, AuthError> {
        let v4 = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
        let port = v4.local_addr()?.port();
        // Some browsers try localhost over IPv6 first.
        let v6 = TcpListener::bind((Ipv6Addr::LOCALHOST, port)).await.ok();
        Ok(Self { v4, v6, port })
    }

    /// The Azure app has http://localhost registered; Microsoft accepts any port on it.
    pub fn redirect_uri(&self) -> String {
        format!("http://localhost:{}", self.port)
    }

    /// Waits for the browser to come back, shows the player a page, and returns the code.
    pub async fn wait_for_code(&self, state: &str) -> Result<String, AuthError> {
        loop {
            let (mut stream, _) = match &self.v6 {
                Some(v6) => tokio::select! {
                    accepted = self.v4.accept() => accepted?,
                    accepted = v6.accept() => accepted?,
                },
                None => self.v4.accept().await?,
            };
            let Some(request_line) = read_request_line(&mut stream).await else { continue };
            match redirect_result(&request_line, state) {
                // Something else, like the browser asking for a favicon.
                None => {
                    let _ = respond(&mut stream, "404 Not Found", "").await;
                }
                Some(result) => {
                    let body = match &result {
                        Ok(_) => page(true, "You're signed in", "You can close this tab and go back to Cube Utility Client."),
                        Err(error) => page(false, "Sign-in didn't work", &error.to_string()),
                    };
                    let _ = respond(&mut stream, "200 OK", &body).await;
                    return result;
                }
            }
        }
    }
}

async fn read_request_line(stream: &mut TcpStream) -> Option<String> {
    let mut head = Vec::with_capacity(2048);
    let mut chunk = [0u8; 2048];
    while !head.windows(4).any(|w| w == b"\r\n\r\n") && head.len() < 16 * 1024 {
        let read = tokio::time::timeout(Duration::from_secs(10), stream.read(&mut chunk)).await.ok()?.ok()?;
        if read == 0 {
            break;
        }
        head.extend_from_slice(&chunk[..read]);
    }
    String::from_utf8_lossy(&head).lines().next().map(str::to_string)
}

/// What a request to the listener means: the code, a refusal, or (None) nothing to do with sign-in.
pub fn redirect_result(request_line: &str, expected_state: &str) -> Option<Result<String, AuthError>> {
    let mut parts = request_line.split_whitespace();
    if parts.next()? != "GET" {
        return None;
    }
    let url = reqwest::Url::parse(&format!("http://localhost{}", parts.next()?)).ok()?;
    if url.path() != "/" {
        return None;
    }
    let params: HashMap<String, String> = url.query_pairs().into_owned().collect();
    if let Some(error) = params.get("error") {
        return Some(Err(match error.as_str() {
            "access_denied" => AuthError::Cancelled,
            _ => AuthError::Microsoft(first_line(params.get("error_description").unwrap_or(error))),
        }));
    }
    let code = params.get("code")?;
    if params.get("state").map(String::as_str) != Some(expected_state) {
        return Some(Err(AuthError::Failed("the browser's answer didn't belong to this sign-in. Try again.".into())));
    }
    Some(Ok(code.clone()))
}

async fn respond(stream: &mut TcpStream, status: &str, body: &str) -> std::io::Result<()> {
    let head = format!(
        "HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
        body.len()
    );
    stream.write_all(head.as_bytes()).await?;
    stream.write_all(body.as_bytes()).await?;
    stream.shutdown().await
}

fn page(ok: bool, title: &str, text: &str) -> String {
    let accent = if ok { "#e4c084" } else { "#ef5a7a" };
    format!(
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\
         <title>Cube Utility Client</title><style>body{{margin:0;min-height:100vh;display:grid;place-items:center;background:#0a1112;color:#e9efee;\
         font:17px/1.6 \"Segoe UI\",system-ui,sans-serif}}main{{max-width:440px;padding:32px;text-align:center}}h1{{margin:0 0 10px;font-size:34px;\
         font-weight:300;color:{accent}}}p{{margin:0;color:#9aaba9}}</style></head><body><main><h1>{}</h1><p>{}</p></main></body></html>",
        escape(title),
        escape(text)
    )
}

fn escape(text: &str) -> String {
    text.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

/// Microsoft's error descriptions end with trace IDs on later lines.
fn first_line(text: &str) -> String {
    text.lines().next().unwrap_or(text).trim().to_string()
}

#[derive(Debug, Deserialize)]
pub struct Tokens {
    pub access_token: String,
    #[serde(default)]
    pub refresh_token: Option<String>,
}

#[derive(Deserialize)]
struct TokenError {
    error: String,
    #[serde(default)]
    error_description: Option<String>,
}

pub async fn redeem_code(net: &Net, client_id: &str, code: &str, redirect_uri: &str, verifier: &str) -> Result<Tokens, AuthError> {
    let fields = [
        ("client_id", client_id),
        ("grant_type", "authorization_code"),
        ("code", code),
        ("redirect_uri", redirect_uri),
        ("code_verifier", verifier),
        ("scope", SCOPE),
    ];
    tokens(net.post_form(TOKEN, &fields).await?)
}

/// A new access token (and usually a new refresh token) from the saved refresh token.
pub async fn refresh(net: &Net, client_id: &str, refresh_token: &str) -> Result<Tokens, AuthError> {
    let fields = [("client_id", client_id), ("grant_type", "refresh_token"), ("refresh_token", refresh_token), ("scope", SCOPE)];
    tokens(net.post_form(TOKEN, &fields).await?)
}

fn tokens(response: ApiResponse) -> Result<Tokens, AuthError> {
    if response.status.is_success() {
        return serde_json::from_slice(&response.body).map_err(|e| AuthError::Failed(format!("Microsoft's answer didn't make sense: {e}")));
    }
    match serde_json::from_slice::<TokenError>(&response.body) {
        // The refresh token expired or was revoked (like after a password change).
        Ok(error) if error.error == "invalid_grant" => Err(AuthError::Expired),
        Ok(error) => Err(AuthError::Microsoft(first_line(error.error_description.as_deref().unwrap_or(&error.error)))),
        Err(_) => Err(AuthError::Microsoft(format!("its server answered {}", response.status))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pkce_matches_rfc_7636() {
        let pkce = Pkce::from_verifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk".to_string());
        assert_eq!(pkce.challenge, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        let fresh = Pkce::new().unwrap();
        assert_eq!(fresh.verifier.len(), 43);
        assert_ne!(fresh.verifier, Pkce::new().unwrap().verifier);
    }

    #[test]
    fn builds_the_sign_in_address() {
        let url = authorize_url("client-id", "http://localhost:51234", "the-state", "the-challenge");
        assert!(url.starts_with("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?client_id=client-id&response_type=code"));
        assert!(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A51234"));
        assert!(url.contains("scope=XboxLive.signin+offline_access"));
        assert!(url.contains("code_challenge=the-challenge&code_challenge_method=S256"));
    }

    #[test]
    fn reads_the_browsers_answer() {
        let ok = redirect_result("GET /?code=M.C123-abc&state=s1 HTTP/1.1", "s1");
        assert_eq!(ok.unwrap().unwrap(), "M.C123-abc");
        assert!(matches!(redirect_result("GET /?code=abc&state=other HTTP/1.1", "s1"), Some(Err(AuthError::Failed(_)))));
        assert!(matches!(redirect_result("GET /?error=access_denied&error_description=no HTTP/1.1", "s1"), Some(Err(AuthError::Cancelled))));
        let other = redirect_result("GET /?error=server_error&error_description=AADSTS1%3A+Broken%0D%0ATrace+ID%3A+x HTTP/1.1", "s1");
        assert!(matches!(other, Some(Err(AuthError::Microsoft(message))) if message == "AADSTS1: Broken"));
        assert!(redirect_result("GET /favicon.ico HTTP/1.1", "s1").is_none());
        assert!(redirect_result("GET / HTTP/1.1", "s1").is_none());
        assert!(redirect_result("POST /?code=a&state=s1 HTTP/1.1", "s1").is_none());
    }

    #[test]
    fn expired_sign_ins_are_recognized() {
        let response = |status: u16, body: &str| ApiResponse { status: reqwest::StatusCode::from_u16(status).unwrap(), body: body.as_bytes().to_vec() };
        assert!(matches!(tokens(response(400, r#"{"error":"invalid_grant","error_description":"AADSTS70000: expired"}"#)), Err(AuthError::Expired)));
        let ok = tokens(response(200, r#"{"access_token":"a","refresh_token":"r","expires_in":3600}"#)).unwrap();
        assert_eq!((ok.access_token.as_str(), ok.refresh_token.as_deref()), ("a", Some("r")));
    }

    #[tokio::test]
    async fn the_listener_catches_the_redirect() {
        let loopback = Loopback::bind().await.unwrap();
        let port = loopback.redirect_uri().rsplit(':').next().unwrap().parse::<u16>().unwrap();
        let browser = tokio::spawn(async move {
            let mut stream = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).await.unwrap();
            stream.write_all(b"GET /?code=the-code&state=s1 HTTP/1.1\r\nHost: localhost\r\n\r\n").await.unwrap();
            let mut page = String::new();
            stream.read_to_string(&mut page).await.unwrap();
            page
        });
        assert_eq!(loopback.wait_for_code("s1").await.unwrap(), "the-code");
        assert!(browser.await.unwrap().contains("You're signed in"));
    }
}
