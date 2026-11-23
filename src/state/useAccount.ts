import { useCallback, useEffect, useState } from 'react'
import { account, inApp, type AccountInfo, type SavedAccount } from '../native'

/** The browser preview pretends two accounts are signed in, so the account menu can be tried. */
const DEMO_ACCOUNTS: SavedAccount[] = [
  { uuid: 'demo-player', name: 'Player', skin: null, active: true },
  { uuid: 'demo-alt', name: 'PlayerAlt', skin: null, active: false }
]
const DEMO: AccountInfo = { state: 'signedIn', name: 'Player', uuid: 'demo-player', skin: null, slim: false, signInRequired: false, accounts: DEMO_ACCOUNTS, notice: null }

export interface AccountView {
  info: AccountInfo
  /** The Minecraft name, else the Xbox gamertag, else "Player". */
  name: string
  /** A few words under the name in the top bar. */
  note: string
  skin?: string
  slim: boolean
  /** Every account signed in on this PC. */
  accounts: SavedAccount[]
  /** The account being switched to, while it signs in. */
  switching?: string
  signIn: () => void
  cancelSignIn: () => void
  signOut: () => void
  switchTo: (uuid: string) => void
  remove: (uuid: string) => void
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

/** The preview's pretend accounts once one of them plays. */
function demoPlaying(uuid: string, accounts: SavedAccount[]): AccountInfo {
  const list = accounts.map((a) => ({ ...a, active: a.uuid === uuid }))
  const playing = list.find((a) => a.active)
  return playing
    ? { state: 'signedIn', name: playing.name, uuid: playing.uuid, skin: null, slim: false, signInRequired: false, accounts: list, notice: null }
    : { state: 'signedOut', message: null, signInRequired: false, accounts: list, notice: null }
}

export function useAccount(): AccountView {
  const [info, setInfo] = useState<AccountInfo>(inApp ? { state: 'checking', signInRequired: false, accounts: [], notice: null } : DEMO)
  const [switching, setSwitching] = useState<string>()

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
      setInfo((current) => ({ ...current, state: 'signingIn' }))
      setTimeout(() => setInfo(DEMO), 1500)
      return
    }
    account.signIn().catch((error: unknown) => setInfo((current) => ({ ...current, state: 'problem', message: String(error) })))
  }, [])

  const cancelSignIn = useCallback(() => {
    if (inApp) account.cancelSignIn().catch(() => {})
    else setInfo(DEMO)
  }, [])

  const switchTo = useCallback((uuid: string) => {
    setSwitching(uuid)
    if (!inApp) {
      setTimeout(() => {
        setInfo((current) => demoPlaying(uuid, current.accounts))
        setSwitching(undefined)
      }, 700)
      return
    }
    // A failed switch shows its reason as the state's notice.
    account
      .switchTo(uuid)
      .catch(() => {})
      .finally(() => setSwitching(undefined))
  }, [])

  const remove = useCallback((uuid: string) => {
    if (!inApp) {
      setInfo((current) => {
        const left = current.accounts.filter((a) => a.uuid !== uuid)
        const playing = left.find((a) => a.active) ?? left[0]
        return demoPlaying(playing?.uuid ?? '', left)
      })
      return
    }
    account.remove(uuid).catch(() => {})
  }, [])

  const signOut = useCallback(() => {
    if (!inApp) {
      remove(info.accounts.find((a) => a.active)?.uuid ?? '')
      return
    }
    account.signOut().catch(() => {})
  }, [info.accounts, remove])

  return { info, ...describe(info), accounts: info.accounts, switching, signIn, cancelSignIn, signOut, switchTo, remove }
}
