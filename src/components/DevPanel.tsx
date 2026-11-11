import { useState } from 'react'
import { inApp } from '../native'
import type { PreviewState } from '../state/useLauncher'

const OPTIONS: { id: PreviewState; label: string }[] = [
  { id: 'update', label: 'Run update' },
  { id: 'ready', label: 'Up to date' },
  { id: 'offline', label: 'Offline' }
]

interface Props {
  onPreview: (state: PreviewState) => void
  onPreviewLauncherUpdate: () => void
}

const BUTTON = 'cursor-pointer rounded-md px-2 py-1 text-text-2 hover:bg-surface-3 hover:text-text'

/** Browser preview only: a small "Dev" button that switches the pretend launcher between its states. */
export function DevPanel({ onPreview, onPreviewLauncherUpdate }: Props) {
  const [open, setOpen] = useState(false)
  if (!import.meta.env.DEV || inApp) return null

  return (
    <div className="fixed bottom-3 left-3 z-50 flex items-center gap-1 rounded-lg border border-line bg-surface-1/90 p-1 text-xs text-text-3 backdrop-blur">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="cursor-pointer rounded-md px-2 py-1 font-semibold text-text-3 hover:text-text"
      >
        Dev
      </button>
      {open && (
        <>
          {OPTIONS.map((o) => (
            <button key={o.id} type="button" onClick={() => onPreview(o.id)} className={BUTTON}>
              {o.label}
            </button>
          ))}
          <button type="button" onClick={onPreviewLauncherUpdate} className={BUTTON}>
            Launcher update
          </button>
        </>
      )}
    </div>
  )
}
