import { ArrowDownToLine, X } from 'lucide-react'
import { motion } from 'motion/react'

interface Props {
  version: string
  error: string
  onRestart: () => void
  onLater: () => void
}

/** "Launcher update ready · Restart". Ignored, the update installs when the launcher closes. */
export function UpdateToast({ version, error, onRestart, onLater }: Props) {
  return (
    <motion.div
      role="status"
      aria-live="polite"
      initial={{ opacity: 0, y: 14 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: 14 }}
      transition={{ duration: 0.25, ease: [0.2, 0.8, 0.2, 1] }}
      className="fixed bottom-12 right-6 z-50 flex w-[340px] max-w-[calc(100vw-48px)] items-start gap-3.5 rounded-2xl border border-line-strong bg-surface-2/95 p-4 shadow-[0_18px_50px_rgb(0_0_0/0.45)] backdrop-blur"
    >
      <span aria-hidden="true" className="grid size-9 shrink-0 place-items-center rounded-xl bg-gold/15 text-gold-bright">
        <ArrowDownToLine className="size-[18px]" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-bold text-text">Launcher update ready</p>
        <p className={`mt-1 text-[13px] leading-5 ${error ? 'select-text text-[#f2a3b4]' : 'text-text-2'}`}>
          {error || `Version ${version} installs when you restart, or by itself when you close the launcher.`}
        </p>
        <button
          type="button"
          onClick={onRestart}
          className="mt-3 inline-flex h-9 cursor-pointer items-center rounded-[10px] bg-linear-135 from-[#efd29c] via-gold to-[#a9844b] px-4 text-[13px] font-extrabold tracking-[0.08em] text-ink transition-shadow hover:shadow-[0_0_24px_rgb(228_192_132/0.35)] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gold-bright"
        >
          RESTART
        </button>
      </div>
      <button
        type="button"
        onClick={onLater}
        aria-label="Later"
        title="Later"
        className="grid size-7 shrink-0 cursor-pointer place-items-center rounded-lg text-text-3 hover:bg-white/5 hover:text-text focus-visible:outline-2 focus-visible:outline-gold-bright"
      >
        <X aria-hidden="true" className="size-4" />
      </button>
    </motion.div>
  )
}
