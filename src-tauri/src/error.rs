use serde::{Serialize, Serializer};

/// Everything that can go wrong in the Rust core. The UI receives the message text.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("network problem: {0}")]
    Http(#[from] reqwest::Error),
    #[error("couldn't read or write a file: {0}")]
    Io(#[from] std::io::Error),
    #[error("couldn't understand {what}: {source}")]
    Json { what: &'static str, source: serde_json::Error },
    #[error("downloads from {0} aren't allowed")]
    HostNotAllowed(String),
    #[error("{0} was damaged while downloading")]
    Checksum(String),
    #[error("Can't reach the download servers, and the game isn't fully downloaded yet. Check your internet connection and try again.")]
    Offline,
    #[error("{0}")]
    Message(String),
}

pub type Result<T> = std::result::Result<T, Error>;

impl Error {
    /// Failures worth another try: dropped connections and corrupted transfers.
    pub fn is_retryable(&self) -> bool {
        matches!(self, Error::Http(_) | Error::Checksum(_))
    }
}

impl Serialize for Error {
    fn serialize<S: Serializer>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.to_string())
    }
}

/// Parses JSON and names the file in the error message.
pub fn parse_json<T: serde::de::DeserializeOwned>(bytes: &[u8], what: &'static str) -> Result<T> {
    serde_json::from_slice(bytes).map_err(|source| Error::Json { what, source })
}
