import { useCallback, useEffect, useState } from 'react'
import { getSystemInfo, inApp, settingsStore, type SettingsChange, type SettingsView } from '../native'
import type { LaunchTarget } from './useLauncher'

export interface Settings {
  memoryGb: number
  hideWhilePlaying: boolean
  betaUpdates: boolean
  launchTarget: LaunchTarget
}

// Older versions kept settings in browser storage. The app now keeps them on the Rust side
// (state/settings.json), so PLAY can read them; the browser preview still uses this key.
const STORAGE_KEY = 'cuc.settings.v1'

const DEFAULTS: Settings = { memoryGb: 4, hideWhilePlaying: true, betaUpdates: false, launchTarget: 'cubecraft' }

function loadStored(): Partial<Settings> | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const { memoryGb, hideWhilePlaying, betaUpdates, launchTarget } = JSON.parse(raw) as Partial<Settings>
    return { memoryGb, hideWhilePlaying, betaUpdates, launchTarget }
  } catch {
    return null
  }
}

function store(settings: Settings) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings))
  } catch {
    // Storage can be unavailable; settings then last for this session only.
  }
}

function forgetStored() {
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Nothing to clean up.
  }
}

function fromView(view: SettingsView): Settings {
  return { memoryGb: view.memoryGb, hideWhilePlaying: view.hideWhilePlaying, betaUpdates: view.betaUpdates, launchTarget: view.launchTarget }
}

export function useSettings() {
  const [settings, setSettings] = useState<Settings>(() => (inApp ? DEFAULTS : { ...DEFAULTS, ...loadStored() }))
  const [maxMemoryGb, setMaxMemoryGb] = useState(8)

  useEffect(() => {
    let alive = true
    if (inApp) {
      settingsStore
        .get()
        .then(async (view) => {
          // The first time: move settings an older version kept in browser storage.
          const old = view.fresh ? loadStored() : null
          if (old) {
            view = await settingsStore.update(old)
            forgetStored()
          }
          if (!alive) return
          setSettings(fromView(view))
          setMaxMemoryGb(view.memory.maxGb)
        })
        .catch(() => {})
    } else {
      // Outside the app there is no Rust side; pretend the PC has 16 GB.
      getSystemInfo()
        .then((info) => alive && setMaxMemoryGb(Math.max(2, Math.min(16, Math.floor((info?.totalMemoryGb ?? 16) / 2)))))
        .catch(() => {})
    }
    return () => {
      alive = false
    }
  }, [])

  const update = useCallback(<K extends keyof Settings>(key: K, value: Settings[K]) => {
    setSettings((current) => {
      const next = { ...current, [key]: value }
      if (!inApp) store(next)
      return next
    })
    if (inApp) {
      const change: SettingsChange = { [key]: value }
      settingsStore
        .update(change)
        .then((view) => setSettings(fromView(view)))
        .catch(() => {})
    }
  }, [])

  return { settings, update, maxMemoryGb }
}
