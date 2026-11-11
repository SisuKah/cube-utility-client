# Cube Utility Client: build spec

**How to use this.** This is the spec for this project. Milestone 1 is done: the Tauri + Rust shell with all three screens on sample data. To continue in a new Claude Code session, open this folder and say, for example, "Continue with milestone 2 from cube-utility-client-prompt.md." For design questions, the launcher mockup is at https://claude.ai/artifact/5TRYMVQGhYDijCijNHWTD2. Use the dark teal-and-gold reference image only for colors and mood.

Tip: start the Microsoft/Azure part early (section 3.4). It's the one step that waits on Mojang's approval.

---

You are building **Cube Utility Client**: a simple, fast Windows launcher that installs, updates and launches a curated Fabric modpack for CubeCraft players. I made most of the mods in it. The whole point: **when I publish an update, every player gets it automatically**, on their next launch or within about 10 minutes if the launcher is already open. Nobody ever downloads or replaces a mod by hand again.

It's written in **Rust** with **Tauri**: Rust does all the real work, and the UI is a thin React layer drawn by the system's WebView2. Keep it as simple as other Minecraft clients: the player's character, one big PLAY button, and the mods. The launcher mockup shows the layout; the reference image is for colors, type and mood only.

## 1. Goals

- Install → sign in with Microsoft → press **PLAY** → land on `play.cubecraft.net`. No setup.
- Fast and light: a small installer (around 10 MB), a quick start, low memory use, and downloads and file checks done in parallel in Rust.
- The launcher owns its own game folder. It never reads or changes the player's `.minecraft`.
- Two update paths, both automatic:
  - **Pack updates** (mods and configs): a signed manifest that every launcher syncs to.
  - **Launcher updates** (the app itself): Tauri's signed updater with GitHub Releases.
- Three screens: Home, Mods and Settings (section 5).

Not in v1: dashboards, stats or charts, news feeds, cosmetics, friends or chat, more than one Minecraft version, macOS/Linux builds (keep the code portable), and anything that automates gameplay or gives an unfair advantage.

## 2. Tech stack

Use these unless you hit a blocking problem. If you do, stop and tell me.

| Area | Choice |
|---|---|
| App shell | Tauri 2. The window uses the system's WebView2, so there's no bundled browser. |
| Core logic | Rust (stable): game install and launch, pack sync, sign-in, settings, logs. The UI never touches files or the network directly. |
| Async and HTTP | tokio (Tauri's runtime) and reqwest with rustls |
| Hashes and signatures | `sha1`, `sha2`, `ed25519-dalek` |
| Archives and processes | `zip` to unpack natives; `tokio::process` to run Java |
| Account tokens | `keyring` (Windows Credential Manager) |
| Errors and logs | `thiserror`; `tracing` with `tracing-appender` (rotated log files) |
| System info | `sysinfo` |
| Plugins | `tauri-plugin-single-instance`, `tauri-plugin-opener` (open links and folders), `tauri-plugin-updater` (self-update) |
| Installer | Tauri's NSIS bundler, per-user install, no admin |
| UI | React, TypeScript, Vite, Tailwind CSS with design tokens, Motion, lucide-react, Manrope bundled locally |
| Pack tooling | A small Rust CLI in the same Cargo workspace, run locally and in GitHub Actions |

## 3. Architecture

### 3.1 Repository

One public repo, as a Cargo workspace. Never put a token or any secret inside the app.

```
cube-utility-client/
  src/                  the UI (React + TypeScript)
  src-tauri/            the launcher's Rust core (the Tauri app)
  crates/manifest/      manifest types, signing and verification, shared by the launcher and the pack tool
  tools/pack/           the pack CLI: add, build, rollback, keys
  pack/
    pack.toml           the modpack's source of truth (I edit this)
    jars/               my mod jars that aren't on Modrinth
    configs/            default config files for players
    changelog.md        patch notes, newest first
  mod/                  the in-game look: a Fabric mod (Java, Gradle) shipped in the pack
  .github/workflows/    publish-pack.yml (also the 30-minute refresh), release-launcher.yml, check.yml
```

Inside `src-tauri/src/`, keep one module per job: `config`, `net` (the download engine and host allow-list), `game` (install and launch), `sync` (the pack sync engine), `modrinth` (Browse Modrinth on the Mods screen), `auth` (Microsoft sign-in), `settings`, `logs`. The UI calls narrow Tauri commands and listens to Tauri events for progress.

### 3.2 Folders on the player's PC

Everything lives under `%APPDATA%\.cubeutilityclient\`:
- `shared/`: versions, libraries, assets and Java runtimes
- `instance/`: the game directory (`mods/`, `config/`, `options.txt`, `saves/`, `logs/`, `screenshots/`)
- `cache/sha512/<first 2 chars>/<hash>`: content-addressed download cache, so rollbacks are instant and nothing downloads twice
- `state/`: `sync-state.json`, `settings.json`, `accounts.json` (names and UUIDs only)
- `logs/`: launcher logs, rotated

Sign-in tokens are stored in the Windows Credential Manager through `keyring`, never in files.

### 3.3 Installing and launching the game (Rust, `game` module)

- Target **Minecraft 26.3**, **Fabric Loader 0.20.1**, **Java 25**. The 26.3 version JSON asks for `javaVersion.component = java-runtime-epsilon` and `majorVersion = 25`. Read all three from the manifest; never hard-code them in the app.
- Write the installer yourself, following the version JSON format (the "client.json" page on minecraft.wiki):
  1. Read Mojang's version manifest, then the 26.3 version JSON.
  2. Download the client jar, the libraries that apply to Windows (honor each library's `rules`), native jars (unpack their files for Windows), the asset index and every asset object (into `shared/assets/objects/<first 2 chars>/<hash>`).
  3. Download Mojang's official Java runtime for the component the version JSON names.
  4. Merge the Fabric profile from `meta.fabricmc.net`: it inherits from 26.3, adds its libraries, sets the main class and appends its arguments.
- Download engine (`net` module): 16 files at a time for the game, 8 for pack files, retries with backoff, sha1 (Mojang) or sha512 (pack) checked while streaming, written to a temp file and renamed into place. Never bundle or rehost Minecraft or Java files.
- Launch: build the classpath and fill the JVM and game arguments from the version JSON (replace the `${...}` placeholders and honor `rules` and `features`). Use the managed Java and the memory from settings. Default RAM is 4 GB (3 GB on PCs with less than 8 GB). The slider goes from 2 GB up to 16 GB or half the system RAM, whichever is lower. Start Java with `tokio::process::Command` without a console window.
- The "Launch into" choice on Home: **CubeCraft** adds the Quick Play argument `--quickPlayMultiplayer play.cubecraft.net`; **Title screen** adds nothing. Check that Quick Play works on 26.3.
- Players never see Minecraft's welcome screen: before every launch, set `onboardAccessibility:false` in the instance's `options.txt`. While the instance is unused (no `options.txt`, or one still on the welcome screen), first copy the newest `options.txt` from the player's other installs (the Minecraft Launcher and its profiles' game folders, Modrinth App, Prism Launcher, CurseForge, Lunar Client), without the resource pack choices.
- Write the game's output to `logs/`. If the game crashes (non-zero exit code, or a new file in `crash-reports/`), show a simple "Minecraft crashed" message with **Copy log** and **Repair files**.
- "Hide launcher while playing" is on by default: hide the window on launch, show it again when the game exits.
- Send progress to the UI as Tauri events (for example `install://progress` with the current file, bytes done and bytes total).

### 3.4 Microsoft sign-in (Rust, `auth` module)

- Azure app registration: personal Microsoft accounts only (`consumers`), public client, redirect URI `http://localhost`, no client secret. The client ID goes in `src-tauri/src/config.rs`.
- Mojang must approve new apps before they can call the Minecraft API. I'll apply at https://aka.ms/mce-reviewappid. Until approval, `api.minecraftservices.com` returns 403, so build everything else meanwhile and show a clear message on 403.
- Flow, written with reqwest: authorization code + PKCE against `https://login.microsoftonline.com/consumers` with the scopes `XboxLive.signin offline_access`. Open the sign-in page in the system browser (tauri-plugin-opener) and catch the redirect with a one-shot listener on `127.0.0.1` on a random port. Then:
  1. `POST https://user.auth.xboxlive.com/user/authenticate` with `RpsTicket: d=<access token>`
  2. `POST https://xsts.auth.xboxlive.com/xsts/authorize` with `RelyingParty: rp://api.minecraftservices.com/`
  3. `POST https://api.minecraftservices.com/authentication/login_with_xbox` with `identityToken: XBL3.0 x=<uhs>;<xsts token>`
  4. `GET /entitlements/mcstore` to confirm the player owns the game, then `GET /minecraft/profile`
- The profile response includes the player's skin URL and model (classic or slim). Home uses it to draw the character (section 5).
- Friendly messages for XSTS errors: 2148916233 (no Xbox profile yet), 2148916235 (Xbox Live isn't available in their country), 2148916238 (child account; an adult must add it to a Microsoft family).
- Refresh tokens silently on start. Sign-out deletes the account's tokens. Never ask for or handle passwords. Tokens go only to Microsoft and Mojang endpoints.
- Dev builds only: an offline test profile, so the game can be launched before Azure approval. Compile it out of release builds (`#[cfg(debug_assertions)]`).

### 3.5 Pack sync engine (Rust, `sync` module; the most important part)

The manifest types, signing and verification live in `crates/manifest`, shared by the launcher and the pack tool. The manifest lives at `https://<github-user>.github.io/cube-utility-client/<channel>/manifest.json`, next to `manifest.json.sig`. Example (generated by the pack tool, never written by hand):

```json
{
  "schema": 1,
  "serial": 42,
  "packVersion": "2026.11.10-1",
  "channel": "stable",
  "releasedAt": "2026-11-10T18:00:00Z",
  "minLauncherVersion": "1.0.0",
  "minecraft": "26.3",
  "loader": { "type": "fabric", "version": "0.20.1" },
  "java": { "component": "java-runtime-epsilon", "major": 25 },
  "quickPlay": "play.cubecraft.net",
  "allowExtraMods": true,
  "notes": { "title": "Cube Utility 1.7.0", "markdown": "..." },
  "files": [
    {
      "id": "cubeutility", "name": "Cube Utility", "group": "own",
      "path": "mods/cube-utility-1.7.0+mc26.1-26.3.jar",
      "url": "https://github.com/<github-user>/cube-utility-client/releases/download/pack-2026.11.10-1/cube-utility-1.7.0+mc26.1-26.3.jar",
      "sha512": "...", "size": 675840, "required": true
    },
    {
      "id": "sodium", "name": "Sodium", "group": "performance",
      "path": "mods/sodium-fabric-0.9.2+mc26.3.jar",
      "url": "https://cdn.modrinth.com/data/AANobbMI/versions/.../sodium-fabric-0.9.2+mc26.3.jar",
      "sha512": "...", "size": 1907712, "required": true,
      "modrinth": { "projectId": "AANobbMI", "versionId": "..." }
    },
    {
      "id": "yacl", "name": "YetAnotherConfigLib", "group": "library",
      "path": "mods/...", "url": "...", "sha512": "...", "size": 0,
      "required": false, "requiredBy": ["zoomify"]
    }
  ],
  "configs": [
    { "path": "config/sodium-options.json", "url": "...", "sha512": "...", "policy": "if-missing" }
  ]
}
```

Rules:
1. **When to check:** at startup, every 10 minutes while open, and right before every launch. Send `If-None-Match` with the last ETag.
2. **Trust:** verify the ed25519 signature over the exact manifest bytes with the public key built into the app, before parsing anything. Reject a bad signature, an unknown `schema`, a `serial` lower than the last one applied on that channel (stops replay and downgrade attacks), and any URL whose host isn't allow-listed (section 7).
3. **Launcher too old:** if `minLauncherVersion` is newer than the app, install the launcher update first.
4. **Plan:** wanted = every required file, plus the optional files the player turned on (starting from `defaultEnabled`), plus any dependency whose `requiredBy` includes an enabled mod. Compare with `sync-state.json`, which stores path → sha512, size and mtime. Re-hash a file only when its size or mtime changed.
5. **Download, then apply:** download missing or changed files into the cache (through the `net` engine), check sha512 and size, then copy into `instance/mods/` under a temp name and rename into place. Delete managed files that are no longer wanted. Write the new state last. If anything fails, keep the previous working set and show "Update failed · Retry".
6. **Game running:** never change files while the game runs. Queue the update and show "Update ready, applies when you close the game".
7. **Jars the player added themselves:** with `allowExtraMods: true`, keep them and list them on the Mods screen under "Your extra mods" with a warning badge. With `false`, move them to `instance/mods-disabled/` and say so.
8. **Configs:** `if-missing` (default: never overwrite a player's settings) or `always` (forced).
9. **Repair files:** re-check every managed file's hash and re-download anything wrong.
10. **Offline:** if the manifest can't be fetched but the last synced files still verify, allow PLAY and show "Offline" in the status line.
11. **What's new:** keep the latest manifest's `notes` so Home can show one line, like "New in this update: Cube Utility 1.7.0 and 3 more".
12. **Beta channel:** a settings switch moves the launcher to `beta/manifest.json`. Track `serial` per channel.

### 3.6 Launcher self-update

- `tauri-plugin-updater` checks a `latest.json` on GitHub Releases at start and every 30 minutes. Updates are signed with Tauri's updater key, which is separate from the pack signing key. Keep its private key out of git (a GitHub secret).
- Download in the background, then show a toast: "Launcher update ready · Restart". If ignored, install on the next quit. Never downgrade.
- The NSIS installer is per-user, so updates never need admin rights. Leave room for Windows code signing later (unsigned installers trigger SmartScreen warnings), for example a workflow step that only runs when signing secrets exist.

### 3.7 How I ship updates

Make each of these a one-liner (npm scripts that call the Rust pack tool) and document them in the README under "How I ship an update".

- `npm run pack:add -- <path-to-jar> --notes "..."`: read the jar's `fabric.mod.json` id, replace the older jar of the same mod in `pack/jars/`, update `pack.toml`, add the notes to `changelog.md`.
- `npm run pack:build`: resolve `pack.toml` to exact Modrinth versions (filtered to the pack's game version and loader), auto-add and pin required dependencies (from Modrinth's dependency data and each jar's `fabric.mod.json`), and fail with a clear message if a dependency is missing or a mod doesn't accept the game version. Then compute sha512 and size, and write and sign `pack/out/<channel>/manifest.json` (not `dist/`, which is the UI's build output).
- **Push to `main`** with changes under `pack/` runs `publish-pack.yml`: build, upload my jars as assets of a `pack-<packVersion>` release, deploy the manifest and signature to GitHub Pages, and bump `serial` by one.
- `publish-pack.yml` also runs every 30 minutes and on demand (one workflow instead of a separate `refresh-pack.yml`). It re-resolves entries marked `version = "latest"` (my own Modrinth projects) and publishes automatically when one changed, using the Modrinth changelog as the patch notes. Pack releases are never marked "latest", because launchers find their own updates at `releases/latest/download/latest.json`. So when I put a new version of CubeCraft AutoVote or Level + Rank on Modrinth, players get it with zero extra steps. GitHub pauses scheduled workflows after 60 days without repo activity; the README must say how to turn it back on.
- `npm run pack:rollback -- <packVersion>`: republish an older file set under a new serial.
- `npm run release:launcher -- patch|minor`: bump the app version, tag `launcher-v*`, push. `release-launcher.yml` builds the signed installer and `latest.json` and publishes them.
- `npm run keys:generate`: create the ed25519 pack-signing key pair, print the public key to put in `src-tauri/src/config.rs`, and tell me to save the private key as the `PACK_SIGNING_KEY` GitHub secret. Never commit a private key.

## 4. The pack

Start `pack/pack.toml` with exactly this list (the real file is TOML; it's shown as YAML here for reading). Every Modrinth version below was checked against Minecraft 26.3 + Fabric on 10 Nov 2026.

```yaml
name: Cube Utility Client
channel: stable
minecraft: "26.3"
loader: { type: fabric, version: "0.20.1" }
quickPlay: play.cubecraft.net
allowExtraMods: true

mods:
  # My mods that aren't on Modrinth (jars in pack/jars)
  - { id: cubeutility, name: Cube Utility,      file: jars/cube-utility-1.7.0+mc26.1-26.3.jar, group: own }
  - { id: ewoptimizer, name: Eggwars Optimizer, file: jars/eggwars-optimizer-1.1.0.jar,         group: own }
  - { id: armorhud,    name: Armor Dura HUD,    file: jars/armor-dura-hud-1.0.0.jar,            group: own }

  # My mods on Modrinth: always follow my newest 26.3 release
  - { modrinth: cubecraft-autovote,   version: latest, group: own }   # 1.2.0 today
  - { modrinth: cubecraft-level-rank, version: latest, group: own }   # V5 today

  # Performance (same set as my Eggwars Optimized pack), pinned
  - { modrinth: sodium,                version: mc26.3-0.9.2-fabric,  group: performance }
  - { modrinth: lithium,               version: mc26.3-0.26.3-fabric, group: performance }
  - { modrinth: immediatelyfast,       version: 1.17.1+26.3-fabric,   group: performance }
  - { modrinth: entityculling,         version: 1.11.3,               group: performance }
  - { modrinth: ferrite-core,          version: 9.0.0-fabric,         group: performance }
  - { modrinth: dynamic-fps,           version: 3.11.10,              group: performance }
  - { modrinth: krypton,               version: 0.3.2,                group: performance }
  - { modrinth: sodium-extra,          version: mc26.3-0.9.5+fabric,  group: performance }
  - { modrinth: reeses-sodium-options, version: mc26.3-2.2.5+fabric,  group: performance }

  # Libraries and UI
  - { modrinth: fabric-api,      version: 0.166.0+26.3,    group: library }
  - { modrinth: modmenu,         version: 21.0.0,          group: library }
  - { modrinth: placeholder-api, version: 3.2.0+26.3,      group: library }   # Mod Menu needs it
  - { modrinth: cloth-config,    version: 26.3.159+fabric, group: library }

  # Optional quality of life (players can switch these on or off)
  - { modrinth: zoomify,               version: 2.17.0+mc26.3, group: qol, optional: true, default: on }   # pulls in yacl + fabric-language-kotlin
  - { modrinth: morechathistory,       version: 2.0.0,         group: qol, optional: true, default: on }
  - { modrinth: cubes-without-borders, version: 5.0.0+26.3,    group: qol, optional: true, default: on }
  - { modrinth: freelook,              version: 1.4.4,         group: qol, optional: true, default: off }  # check CubeCraft's rules before defaulting this on
  - { modrinth: variants-cit,          version: 5.8.1+26.3,    group: qol, optional: true, default: off }
```

My jars are in `pack/jars/`. Newer builds go in with `npm run pack:add` (ask me if one seems missing).

All of my mods are client-side Fabric mods that need Fabric API and Java 25, and all have Mod Menu integration.

Left out on purpose:
- **Smart Mod Updater:** this launcher's sync replaces it, and it only exists for 1.21.11.
- **Weakness Glow and Replay Clip:** 1.21.11 dev snapshots. Add them once they're ported to 26.3.
- **JustArmorHud:** All Rights Reserved license, and Armor Dura HUD covers it.
- **Better Ping Display:** no 26.3 build yet.
- **Noxesium:** made for MCC Island, not CubeCraft.
- **Packet Logger and Packet Tool:** developer tools.

The launcher always downloads third-party mods from Modrinth's CDN at the pinned version and never rehosts them. That respects their licenses (for example Sodium's PolyForm Shield and Entity Culling's custom license) and keeps the authors' download counts.

## 5. Screens

Keep it this simple. If something isn't listed here, leave it out.

**Window and top bar.** Default size 1280×800, minimum 1000×640. Frameless (`decorations: false`) with our own top bar:
- on the left, the logo and the "Cube Utility / Client" wordmark
- in the center, three tabs (Home · Mods · Settings) in a small pill
- on the right, the player's face (from their skin) and name, then minimize, maximize and close buttons

Empty parts of the top bar drag the window (`data-tauri-drag-region`), and double-clicking them maximizes it. A tiny disclaimer line sits at the bottom of the window.

**Home.** Two columns: text and PLAY on the left, the character on the right. On narrow windows the character goes on top.
- Left side, top to bottom:
  - A small gold caps line: "MINECRAFT 26.3 · CUBECRAFT READY".
  - A big, light headline that follows the state: Ready to play / Updating your mods / Starting Minecraft / You're in game / Ready to play offline.
  - One short line under it, for example "All your CubeCraft mods are installed and up to date."
  - The **PLAY** button: the biggest thing on screen (about 340×76), gold gradient, dark text. While updating it shows a spinner and "UPDATING 64%" with a darker fill for the progress, and it's disabled. While starting it shows a spinner and "STARTING". In game it says "IN GAME".
  - A small "Launch into" select: CubeCraft (play.cubecraft.net) or Title screen.
  - One status line: a green dot and "Up to date · 23 mods · pack 2026.11.10-1". During an update it becomes the striped progress bar with the current file and "1.9 / 2.9 MB".
  - One gold link: "New in this update: Cube Utility 1.7.0 and 3 more →", which opens Mods.
- Right side: the player's character, full body, front view, drawn from their skin. It floats gently over a soft teal glow, with a thin gold ring and a shadow on the floor. Their name and "Skin from your Microsoft account" go under it.
- Drawing the character from the 64×64 skin PNG (already built in `src/skin/skin.ts`):
  - Draw the front faces: head (8,8, size 8×8) with the hat layer (40,8); body (20,20, size 8×12) with the jacket (20,36).
  - Arms: right (44,20) with sleeve (44,36); left (36,52) with sleeve (52,52). Legs: right (4,20) with pants (4,36); left (20,52) with pants (4,52).
  - Arms and legs are 4×12, but arms are 3×12 on slim skins. On old 64×32 skins, mirror the right arm and leg for the left.
  - Scale with nearest-neighbor so the pixels stay crisp. If the skin can't load, show the default character.

**Mods**
- Title "Mods" and "23 mods, kept up to date automatically."
- "Cube Utility mods": a card for each of my five mods with a monogram icon, name, version and a one-line description, plus an "Updated from 1.5.3" badge when it changed in the latest pack.
- "Extras · you choose": the optional mods as rows with on/off switches, and a note under them: "Changes apply right away, or when you close Minecraft if it's running."
- "Performance and libraries": one card of name chips, always on.
- "Your extra mods" appears only when the player added jars themselves (rule 7 in 3.5).
- Two tabs at the top right, in the same pill as the main tabs: **Installed** (everything above) and **Browse Modrinth**. Browse Modrinth is left out when the pack sets `allowExtraMods: false`.
- **Browse Modrinth**, built into the screen (not the website): a search field and a list of mods from Modrinth's API that work with the pack's Minecraft version and loader and run on the player's side. Before the player types, it shows the most downloaded ones, leaving out mods the pack already has. Each row has the icon, name, author, a two-line description, downloads, a link to the mod's Modrinth page (opens the browser) and **Install**, or "In the pack" / "Installed". A note under the search: on CubeCraft, use only mods its rules allow.
- **Install** picks the newest release for the pack's game (else beta, else alpha), adds the libraries it requires that the pack doesn't have, checks each file's SHA-512 from the API, and puts the jars in `mods/`. It refuses mods the pack has, mods marked incompatible with ones already there, and mods that need a pack mod the player switched off (and says which switch to turn on). Installed mods load with the next game.
- "From Modrinth" on the Installed tab lists what the player installed there, with the libraries that came with each, and **Remove**, which also removes libraries no other mod needs (not while Minecraft runs). These are the player's own extra mods: the launcher never updates them, and if the pack later includes the same mod, the pack's copy replaces the player's. `state/modrinth-mods.json` records what came from Modrinth.

**Settings**
- Memory slider (2 GB up to 16 GB or half the system RAM, whichever is lower), with a hint that 4–8 GB is plenty.
- Switches: Hide launcher while playing (on), Beta updates (off).
- Small buttons: Open game folder, Open logs, Repair files, Sign out.
- About: launcher, pack, Minecraft, Fabric and Java versions, plus the disclaimers.

**The only other UI**
- First run: welcome → Sign in with Microsoft → install with the striped progress bar → Home.
- A toast when a launcher update is ready: "Launcher update ready · Restart".
- The crash message from 3.3.
- Tray menu: Play CubeCraft, Quit.

**In the game** (the `cubeutilityclient` mod in `mod/`, shipped in the pack). Minecraft's own screens get the launcher's colours and type (section 6):
- **Title screen:** deep space with a slowly turning spiral galaxy on the right, stars drifting at three depths (they lean a little toward the mouse) and now and then a shooting star. On the left, "CUBE UTILITY" over a gold "CLIENT" in large Manrope ExtraLight, and under it one row: a gold **Play CubeCraft** pill that joins play.cubecraft.net, then glass **Singleplayer** and **Multiplayer** pills. Small round icon buttons at the top right: Mods (only when Mod Menu is in the pack), Settings, Quit. The credits link sits at the bottom right. Nothing else: no panorama, logo, splash text, Realms or version text. Everything rises and fades in when the screen opens, and hovered buttons turn gold.
- **Other menus:** Minecraft's buttons and sliders become glass pills with a hairline border that turns gold on hover; sliders fill gold, and labels use Manrope. Outside a world, all menu text uses Manrope and the galaxy shows behind the menus under a dark teal wash with gold header lines. In a world, other text keeps Minecraft's font, because servers draw parts of their menus with extra glyphs in it.
- **Window:** titled "Cube Utility Client", with the app icon and a dark title bar instead of the white one.
- The title screen is laid out at 1280 × 720 and scales with the window. In small windows, text stops shrinking at 10 px and icons at 16 px, and icons are drawn as shapes at their exact pixel size.

## 6. Style (from the reference image)

Use the reference image only for colors, type and mood. These tokens are my measurements of it, and they're already in `src/styles.css`.

**Mood:** dark, premium, calm. A near-black teal background with soft teal glows, muted gold accents, big light-weight type, lots of space, and borders you barely see.

**Colors** (CSS variables):

| Token | Value | Use |
|---|---|---|
| `--bg` | `#0A1112` | Background, with soft teal radial glows: one behind the character, one at the top left |
| `--surface-1` | `#0F1819` | Cards |
| `--surface-2` | `#132021` | Inputs, chips, the tab pill |
| `--surface-3` | `#1D3031` | Active tab |
| `--border` / `--border-strong` | `rgba(255,255,255,.06)` / `rgba(255,255,255,.12)` | 1 px borders |
| `--text` / `--text-2` / `--text-3` | `#E9EFEE` / `#9AABA9` / `#7E8F8D` | Primary / secondary / hints |
| `--gold` / `--gold-bright` / `--gold-dim` | `#CDA76A` / `#E4C084` / `#8E7448` | Brand, PLAY, active tab, switches |
| `--teal` / `--teal-bright` | `#2F9C95` / `#46C2B8` | Glows, icons |
| `--green` | `#46D399` | "Up to date" |
| `--crimson` | `#E0395E` | Errors, the close button's hover |

**Type:** Manrope (OFL license; bundled locally).
- Home headline: 60/66 at weight 300 with letter-spacing -0.03em.
- Screen titles: 44/52 at weight 300.
- Gold caps labels: 13 px, weight 700, letter-spacing 0.16em.
- Body text: 15 to 17 px. Secondary text: 13 px in `--text-2`.

**Shape:** cards radius 14 to 16, buttons 10 to 16, fully round switches. 1 px borders. No heavy shadows.

**Components:**
- **PLAY:** gradient `#EFD29C → #CDA76A → #A9844B`, text `#1A1408` at 22 px, weight 800, letter-spacing 0.14em, with a soft gold glow on hover.
- **Tabs:** a pill container in `--surface-2`; the active tab gets `--surface-3` and gold text.
- **Switches:** off is a dark track with a grey knob; on is a gold track with a dark knob.
- **Striped progress bar** (the one element borrowed from the reference): a bordered container whose fill is 2 px vertical stripes, light `#C9D3D2` where filled and dark where empty. The stripes slide while downloading.
- **Status dot:** 8 px, with a soft glow in the status color.
- **Character stage:** a teal radial glow, a thin gold ring, a dashed inner ring and a floor shadow. The character floats 10 px up and down every 4.5 seconds.

**Motion** (honor `prefers-reduced-motion`): screens fade in and rise 8 px over 300 ms. The character floats, and PLAY glows on hover. Nothing else moves.

**Icons:** lucide-react at 1.5 px stroke.

**Accessibility:** text contrast of at least 4.5:1, visible gold focus rings, everything reachable by keyboard, labels on icon buttons, and switches built as real buttons with `role="switch"`.

## 7. Security and legal (non-negotiable)

- **The UI can do almost nothing on its own.** Tauri capabilities (`src-tauri/capabilities/`) allow only window dragging, minimize, maximize and close, plus our own commands. Don't add the fs, shell, http or dialog plugins to the UI. Every file and network action lives in Rust, behind narrow commands that validate their input (for example, a mod id must exist in the current manifest).
- **Content Security Policy** is set in `tauri.conf.json`. Keep it strict; the UI loads nothing remote except skin images from `textures.minecraft.net`.
- **Download allow-list,** enforced in one place in the `net` module: Mojang (`piston-meta.mojang.com`, `piston-data.mojang.com`, `launchermeta.mojang.com`, `libraries.minecraft.net`, `resources.download.minecraft.net`, `textures.minecraft.net`), Fabric (`meta.fabricmc.net`, `maven.fabricmc.net`), `cdn.modrinth.com`, my GitHub Pages host, and GitHub release assets plus the CDN host they redirect to (check which one it is today). `api.modrinth.com` is for the pack tool and the Mods screen's Browse Modrinth: search and version lookups only, while the jars still come from `cdn.modrinth.com` with the SHA-512 the API lists. Verify every hash and the manifest signature, and refuse lower serials.
- Never redistribute Minecraft or Java. Release builds require a real Microsoft account.
- **No telemetry.** Nothing about the player leaves their PC except the requests to Microsoft, Mojang, Modrinth and GitHub that the launcher needs to work.
- **Naming:** never put "Minecraft" in the product name or logo. Show this in About and in the installer: "NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT." Also show: "Not affiliated with CubeCraft Games."
- Follow CubeCraft's rules for client mods: nothing that automates gameplay or gives an unfair advantage.

## 8. Milestones

Finish each one, check it against its test, and show me before moving on.

1. ~~**Shell and style:**~~ **Done.** Tauri + Rust shell, the frameless window with its own controls, and Home, Mods and Settings on sample data (a fake launcher in `src/state/useLauncher.ts`).
2. **Install and launch (Rust):** the `net` and `game` modules install vanilla 26.3, Fabric 0.20.1 and Java 25 into the isolated folder, and PLAY starts the game with the dev offline profile. Replace the fake launcher with Tauri commands and progress events. *Done when* PLAY gets the game to the title screen with Fabric loaded, and unit tests cover the library `rules` and argument building.
3. **Pack tool and sync engine:** `crates/manifest`, `tools/pack` (`pack.toml` → signed manifest), a local test server, and sync with progress, removals, rollback, repair and signature/serial rejection. *Done when* tests cover the planner, the apply step (including a crash halfway through) and the signature checks, and editing `pack.toml` updates a running launcher within one check interval.
4. **Sign-in, skin and Quick Play** (needs the approved Azure client ID). *Done when* a real account shows its own skin on Home and joins `play.cubecraft.net` from PLAY.
5. **Self-update, CI and installer.** *Done when* installing 1.0.0, publishing 1.0.1 and clicking "Restart" in the app works end to end.
6. **Mods and Settings wired up, and polish:** switches that change the synced set, settings saved on the Rust side, offline and error states, the tray menu, an accessibility pass, and the README section "How I ship an update".
7. **The in-game look** (added after milestone 6): the `mod/` Fabric mod from "In the game" in section 5, shipped in the pack. *Done when* the game opens on the new title screen at any window size, Play CubeCraft joins the server, and the menus outside a world use the new style.

## 9. How to work

- Ask me before creating the Azure app or GitHub repos, before publishing anything, and before adding a dependency that isn't listed here.
- "Cube Utility Client" is a working name. Brand text lives in `src/config.ts`; deployment values (manifest URL, signing public key, Azure client ID) live in `src-tauri/src/config.rs`.
- Keep secrets out of git; commit only `.env.example`.
- `cargo clippy` (no warnings), `cargo test`, `npm run typecheck` and `npm run build` must pass before a milestone counts as done.
- Write unit tests for the manifest builder, the signature check, the sync planner, the apply step, library `rules` and argument building.
