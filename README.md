# Cube Utility Client

A fast launcher for the Cube Utility CubeCraft modpack, written in Rust with Tauri. Players press PLAY, and the launcher keeps their mods in sync with whatever was published last, so nobody updates mods by hand.

The full spec is in `cube-utility-client-prompt.md`.

**Players:** download `CubeUtilityClient_<version>_x64-setup.exe` from the [newest launcher release](https://github.com/SisuKah/cube-utility-client/releases/latest) and run it. After that, the launcher and the mods update themselves. (Release builds can't start the game until Mojang approves the sign-in; see milestone 4.)

## Status

- [x] Milestone 1: shell and style (Tauri + Rust; Home, Mods and Settings with sample data)
- [x] Milestone 2: install and launch Minecraft 26.3 with Fabric, in Rust (dev builds use an offline "DevPlayer" until sign-in)
- [x] Milestone 3: the pack tool (`pack.toml` → signed manifest) and the sync engine (signature and replay checks, crash-safe updates, rollback, repair)
- [ ] Milestone 4: Microsoft sign-in, skins and Quick Play. Sign-in is built: Microsoft and Xbox sign-in work, and the token stays in the Windows Credential Manager. Minecraft's own login answers 403 until Mojang approves the Azure app (requested 2026-11-11, reply by email); then the player's skin shows and PLAY joins play.cubecraft.net with no code changes.
- [x] Milestone 5: self-update ("Launcher update ready · Restart"), CI (checks, pack publishing, launcher releases) and the per-user installer
- [x] Milestone 6: the Mods switches install and remove mods, settings are saved by the launcher, a beta channel, "Minecraft crashed" with Copy log, the tray menu (Play CubeCraft, Quit), and an accessibility pass

## Requirements

- Rust (stable), installed with rustup
- Visual Studio Build Tools with the C++ workload (MSVC)
- WebView2 (already part of Windows 10 and 11)
- Node.js 22 or newer

## Develop

```bash
npm install
npm run dev        # the app: hot reload for the UI, automatic rebuild when Rust changes
npm run dev:web    # only the UI, in a browser at http://localhost:1420
npm run typecheck
npm run build      # release build and installer, in target/release/bundle/nsis (signs the update files when src-tauri/updater.key exists)
```

Rust checks, from the repo root (one Cargo workspace: the launcher, `crates/manifest` and `tools/pack`):

```bash
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
```

In the browser preview (`dev:web`), a small "Dev" button in the bottom-left corner switches the fake launcher between the update, up-to-date and offline states.

## How I ship an update

### Mods: push `pack/`

`pack/pack.toml` is the modpack's source of truth. Change it (or drop in a new build of one of my mods), commit, and push to `main`. GitHub Actions (`publish-pack.yml`) builds and signs the manifest, attaches my jars to a `pack-<version>` release, and puts the manifest on GitHub Pages. Every launcher checks at start, every 10 minutes and before each PLAY, and brings its mods folder in line.

```bash
npm run pack:add -- "C:\path\to\cube-utility-1.8.0+mc26.1-26.3.jar" --notes "- What changed"
```
```bash
git commit -am "Cube Utility 1.8.0" && git push
```

- `pack:add` reads the jar's mod id, replaces the older jar of the same mod in `pack/jars/`, updates `pack.toml` and adds the notes to `pack/changelog.md`. A jar with a new mod id is added as a new mod. (Add new jars to git too: `git add pack/jars`.)
- The build resolves every Modrinth entry to an exact version for the pack's Minecraft version and loader, adds what the mods need (from Modrinth and from each jar's `fabric.mod.json`), and checks that every jar accepts this Minecraft, Fabric Loader and Java version. It stops with a clear message when something is missing or incompatible, and publishes nothing when nothing changed. `npm run pack:build` runs the same checks on your PC.
- The newest section of `pack/changelog.md` becomes "What's new" on Home. If you don't add a section, the notes describe what changed, with Modrinth's changelog for mods marked `version = "latest"`.
- Every 30 minutes the same workflow re-checks mods marked `version = "latest"` (my own Modrinth projects) and publishes when one has a new release, so those reach players with no steps at all. GitHub pauses scheduled workflows after 60 days without commits: if that happens, open the repo's **Actions** tab, pick **Publish pack** and click **Enable workflow**.
- To roll players back to an older pack: `gh workflow run "Publish pack" -f rollback_to=2026.11.11-1` (or **Run workflow** on the Actions tab). It republishes that release's files under a new number. `pack.toml` still lists the newer mods, so change it before the next push.

### A beta first (optional)

Players who turn on **Beta updates** in Settings get the beta pack while one is published, and the regular pack otherwise. To try changes on them first, make them on a branch instead of `main` (pushing another branch publishes nothing):

```bash
git switch -c beta
```

Change the pack and commit as above, then push the branch and publish it to beta:

```bash
git push -u origin beta
```
```bash
gh workflow run "Publish pack" --ref beta -f channel=beta
```

Beta jars go to `pack-beta-<version>` pre-releases. Beta players only get what's published to beta, so when the beta is done, merge it into `main` (that publishes it for everyone) and publish beta once more from `main`, which puts them back on the regular mods: `gh workflow run "Publish pack" -f channel=beta`.

### The launcher: tag a release

```bash
npm run release:launcher -- patch
```

This bumps the version (`patch`, `minor`, `major` or an exact one like `1.2.0`), commits, tags `launcher-v<version>` and pushes. GitHub Actions (`release-launcher.yml`) builds the installer, signs the update files with the updater key, and publishes them with `latest.json`. Installed launchers find it within 30 minutes, download it in the background and show "Launcher update ready · Restart"; if the player ignores it, it installs when they close the launcher. Launchers never downgrade.

New players download `CubeUtilityClient_<version>_x64-setup.exe` from the newest launcher release. It installs for their Windows account only, without administrator rights. Until the installer is code-signed, Windows SmartScreen warns about it ("More info", then "Run anyway").

### Keys

Two key pairs sign everything players download. Both secret halves stay out of git; back them up somewhere safe.

| Key | Made by | Secret on this PC | GitHub secret | Without it |
|---|---|---|---|---|
| Pack | `npm run keys:generate` | `pack/signing-key.secret` | `PACK_SIGNING_KEY` | launchers refuse new packs until they get a new build |
| Launcher updates | `npm run keys:updater` | `src-tauri/updater.key` + `.password` | `TAURI_SIGNING_PRIVATE_KEY`, `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` | installed launchers can't update themselves anymore |

```bash
gh secret set PACK_SIGNING_KEY < pack/signing-key.secret
```
```bash
gh secret set TAURI_SIGNING_PRIVATE_KEY < src-tauri/updater.key
```
```bash
gh secret set TAURI_SIGNING_PRIVATE_KEY_PASSWORD < src-tauri/updater.key.password
```

The public halves are built into the app: `PACK_SIGNING_PUBLIC_KEY` in `src-tauri/src/config.rs` and `plugins.updater.pubkey` in `src-tauri/tauri.conf.json`.

### Test a pack locally

```bash
npm run pack:serve   # serves pack/out at http://127.0.0.1:8799
npm run dev          # debug builds sync from there (or from CUC_PACK_URL)
```

Edit `pack.toml`, run `npm run pack:build`, and the running launcher picks it up within 30 seconds (debug builds check that often). Stop `pack:serve` before changing the pack tool's own code, since Windows can't replace a running program.

## Layout

- `src/`: the UI (React + TypeScript). `src/native.ts` is its only bridge to Rust; outside the app (`dev:web`) those calls do nothing, so the screens still work for design work.
- `src-tauri/`: the launcher's Rust core. `src/config.rs` holds deployment values, `src/sync/` the pack sync engine, and `capabilities/` limits what the UI may do.
- `crates/manifest/`: the manifest format, its checks, and ed25519 signing, shared by the launcher and the pack tool.
- `tools/pack/`: the pack tool behind the `pack:*` scripts.
- `pack/`: `pack.toml`, my jars (`jars/`), default configs (`configs/`) and `changelog.md`. `out/` (the built manifest) and `.cache/` (downloaded Modrinth jars) aren't committed.
- `app-icon.svg`: the source for the app icons (`npx tauri icon app-icon.svg`).

## On the player's PC

Everything lives in `%APPDATA%\.cubeutilityclient`: `shared/` (Minecraft, libraries, assets, Java), `instance/` (the game folder with `mods/`), `cache/` (pack files by hash, so rollbacks don't download again), `state/` (the installed manifest, `sync-state.json`, `mod-choices.json`, `settings.json`) and `logs/` (the launcher's log and `game.log`).

- Updates never touch files while the game runs; they apply when it closes. The same goes for the switches on the Mods screen: they install or remove a mod right away, or when Minecraft closes.
- New files are copied in under temp names first, and the state lists every file before anything is swapped, so a crash halfway is finished by the next sync.
- Jars players add themselves stay (listed as extra mods) while the pack has `allowExtraMods = true`.
- **Repair files** (in Settings, and shown when the game fails to start) re-hashes every mod and game file and downloads what's damaged.
- When Minecraft crashes, Home says so and offers **Copy log** (the end of `game.log`) for asking someone for help.
- The tray icon has **Play CubeCraft** and **Quit**; clicking it brings the launcher back while it hides during play.

## Notes

- The first `npm run dev` compiles about 350 Rust crates and takes a few minutes. After that, rebuilds take seconds.
- The window is frameless. Empty parts of the top bar drag it (`data-tauri-drag-region`), and the minimize, maximize and close buttons are part of the UI.
- Release builds sync the pack from GitHub Pages: https://sisukah.github.io/cube-utility-client/stable/manifest.json (`PACK_BASE_URL` in `src-tauri/src/config.rs`). Debug builds use the local `pack:serve` instead.
- Launcher updates come from `releases/latest/download/latest.json` (`plugins.updater` in `src-tauri/tauri.conf.json`), so pack releases are never marked "latest".
