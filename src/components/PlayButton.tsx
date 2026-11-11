import { LoaderCircle, LogIn, Play } from 'lucide-react'
import type { Phase } from '../state/useLauncher'

/** Takes over the button while sign-in stands between the player and PLAY. */
export interface ButtonOverride {
  label: string
  /** Shows the spinner. */
  busy?: boolean
  /** Without an action the button is disabled. */
  onClick?: () => void
}

interface Props {
  phase: Phase
  percent: number
  onPlay: () => void
  override?: ButtonOverride
}

const LABELS: Record<Phase, string> = {
  idle: 'PLAY',
  offline: 'PLAY',
  needsInstall: 'INSTALL & PLAY',
  error: 'TRY AGAIN',
  checking: 'CHECKING',
  repairing: 'REPAIRING',
  installing: 'INSTALLING',
  updating: 'UPDATING',
  launching: 'STARTING',
  playing: 'IN GAME'
}

const BUSY: Phase[] = ['checking', 'repairing', 'installing', 'updating', 'launching']
const DOWNLOADING: Phase[] = ['installing', 'updating']

export function PlayButton({ phase, percent, onPlay, override }: Props) {
  const downloading = !override && DOWNLOADING.includes(phase)
  const busy = override ? Boolean(override.busy) : BUSY.includes(phase)
  const disabled = override ? !override.onClick : busy || phase === 'playing'
  const label = override ? override.label : downloading ? `${LABELS[phase]} ${percent}%` : LABELS[phase]
  // A waiting state with nothing to click has no icon, which leaves room for longer labels.
  const Icon = busy ? LoaderCircle : !override ? Play : override.onClick ? LogIn : null

  return (
    <button
      type="button"
      onClick={override ? override.onClick : onPlay}
      disabled={disabled}
      className="relative inline-flex h-[76px] w-[340px] max-w-full cursor-pointer items-center justify-center gap-3.5 overflow-hidden rounded-2xl bg-linear-135 from-[#efd29c] via-gold to-[#a9844b] px-7 text-ink shadow-[0_10px_30px_rgb(205_167_106/0.18),inset_0_1px_0_rgb(255_255_255/0.35)] transition-[box-shadow,opacity] duration-200 enabled:hover:shadow-[0_0_44px_rgb(228_192_132/0.38),inset_0_1px_0_rgb(255_255_255/0.35)] disabled:cursor-default disabled:opacity-85"
    >
      <span
        aria-hidden="true"
        className="absolute inset-y-0 left-0 bg-ink/20 transition-[width] duration-200 ease-linear"
        style={{ width: `${downloading ? percent : 0}%` }}
      />
      {Icon && (
        <Icon
          aria-hidden="true"
          className={`relative size-6 shrink-0 ${busy ? 'animate-spin' : ''}`}
          strokeWidth={busy || override ? 2.4 : 1.5}
          fill={!busy && !override ? 'currentColor' : 'none'}
        />
      )}
      <span className="relative whitespace-nowrap text-[22px] font-extrabold tracking-[0.12em]">{label}</span>
    </button>
  )
}
