use std::path::Path;

use tracing_appender::non_blocking::WorkerGuard;
use tracing_appender::rolling::{Builder, Rotation};
use tracing_subscriber::{filter::LevelFilter, fmt, prelude::*};

/// Keeps the background log writer alive for the whole run.
pub struct LogGuard {
    _guard: Option<WorkerGuard>,
}

/// Launcher logs go to logs/launcher.<date>.log (the last 7 days are kept), and also to
/// the terminal in development builds.
pub fn init(dir: &Path) -> LogGuard {
    let appender = Builder::new()
        .rotation(Rotation::DAILY)
        .filename_prefix("launcher")
        .filename_suffix("log")
        .max_log_files(7)
        .build(dir);
    let (file_layer, guard) = match appender {
        Ok(appender) => {
            let (writer, guard) = tracing_appender::non_blocking(appender);
            (Some(fmt::layer().with_writer(writer).with_ansi(false).with_target(false)), Some(guard))
        }
        Err(_) => (None, None),
    };
    let registry = tracing_subscriber::registry().with(LevelFilter::INFO).with(file_layer);
    #[cfg(debug_assertions)]
    let registry = registry.with(fmt::layer().with_writer(std::io::stderr).with_target(false));
    let _ = registry.try_init();
    LogGuard { _guard: guard }
}
