import { useCallback, useEffect, useState } from 'react'
import { account, inApp, type AccountInfo } from '../native'

/** The browser preview pretends someone called "Player" is signed in. */
const DEMO: AccountInfo = { state: 'signedIn', name: 'Player', uuid: '', skin: null, slim: false, signInRequired: false }

export interface AccountView {
  info: AccountInfo
  /** The Minecraft name, else the Xbox gamertag, else "Player". */
  name: string
  /** A few words under the name in the top bar. */
  note: string
  skin?: string
  slim: boolean
  signIn: () => void
  cancelSignIn: () => void
  signOut: () => void
}

function describe(info: AccountInfo): { name: string; note: string; skin?: string; slim: boolean } {
  switch (info.state) {
    case 'signedIn':
      return { name: info.name, note: 'Microsoft account', skin: info.skin ?? undefined, slim: info.slim }
    case 'offline':
      return { name: info.name, note: 'Offline', skin: info.skin ?? undefined, slim: false }
    case 'awaitingApproval':
      return { name: info.gamertag ?? 'Signed in', note: 'Waiting for Mojang', slim: false }
    case 'checking':
      return { name: 'Player', note: 'Checking sign-in…', slim: false }
    case 'signingIn':
      return { name: 'Player', note: 'Finish in your browser', slim: false }
    case 'problem':
      return { name: 'Player', note: 'Sign-in problem', slim: false }
    case 'signedOut':
      return { name: 'Player', note: 'Not signed in', slim: false }
  }
}

export function useAccount(): AccountView {
  const [info, setInfo] = useState<AccountInfo>(inApp ? { state: 'checking', signInRequired: false } : DEMO)

  // Listen first, then ask for the current state, so no change slips through.
  useEffect(() => {
    if (!inApp) return
    let alive = true
    let stop: (() => void) | undefined
    account
      .onState((next) => alive && setInfo(next))
      .then((unlisten) => (alive ? (stop = unlisten) : unlisten()))
      .catch(() => {})
    account
      .state()
      .then((next) => alive && setInfo(next))
      .catch(() => {})
    return () => {
      alive = false
      stop?.()
    }
  }, [])

  const signIn = useCallback(() => {
    if (!inApp) {
      setInfo({ state: 'signingIn', signInRequired: false })
      setTimeout(() => setInfo(DEMO), 1500)
      return
    }
    account.signIn().catch((error: unknown) => setInfo((current) => ({ state: 'problem', message: String(error), signInRequired: current.signInRequired })))
  }, [])

  const cancelSignIn = useCallback(() => {
    if (inApp) account.cancelSignIn().catch(() => {})
    else setInfo({ state: 'signedOut', message: null, signInRequired: false })
  }, [])

  const signOut = useCallback(() => {
    if (inApp) account.signOut().catch(() => {})
    else setInfo({ state: 'signedOut', message: null, signInRequired: false })
  }, [])

  return { info, ...describe(info), signIn, cancelSignIn, signOut }
}
