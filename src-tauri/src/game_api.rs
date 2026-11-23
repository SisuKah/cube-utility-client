//! The doorway from the game. While Minecraft runs, the client's settings page browses Modrinth
//! and adds mods through the launcher, with the same checks and the same list as the Mods screen
//! (modrinth.rs), and the title screen's Accounts switches the account Minecraft plays with,
//! without closing it: the launcher keeps every account's sign-in and hands the game a login
//! (auth/mod.rs). It's a tiny HTTP server on 127.0.0.1 at a random port, answering only requests
//! that carry the random token it writes, with the port, into the game folder
//! (config/cubeutilityclient/launcher.json) for the client mod. The server stops and the file
//! goes when the game closes. A mod the player removes in the game goes then too, since Windows
//! keeps a running game's jars locked.

use std::collections::BTreeSet;
use std::future::Future;
use std::net::Ipv4Addr;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use reqwest::Url;
use serde::Serialize;
use tauri::{AppHandle, Manager};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

use crate::auth::{Auth, AuthError};
use crate::error::Error;
use crate::game::launch::Account;
use crate::launcher::Launcher;
use crate::modrinth;
use crate::paths::Paths;
use crate::sync::write_atomic;

/// Where the client mod finds the doorway, inside the game folder.
const FILE: &str = "config/cubeutilityclient/launcher.json";
/// A request is a line and a few headers; anything longer isn't from the client mod.
const MAX_HEAD: usize = 16 * 1024;

/// What the game asked for.
#[derive(Debug, Clone, PartialEq)]
enum Ask {
    /// One page of mods that work with the pack.
    Search { query: String, offset: u32 },
    /// The mods installed from Modrinth.
    Installed,
    Install(String),
    /// Remove a mod once the game has closed.
    Remove(String),
    /// Take back a Remove before the game closes.
    Keep(String),
    /// The accounts signed in on this PC, with their skins.
    Accounts,
    /// A Minecraft login for an account (by UUID), which plays from now on.
    Login(String),
    /// Microsoft's sign-in page in the browser; the new account's login comes back.
    SignIn,
    CancelSignIn,
    /// Signs an account (by UUID) out of this PC.
    SignOut(String),
}

/// A status code and a JSON body.
#[derive(Debug, PartialEq)]
struct Reply {
    status: u16,
    body: String,
}

impl Reply {
    fn json(value: &impl Serialize) -> Self {
        match serde_json::to_string(value) {
            Ok(body) => Self { status: 200, body },
            Err(_) => Self::error(500, "Something went wrong."),
        }
    }

    fn error(status: u16, message: &str) -> Self {
        Self { status, body: serde_json::json!({ "error": message }).to_string() }
    }
}

/// One game session's doorway.
pub struct GameApi {
    file: PathBuf,
    server: tauri::async_runtime::JoinHandle<()>,
    removals: Arc<Mutex<BTreeSet<String>>>,
}

impl GameApi {
    /// Opens the doorway, before the game starts. None when it can't: the game starts anyway,
    /// and the settings page says to add mods in the launcher instead.
    pub async fn open(app: &AppHandle, paths: &Paths) -> Option<Self> {
        match Self::try_open(app, paths).await {
            Ok(api) => Some(api),
            Err(error) => {
                tracing::warn!("couldn't open Modrinth to the game: {error}");
                None
            }
        }
    }

    async fn try_open(app: &AppHandle, paths: &Paths) -> std::io::Result<Self> {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
        let port = listener.local_addr()?.port();
        let token: Arc<str> = token()?.into();
        let file = paths.instance().join(FILE);
        let about = serde_json::json!({ "port": port, "token": &*token, "launcher": app.package_info().version.to_string() });
        write_atomic(&file, about.to_string().as_bytes())?;
        let removals: Arc<Mutex<BTreeSet<String>>> = Arc::default();
        let (handler_app, handler_removals) = (app.clone(), removals.clone());
        let server = tauri::async_runtime::spawn(serve(listener, port, token, move |ask| {
            let (app, removals) = (handler_app.clone(), handler_removals.clone());
            async move { answer(&app, &removals, ask).await }
        }));
        Ok(Self { file, server, removals })
    }

    /// Closes the doorway once the game has exited, and removes the mods the player asked to.
    pub async fn close(self, app: &AppHandle) {
        let removals: Vec<String> = std::mem::take(&mut *lock(&self.removals)).into_iter().collect();
        drop(self);
        for project_id in removals {
            if let Err(error) = modrinth::remove_now(app, &project_id).await {
                tracing::warn!("couldn't remove {project_id} after the game closed: {error}");
            }
        }
    }
}

impl Drop for GameApi {
    fn drop(&mut self) {
        self.server.abort();
        let _ = std::fs::remove_file(&self.file);
    }
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// 32 random bytes, as hex.
fn token() -> std::io::Result<String> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).map_err(|error| std::io::Error::other(format!("couldn't get random numbers: {error}")))?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

// ------------------------------------------------------------------ HTTP

async fn serve<F, Fut>(listener: TcpListener, port: u16, token: Arc<str>, handler: F)
where
    F: Fn(Ask) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = Reply> + Send + 'static,
{
    let handler = Arc::new(handler);
    loop {
        let Ok((stream, _)) = listener.accept().await else {
            tokio::time::sleep(Duration::from_millis(200)).await;
            continue;
        };
        let (handler, token) = (handler.clone(), token.clone());
        tokio::spawn(async move {
            let _ = handle(stream, port, &token, &*handler).await;
        });
    }
}

/// Answers one request on its own connection.
async fn handle<F, Fut>(mut stream: TcpStream, port: u16, token: &str, handler: &F) -> std::io::Result<()>
where
    F: Fn(Ask) -> Fut,
    Fut: Future<Output = Reply>,
{
    let reply = match read_head(&mut stream).await {
        Some(head) => match parse(&head, port, token) {
            Ok(ask) => handler(ask).await,
            Err(reply) => reply,
        },
        None => Reply::error(400, "Bad request."),
    };
    let reason = match reply.status {
        200 => "OK",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        409 => "Conflict",
        _ => "Internal Server Error",
    };
    let head = format!(
        "HTTP/1.1 {} {reason}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
        reply.status,
        reply.body.len()
    );
    stream.write_all(head.as_bytes()).await?;
    stream.write_all(reply.body.as_bytes()).await?;
    stream.shutdown().await
}

/// The request line and headers, up to the blank line. The client mod sends no bodies.
async fn read_head(stream: &mut TcpStream) -> Option<String> {
    let mut head = Vec::new();
    let mut chunk = [0u8; 2048];
    while !head.windows(4).any(|window| window == b"\r\n\r\n") {
        if head.len() > MAX_HEAD {
            return None;
        }
        let read = tokio::time::timeout(Duration::from_secs(10), stream.read(&mut chunk)).await.ok()?.ok()?;
        if read == 0 {
            return None;
        }
        head.extend_from_slice(&chunk[..read]);
    }
    String::from_utf8(head).ok()
}

/// What a request asks for, if it may ask at all.
fn parse(head: &str, port: u16, token: &str) -> Result<Ask, Reply> {
    let mut lines = head.split("\r\n");
    let mut request = lines.next().unwrap_or_default().split(' ');
    let (method, target) = (request.next().unwrap_or_default(), request.next().unwrap_or_default());
    let (mut host, mut auth) = (None, None);
    for line in lines {
        let Some((name, value)) = line.split_once(':') else { continue };
        match name.trim().to_ascii_lowercase().as_str() {
            "host" => host = Some(value.trim()),
            "authorization" => auth = Some(value.trim()),
            _ => {}
        }
    }
    // Only this PC's own address, so a web page can't reach the doorway under another name.
    if host != Some(format!("127.0.0.1:{port}").as_str()) {
        return Err(Reply::error(403, "Forbidden."));
    }
    let given = auth.and_then(|value| value.strip_prefix("Bearer ")).unwrap_or_default();
    if !same(given.as_bytes(), token.as_bytes()) {
        return Err(Reply::error(401, "Unauthorized."));
    }
    let url = Url::parse(&format!("http://127.0.0.1{target}")).map_err(|_| Reply::error(400, "Bad request."))?;
    let param = |name: &str| url.query_pairs().find(|(key, _)| key == name).map(|(_, value)| value.into_owned());
    let project = || param("project").filter(|id| modrinth::is_id(id)).ok_or_else(|| Reply::error(400, "That isn't a mod from Modrinth."));
    let account = || param("uuid").filter(|uuid| is_uuid(uuid)).ok_or_else(|| Reply::error(400, "That isn't an account."));
    match (method, url.path()) {
        ("GET", "/modrinth/search") => Ok(Ask::Search {
            query: param("query").unwrap_or_default(),
            offset: param("offset").and_then(|offset| offset.parse().ok()).unwrap_or(0),
        }),
        ("GET", "/modrinth/installed") => Ok(Ask::Installed),
        ("POST", "/modrinth/install") => project().map(Ask::Install),
        ("POST", "/modrinth/remove") => project().map(Ask::Remove),
        ("POST", "/modrinth/keep") => project().map(Ask::Keep),
        ("GET", "/accounts") => Ok(Ask::Accounts),
        ("POST", "/accounts/login") => account().map(Ask::Login),
        ("POST", "/accounts/sign-in") => Ok(Ask::SignIn),
        ("POST", "/accounts/cancel-sign-in") => Ok(Ask::CancelSignIn),
        ("POST", "/accounts/sign-out") => account().map(Ask::SignOut),
        (_, "/modrinth/search" | "/modrinth/installed" | "/modrinth/install" | "/modrinth/remove" | "/modrinth/keep") => Err(Reply::error(405, "Method not allowed.")),
        (_, "/accounts" | "/accounts/login" | "/accounts/sign-in" | "/accounts/cancel-sign-in" | "/accounts/sign-out") => Err(Reply::error(405, "Method not allowed.")),
        _ => Err(Reply::error(404, "Not found.")),
    }
}

/// A Minecraft profile's UUID, with or without dashes.
fn is_uuid(text: &str) -> bool {
    (32..=36).contains(&text.len()) && text.chars().all(|c| c.is_ascii_hexdigit() || c == '-') && text.chars().filter(char::is_ascii_hexdigit).count() == 32
}

/// Compares the token without giving away how much of it matched.
fn same(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |diff, (x, y)| diff | (x ^ y)) == 0
}

// ------------------------------------------------------------------ answers

/// A mod installed from Modrinth, as the settings page shows it.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Entry {
    project_id: String,
    title: String,
    version: String,
    icon_url: Option<String>,
    /// The player chose it, rather than it coming along as a library.
    picked: bool,
    needed_by: Vec<String>,
    /// Goes when the game closes.
    removing: bool,
}

#[derive(Serialize)]
struct Installed {
    mods: Vec<Entry>,
}

async fn answer(app: &AppHandle, removals: &Mutex<BTreeSet<String>>, ask: Ask) -> Reply {
    let result = match ask {
        Ask::Search { query, offset } => modrinth::modrinth_search(app.clone(), query, offset).await.map(|page| Reply::json(&page)),
        Ask::Installed => Ok(Reply::json(&installed(app, removals))),
        Ask::Install(project_id) => {
            lock(removals).remove(&project_id);
            modrinth::modrinth_install(app.clone(), project_id).await.map(|outcome| Reply::json(&outcome))
        }
        Ask::Remove(project_id) => queue_removal(app, removals, &project_id).map(|()| Reply::json(&installed(app, removals))),
        Ask::Keep(project_id) => {
            lock(removals).remove(&project_id);
            Ok(Reply::json(&installed(app, removals)))
        }
        Ask::Accounts | Ask::Login(_) | Ask::SignIn | Ask::CancelSignIn | Ask::SignOut(_) => Ok(accounts(app, ask).await),
    };
    result.unwrap_or_else(|error| Reply::error(409, &error.to_string()))
}

/// The title screen's Accounts: every answer about accounts comes from the launcher's sign-ins.
async fn accounts(app: &AppHandle, ask: Ask) -> Reply {
    let auth = app.state::<Auth>();
    match ask {
        Ask::Login(uuid) => {
            tracing::info!("the game asked to switch accounts");
            login(auth.use_account(app, &uuid).await)
        }
        Ask::SignIn => {
            tracing::info!("the game asked to add an account");
            login(auth.add_account(app).await)
        }
        Ask::CancelSignIn => {
            auth.cancel_sign_in();
            Reply::json(&auth.game_accounts())
        }
        Ask::SignOut(uuid) => match auth.remove(app, &uuid).await {
            Ok(()) => Reply::json(&auth.game_accounts()),
            Err(error) => refusal(&error),
        },
        _ => Reply::json(&auth.game_accounts()),
    }
}

/// A Minecraft login for the game. Other than the command line that starts the game, this is the
/// only way an access token leaves the launcher.
fn login(result: Result<Account, AuthError>) -> Reply {
    match result {
        Ok(account) => Reply::json(&serde_json::json!({
            "uuid": account.uuid,
            "name": account.name,
            "accessToken": account.access_token,
            "xuid": account.xuid,
        })),
        Err(error) => refusal(&error),
    }
}

/// Why an account couldn't be used. A sign-in the player cancelled says so, so the game shows no
/// error for it.
fn refusal(error: &AuthError) -> Reply {
    let body = serde_json::json!({ "error": error.to_string(), "cancelled": matches!(error, AuthError::Cancelled) });
    Reply { status: 409, body: body.to_string() }
}

fn installed(app: &AppHandle, removals: &Mutex<BTreeSet<String>>) -> Installed {
    let launcher = app.state::<Launcher>();
    let removing = lock(removals).clone();
    let mods = modrinth::installed(launcher.paths())
        .into_iter()
        .map(|m| Entry {
            removing: removing.contains(&m.project_id),
            project_id: m.project_id,
            title: m.title,
            version: m.version,
            icon_url: m.icon_url,
            picked: m.picked,
            needed_by: m.needed_by,
        })
        .collect();
    Installed { mods }
}

/// Marks a mod the player picked for removal when the game closes. Libraries go with the mod
/// that brought them, as on the Mods screen.
fn queue_removal(app: &AppHandle, removals: &Mutex<BTreeSet<String>>, project_id: &str) -> Result<(), Error> {
    let launcher = app.state::<Launcher>();
    let mods = modrinth::installed(launcher.paths());
    let Some(target) = mods.iter().find(|m| m.project_id == project_id) else {
        return Err(Error::Message("That mod isn't installed from Modrinth.".into()));
    };
    if !target.picked {
        let by: Vec<&str> = target.needed_by.iter().filter_map(|id| mods.iter().find(|m| &m.project_id == id)).map(|m| m.title.as_str()).collect();
        return Err(Error::Message(format!("{} came with {}. Remove that instead.", target.title, by.join(" and "))));
    }
    lock(removals).insert(project_id.to_string());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const TOKEN: &str = "0123456789abcdef";

    fn request(method: &str, target: &str, host: &str, token: Option<&str>) -> String {
        let auth = token.map(|t| format!("Authorization: Bearer {t}\r\n")).unwrap_or_default();
        format!("{method} {target} HTTP/1.1\r\nHost: {host}\r\nUser-Agent: Java-http-client/25\r\n{auth}Content-Length: 0\r\n\r\n")
    }

    fn ask(method: &str, target: &str) -> Result<Ask, u16> {
        parse(&request(method, target, "127.0.0.1:5050", Some(TOKEN)), 5050, TOKEN).map_err(|reply| reply.status)
    }

    #[test]
    fn reads_what_the_game_asks_for() {
        assert_eq!(ask("GET", "/modrinth/search?query=map%20art&offset=20"), Ok(Ask::Search { query: "map art".into(), offset: 20 }));
        assert_eq!(ask("GET", "/modrinth/search"), Ok(Ask::Search { query: String::new(), offset: 0 }));
        assert_eq!(ask("GET", "/modrinth/installed"), Ok(Ask::Installed));
        assert_eq!(ask("POST", "/modrinth/install?project=AANobbMI"), Ok(Ask::Install("AANobbMI".into())));
        assert_eq!(ask("POST", "/modrinth/remove?project=AANobbMI"), Ok(Ask::Remove("AANobbMI".into())));
        assert_eq!(ask("POST", "/modrinth/keep?project=AANobbMI"), Ok(Ask::Keep("AANobbMI".into())));
    }

    #[test]
    fn reads_account_requests() {
        const UUID: &str = "069a79f444e94726a5befca90e38aaf5";
        assert_eq!(ask("GET", "/accounts"), Ok(Ask::Accounts));
        assert_eq!(ask("POST", &format!("/accounts/login?uuid={UUID}")), Ok(Ask::Login(UUID.into())));
        assert_eq!(ask("POST", "/accounts/login?uuid=069a79f4-44e9-4726-a5be-fca90e38aaf5"), Ok(Ask::Login("069a79f4-44e9-4726-a5be-fca90e38aaf5".into())));
        assert_eq!(ask("POST", "/accounts/sign-in"), Ok(Ask::SignIn));
        assert_eq!(ask("POST", "/accounts/cancel-sign-in"), Ok(Ask::CancelSignIn));
        assert_eq!(ask("POST", &format!("/accounts/sign-out?uuid={UUID}")), Ok(Ask::SignOut(UUID.into())));

        assert_eq!(ask("POST", "/accounts/login"), Err(400));
        assert_eq!(ask("POST", "/accounts/login?uuid=Notch"), Err(400));
        assert_eq!(ask("POST", "/accounts/sign-out?uuid=..%2F..%2Fevil-0000000000000000000000000"), Err(400));
        assert_eq!(ask("GET", &format!("/accounts/login?uuid={UUID}")), Err(405));
        assert_eq!(ask("POST", "/accounts"), Err(405));
    }

    #[test]
    fn logins_and_refusals_for_the_game() {
        let ok = login(Ok(Account { name: "Steve".into(), uuid: "abc".into(), access_token: "secret".into(), xuid: "0".into() }));
        assert_eq!(ok.status, 200);
        let body: serde_json::Value = serde_json::from_str(&ok.body).unwrap();
        assert_eq!((body["name"].as_str(), body["accessToken"].as_str()), (Some("Steve"), Some("secret")));

        let cancelled = login(Err(AuthError::Cancelled));
        assert_eq!(cancelled.status, 409);
        let body: serde_json::Value = serde_json::from_str(&cancelled.body).unwrap();
        assert_eq!(body["cancelled"], true);
        let offline: serde_json::Value = serde_json::from_str(&login(Err(AuthError::Offline)).body).unwrap();
        assert_eq!(offline["cancelled"], false);
        assert!(offline["error"].as_str().unwrap().contains("internet"));
    }

    #[test]
    fn turns_away_everything_else() {
        assert_eq!(ask("POST", "/modrinth/install?project=../../evil"), Err(400));
        assert_eq!(ask("POST", "/modrinth/install"), Err(400));
        assert_eq!(ask("GET", "/modrinth/install?project=AANobbMI"), Err(405));
        assert_eq!(ask("GET", "/settings"), Err(404));
        let status = |head: String| parse(&head, 5050, TOKEN).unwrap_err().status;
        assert_eq!(status(request("GET", "/modrinth/installed", "127.0.0.1:5050", None)), 401);
        assert_eq!(status(request("GET", "/modrinth/installed", "127.0.0.1:5050", Some("0123456789abcdee"))), 401);
        assert_eq!(status(request("GET", "/modrinth/installed", "127.0.0.1:5050", Some("0123"))), 401);
        // A web page that renamed itself to this address still can't get in.
        assert_eq!(status(request("GET", "/modrinth/installed", "evil.example:5050", Some(TOKEN))), 403);
        assert_eq!(status(request("GET", "/modrinth/installed", "localhost:5050", Some(TOKEN))), 403);
    }

    #[test]
    fn tokens_are_long_and_random() {
        let (a, b) = (token().unwrap(), token().unwrap());
        assert_eq!(a.len(), 64);
        assert!(a.bytes().all(|c| c.is_ascii_hexdigit()));
        assert_ne!(a, b);
    }

    #[tokio::test]
    async fn answers_over_a_real_connection() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(serve(listener, port, TOKEN.into(), |ask| async move { Reply::json(&format!("{ask:?}")) }));
        let send = |head: String| async move {
            let mut stream = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).await.unwrap();
            stream.write_all(head.as_bytes()).await.unwrap();
            let mut answer = String::new();
            stream.read_to_string(&mut answer).await.unwrap();
            answer
        };
        let host = format!("127.0.0.1:{port}");
        let ok = send(request("GET", "/modrinth/installed", &host, Some(TOKEN))).await;
        assert!(ok.starts_with("HTTP/1.1 200 OK\r\n"), "{ok}");
        assert!(ok.contains("Content-Length: 11\r\n") && ok.ends_with("\r\n\r\n\"Installed\""), "{ok}");
        let refused = send(request("GET", "/modrinth/installed", &host, None)).await;
        assert!(refused.starts_with("HTTP/1.1 401 Unauthorized\r\n") && refused.ends_with("{\"error\":\"Unauthorized.\"}"), "{refused}");
    }
}
