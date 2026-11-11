import { Minus, Square, X } from 'lucide-react'
import { appWindow, inApp } from '../native'

const BUTTON = 'flex size-9 cursor-pointer items-center justify-center rounded-lg text-text-2 transition-colors'

/** Minimize, maximize and close for the frameless window. Hidden outside the app. */
export function WindowControls() {
  if (!inApp) return null
  return (
    <div className="flex items-center gap-0.5">
      <button type="button" aria-label="Minimize" onClick={() => void appWindow.minimize()} className={`${BUTTON} hover:bg-white/8 hover:text-text`}>
        <Minus aria-hidden="true" className="size-4" strokeWidth={1.8} />
      </button>
      <button type="button" aria-label="Maximize" onClick={() => void appWindow.toggleMaximize()} className={`${BUTTON} hover:bg-white/8 hover:text-text`}>
        <Square aria-hidden="true" className="size-3.5" strokeWidth={1.8} />
      </button>
      <button type="button" aria-label="Close" onClick={() => void appWindow.close()} className={`${BUTTON} hover:bg-crimson hover:text-white`}>
        <X aria-hidden="true" className="size-4" strokeWidth={1.8} />
      </button>
    </div>
  )
}
