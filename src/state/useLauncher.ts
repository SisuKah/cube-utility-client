import { useCallback, useEffect, useRef, useState } from 'react'
import { pack as samplePack } from '../data/pack'
import { inApp, launcher, type LauncherPhase, type PackInfo, type PlayOptions } from '../native'

export type Phase =
  | 'checking'
  | 'repairing'
  | 'needsInstall'
  | 'installing'
  | 'updating'
  | 'idle'
  | 'launching'
  | 'playing'
  | 'offline'
  | 'error'
export type LaunchTarget = PlayOptions['target']
export type PreviewState = 'update' | 'ready' | 'offline'

/** The pack as the screens show it. */
export interface PackSummary {
  /** A pack server is set (release builds have none until the GitHub repo exists). */
  configured: boolean
  version: string | null
  minecraft: string
  fabricLoader: string
  java: number | null
  modCount: number
  /** Title of the newest patch notes, like "Cube Utility 1.7.0". */
  whatsNew: string | null
  /** The last time the pack server answered (ms since 1970). */
  checkedAtMs: number | null
  /** An update that failed or was ignored. The installed mods still work. */
  problem: string | null
  /** A newer pack is waiting for the game to close. */
  updateReady: boolean
  extraMods: string[]
}

export interface LauncherState {
  /** True in the browser preview, where everything is sample data. */
  demo: boolean
  phase: Phase
  /** 0–100 while downloading. */
  percent: number
  doneMb: number
  totalMb: number
  currentFile: string
  /** Size of the first install, while it hasn't happened yet. */
  installMb: number
  /** The launcher changed the mods since it opened. */
  justUpdated: boolean
  playSeconds: number
  error: string
  pack: PackSummary
}

const MB = 1024 * 1024

const NO_PACK: PackSummary = {
  configured: false,
  version: null,
  minecraft: samplePack.minecraft,
  fabricLoader: samplePack.fabricLoader,
  java: samplePack.java,
  modCount: 0,
  whatsNew: null,
  checkedAtMs: null,
  problem: null,
  updateReady: false,
  extraMods: []
}

const BASE: Omit<LauncherState, 'demo' | 'phase' | 'pack'> = {
  percent: 100,
  doneMb: 0,
  totalMb: 0,
  currentFile: '',
  installMb: 0,
  justUpdated: false,
  playSeconds: 0,
  error: ''
}

/** The real launcher in the app; a pretend one in the browser preview. */
export function useLauncher(options: PlayOptions) {
  // `inApp` never changes while the page runs, so the hook order stays stable.
  return inApp ? useAppLauncher(options) : useDemoLauncher()
}

function summarize(info: PackInfo | null): PackSummary {
  if (!info) return NO_PACK
  return {
    configured: info.configured,
    version: info.packVersion,
    minecraft: info.minecraft,
    fabricLoader: info.loaderVersion,
    java: info.javaMajor,
    modCount: info.modCount,
    whatsNew: info.notesTitle,
    checkedAtMs: info.checkedAtMs,
    problem: info.problem,
    updateReady: info.updateReady,
    extraMods: info.extraMods
  }
}

function percentOf(done: number, total: number): number {
  return total > 0 ? Math.min(100, Math.floor((done / total) * 100)) : 100
}

function toState(rust: LauncherPhase, info: PackInfo | null, now: number): LauncherState {
  const base = { ...BASE, demo: false, pack: summarize(info), justUpdated: info?.updatedAtMs != null }
  switch (rust.phase) {
    case 'checking':
      return { ...base, phase: 'checking', percent: 0 }
    case 'verifying':
      return { ...base, phase: 'repairing', percent: percentOf(rust.doneFiles, rust.totalFiles) }
    case 'syncing':
      return {
        ...base,
        phase: 'updating',
        percent: percentOf(rust.doneBytes, rust.totalBytes),
        doneMb: rust.doneBytes / MB,
        totalMb: rust.totalBytes / MB,
        currentFile: rust.current
      }
    case 'needsInstall':
      return { ...base, phase: 'needsInstall', installMb: rust.downloadBytes / MB }
    case 'installing':
      return {
        ...base,
        phase: 'installing',
        percent: percentOf(rust.doneBytes, rust.totalBytes),
        doneMb: rust.doneBytes / MB,
        totalMb: rust.totalBytes / MB,
        currentFile: rust.current
      }
    case 'ready':
      return { ...base, phase: info?.offline ? 'offline' : 'idle' }
    case 'launching':
      return { ...base, phase: 'launching' }
    case 'playing':
      return { ...base, phase: 'playing', playSeconds: Math.max(0, Math.floor((now - rust.startedAtMs) / 1000)) }
    case 'error':
      return { ...base, phase: 'error', error: rust.message }
  }
}

function useAppLauncher(options: PlayOptions) {
  const [rust, setRust] = useState<LauncherPhase>({ phase: 'checking' })
  const [info, setInfo] = useState<PackInfo | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const latestOptions = useRef(options)

  useEffect(() => {
    latestOptions.current = options
  })

  // Listen first, then ask for the current state, so no update slips through.
  useEffect(() => {
    let alive = true
    const stops: Array<() => void> = []
    const keep = (stop: () => void) => (alive ? stops.push(stop) : stop())
    launcher.onState((state) => alive && setRust(state)).then(keep).catch(() => {})
    launcher.onPackInfo((pack) => alive && setInfo(pack)).then(keep).catch(() => {})
    launcher
      .state()
      .then((state) => alive && setRust(state))
      .catch(() => {})
    launcher
      .packInfo()
      .then((pack) => alive && setInfo(pack))
      .catch(() => {})
    return () => {
      alive = false
      stops.forEach((stop) => stop())
    }
  }, [])

  // Keeps the "in game for" timer and the "last synced" time fresh.
  useEffect(() => {
    setNow(Date.now())
    const timer = setInterval(() => setNow(Date.now()), rust.phase === 'playing' ? 1000 : 30_000)
    return () => clearInterval(timer)
  }, [rust.phase])

  const fail = (error: unknown) => setRust({ phase: 'error', message: String(error) })
  const play = useCallback(() => {
    launcher.play(latestOptions.current).catch(fail)
  }, [])
  const repair = useCallback(() => {
    launcher.repair().catch(fail)
  }, [])
  const retryUpdate = useCallback(() => {
    launcher.syncNow().catch(() => {})
  }, [])

  return { state: toState(rust, info, now), play, repair, retryUpdate, preview: (_state: PreviewState) => {} }
}

// ---------------------------------------------------------------------------
// The browser preview: a pretend mod update, then PLAY runs a short fake session.

interface SyncFile {
  name: string
  mb: number
}

const UPDATE_FILES: SyncFile[] = [
  { name: 'Cube Utility 1.7.0', mb: 0.66 },
  { name: 'Fabric API 0.166.0', mb: 1.74 },
  { name: 'Sodium Extra 0.9.5', mb: 0.47 },
  { name: 'More Chat History 2.0.0', mb: 0.03 }
]
const TOTAL_MB = UPDATE_FILES.reduce((sum, file) => sum + file.mb, 0)

function fileAt(doneMb: number): string {
  let end = 0
  for (const file of UPDATE_FILES) {
    end += file.mb
    if (doneMb < end) return file.name
  }
  return UPDATE_FILES[UPDATE_FILES.length - 1]?.name ?? ''
}

const DEMO_PACK: PackSummary = {
  ...NO_PACK,
  configured: true,
  version: samplePack.version,
  whatsNew: `${samplePack.whatsNew} and ${samplePack.whatsNewMore} more`
}

function useDemoLauncher() {
  const [phase, setPhase] = useState<Phase>('updating')
  const [doneMb, setDoneMb] = useState(0)
  const [justUpdated, setJustUpdated] = useState(false)
  const [playSeconds, setPlaySeconds] = useState(0)
  const [offlineSince, setOfflineSince] = useState<number | null>(null)

  useEffect(() => {
    if (phase !== 'updating') return
    const timer = setInterval(() => setDoneMb((mb) => Math.min(TOTAL_MB, mb + TOTAL_MB * 0.015)), 80)
    return () => clearInterval(timer)
  }, [phase])

  useEffect(() => {
    if (phase === 'updating' && doneMb >= TOTAL_MB) {
      setPhase('idle')
      setJustUpdated(true)
    }
  }, [phase, doneMb])

  useEffect(() => {
    if (phase !== 'launching') return
    const timer = setTimeout(() => {
      setPlaySeconds(0)
      setPhase('playing')
    }, 2000)
    return () => clearTimeout(timer)
  }, [phase])

  useEffect(() => {
    if (phase !== 'playing') return
    const timer = setInterval(() => setPlaySeconds((s) => s + 1), 1000)
    return () => clearInterval(timer)
  }, [phase])

  // The pretend game "closes" after 10 seconds.
  useEffect(() => {
    if (phase === 'playing' && playSeconds >= 10) setPhase(offlineSince ? 'offline' : 'idle')
  }, [phase, playSeconds, offlineSince])

  const play = useCallback(() => {
    setPhase((current) => (current === 'idle' || current === 'offline' ? 'launching' : current))
  }, [])

  const preview = useCallback((state: PreviewState) => {
    setPlaySeconds(0)
    setJustUpdated(false)
    setOfflineSince(state === 'offline' ? Date.now() - 3 * 60 * 60 * 1000 : null)
    setDoneMb(state === 'update' ? 0 : TOTAL_MB)
    setPhase(state === 'update' ? 'updating' : state === 'offline' ? 'offline' : 'idle')
  }, [])

  const state: LauncherState = {
    ...BASE,
    demo: true,
    phase,
    percent: phase === 'updating' ? Math.floor((doneMb / TOTAL_MB) * 100) : 100,
    doneMb,
    totalMb: TOTAL_MB,
    currentFile: fileAt(doneMb),
    justUpdated,
    playSeconds,
    pack: { ...DEMO_PACK, checkedAtMs: offlineSince }
  }
  return { state, play, repair: () => {}, retryUpdate: () => {}, preview }
}
