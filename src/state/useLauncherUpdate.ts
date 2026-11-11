import { useCallback, useEffect, useState } from 'react'
import { inApp, selfUpdate, type LauncherUpdate } from '../native'

/** A launcher update that's downloaded and waiting for "Restart" (or for the launcher to close). */
export function useLauncherUpdate() {
  const [update, setUpdate] = useState<LauncherUpdate | null>(null)
  const [dismissed, setDismissed] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!inApp) return
    let alive = true
    let stop: (() => void) | undefined
    selfUpdate
      .onReady((ready) => {
        if (!alive) return
        setUpdate(ready)
        setDismissed(false)
      })
      .then((unlisten) => (alive ? (stop = unlisten) : unlisten()))
      .catch(() => {})
    selfUpdate
      .ready()
      .then((ready) => alive && ready && setUpdate(ready))
      .catch(() => {})
    return () => {
      alive = false
      stop?.()
    }
  }, [])

  const restart = useCallback(() => {
    setError('')
    // The browser preview has nothing to install.
    if (!inApp) return setDismissed(true)
    selfUpdate.restart().catch((reason: unknown) => setError(String(reason)))
  }, [])

  /** Browser preview: pretend an update has downloaded. */
  const preview = useCallback(() => {
    setUpdate({ version: '1.0.1' })
    setDismissed(false)
  }, [])

  return { update: dismissed ? null : update, error, restart, dismiss: () => setDismissed(true), preview }
}
