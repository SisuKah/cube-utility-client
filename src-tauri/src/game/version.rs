//! Mojang's version file, and Fabric's profile, which uses the same format.

use std::collections::HashSet;

use serde::Deserialize;

use super::rules::Rule;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionJson {
    pub id: String,
    #[serde(default)]
    pub inherits_from: Option<String>,
    pub main_class: String,
    #[serde(default)]
    pub arguments: Arguments,
    #[serde(default)]
    pub libraries: Vec<Library>,
    #[serde(default)]
    pub asset_index: Option<AssetIndexRef>,
    #[serde(default)]
    pub downloads: Option<VersionDownloads>,
    #[serde(default)]
    pub java_version: Option<JavaVersion>,
    #[serde(rename = "type", default)]
    pub kind: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Arguments {
    #[serde(default)]
    pub game: Vec<Argument>,
    #[serde(default)]
    pub jvm: Vec<Argument>,
}

/// An argument is either plain text or a value guarded by rules.
#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum Argument {
    Plain(String),
    Conditional { rules: Vec<Rule>, value: ArgValue },
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum ArgValue {
    One(String),
    Many(Vec<String>),
}

#[derive(Debug, Clone, Deserialize)]
pub struct Library {
    pub name: String,
    #[serde(default)]
    pub downloads: Option<LibraryDownloads>,
    /// A Maven repository base URL (Fabric's libraries use this instead of `downloads`).
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub rules: Option<Vec<Rule>>,
    /// The pre-1.19 natives format. 26.x doesn't use it; it's only detected and logged.
    #[serde(default)]
    pub natives: Option<serde_json::Value>,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LibraryDownloads {
    #[serde(default)]
    pub artifact: Option<Artifact>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Artifact {
    #[serde(default)]
    pub path: Option<String>,
    pub url: String,
    pub sha1: String,
    pub size: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AssetIndexRef {
    pub id: String,
    pub sha1: String,
    pub url: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct VersionDownloads {
    pub client: Artifact,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaVersion {
    pub component: String,
    pub major_version: u32,
}

/// Maven coordinates: `group:artifact:version[:classifier][@extension]`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Coordinates {
    pub group: String,
    pub artifact: String,
    pub version: String,
    pub classifier: Option<String>,
    pub extension: String,
}

impl Coordinates {
    pub fn parse(name: &str) -> Option<Self> {
        let (main, extension) = match name.split_once('@') {
            Some((main, extension)) => (main, extension.to_string()),
            None => (name, "jar".to_string()),
        };
        let mut parts = main.split(':');
        let group = parts.next()?.to_string();
        let artifact = parts.next()?.to_string();
        let version = parts.next()?.to_string();
        let classifier = parts.next().map(str::to_string);
        if parts.next().is_some() || group.is_empty() || artifact.is_empty() || version.is_empty() {
            return None;
        }
        Some(Self { group, artifact, version, classifier, extension })
    }

    /// The file's path inside a Maven repository, which is also its path under `libraries/`.
    pub fn path(&self) -> String {
        let file = match &self.classifier {
            Some(classifier) => format!("{}-{}-{}.{}", self.artifact, self.version, classifier, self.extension),
            None => format!("{}-{}.{}", self.artifact, self.version, self.extension),
        };
        format!("{}/{}/{}/{}", self.group.replace('.', "/"), self.artifact, self.version, file)
    }

    /// The library's identity without its version, used to spot duplicates.
    pub fn key(&self) -> String {
        match &self.classifier {
            Some(classifier) => format!("{}:{}:{}", self.group, self.artifact, classifier),
            None => format!("{}:{}", self.group, self.artifact),
        }
    }
}

fn library_key(library: &Library) -> String {
    Coordinates::parse(&library.name).map(|c| c.key()).unwrap_or_else(|| library.name.clone())
}

impl VersionJson {
    /// Merges this profile (Fabric's) onto the version it inherits from (vanilla). The
    /// child's libraries come first, and a parent library with the same identity is dropped.
    pub fn inherit(self, parent: VersionJson) -> VersionJson {
        let child_keys: HashSet<String> = self.libraries.iter().map(library_key).collect();
        let mut libraries = self.libraries;
        libraries.extend(parent.libraries.into_iter().filter(|lib| !child_keys.contains(&library_key(lib))));

        let mut jvm = parent.arguments.jvm;
        jvm.extend(self.arguments.jvm);
        let mut game = parent.arguments.game;
        game.extend(self.arguments.game);

        VersionJson {
            id: self.id,
            inherits_from: None,
            main_class: self.main_class,
            arguments: Arguments { game, jvm },
            libraries,
            asset_index: self.asset_index.or(parent.asset_index),
            downloads: self.downloads.or(parent.downloads),
            java_version: self.java_version.or(parent.java_version),
            kind: self.kind.or(parent.kind),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maven_paths() {
        let plain = Coordinates::parse("net.fabricmc:fabric-loader:0.20.1").unwrap();
        assert_eq!(plain.path(), "net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar");
        assert_eq!(plain.key(), "net.fabricmc:fabric-loader");

        let natives = Coordinates::parse("org.lwjgl:lwjgl:3.4.3:natives-windows").unwrap();
        assert_eq!(natives.path(), "org/lwjgl/lwjgl/3.4.3/lwjgl-3.4.3-natives-windows.jar");
        assert_eq!(natives.key(), "org.lwjgl:lwjgl:natives-windows");

        let zip = Coordinates::parse("com.example:thing:1.0@zip").unwrap();
        assert_eq!(zip.path(), "com/example/thing/1.0/thing-1.0.zip");

        assert!(Coordinates::parse("not-a-coordinate").is_none());
        assert!(Coordinates::parse("a:b:c:d:e").is_none());
    }

    // A trimmed copy of the real 26.3 version file and Fabric 0.20.1 profile.
    const VANILLA: &str = r#"{
        "id": "26.3", "type": "release", "mainClass": "net.minecraft.client.main.Main",
        "assetIndex": {"id": "34", "sha1": "e99f9973047785b5a9d52eaf72020b97545053c9", "size": 597235, "totalSize": 487358754, "url": "https://piston-meta.mojang.com/v1/packages/e99f/34.json"},
        "downloads": {"client": {"sha1": "e877b6a07acd633fb3bb475002175cec036e7b87", "size": 41483720, "url": "https://piston-data.mojang.com/v1/objects/e877/client.jar"}},
        "javaVersion": {"component": "java-runtime-epsilon", "majorVersion": 25},
        "arguments": {
            "game": ["--username", "${auth_player_name}",
                {"rules": [{"action": "allow", "features": {"is_quick_play_multiplayer": true}}], "value": ["--quickPlayMultiplayer", "${quickPlayMultiplayer}"]}],
            "jvm": [{"rules": [{"action": "allow", "os": {"name": "windows"}}], "value": "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump"},
                "-cp", "${classpath}"]
        },
        "libraries": [
            {"name": "org.ow2.asm:asm:9.6", "downloads": {"artifact": {"path": "org/ow2/asm/asm/9.6/asm-9.6.jar", "sha1": "aa", "size": 1, "url": "https://libraries.minecraft.net/org/ow2/asm/asm/9.6/asm-9.6.jar"}}},
            {"name": "org.lwjgl:lwjgl:3.4.3:natives-windows", "downloads": {"artifact": {"path": "org/lwjgl/lwjgl/3.4.3/lwjgl-3.4.3-natives-windows.jar", "sha1": "bb", "size": 2, "url": "https://libraries.minecraft.net/x.jar"}}, "rules": [{"action": "allow", "os": {"name": "windows"}}]}
        ]
    }"#;

    const FABRIC: &str = r#"{
        "id": "fabric-loader-0.20.1-26.3", "inheritsFrom": "26.3", "type": "release",
        "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
        "arguments": {"game": [], "jvm": ["-DFabricMcEmu= net.minecraft.client.main.Main "]},
        "libraries": [
            {"name": "org.ow2.asm:asm:9.10.1", "url": "https://maven.fabricmc.net/", "sha1": "ada2141c0cc52ee8f5c48cd5fa4ce0e794f22236", "size": 126151},
            {"name": "net.fabricmc:fabric-loader:0.20.1", "url": "https://maven.fabricmc.net/"}
        ]
    }"#;

    #[test]
    fn parses_the_real_formats() {
        let vanilla: VersionJson = serde_json::from_str(VANILLA).unwrap();
        assert_eq!(vanilla.arguments.game.len(), 3);
        assert!(matches!(vanilla.arguments.game[2], Argument::Conditional { .. }));
        assert_eq!(vanilla.java_version.unwrap().major_version, 25);
        let fabric: VersionJson = serde_json::from_str(FABRIC).unwrap();
        assert_eq!(fabric.inherits_from.as_deref(), Some("26.3"));
        assert!(fabric.libraries[1].sha1.is_none());
    }

    #[test]
    fn fabric_merges_onto_vanilla() {
        let vanilla: VersionJson = serde_json::from_str(VANILLA).unwrap();
        let fabric: VersionJson = serde_json::from_str(FABRIC).unwrap();
        let merged = fabric.inherit(vanilla);

        assert_eq!(merged.id, "fabric-loader-0.20.1-26.3");
        assert_eq!(merged.main_class, "net.fabricmc.loader.impl.launch.knot.KnotClient");
        // Fabric's ASM wins over vanilla's; the natives library stays.
        let names: Vec<&str> = merged.libraries.iter().map(|l| l.name.as_str()).collect();
        assert_eq!(names, ["org.ow2.asm:asm:9.10.1", "net.fabricmc:fabric-loader:0.20.1", "org.lwjgl:lwjgl:3.4.3:natives-windows"]);
        // Vanilla's arguments first, then Fabric's.
        assert_eq!(merged.arguments.jvm.len(), 4);
        assert!(matches!(&merged.arguments.jvm[3], Argument::Plain(a) if a.starts_with("-DFabricMcEmu")));
        // Things only vanilla has are kept.
        assert_eq!(merged.asset_index.unwrap().id, "34");
        assert!(merged.downloads.is_some());
    }
}
