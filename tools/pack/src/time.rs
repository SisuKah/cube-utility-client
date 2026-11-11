//! Dates without a date library: pack versions look like 2026.11.10-1.

use std::time::{SystemTime, UNIX_EPOCH};

fn now_parts() -> (i64, u32, u32, u64) {
    let secs = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    let (y, m, d) = civil_from_days((secs / 86_400) as i64);
    (y, m, d, secs % 86_400)
}

/// Howard Hinnant's days-to-date algorithm.
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (yoe + era * 400 + i64::from(m <= 2), m, d)
}

pub fn now_rfc3339() -> String {
    let (y, m, d, secs) = now_parts();
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z", secs / 3600, secs / 60 % 60, secs % 60)
}

fn today() -> String {
    let (y, m, d, _) = now_parts();
    format!("{y:04}.{m:02}.{d:02}")
}

/// Today's date plus a counter that goes up when there's more than one release in a day.
pub fn next_pack_version(previous: Option<&str>) -> String {
    next_after(previous, &today())
}

fn next_after(previous: Option<&str>, today: &str) -> String {
    let count = previous
        .and_then(|p| p.split_once('-'))
        .filter(|(date, _)| *date == today)
        .and_then(|(_, n)| n.parse::<u32>().ok())
        .map_or(1, |n| n + 1);
    format!("{today}-{count}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dates() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(civil_from_days(20_767), (2026, 11, 10));
        assert_eq!(civil_from_days(11_016), (2000, 2, 29));
    }

    #[test]
    fn pack_versions_count_up_within_a_day() {
        assert_eq!(next_after(None, "2026.11.10"), "2026.11.10-1");
        assert_eq!(next_after(Some("2026.11.10-1"), "2026.11.10"), "2026.11.10-2");
        assert_eq!(next_after(Some("2026.11.09-4"), "2026.11.10"), "2026.11.10-1");
    }
}
