//! Turning the version file's arguments into a real command line.

use std::collections::HashMap;

use super::rules::{self, Features, Platform};
use super::version::{ArgValue, Argument};

/// Applies each argument's rules and fills in its `${placeholders}`.
pub fn expand(arguments: &[Argument], platform: &Platform, features: &Features, vars: &HashMap<&str, String>) -> Vec<String> {
    let mut out = Vec::new();
    for argument in arguments {
        match argument {
            Argument::Plain(value) => out.push(substitute(value, vars)),
            Argument::Conditional { rules, value } => {
                if rules::allowed(Some(rules.as_slice()), platform, features) {
                    match value {
                        ArgValue::One(value) => out.push(substitute(value, vars)),
                        ArgValue::Many(values) => out.extend(values.iter().map(|v| substitute(v, vars))),
                    }
                }
            }
        }
    }
    out
}

/// Replaces every known `${name}`. Unknown names are left as they are and logged.
pub fn substitute(text: &str, vars: &HashMap<&str, String>) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(start) = rest.find("${") {
        out.push_str(&rest[..start]);
        let after = &rest[start + 2..];
        match after.find('}') {
            Some(end) => {
                let key = &after[..end];
                match vars.get(key) {
                    Some(value) => out.push_str(value),
                    None => {
                        tracing::warn!("unknown launch placeholder ${{{key}}}");
                        out.push_str(&rest[start..start + 2 + end + 1]);
                    }
                }
                rest = &after[end + 1..];
            }
            None => {
                out.push_str(&rest[start..]);
                rest = "";
            }
        }
    }
    out.push_str(rest);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const WINDOWS_X64: Platform = Platform { os: "windows", arch: "x86_64", version: "10.0" };

    fn vars() -> HashMap<&'static str, String> {
        HashMap::from([
            ("natives_directory", "C:/game/natives".to_string()),
            ("auth_player_name", "Steve".to_string()),
            ("quickPlayMultiplayer", "play.cubecraft.net".to_string()),
            ("classpath", "a.jar;b.jar".to_string()),
        ])
    }

    #[test]
    fn fills_placeholders() {
        let v = vars();
        assert_eq!(substitute("-Djava.library.path=${natives_directory}/java", &v), "-Djava.library.path=C:/game/natives/java");
        assert_eq!(substitute("${auth_player_name}:${auth_player_name}", &v), "Steve:Steve");
        assert_eq!(substitute("no placeholders", &v), "no placeholders");
        assert_eq!(substitute("${unknown_thing}", &v), "${unknown_thing}");
        assert_eq!(substitute("broken ${end", &v), "broken ${end");
    }

    // The real 26.3 arguments that depend on rules.
    fn real_arguments() -> (Vec<Argument>, Vec<Argument>) {
        let game: Vec<Argument> = serde_json::from_str(
            r#"["--username", "${auth_player_name}",
                {"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"},
                {"rules":[{"action":"allow","features":{"has_custom_resolution":true}}],"value":["--width","${resolution_width}","--height","${resolution_height}"]},
                {"rules":[{"action":"allow","features":{"is_quick_play_multiplayer":true}}],"value":["--quickPlayMultiplayer","${quickPlayMultiplayer}"]}]"#,
        )
        .unwrap();
        let jvm: Vec<Argument> = serde_json::from_str(
            r#"[{"rules":[{"action":"allow","os":{"name":"osx"}}],"value":["-XstartOnFirstThread"]},
                {"rules":[{"action":"allow","os":{"name":"windows"}}],"value":"-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump"},
                {"rules":[{"action":"allow","os":{"arch":"x86"}}],"value":"-Xss1M"},
                "-Djava.library.path=${natives_directory}/java", "-cp", "${classpath}"]"#,
        )
        .unwrap();
        (game, jvm)
    }

    #[test]
    fn quick_play_only_when_joining_cubecraft() {
        let (game, _) = real_arguments();
        let title = expand(&game, &WINDOWS_X64, &Features::default(), &vars());
        assert_eq!(title, ["--username", "Steve"]);
        let cubecraft = expand(&game, &WINDOWS_X64, &Features { quick_play_multiplayer: true }, &vars());
        assert_eq!(cubecraft, ["--username", "Steve", "--quickPlayMultiplayer", "play.cubecraft.net"]);
    }

    #[test]
    fn jvm_arguments_for_64_bit_windows() {
        let (_, jvm) = real_arguments();
        let out = expand(&jvm, &WINDOWS_X64, &Features::default(), &vars());
        assert_eq!(
            out,
            [
                "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump",
                "-Djava.library.path=C:/game/natives/java",
                "-cp",
                "a.jar;b.jar"
            ]
        );
    }
}
