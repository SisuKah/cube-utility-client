use serde::Serialize;
use sysinfo::System;

/// Facts about the player's PC that the UI needs, such as how much memory Minecraft may use.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SystemInfo {
    pub total_memory_gb: u64,
}

/// Reads the PC's total memory. On error the UI falls back to a safe default.
#[tauri::command]
pub fn system_info() -> Result<SystemInfo, String> {
    let mut system = System::new().map_err(|e| format!("could not read system information: {e}"))?;
    system.refresh_memory();
    let gib = system.total_memory() as f64 / 1024_f64.powi(3);
    Ok(SystemInfo {
        total_memory_gb: gib.round() as u64,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_total_memory() {
        let info = system_info().expect("system info should be readable");
        assert!(info.total_memory_gb >= 1, "a PC that runs Minecraft has at least 1 GB of memory");
    }

    #[test]
    fn serializes_for_the_ui() {
        let json = serde_json::to_string(&SystemInfo { total_memory_gb: 16 }).unwrap();
        assert_eq!(json, r#"{"totalMemoryGb":16}"#);
    }
}
