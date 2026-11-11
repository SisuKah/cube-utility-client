import { LogIn } from 'lucide-react'
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
      <span className="block size-[34px] shrink-0 overflow-hidden rounded-[9px] border border-white/10">
        <SkinCanvas mode="face" skinUrl={account.skin} className="pixelated block size-full" />
      </span>
      <span className="flex min-w-0 flex-col leading-tight">
        <span className="truncate text-sm font-semibold">{account.name}</span>
        <span className="truncate text-xs text-text-2">{account.note}</span>
      </span>
    </div>
  )
}
