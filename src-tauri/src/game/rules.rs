//! The `rules` on libraries and launch arguments: which OS, which architecture, which features.

use std::collections::HashMap;

use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
pub struct Rule {
    pub action: Action,
    #[serde(default)]
    pub os: Option<OsRule>,
    #[serde(default)]
    pub features: Option<HashMap<String, bool>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Action {
    Allow,
    Disallow,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct OsRule {
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub arch: Option<String>,
    #[serde(default)]
    pub version: Option<String>,
}

/// Launch-time switches that rules can test. Everything not listed here (demo mode,
/// custom resolution, the other Quick Play modes) is always off.
#[derive(Debug, Clone, Default)]
pub struct Features {
    pub quick_play_multiplayer: bool,
}

impl Features {
    fn get(&self, name: &str) -> bool {
        match name {
            "is_quick_play_multiplayer" => self.quick_play_multiplayer,
            _ => false,
        }
    }
}

/// The machine the rules are checked against.
#[derive(Debug, Clone)]
pub struct Platform {
    pub os: &'static str,
    pub arch: &'static str,
    pub version: &'static str,
}

impl Platform {
    pub fn current() -> Self {
        let os = match std::env::consts::OS {
            "macos" => "osx",
            other => other,
        };
        // Windows 10 and 11 both report 10.0, which is what Mojang's rules expect.
        let version = if cfg!(windows) { "10.0" } else { "" };
        Self { os, arch: std::env::consts::ARCH, version }
    }
}

/// Mojang's semantics: no rules means allowed; otherwise start disallowed and let every
/// matching rule set the result, so later rules win.
pub fn allowed(rules: Option<&[Rule]>, platform: &Platform, features: &Features) -> bool {
    match rules {
        None | Some([]) => true,
        Some(rules) => rules.iter().fold(false, |allowed, rule| {
            if matches(rule, platform, features) {
                rule.action == Action::Allow
            } else {
                allowed
            }
        }),
    }
}

fn matches(rule: &Rule, platform: &Platform, features: &Features) -> bool {
    if let Some(os) = &rule.os {
        if os.name.as_deref().is_some_and(|name| name != platform.os) {
            return false;
        }
        if os.arch.as_deref().is_some_and(|arch| arch != platform.arch) {
            return false;
        }
        if os.version.as_deref().is_some_and(|pattern| !version_matches(pattern, platform.version)) {
            return false;
        }
    }
    if let Some(wanted) = &rule.features {
        if wanted.iter().any(|(name, value)| features.get(name) != *value) {
            return false;
        }
    }
    true
}

/// OS version rules are regexes like `^10\.`. This handles that simple shape (an optional
/// `^` and escaped dots) and treats anything fancier as "no match".
fn version_matches(pattern: &str, version: &str) -> bool {
    let anchored = pattern.strip_prefix('^');
    let literal = anchored.unwrap_or(pattern).replace("\\.", ".");
    if literal.chars().any(|c| "[]()*+?|$\\{}".contains(c)) {
        return false;
    }
    match anchored {
        Some(_) => version.starts_with(&literal),
        None => version.contains(&literal),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const WINDOWS_X64: Platform = Platform { os: "windows", arch: "x86_64", version: "10.0" };

    fn rules(json: &str) -> Vec<Rule> {
        serde_json::from_str(json).unwrap()
    }

    #[test]
    fn no_rules_means_allowed() {
        let off = Features::default();
        assert!(allowed(None, &WINDOWS_X64, &off));
        assert!(allowed(Some(&[]), &WINDOWS_X64, &off));
    }

    #[test]
    fn os_name_rules() {
        let off = Features::default();
        assert!(allowed(Some(&rules(r#"[{"action":"allow","os":{"name":"windows"}}]"#)), &WINDOWS_X64, &off));
        assert!(!allowed(Some(&rules(r#"[{"action":"allow","os":{"name":"osx"}}]"#)), &WINDOWS_X64, &off));
        assert!(!allowed(Some(&rules(r#"[{"action":"allow","os":{"name":"linux"}}]"#)), &WINDOWS_X64, &off));
    }

    #[test]
    fn later_rules_win() {
        let off = Features::default();
        let r = rules(r#"[{"action":"allow"},{"action":"disallow","os":{"name":"windows"}}]"#);
        assert!(!allowed(Some(&r), &WINDOWS_X64, &off));
        let r = rules(r#"[{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}]"#);
        assert!(allowed(Some(&r), &WINDOWS_X64, &off));
    }

    #[test]
    fn the_32_bit_rule_skips_64_bit_windows() {
        let r = rules(r#"[{"action":"allow","os":{"arch":"x86"}}]"#);
        assert!(!allowed(Some(&r), &WINDOWS_X64, &Features::default()));
    }

    #[test]
    fn feature_rules_follow_quick_play() {
        let r = rules(r#"[{"action":"allow","features":{"is_quick_play_multiplayer":true}}]"#);
        assert!(!allowed(Some(&r), &WINDOWS_X64, &Features::default()));
        assert!(allowed(Some(&r), &WINDOWS_X64, &Features { quick_play_multiplayer: true }));
        let demo = rules(r#"[{"action":"allow","features":{"is_demo_user":true}}]"#);
        assert!(!allowed(Some(&demo), &WINDOWS_X64, &Features { quick_play_multiplayer: true }));
    }

    #[test]
    fn os_version_patterns() {
        assert!(version_matches("^10\\.", "10.0"));
        assert!(!version_matches("^6\\.1", "10.0"));
        assert!(!version_matches("^(10|11)\\.", "10.0"));
    }
}
