import { Check, ChevronDown, LoaderCircle, LogIn, UserPlus, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { config } from '../config'
import type { AccountView } from '../state/useAccount'
import { Logo } from './Logo'
import { SkinCanvas } from './SkinCanvas'
import { WindowControls } from './WindowControls'

export type Tab = 'home' | 'mods' | 'settings'

const TABS: { id: Tab; label: string }[] = [
  { id: 'home', label: 'Home' },
  { id: 'mods', label: 'Mods' },
  { id: 'settings', label: 'Settings' }
]

interface Props {
  tab: Tab
  onTab: (tab: Tab) => void
  account: AccountView
}

// The window has no native title bar. Empty parts of this bar (marked with
// data-tauri-drag-region) move the window; double-clicking them maximizes it.
export function TopBar({ tab, onTab, account }: Props) {
  return (
    <header data-tauri-drag-region className="relative z-10 grid h-[76px] shrink-0 grid-cols-[1fr_auto_1fr] items-center gap-4 pl-8 pr-4">
      <div data-tauri-drag-region className="flex items-center gap-3">
        <Logo className="pointer-events-none size-[38px] shrink-0" />
        <div className="pointer-events-none flex flex-col leading-tight">
          <span className="text-base font-bold text-gold-bright">{config.brand.wordmarkTop}</span>
          <span className="text-[15px] font-medium">{config.brand.wordmarkBottom}</span>
        </div>
      </div>

      <nav aria-label="Main" className="flex gap-1 rounded-xl border border-line bg-surface-2/75 p-1">
        {TABS.map((t) => {
          const active = t.id === tab
          return (
            <button
              key={t.id}
              type="button"
              aria-current={active ? 'page' : undefined}
              onClick={() => onTab(t.id)}
              className={`h-[38px] cursor-pointer rounded-[9px] px-5 text-sm font-semibold transition-colors ${
                active ? 'bg-surface-3 text-gold-bright' : 'text-text-2 hover:text-text'
              }`}
            >
              {t.label}
            </button>
          )
        })}
      </nav>

      <div data-tauri-drag-region className="flex min-w-0 items-center justify-end gap-4">
        <AccountArea account={account} />
        <WindowControls />
      </div>
    </header>
  )
}

const LINK = 'cursor-pointer font-semibold text-gold-bright hover:text-[#f1d7a6] focus-visible:outline-2 focus-visible:outline-gold-bright'

function AccountArea({ account }: { account: AccountView }) {
  const { info } = account

  if (account.accounts.length > 0 && info.state !== 'signingIn' && info.state !== 'checking') {
    return <AccountMenu account={account} />
  }

  if (info.state === 'signedOut' || info.state === 'problem') {
    const problem = info.state === 'problem' ? info.message : undefined
    return (
      <div className="flex min-w-0 items-center gap-3">
        {problem && (
          <span title={problem} className="truncate text-xs text-[#f2a3b4]">
            {account.note}
          </span>
        )}
        <button
          type="button"
          onClick={account.signIn}
          className="inline-flex h-9 shrink-0 cursor-pointer items-center gap-2 rounded-[10px] border border-gold/40 bg-gold/10 px-3.5 text-sm font-semibold text-gold-bright transition-colors hover:bg-gold/15 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gold-bright"
        >
          <LogIn aria-hidden="true" className="size-4" />
          Sign in
        </button>
      </div>
    )
  }

  if (info.state === 'signingIn') {
    return (
      <div className="flex min-w-0 flex-col items-end leading-tight">
        <span className="truncate text-sm font-semibold">Signing in…</span>
        <span className="text-xs text-text-2">
          Finish in your browser ·{' '}
          <button type="button" onClick={account.cancelSignIn} className={LINK}>
            Cancel
          </button>
        </span>
      </div>
    )
  }

  return (
    <div className="pointer-events-none flex min-w-0 items-center gap-2.5" title={info.state === 'awaitingApproval' ? 'Mojang hasn’t approved this launcher’s sign-in yet.' : undefined}>
      <Face skin={account.skin} />
      <span className="flex min-w-0 flex-col leading-tight">
        <span className="truncate text-sm font-semibold">{account.name}</span>
        <span className="truncate text-xs text-text-2">{account.note}</span>
      </span>
    </div>
  )
}

function Face({ skin, small = false }: { skin?: string; small?: boolean }) {
  return (
    <span className={`block shrink-0 overflow-hidden border border-white/10 ${small ? 'size-8 rounded-lg' : 'size-[34px] rounded-[9px]'}`}>
      <SkinCanvas mode="face" skinUrl={skin} className="pixelated block size-full" />
    </span>
  )
}

const MENU_ROW = 'flex w-full cursor-pointer items-center gap-3 rounded-xl px-3 py-2 text-left transition-colors hover:bg-white/5 focus-visible:outline-2 focus-visible:outline-gold-bright'

/**
 * The account that plays, as a button that opens the list of accounts on this PC: click one to
 * play as it, add another, or sign one out. Minecraft has the same list on its title screen.
 */
function AccountMenu({ account }: { account: AccountView }) {
  const { info, accounts, switching } = account
  const [open, setOpen] = useState(false)
  /** The account whose sign-out waits for a second click. */
  const [confirming, setConfirming] = useState<string>()
  const area = useRef<HTMLDivElement>(null)
  const switchedTo = useRef<string | undefined>(undefined)

  // A click elsewhere or Escape closes the menu.
  useEffect(() => {
    if (!open) return
    const onDown = (event: MouseEvent) => {
      if (!area.current?.contains(event.target as Node)) setOpen(false)
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false)
    }
    window.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('mousedown', onDown)
      window.removeEventListener('keydown', onKey)
    }
  }, [open])

  // Closes once a switch worked; a switch that didn't leaves it open with the reason.
  useEffect(() => {
    const target = switchedTo.current
    if (target && !switching) {
      switchedTo.current = undefined
      if (accounts.some((a) => a.uuid === target && a.active)) setOpen(false)
    }
  }, [switching, accounts])

  useEffect(() => {
    if (!confirming) return
    const timer = setTimeout(() => setConfirming(undefined), 4000)
    return () => clearTimeout(timer)
  }, [confirming])

  const playing = accounts.some((a) => a.active)
  const name = playing ? account.name : 'Choose an account'
  const note = info.state === 'signedOut' ? (info.message ?? 'Not signed in') : info.state === 'problem' ? 'Sign-in problem' : account.note

  return (
    <div ref={area} className="relative min-w-0">
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        title={info.state === 'problem' ? info.message : info.state === 'awaitingApproval' ? 'Mojang hasn’t approved this launcher’s sign-in yet.' : undefined}
        className="flex min-w-0 cursor-pointer items-center gap-2.5 rounded-xl py-1 pl-1 pr-2 text-left transition-colors hover:bg-white/5 focus-visible:outline-2 focus-visible:outline-gold-bright"
      >
        <Face skin={playing ? account.skin : undefined} />
        <span className="flex min-w-0 flex-col leading-tight">
          <span className="truncate text-sm font-semibold">{name}</span>
          <span className={`truncate text-xs ${info.state === 'problem' ? 'text-[#f2a3b4]' : 'text-text-2'}`}>{note}</span>
        </span>
        <ChevronDown aria-hidden="true" className={`size-4 shrink-0 text-text-3 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>

      {open && (
        <div
          role="menu"
          aria-label="Accounts"
          className="absolute right-0 top-[calc(100%+8px)] z-30 w-[292px] rounded-2xl border border-line-strong bg-surface-1/95 p-1.5 shadow-[0_18px_48px_rgba(0,0,0,0.45)] backdrop-blur"
        >
          {accounts.map((a) => (
            <div key={a.uuid} className="group flex items-center gap-1">
              <button
                type="button"
                role="menuitemradio"
                aria-checked={a.active}
                disabled={!!switching}
                onClick={() => {
                  if (a.active) return setOpen(false)
                  switchedTo.current = a.uuid
                  account.switchTo(a.uuid)
                }}
                className={`${MENU_ROW} min-w-0 flex-1 disabled:cursor-default`}
              >
                <Face skin={a.skin ?? undefined} small />
                <span className="flex min-w-0 flex-1 flex-col leading-tight">
                  <span className={`truncate text-sm font-semibold ${a.active ? 'text-gold-bright' : ''}`}>{a.name}</span>
                  <span className="truncate text-xs text-text-3">{a.active ? 'Plays now' : switching === a.uuid ? 'Signing in…' : 'Play as this account'}</span>
                </span>
                {a.active && <Check aria-hidden="true" className="size-4 shrink-0 text-gold-bright" />}
                {switching === a.uuid && <LoaderCircle aria-hidden="true" className="size-4 shrink-0 animate-spin text-gold-bright" />}
              </button>
              {confirming === a.uuid ? (
                <button
                  type="button"
                  onClick={() => {
                    setConfirming(undefined)
                    account.remove(a.uuid)
                  }}
                  className="mr-1 shrink-0 cursor-pointer rounded-lg px-2 py-1.5 text-xs font-semibold text-[#f2a3b4] hover:bg-[#e0395e]/10 focus-visible:outline-2 focus-visible:outline-gold-bright"
                >
                  Sign out
                </button>
              ) : (
                <button
                  type="button"
                  aria-label={`Sign out ${a.name}`}
                  title={`Sign out ${a.name}`}
                  disabled={!!switching}
                  onClick={() => setConfirming(a.uuid)}
                  className="mr-1 grid size-8 shrink-0 cursor-pointer place-items-center rounded-lg text-text-3 opacity-0 transition-opacity hover:bg-white/5 hover:text-text focus-visible:opacity-100 focus-visible:outline-2 focus-visible:outline-gold-bright group-hover:opacity-100 disabled:hidden"
                >
                  <X aria-hidden="true" className="size-4" />
                </button>
              )}
            </div>
          ))}
          {info.notice && <p className="px-3 py-2 text-xs leading-snug text-[#f2a3b4]">{info.notice}</p>}
          <div className="mx-2 my-1 h-px bg-line" />
          <button type="button" role="menuitem" disabled={!!switching} onClick={account.signIn} className={`${MENU_ROW} text-sm font-semibold text-gold-bright`}>
            <span className="grid size-8 shrink-0 place-items-center rounded-lg border border-gold/30 bg-gold/10">
              <UserPlus aria-hidden="true" className="size-4" />
            </span>
            Add account
          </button>
          <p className="px-3 pb-2 pt-1 text-[11px] leading-snug text-text-3">Minecraft has this list too, on its title screen, so you can switch without closing the game.</p>
        </div>
      )}
    </div>
  )
}
