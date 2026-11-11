import { useCallback, useEffect, useState } from 'react'
import { defaultExtras, type ExtrasState } from '../data/pack'
import { getSystemInfo } from '../native'
import type { LaunchTarget } from './useLauncher'

export interface Settings {
  memoryGb: number
  hideWhilePlaying: boolean
  betaUpdates: boolean
  launchTarget: LaunchTarget
  extras: ExtrasState
}

const STORAGE_KEY = 'cuc.settings.v1'

function defaults(): Settings {
  return { memoryGb: 4, hideWhilePlaying: true, betaUpdates: false, launchTarget: 'cubecraft', extras: defaultExtras() }
}

// Milestone 1 keeps settings in localStorage. A later milestone moves them to
// state/settings.json on the Rust side, where the game launch can read them.
function load(): Settings {
  const fallback = defaults()
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return fallback
    const saved = JSON.parse(raw) as Partial<Settings>
    return { ...fallback, ...saved, extras: { ...fallback.extras, ...saved.extras } }
  } catch {
    return fallback
  }
}

export function useSettings() {
  const [settings, setSettings] = useState<Settings>(load)
  const [totalMemoryGb, setTotalMemoryGb] = useState<number | null>(null)

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(settings))
    } catch {
      // Storage can be unavailable; settings then last for this session only.
    }
  }, [settings])

  useEffect(() => {
    let alive = true
    getSystemInfo()
      // Outside the app there is no Rust side; pretend the PC has 16 GB.
      .then((info) => alive && setTotalMemoryGb(info?.totalMemoryGb ?? 16))
      .catch(() => alive && setTotalMemoryGb(8))
    return () => {
      alive = false
    }
  }, [])

  const update = useCallback(<K extends keyof Settings>(key: K, value: Settings[K]) => {
    setSettings((current) => ({ ...current, [key]: value }))
  }, [])

  const setExtra = useCallback((id: string, on: boolean) => {
    setSettings((current) => ({ ...current, extras: { ...current.extras, [id]: on } }))
  }, [])

  // Up to 16 GB or half the PC's memory, whichever is lower.
  const maxMemoryGb = Math.max(2, Math.min(16, Math.floor((totalMemoryGb ?? 8) / 2)))

  return { settings, update, setExtra, maxMemoryGb }
}
