//! `pack build`: turns pack/pack.toml into a checked, signed manifest in pack/out/<channel>/.
//!
//! 1. Resolve every entry: my jars from pack/jars, the rest from Modrinth (exact versions for
//!    this Minecraft version and loader).
//! 2. Add what's missing: Modrinth's required dependencies, then anything a jar's
//!    fabric.mod.json `depends` on that no jar in the pack provides.
//! 3. Check that every jar accepts this Minecraft, loader and Java version.
//! 4. Work out when each library is needed: always, or only while some optional mods are on.
//! 5. Copy my jars and the configs next to the manifest, then sign and write it.

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use cuc_manifest::{ConfigFile, Group, JavaRequirement, Loader, Manifest, ManifestFile, ModrinthRef, Notes, SCHEMA};
use sha2::{Digest, Sha512};

use crate::jar::{self, ModInfo};
use crate::modrinth::{self, Modrinth, Project, Version};
use crate::packfile::{ModEntry, PackFile, Toggle};
use crate::{changelog, pack_dir, signing_key, time, Args, Result};

/// Mod ids every Fabric game has without a jar for them.
const BUILT_IN: &[&str] = &["minecraft", "java", "fabricloader", "fabric-loader", "mixinextras"];

/// Fabric mod ids whose Modrinth project has a different slug.
const MODRINTH_SLUGS: &[(&str, &str)] = &[("fabric", "fabric-api"), ("cloth-config2", "cloth-config"), ("yet_another_config_lib_v3", "yacl")];

/// Where launchers download my jars and the configs from when testing locally (`pack serve`).
pub const LOCAL_FILES_URL: &str = "http://127.0.0.1:8799/{channel}/files/{packVersion}";

/// When a mod has to be installed.
#[derive(Debug, Clone, PartialEq, Eq)]
enum Need {
    Always,
    /// While any of these optional mods is switched on.
    When(BTreeSet<String>),
}

impl Need {
    /// Widens this to also cover `other`. Returns whether anything changed.
    fn absorb(&mut self, other: &Need) -> bool {
        match (&mut *self, other) {
            (Need::Always, _) => false,
            (_, Need::Always) => {
                *self = Need::Always;
                true
            }
            (Need::When(mine), Need::When(theirs)) => {
                let before = mine.len();
                mine.extend(theirs.iter().cloned());
                mine.len() != before
            }
        }
    }

    /// Whether this is installed whenever `other` is.
    fn covers(&self, other: &Need) -> bool {
        match (self, other) {
            (Need::Always, _) => true,
            (Need::When(_), Need::Always) => false,
            (Need::When(mine), Need::When(theirs)) => theirs.is_subset(mine),
        }
    }
}

enum Source {
    Local(PathBuf),
    Modrinth { url: String, project_id: String, version_id: String },
}

struct Mod {
    id: String,
    name: String,
    version: String,
    description: Option<String>,
    group: Group,
    /// The jar's name in the game's mods folder.
    file_name: String,
    sha512: String,
    size: u64,
    source: Source,
    /// Listed in pack.toml, as opposed to added because something needs it.
    explicit: bool,
    /// Players can switch it on or off (`optional = true`).
    toggle: bool,
    default_on: bool,
    /// A Modrinth entry with `version = "latest"`.
    follows_latest: bool,
    /// The Modrinth version's changelog.
    changelog: Option<String>,
    /// Modrinth projects this version says it needs, and says it doesn't work with.
    modrinth_needs: Vec<String>,
    modrinth_breaks: Vec<String>,
    /// Whether the jar has been read (Modrinth jars are downloaded first).
    read: bool,
    info: Option<ModInfo>,
    need: Need,
}

struct Builder<'a> {
    pack: &'a PackFile,
    dir: PathBuf,
    cache: PathBuf,
    modrinth: Modrinth,
    mods: Vec<Mod>,
    /// (a, b): mod a can't run without mod b.
    needs: BTreeSet<(usize, usize)>,
}

pub fn run(root: &Path, args: &Args) -> Result<()> {
    let dir = pack_dir(root);
    let text = fs::read_to_string(dir.join("pack.toml")).map_err(|e| format!("can't read pack/pack.toml: {e}"))?;
    let pack: PackFile = toml::from_str(&text).map_err(|e| format!("pack/pack.toml: {e}"))?;
    let channel = args.flag("channel").filter(|c| !c.is_empty()).unwrap_or(&pack.channel).to_string();
    let out = dir.join("out").join(&channel);
    let previous = read_manifest(&out.join("manifest.json"))?;
    // Fail before any downloading when there's no key to sign with.
    let secret = signing_key(root)?;

    println!(
        "Resolving {} mods for Minecraft {} + {} {}",
        pack.mods.len(),
        pack.minecraft,
        pack.loader.kind,
        pack.loader.version
    );
    let mut builder = Builder::new(&pack, &dir)?;
    for entry in &pack.mods {
        builder.add_entry(entry)?;
    }
    builder.resolve_dependencies()?;
    builder.check_versions()?;
    builder.check_incompatible()?;
    builder.work_out_needs()?;

    let serial = match args.flag("serial") {
        Some(serial) => serial.parse().map_err(|_| "--serial needs a whole number")?,
        None => previous.as_ref().map_or(1, |p| p.serial + 1),
    };
    let pack_version = time::next_pack_version(previous.as_ref().map(|p| p.pack_version.as_str()));
    let files_url = args
        .flag("files-url")
        .filter(|u| !u.is_empty())
        .map(str::to_string)
        .or_else(|| std::env::var("PACK_FILES_URL").ok().filter(|u| !u.is_empty()))
        .unwrap_or_else(|| LOCAL_FILES_URL.to_string())
        .replace("{channel}", &channel)
        .replace("{packVersion}", &pack_version)
        .trim_end_matches('/')
        .to_string();

    let mut uploads = Vec::new();
    let files = builder.manifest_files(&files_url, &mut uploads);
    let configs = builder.config_files(&files_url, &mut uploads)?;
    let mut assets = BTreeSet::new();
    if let Some((asset, _)) = uploads.iter().find(|(asset, _)| !assets.insert(asset.to_ascii_lowercase())) {
        return Err(format!("two files would be uploaded as '{asset}'; rename one of them").into());
    }

    let mut manifest = Manifest {
        schema: SCHEMA,
        serial,
        pack_version: pack_version.clone(),
        channel: channel.clone(),
        released_at: time::now_rfc3339(),
        min_launcher_version: pack.min_launcher_version.clone(),
        minecraft: pack.minecraft.clone(),
        loader: Loader { kind: pack.loader.kind.clone(), version: pack.loader.version.clone() },
        java: pack.java.as_ref().map(|j| JavaRequirement { component: j.component.clone(), major: j.major }),
        quick_play: pack.quick_play.clone(),
        allow_extra_mods: pack.allow_extra_mods,
        notes: None,
        files,
        configs,
    };
    manifest.validate()?;

    if let Some(previous) = &previous {
        if same_content(previous, &manifest) && !args.flags.contains_key("force") {
            println!("No changes since {} (serial {}), so nothing was published.", previous.pack_version, previous.serial);
            return Ok(());
        }
    }
    manifest.notes = release_notes(&dir, previous.as_ref(), &read_history(&out), &manifest, &builder.mods);

    // My jars and the configs go next to the manifest, in a folder per release, the same way
    // they'll be attached to one GitHub release per pack version.
    let release_dir = out.join("files").join(&pack_version);
    fs::create_dir_all(&release_dir)?;
    for (asset, source) in &uploads {
        fs::copy(source, release_dir.join(asset))?;
    }
    write_signed_with(&out, &manifest, &secret)?;
    print_summary(&pack.name, &manifest, &out, &files_url);
    warn_if_app_key_differs(root, &secret);
    Ok(())
}

/// Signs the manifest and writes it, its signature and a copy in history/.
pub fn write_signed(root: &Path, out: &Path, manifest: &Manifest) -> Result<()> {
    write_signed_with(out, manifest, &signing_key(root)?)
}

fn write_signed_with(out: &Path, manifest: &Manifest, secret: &str) -> Result<()> {
    manifest.validate()?;
    let bytes = manifest.to_bytes()?;
    let signature = cuc_manifest::sign(&bytes, secret)?;
    cuc_manifest::verify(&bytes, &signature, &cuc_manifest::public_key(secret)?)?;
    fs::create_dir_all(out.join("history"))?;
    write_atomic(&out.join("history").join(format!("{}.json", manifest.pack_version)), &bytes)?;
    write_atomic(&out.join("manifest.json"), &bytes)?;
    write_atomic(&out.join("manifest.json.sig"), signature.as_bytes())?;
    Ok(())
}

fn write_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    let part = path.with_extension("part");
    fs::write(&part, bytes)?;
    fs::rename(&part, path)?;
    Ok(())
}

fn read_manifest(path: &Path) -> Result<Option<Manifest>> {
    match fs::read(path) {
        Ok(bytes) => Ok(Some(Manifest::parse(&bytes).map_err(|e| format!("{} is broken: {e}", path.display()))?)),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e.into()),
    }
}

fn read_history(out: &Path) -> Vec<Manifest> {
    let Ok(entries) = fs::read_dir(out.join("history")) else { return Vec::new() };
    entries
        .flatten()
        .filter_map(|entry| fs::read(entry.path()).ok())
        .filter_map(|bytes| Manifest::parse(&bytes).ok())
        .collect()
}

impl<'a> Builder<'a> {
    fn new(pack: &'a PackFile, dir: &Path) -> Result<Self> {
        Ok(Self {
            pack,
            dir: dir.to_path_buf(),
            cache: dir.join(".cache"),
            modrinth: Modrinth::new()?,
            mods: Vec::new(),
            needs: BTreeSet::new(),
        })
    }

    fn add_entry(&mut self, entry: &ModEntry) -> Result<()> {
        if entry.default_state.is_some() && !entry.optional {
            return Err(format!("{}: `default` only applies to optional mods", entry.label()).into());
        }
        let mut m = match (&entry.file, &entry.modrinth) {
            (Some(file), None) => self.local_mod(file)?,
            (None, Some(slug)) => {
                let version = entry
                    .version
                    .as_deref()
                    .ok_or_else(|| format!("{slug}: add version = \"latest\" or an exact Modrinth version number"))?;
                self.modrinth_mod(slug, version)?
            }
            _ => return Err(format!("{}: give either `file` or `modrinth`", entry.label()).into()),
        };
        if let Some(id) = &entry.id {
            m.id = id.clone();
        }
        if let Some(name) = &entry.name {
            m.name = name.clone();
        }
        if entry.description.is_some() {
            m.description = entry.description.clone();
        }
        if let Some(other) = self.mods.iter().find(|other| other.id == m.id || same_project(other, &m)) {
            return Err(format!("{} is in pack.toml twice (also as '{}')", entry.label(), other.id).into());
        }
        m.group = entry.group;
        m.explicit = true;
        m.toggle = entry.optional;
        m.default_on = entry.default_state != Some(Toggle::Off);
        m.need = if entry.optional { Need::When(BTreeSet::from([m.id.clone()])) } else { Need::Always };
        self.mods.push(m);
        Ok(())
    }

    fn local_mod(&self, file: &str) -> Result<Mod> {
        let path = self.dir.join(file);
        let bytes = fs::read(&path).map_err(|e| format!("can't read pack/{file}: {e}"))?;
        let info = jar::read_jar_bytes(&bytes).map_err(|e| format!("pack/{file}: {e}"))?;
        let file_name = Path::new(file)
            .file_name()
            .and_then(|n| n.to_str())
            .ok_or_else(|| format!("pack/{file}: that isn't a file name"))?
            .to_string();
        Ok(Mod {
            id: info.id.clone(),
            name: info.name.clone().unwrap_or_else(|| info.id.clone()),
            version: info.version.clone(),
            description: info.description.clone().filter(|d| !d.trim().is_empty()),
            group: Group::Own,
            file_name,
            sha512: sha512_hex(&bytes),
            size: bytes.len() as u64,
            source: Source::Local(path),
            explicit: true,
            toggle: false,
            default_on: true,
            follows_latest: false,
            changelog: None,
            modrinth_needs: Vec::new(),
            modrinth_breaks: Vec::new(),
            read: true,
            info: Some(info),
            need: Need::Always,
        })
    }

    fn modrinth_mod(&self, slug: &str, wanted: &str) -> Result<Mod> {
        let project = self.modrinth.project(slug)?.ok_or_else(|| format!("Modrinth has no project '{slug}'"))?;
        let versions = self.versions(&project)?;
        let version = modrinth::choose(&versions, wanted).ok_or_else(|| {
            let newest = match versions.first() {
                Some(v) => format!(" The newest one is {}.", v.version_number),
                None => " It has no versions for it at all.".to_string(),
            };
            format!("{} has no version '{wanted}' for {}.{newest}", project.title, self.target())
        })?;
        self.mod_from_version(&project, version, wanted == "latest")
    }

    fn versions(&self, project: &Project) -> Result<Vec<Version>> {
        self.modrinth.versions(&project.id, &self.pack.minecraft, &self.pack.loader.kind)
    }

    fn target(&self) -> String {
        format!("Minecraft {} + {}", self.pack.minecraft, self.pack.loader.kind)
    }

    fn mod_from_version(&self, project: &Project, version: &Version, follows_latest: bool) -> Result<Mod> {
        let file = version
            .primary_file()
            .ok_or_else(|| format!("{} {} has no files", project.title, version.version_number))?;
        if !file.url.starts_with("https://cdn.modrinth.com/") {
            return Err(format!("{}: launchers only download from cdn.modrinth.com, not {}", project.title, file.url).into());
        }
        let sha512 = file.hashes.sha512.to_ascii_lowercase();
        if sha512.len() != 128 {
            return Err(format!("{}: Modrinth gave no SHA-512 hash for {}", project.title, file.filename).into());
        }
        let mut needs = Vec::new();
        let mut breaks = Vec::new();
        for dependency in &version.dependencies {
            let project_id = match (&dependency.project_id, &dependency.version_id) {
                (Some(project_id), _) => project_id.clone(),
                (None, Some(version_id)) => match self.modrinth.version(version_id)? {
                    Some(v) => v.project_id,
                    None => continue,
                },
                (None, None) => continue,
            };
            match dependency.dependency_type.as_str() {
                "required" => needs.push(project_id),
                "incompatible" => breaks.push(project_id),
                _ => {}
            }
        }
        Ok(Mod {
            id: project.slug.clone(),
            name: project.title.clone(),
            version: version.version_number.clone(),
            description: Some(project.description.clone()).filter(|d| !d.trim().is_empty()),
            group: Group::Library,
            file_name: file.filename.clone(),
            sha512,
            size: file.size,
            source: Source::Modrinth { url: file.url.clone(), project_id: project.id.clone(), version_id: version.id.clone() },
            explicit: false,
            toggle: false,
            default_on: false,
            follows_latest,
            changelog: version.changelog.clone().filter(|c| !c.trim().is_empty()),
            modrinth_needs: needs,
            modrinth_breaks: breaks,
            read: false,
            info: None,
            need: Need::When(BTreeSet::new()),
        })
    }

    fn find_project(&self, project_id: &str) -> Option<usize> {
        self.mods
            .iter()
            .position(|m| matches!(&m.source, Source::Modrinth { project_id: p, .. } if p == project_id))
    }

    /// Adds the newest version of a Modrinth project as a library that `needer` can't run
    /// without, unless the project is in the pack already.
    fn add_library(&mut self, id_or_slug: &str, needer: usize) -> Result<Lookup> {
        let Some(project) = self.modrinth.project(id_or_slug)? else { return Ok(Lookup::NoProject) };
        if let Some(existing) = self.find_project(&project.id) {
            return Ok(Lookup::Found(existing));
        }
        let versions = self.versions(&project)?;
        let Some(version) = modrinth::choose(&versions, "latest") else { return Ok(Lookup::NoVersion(project.title)) };
        let mut library = self.mod_from_version(&project, version, false)?;
        if self.mods.iter().any(|m| m.id == library.id) {
            library.id = project.id.clone();
        }
        println!("  + {} {} (needed by {})", library.name, library.version, self.mods[needer].name);
        self.mods.push(library);
        Ok(Lookup::Found(self.mods.len() - 1))
    }

    fn resolve_dependencies(&mut self) -> Result<()> {
        loop {
            self.add_modrinth_dependencies()?;
            self.read_jars()?;
            if !self.add_missing_dependencies()? {
                return Ok(());
            }
        }
    }

    /// Adds every project Modrinth lists as a required dependency, all the way down.
    ///
    /// One Modrinth version often covers several loaders, and then its list also names
    /// libraries for the others (like KotlinLangForge for NeoForge). So a listed project without
    /// a version for this loader is skipped here; the jar check after this decides whether a
    /// mod really needs something that's missing.
    fn add_modrinth_dependencies(&mut self) -> Result<()> {
        let mut i = 0;
        while i < self.mods.len() {
            for project_id in std::mem::take(&mut self.mods[i].modrinth_needs) {
                let target = match self.find_project(&project_id) {
                    Some(j) => j,
                    None => match self.add_library(&project_id, i)? {
                        Lookup::Found(j) => j,
                        Lookup::NoProject => continue,
                        Lookup::NoVersion(title) => {
                            println!("  - skipped {title}: {} lists it on Modrinth, but it has no version for {}", self.mods[i].name, self.target());
                            continue;
                        }
                    },
                };
                if target != i {
                    self.needs.insert((i, target));
                }
            }
            i += 1;
        }
        Ok(())
    }

    /// Downloads the Modrinth jars that haven't been read yet (into pack/.cache) and reads them.
    fn read_jars(&mut self) -> Result<()> {
        let jobs: Vec<Job> = self
            .mods
            .iter()
            .enumerate()
            .filter(|(_, m)| !m.read)
            .filter_map(|(index, m)| match &m.source {
                Source::Modrinth { url, .. } => Some(Job { index, url: url.clone(), sha512: m.sha512.clone(), size: m.size }),
                Source::Local(_) => None,
            })
            .collect();
        for (index, result) in self.fetch_all(jobs) {
            let m = &mut self.mods[index];
            m.read = true;
            let path = result.map_err(|e| format!("{}: {e}", m.name))?;
            match jar::read_jar(&path) {
                Ok(info) => m.info = Some(info),
                Err(e) => println!("  warning: skipping the checks for {} ({e})", m.name),
            }
        }
        Ok(())
    }

    fn fetch_all(&self, jobs: Vec<Job>) -> Vec<(usize, std::result::Result<PathBuf, String>)> {
        let workers = jobs.len().min(6);
        let queue = Mutex::new(jobs.into_iter());
        let results = Mutex::new(Vec::new());
        std::thread::scope(|scope| {
            for _ in 0..workers {
                scope.spawn(|| loop {
                    let Some(job) = queue.lock().unwrap().next() else { break };
                    let result = self.fetch(&job).map_err(|e| e.to_string());
                    results.lock().unwrap().push((job.index, result));
                });
            }
        });
        results.into_inner().unwrap()
    }

    fn fetch(&self, job: &Job) -> Result<PathBuf> {
        let path = self.cache.join(&job.sha512[..2]).join(&job.sha512);
        if let Ok(bytes) = fs::read(&path) {
            if bytes.len() as u64 == job.size && sha512_hex(&bytes) == job.sha512 {
                return Ok(path);
            }
        }
        let bytes = self.modrinth.download(&job.url)?;
        if bytes.len() as u64 != job.size || sha512_hex(&bytes) != job.sha512 {
            return Err(format!("the download from {} doesn't match Modrinth's SHA-512", job.url).into());
        }
        fs::create_dir_all(path.parent().expect("cache paths have a parent"))?;
        write_atomic(&path, &bytes)?;
        Ok(path)
    }

    /// Mod id → the mods that provide it.
    fn providers(&self) -> HashMap<String, Vec<usize>> {
        let mut providers: HashMap<String, Vec<usize>> = HashMap::new();
        for (index, m) in self.mods.iter().enumerate() {
            for id in m.info.iter().flat_map(|info| &info.provides) {
                providers.entry(id.clone()).or_default().push(index);
            }
        }
        providers
    }

    /// Prefers mods that are always installed, then the mod whose own id it is, then mods
    /// listed in pack.toml.
    fn best_provider(&self, id: &str, providers: &[usize]) -> usize {
        *providers
            .iter()
            .min_by_key(|&&j| {
                let m = &self.mods[j];
                let own_id = m.info.as_ref().is_some_and(|info| info.id == id);
                (m.toggle, !own_id, !m.explicit)
            })
            .expect("a provider list is never empty")
    }

    /// Checks every jar's fabric.mod.json `depends` against what the pack provides, and adds
    /// what's missing from Modrinth. Returns whether anything was added.
    fn add_missing_dependencies(&mut self) -> Result<bool> {
        let providers = self.providers();
        let mut found = Vec::new();
        let mut missing: BTreeMap<String, Vec<usize>> = BTreeMap::new();
        for (index, m) in self.mods.iter().enumerate() {
            let Some(info) = &m.info else { continue };
            for id in info.depends.keys() {
                if BUILT_IN.contains(&id.as_str()) || info.provides.contains(id) {
                    continue;
                }
                match providers.get(id) {
                    Some(list) => found.push((index, self.best_provider(id, list))),
                    None => missing.entry(id.clone()).or_default().push(index),
                }
            }
        }
        self.needs.extend(found.into_iter().filter(|(a, b)| a != b));
        if missing.is_empty() {
            return Ok(false);
        }

        let mut errors = Vec::new();
        for (id, needers) in &missing {
            let names = needers.iter().map(|&i| self.mods[i].name.clone()).collect::<Vec<_>>().join(" and ");
            let slug = MODRINTH_SLUGS.iter().find(|(mod_id, _)| mod_id == id).map_or(id.as_str(), |(_, slug)| slug);
            let before = self.mods.len();
            match self.add_library(slug, needers[0]) {
                Ok(Lookup::Found(index)) if index >= before => {}
                Ok(Lookup::Found(index)) => errors.push(format!(
                    "{names} needs the mod '{id}'. {} is in the pack, but its jar doesn't include that mod.",
                    self.mods[index].name
                )),
                Ok(Lookup::NoProject) => errors.push(format!(
                    "{names} needs the mod '{id}', but nothing in the pack provides it and Modrinth has no project '{slug}'. Add the mod that provides it to pack/pack.toml."
                )),
                Ok(Lookup::NoVersion(title)) => {
                    errors.push(format!("{names} needs the mod '{id}' ({title} on Modrinth), which has no version for {}", self.target()))
                }
                Err(e) => errors.push(e.to_string()),
            }
        }
        if errors.is_empty() {
            Ok(true)
        } else {
            Err(errors.join("\n").into())
        }
    }

    /// Every jar must accept this Minecraft, loader and Java version, or Fabric refuses to start.
    fn check_versions(&self) -> Result<()> {
        let java = self.pack.java.as_ref().map(|j| j.major.to_string());
        let checks = [
            ("minecraft", "Minecraft", Some(self.pack.minecraft.as_str())),
            ("fabricloader", "Fabric Loader", Some(self.pack.loader.version.as_str())),
            ("java", "Java", java.as_deref()),
        ];
        let mut errors = Vec::new();
        for m in &self.mods {
            let Some(info) = &m.info else { continue };
            for (key, label, have) in checks {
                let Some(have) = have else { continue };
                match jar::accepts(&info.depends, key, have) {
                    Some(true) => {}
                    Some(false) => errors.push(format!("{} {} doesn't accept {label} {have} (it asks for {})", m.name, m.version, info.depends[key])),
                    None => println!("  warning: couldn't read {}'s {label} requirement {}; check it by hand", m.name, info.depends[key]),
                }
            }
        }
        if errors.is_empty() {
            Ok(())
        } else {
            Err(errors.join("\n").into())
        }
    }

    fn check_incompatible(&self) -> Result<()> {
        for m in &self.mods {
            for project_id in &m.modrinth_breaks {
                if let Some(other) = self.find_project(project_id) {
                    return Err(format!("{} says it doesn't work with {}", m.name, self.mods[other].name).into());
                }
            }
        }
        Ok(())
    }

    /// A library is needed whenever something that needs it is installed. Spreads that along
    /// the dependency links until nothing changes, then drops what nothing needs.
    fn work_out_needs(&mut self) -> Result<()> {
        let links: Vec<(usize, usize)> = self.needs.iter().copied().collect();
        loop {
            let mut changed = false;
            for &(a, b) in &links {
                let need = self.mods[a].need.clone();
                if self.mods[b].toggle {
                    if !self.mods[b].need.covers(&need) {
                        let (needer, optional) = (&self.mods[a].name, &self.mods[b].name);
                        return Err(format!(
                            "{needer} needs {optional}, but {optional} is optional, so players could switch it off. Remove `optional` from {optional} in pack.toml."
                        )
                        .into());
                    }
                } else {
                    changed |= self.mods[b].need.absorb(&need);
                }
            }
            if !changed {
                break;
            }
        }
        self.mods.retain(|m| m.explicit || m.need != Need::When(BTreeSet::new()));
        self.needs.clear();
        Ok(())
    }

    fn manifest_files(&self, files_url: &str, uploads: &mut Vec<(String, PathBuf)>) -> Vec<ManifestFile> {
        self.mods
            .iter()
            .map(|m| {
                let (url, modrinth) = match &m.source {
                    Source::Local(path) => {
                        let asset = asset_name(&m.file_name);
                        uploads.push((asset.clone(), path.clone()));
                        (format!("{files_url}/{asset}"), None)
                    }
                    Source::Modrinth { url, project_id, version_id } => {
                        (url.clone(), Some(ModrinthRef { project_id: project_id.clone(), version_id: version_id.clone() }))
                    }
                };
                let (required, default_enabled, required_by) = match &m.need {
                    Need::Always => (true, true, Vec::new()),
                    Need::When(_) if m.toggle => (false, m.default_on, Vec::new()),
                    Need::When(ids) => (false, false, ids.iter().cloned().collect()),
                };
                ManifestFile {
                    id: m.id.clone(),
                    name: m.name.clone(),
                    version: m.version.clone(),
                    description: m.description.clone(),
                    group: m.group,
                    path: format!("mods/{}", m.file_name),
                    url,
                    sha512: m.sha512.clone(),
                    size: m.size,
                    required,
                    default_enabled,
                    required_by,
                    modrinth,
                }
            })
            .collect()
    }

    fn config_files(&self, files_url: &str, uploads: &mut Vec<(String, PathBuf)>) -> Result<Vec<ConfigFile>> {
        let mut configs = Vec::new();
        for entry in &self.pack.configs {
            let source = self.dir.join(&entry.file);
            let bytes = fs::read(&source).map_err(|e| format!("can't read pack/{}: {e}", entry.file))?;
            let asset = asset_name(&entry.path.replace('/', "-"));
            uploads.push((asset.clone(), source));
            configs.push(ConfigFile {
                path: entry.path.clone(),
                url: format!("{files_url}/{asset}"),
                sha512: sha512_hex(&bytes),
                size: bytes.len() as u64,
                policy: entry.policy,
            });
        }
        Ok(configs)
    }
}

struct Job {
    index: usize,
    url: String,
    sha512: String,
    size: u64,
}

enum Lookup {
    /// The project's index in the pack.
    Found(usize),
    NoProject,
    /// The project (named here) exists, but has no version for this game version and loader.
    NoVersion(String),
}

fn same_project(a: &Mod, b: &Mod) -> bool {
    match (&a.source, &b.source) {
        (Source::Modrinth { project_id: x, .. }, Source::Modrinth { project_id: y, .. }) => x == y,
        _ => false,
    }
}

/// A name GitHub keeps as-is for release assets: letters, digits, `.`, `-` and `_`.
fn asset_name(name: &str) -> String {
    name.chars().map(|c| if c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_') { c } else { '-' }).collect()
}

fn sha512_hex(bytes: &[u8]) -> String {
    cuc_manifest::encode_hex(&Sha512::digest(bytes))
}

/// Whether two manifests install exactly the same thing (ignoring serial, dates, notes and
/// where my own files are hosted).
fn same_content(a: &Manifest, b: &Manifest) -> bool {
    fn normalized(m: &Manifest) -> Manifest {
        let mut m = m.clone();
        m.serial = 0;
        m.pack_version.clear();
        m.released_at.clear();
        m.notes = None;
        for file in &mut m.files {
            if file.modrinth.is_none() {
                file.url.clear();
            }
        }
        for config in &mut m.configs {
            config.url.clear();
        }
        m.files.sort_by(|a, b| a.id.cmp(&b.id));
        m.configs.sort_by(|a, b| a.path.cmp(&b.path));
        m
    }
    normalized(a) == normalized(b)
}

/// The newest section of changelog.md if it hasn't been published yet; otherwise a summary of
/// what changed, with Modrinth's changelog for mods that follow their latest version.
fn release_notes(dir: &Path, previous: Option<&Manifest>, history: &[Manifest], manifest: &Manifest, mods: &[Mod]) -> Option<Notes> {
    let text = fs::read_to_string(dir.join("changelog.md")).unwrap_or_default();
    let written = changelog::latest_notes(&text).map(|(title, markdown)| Notes { title, markdown });
    let Some(previous) = previous else { return written };
    let published_before =
        |notes: &Notes| previous.notes.as_ref() == Some(notes) || history.iter().any(|m| m.notes.as_ref() == Some(notes));
    match written {
        Some(notes) if !published_before(&notes) => Some(notes),
        _ => Some(describe_changes(previous, manifest, mods)),
    }
}

fn describe_changes(previous: &Manifest, manifest: &Manifest, mods: &[Mod]) -> Notes {
    let before: HashMap<&str, &ManifestFile> = previous.files.iter().map(|f| (f.id.as_str(), f)).collect();
    let mut headlines: Vec<(bool, String)> = Vec::new();
    let mut lines = Vec::new();
    if previous.minecraft != manifest.minecraft || previous.loader != manifest.loader {
        headlines.push((false, format!("Minecraft {}", manifest.minecraft)));
        lines.push(format!("- Minecraft {} with {} {}", manifest.minecraft, manifest.loader.kind, manifest.loader.version));
    }
    for file in &manifest.files {
        let library = file.group == Group::Library;
        match before.get(file.id.as_str()) {
            None => {
                headlines.push((library, format!("{} {}", file.name, file.version)));
                lines.push(format!("- Added {} {}", file.name, file.version));
            }
            Some(old) if old.sha512 != file.sha512 => {
                headlines.push((library, format!("{} {}", file.name, file.version)));
                lines.push(format!("- Updated {} to {}", file.name, file.version));
                let changelog = mods.iter().find(|m| m.id == file.id && m.follows_latest).and_then(|m| m.changelog.as_deref());
                if let Some(changelog) = changelog {
                    lines.extend(shorten(changelog.trim(), 1500).lines().map(|line| format!("  {line}")));
                }
            }
            Some(old) if switchable(old) && switchable(file) && old.default_enabled != file.default_enabled => {
                let state = if file.default_enabled { "on" } else { "off" };
                headlines.push((library, format!("{} {state} by default", file.name)));
                lines.push(format!("- {} now starts {state} (unless you switched it yourself)", file.name));
            }
            Some(old) if switchable(old) != switchable(file) && file.required_by.is_empty() => {
                let now = if switchable(file) { "optional" } else { "always installed" };
                headlines.push((library, format!("{} {now}", file.name)));
                lines.push(format!("- {} is now {now}", file.name));
            }
            Some(_) => {}
        }
    }
    for old in &previous.files {
        if !manifest.files.iter().any(|f| f.id == old.id) {
            headlines.push((old.group == Group::Library, format!("{} removed", old.name)));
            lines.push(format!("- Removed {}", old.name));
        }
    }
    let config_key = |m: &Manifest| m.configs.iter().map(|c| (c.path.clone(), c.sha512.clone(), c.policy)).collect::<Vec<_>>();
    if config_key(previous) != config_key(manifest) {
        lines.push("- Updated the default settings".to_string());
    }
    if lines.is_empty() {
        lines.push("- Pack settings updated".to_string());
    }
    // Mods players know first; libraries last.
    headlines.sort_by_key(|(library, _)| *library);
    let title = match headlines.split_first() {
        None => "Pack update".to_string(),
        Some(((_, first), [])) => first.clone(),
        Some(((_, first), rest)) => format!("{first} and {} more", rest.len()),
    };
    Notes { title, markdown: lines.join("\n") }
}

/// A mod players can switch on or off themselves.
fn switchable(file: &ManifestFile) -> bool {
    !file.required && file.required_by.is_empty()
}

fn shorten(text: &str, max_chars: usize) -> String {
    match text.char_indices().nth(max_chars) {
        Some((cut, _)) => format!("{}…", &text[..cut]),
        None => text.to_string(),
    }
}

fn print_summary(name: &str, manifest: &Manifest, out: &Path, files_url: &str) {
    println!();
    println!("{name} {} (serial {}, {} channel)", manifest.pack_version, manifest.serial, manifest.channel);
    for file in &manifest.files {
        let when = if file.required {
            "always".to_string()
        } else if file.required_by.is_empty() {
            format!("optional, starts {}", if file.default_enabled { "on" } else { "off" })
        } else {
            format!("with {}", file.required_by.join(", "))
        };
        let group = format!("{:?}", file.group).to_lowercase();
        println!("  {group:<12} {:<52} {when}", format!("{} {}", file.name, file.version));
    }
    if let Some(notes) = &manifest.notes {
        println!("What's new: {}", notes.title);
    }
    println!("Signed and written to {}", out.join("manifest.json").display());
    if files_url.starts_with("http://127.0.0.1") {
        println!("My jars and configs are served from {files_url} (run `npm run pack:serve`).");
    }
}

/// Launchers only trust manifests signed by the key built into them.
fn warn_if_app_key_differs(root: &Path, secret: &str) {
    let Ok(public) = cuc_manifest::public_key(secret) else { return };
    let Ok(config) = fs::read_to_string(root.join("src-tauri").join("src").join("config.rs")) else { return };
    let Some(line) = config.lines().find(|line| line.contains("PACK_SIGNING_PUBLIC_KEY")) else { return };
    if line.split('"').nth(1) != Some(public.as_str()) {
        println!("warning: src-tauri/src/config.rs has a different PACK_SIGNING_PUBLIC_KEY, so launchers built from it will refuse this manifest. It should be {public}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn when(ids: &[&str]) -> Need {
        Need::When(ids.iter().map(|id| id.to_string()).collect())
    }

    #[test]
    fn needs_widen_and_cover() {
        let mut need = when(&[]);
        assert!(need.absorb(&when(&["zoomify"])));
        assert!(!need.absorb(&when(&["zoomify"])));
        assert!(need.covers(&when(&["zoomify"])));
        assert!(!need.covers(&when(&["freelook"])));
        assert!(!need.covers(&Need::Always));
        assert!(need.absorb(&Need::Always));
        assert_eq!(need, Need::Always);
        assert!(need.covers(&when(&["anything"])));
    }

    #[test]
    fn asset_names_survive_github() {
        assert_eq!(asset_name("cube-utility-1.7.0+mc26.1-26.3.jar"), "cube-utility-1.7.0-mc26.1-26.3.jar");
        assert_eq!(asset_name("config-sodium options.json"), "config-sodium-options.json");
    }

    #[test]
    fn shortens_on_character_boundaries() {
        assert_eq!(shorten("héllo", 2), "hé…");
        assert_eq!(shorten("hi", 5), "hi");
    }
}
