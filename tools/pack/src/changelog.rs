//! pack/changelog.md: patch notes, newest first, one `## Title` section per release.

/// The newest section: its title and body.
pub fn latest_notes(text: &str) -> Option<(String, String)> {
    let mut lines = text.lines().skip_while(|line| !line.starts_with("## "));
    let title = lines.next()?.trim_start_matches("## ").trim().to_string();
    let body: Vec<&str> = lines.take_while(|line| !line.starts_with("## ")).collect();
    Some((title, body.join("\n").trim().to_string()))
}

/// Adds a new section above the others, keeping any heading at the top of the file.
pub fn prepend(text: &str, title: &str, body: &str) -> String {
    let section = format!("## {title}\n\n{}\n", body.trim());
    match text.find("\n## ") {
        Some(at) => format!("{}\n\n{section}\n{}", text[..at].trim_end(), &text[at + 1..]),
        None if text.trim().is_empty() => format!("# Changelog\n\n{section}"),
        None => format!("{}\n\n{section}", text.trim_end()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_newest_section() {
        let text = "# Changelog\n\n## Cube Utility 1.7.0\n\n- Faster\n- Fixed\n\n## Older\n\n- Old\n";
        assert_eq!(latest_notes(text), Some(("Cube Utility 1.7.0".into(), "- Faster\n- Fixed".into())));
        assert_eq!(latest_notes("# Changelog\n"), None);
    }

    #[test]
    fn prepends_above_older_sections() {
        let text = "# Changelog\n\n## Older\n\n- Old\n";
        let updated = prepend(text, "Cube Utility 1.8.0", "- New");
        assert_eq!(updated, "# Changelog\n\n## Cube Utility 1.8.0\n\n- New\n\n## Older\n\n- Old\n");
        assert_eq!(latest_notes(&updated).unwrap().0, "Cube Utility 1.8.0");
        assert!(prepend("", "First", "- Hi").starts_with("# Changelog\n\n## First"));
    }
}
