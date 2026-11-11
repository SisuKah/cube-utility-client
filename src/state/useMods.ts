import { useCallback, useEffect, useState } from 'react'
import { alwaysOn, defaultExtras, extraMods, ownMods } from '../data/pack'
import { inApp, launcher, modrinth, type ModsView } from '../native'

export interface ModsState {
  view: ModsView | null
  /** Switches an optional mod on or off. */
  toggle: (id: string, on: boolean) => void
  error: string
  /** Removes a mod installed from Modrinth, with the libraries only it needed. */
  remove: (projectId: string) => void
  /** Mods being removed right now. */
  removing: string[]
  removeError: string
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

  const [removing, setRemoving] = useState<string[]>([])
  const [removeError, setRemoveError] = useState('')
  const remove = useCallback((projectId: string) => {
    setRemoveError('')
    setRemoving((current) => [...current, projectId])
    // The list reloads on its own once Rust reports the mods folder changed.
    modrinth
      .remove(projectId)
      .catch((reason: unknown) => setRemoveError(String(reason)))
      .finally(() => setRemoving((current) => current.filter((id) => id !== projectId)))
  }, [])

  return { view, toggle, error, remove, removing, removeError }
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
    modrinth: [],
    playerJars: [],
    disabledJars: [],
    allowExtraMods: true
  }
  const toggle = useCallback((id: string, on: boolean) => setEnabled((current) => ({ ...current, [id]: on })), [])
  return { view, toggle, error: '', remove: () => {}, removing: [], removeError: '' }
}

/** How many mods the view installs: own, always-on, the extras that are on and their libraries. */
export function countMods(view: ModsView): number {
  const on = view.extras.filter((e) => e.enabled)
  return view.own.length + view.always.length + on.length + new Set(on.flatMap((e) => e.needs)).size
}
