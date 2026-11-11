import { FileText, FolderOpen, LogOut, Wrench } from 'lucide-react'
import { config } from '../config'
import { Switch } from '../components/Switch'
import { pack } from '../data/pack'
import type { Settings as SettingsState } from '../state/useSettings'

interface Props {
  settings: SettingsState
  maxMemoryGb: number
  onChange: <K extends keyof SettingsState>(key: K, value: SettingsState[K]) => void
}

const TOGGLES = [
  { key: 'hideWhilePlaying', name: 'Hide launcher while playing', description: 'It comes back when you close the game.' },
  { key: 'betaUpdates', name: 'Beta updates', description: 'Get new versions of the mods a little earlier. They may have bugs.' }
] as const

// These start working in later milestones (game folder, logs, repair, sign-in).
const ACTIONS = [
  { label: 'Open game folder', Icon: FolderOpen },
  { label: 'Open logs', Icon: FileText },
  { label: 'Repair files', Icon: Wrench },
  { label: 'Sign out', Icon: LogOut }
]

export function Settings({ settings, maxMemoryGb, onChange }: Props) {
  const memory = Math.min(settings.memoryGb, maxMemoryGb)

  return (
    <div className="mx-auto flex w-full max-w-[760px] flex-col gap-4 px-8 pb-8 pt-2">
      <h1 className="mb-2 text-[44px] font-light leading-[52px] tracking-[-0.02em]">Settings</h1>

      <section className="rounded-2xl border border-line bg-surface-1 px-[22px] py-5">
        <div className="flex items-baseline justify-between gap-3">
          <label htmlFor="memory" className="text-[15px] font-semibold">
            Memory for Minecraft
          </label>
          <span className="text-[28px] font-light tabular-nums">
            {memory}
            <span className="ml-1 text-[15px] text-text-2">GB</span>
          </span>
        </div>
        <input
          id="memory"
          type="range"
          min={2}
          max={maxMemoryGb}
          step={1}
          value={memory}
          onChange={(e) => onChange('memoryGb', Number(e.target.value))}
          className="mt-3.5 w-full cursor-pointer"
        />
        <div className="mt-1.5 flex justify-between gap-2 text-xs text-text-3">
          <span>2 GB</span>
          <span>4–8 GB is plenty for this pack</span>
          <span>{maxMemoryGb} GB</span>
        </div>
      </section>

      <section className="rounded-2xl border border-line bg-surface-1 px-[22px] py-1">
        {TOGGLES.map((toggle, i) => (
          <div key={toggle.key} className={`flex items-center gap-4 py-4 ${i < TOGGLES.length - 1 ? 'border-b border-line' : ''}`}>
            <div className="min-w-0 flex-1">
              <div className="text-[15px] font-semibold">{toggle.name}</div>
              <div className="mt-0.5 text-[13px] leading-[19px] text-text-2">{toggle.description}</div>
            </div>
            <Switch checked={settings[toggle.key]} onChange={(on) => onChange(toggle.key, on)} label={toggle.name} />
          </div>
        ))}
      </section>

      <section className="rounded-2xl border border-line bg-surface-1 px-[22px] py-5">
        <div className="flex flex-wrap gap-2">
          {ACTIONS.map(({ label, Icon }) => (
            <button
              key={label}
              type="button"
              disabled
              className="inline-flex h-9 items-center gap-2 rounded-[10px] border border-white/10 bg-surface-2 px-3.5 text-sm text-text-2 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <Icon aria-hidden="true" className="size-4 text-gold" strokeWidth={1.6} />
              {label}
            </button>
          ))}
        </div>
        <p className="mt-2.5 text-xs text-text-3">These buttons start working in later milestones.</p>
      </section>

      <section className="rounded-2xl border border-line bg-surface-1 px-[22px] py-[18px]">
        <h2 className="text-[15px] font-semibold">About</h2>
        <p className="mt-1.5 text-[13px] leading-5 text-text-2">
          Launcher {__APP_VERSION__} · Pack {pack.version} · Minecraft {pack.minecraft} · Fabric Loader {pack.fabricLoader} · Java {pack.java}
        </p>
        <p className="mt-1.5 text-xs leading-[18px] text-text-3">{config.disclaimer}</p>
      </section>
    </div>
  )
}
