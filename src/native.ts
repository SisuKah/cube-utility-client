// The UI's only door to the Rust side. Outside the app (`npm run dev:web` in a normal
// browser) nothing here is called, so the screens still work for design work.
import { invoke, isTauri } from '@tauri-apps/api/core'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import { getCurrentWindow } from '@tauri-apps/api/window'

export const inApp = isTauri()

export interface SystemInfo {
  totalMemoryGb: number
}

/** Facts about the player's PC, read in Rust. Returns null outside the app. */
export async function getSystemInfo(): Promise<SystemInfo | null> {
  return inApp ? invoke<SystemInfo>('system_info') : null
}

/** The launcher's state as Rust reports it (see src-tauri/src/launcher.rs). */
export type LauncherPhase =
  | { phase: 'checking' }
  | { phase: 'syncing'; doneBytes: number; totalBytes: number; current: string }
  | { phase: 'verifying'; doneFiles: number; totalFiles: number }
  | { phase: 'needsInstall'; downloadBytes: number }
  | { phase: 'installing'; doneBytes: number; totalBytes: number; current: string }
  | { phase: 'ready' }
  | { phase: 'launching' }
  | { phase: 'playing'; startedAtMs: number }
  | { phase: 'error'; message: string }

export interface PlayOptions {
  target: 'cubecraft' | 'title'
  memoryGb: number
  hideWhilePlaying: boolean
}

/** What Rust knows about the installed pack (PackInfo in src-tauri/src/launcher.rs). */
export interface PackInfo {
  /** A pack server is set. Release builds have none until the GitHub repo exists. */
  configured: boolean
  channel: string
  packVersion: string | null
  minecraft: string
  loaderVersion: string
  javaMajor: number | null
  modCount: number
  notesTitle: string | null
  notes: string | null
  /** The last time the pack server answered (ms since 1970). */
  checkedAtMs: number | null
  offline: boolean
  problem: string | null
  /** A newer pack is waiting for the game to close. */
  updateReady: boolean
  /** When the launcher last changed the mods while it was open (ms since 1970). */
  updatedAtMs: number | null
  extraMods: string[]
  disabledMods: string[]
}

export const launcher = {
  state: () => invoke<LauncherPhase>('launcher_state'),
  packInfo: () => invoke<PackInfo>('pack_info'),
  /** Updates the mods, installs anything missing, then starts the game. Progress arrives through onState. */
  play: (options: PlayOptions) => invoke<void>('play', { options }),
  /** Re-checks every mod and game file and downloads what's damaged. */
  repair: () => invoke<void>('repair'),
  /** Looks for a pack update now. */
  syncNow: () => invoke<void>('sync_now'),
  setModEnabled: (id: string, enabled: boolean) => invoke<void>('set_mod_enabled', { id, enabled }),
  onState: (callback: (state: LauncherPhase) => void): Promise<UnlistenFn> =>
    listen<LauncherPhase>('launcher://state', (event) => callback(event.payload)),
  onPackInfo: (callback: (info: PackInfo) => void): Promise<UnlistenFn> =>
    listen<PackInfo>('pack://info', (event) => callback(event.payload))
}

/** Who's signed in (AccountState in src-tauri/src/auth/mod.rs). */
export type AccountState =
  | { state: 'signedOut'; message: string | null }
  | { state: 'checking' }
  | { state: 'signingIn' }
  | { state: 'signedIn'; name: string; uuid: string; skin: string | null; slim: boolean }
  | { state: 'awaitingApproval'; gamertag: string | null }
  | { state: 'problem'; message: string }
  | { state: 'offline'; name: string; uuid: string; skin: string | null }

/** Release builds only start the game for a signed-in player. */
export type AccountInfo = AccountState & { signInRequired: boolean }

export const account = {
  state: () => invoke<AccountInfo>('account_state'),
  /** Opens Microsoft's sign-in page in the player's browser. */
  signIn: () => invoke<void>('sign_in'),
  cancelSignIn: () => invoke<void>('cancel_sign_in'),
  signOut: () => invoke<void>('sign_out'),
  onState: (callback: (info: AccountInfo) => void): Promise<UnlistenFn> =>
    listen<AccountInfo>('account://state', (event) => callback(event.payload))
}

/** A newer launcher, downloaded and waiting to be installed. */
export interface LauncherUpdate {
  version: string
}

export const selfUpdate = {
  ready: () => (inApp ? invoke<LauncherUpdate | null>('update_ready') : Promise.resolve(null)),
  /** Installs the update now; the launcher closes and the new version opens. */
  restart: () => invoke<void>('restart_to_update'),
  onReady: (callback: (update: LauncherUpdate) => void): Promise<UnlistenFn> =>
    listen<LauncherUpdate>('update://ready', (event) => callback(event.payload))
}

/** Controls for the frameless window. */
export const appWindow = {
  minimize: () => (inApp ? getCurrentWindow().minimize() : Promise.resolve()),
  toggleMaximize: () => (inApp ? getCurrentWindow().toggleMaximize() : Promise.resolve()),
  close: () => (inApp ? getCurrentWindow().close() : Promise.resolve())
}
