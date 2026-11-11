import { useCallback, useEffect, useState } from 'react'
import { alwaysOn, defaultExtras, extraMods, ownMods } from '../data/pack'
import { inApp, launcher, type ModsView } from '../native'

export interface ModsState {
  view: ModsView | null
  /** Switches an optional mod on or off. */
  toggle: (id: string, on: boolean) => void
  error: string
}

/** The Mods screen's data: the installed pack in the app, sample data in the browser preview. */
export function useMods(): ModsState {
  return inApp ? useAppMods() : useDemoMods()
}

function useAppMods(): ModsState {
  const [view, setView] = useState<ModsView | null>(null)
  const [error, setError] = useState('')

  // Fetch now, and again whenever the pack changes (an update, or a switch applied).
  useEffect(() => {
    let alive = true
    let stop: (() => void) | undefined
    const load = () =>
      launcher
        .modsView()
        .then((next) => alive && setView(next))
        .catch(() => {})
    launcher
      .onPackInfo(() => void load())
      .then((unlisten) => (alive ? (stop = unlisten) : unlisten()))
      .catch(() => {})
    void load()
    return () => {
      alive = false
      stop?.()
    }
  }, [])

  const toggle = useCallback((id: string, on: boolean) => {
    setError('')
    // Show the switch moved right away; the sync installs or removes the mod.
    setView((current) => current && { ...current, extras: current.extras.map((e) => (e.id === id ? { ...e, enabled: on } : e)) })
    launcher.setModEnabled(id, on).catch((reason: unknown) => {
      setError(String(reason))
      setView((current) => current && { ...current, extras: current.extras.map((e) => (e.id === id ? { ...e, enabled: !on } : e)) })
    })
  }, [])

  return { view, toggle, error }
}

function useDemoMods(): ModsState {
  const [enabled, setEnabled] = useState(defaultExtras)
  const view: ModsView = {
    installed: true,
    own: ownMods.map((mod) => ({ id: mod.id, name: mod.name, version: mod.version, description: mod.description, updatedFrom: mod.updatedFrom ?? null })),
    extras: extraMods.map((mod) => ({
      id: mod.id,
      name: mod.name,
      version: mod.version,
      description: mod.description,
      enabled: Boolean(enabled[mod.id]),
      needs: mod.needs ?? []
    })),
    always: alwaysOn,
    playerJars: [],
    disabledJars: [],
    allowExtraMods: true
  }
  const toggle = useCallback((id: string, on: boolean) => setEnabled((current) => ({ ...current, [id]: on })), [])
  return { view, toggle, error: '' }
}

/** How many mods the view installs: own, always-on, the extras that are on and their libraries. */
export function countMods(view: ModsView): number {
  const on = view.extras.filter((e) => e.enabled)
  return view.own.length + view.always.length + on.length + new Set(on.flatMap((e) => e.needs)).size
}
