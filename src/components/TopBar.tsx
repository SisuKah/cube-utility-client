import { config } from '../config'
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
  playerName: string
  accountNote: string
}

// The window has no native title bar. Empty parts of this bar (marked with
// data-tauri-drag-region) move the window; double-clicking them maximizes it.
export function TopBar({ tab, onTab, playerName, accountNote }: Props) {
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
        <div className="pointer-events-none flex min-w-0 items-center gap-2.5">
          <span className="block size-[34px] shrink-0 overflow-hidden rounded-[9px] border border-white/10">
            <SkinCanvas mode="face" className="pixelated block size-full" />
          </span>
          <span className="flex min-w-0 flex-col leading-tight">
            <span className="truncate text-sm font-semibold">{playerName}</span>
            <span className="truncate text-xs text-text-2">{accountNote}</span>
          </span>
        </div>
        <WindowControls />
      </div>
    </header>
  )
}
