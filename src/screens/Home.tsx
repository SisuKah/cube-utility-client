import { ArrowRight, RotateCw, Wrench } from 'lucide-react'
import { config } from '../config'
import { CharacterStage } from '../components/CharacterStage'
import { PlayButton, type ButtonOverride } from '../components/PlayButton'
import { StripedProgress } from '../components/StripedProgress'
import type { AccountView } from '../state/useAccount'
import type { LaunchTarget, LauncherState, Phase } from '../state/useLauncher'

interface Props {
  launcher: LauncherState
  account: AccountView
  onPlay: () => void
  onRepair: () => void
  onRetryUpdate: () => void
  target: LaunchTarget
  onTarget: (target: LaunchTarget) => void
  modCount: number
  onOpenMods: () => void
}

/** What Home says instead while sign-in stands between the player and PLAY. */
interface SignInGate {
  headline: string
  subline: string
  button: ButtonOverride
}

/** Release builds need a signed-in player before PLAY does anything. */
function signInGate(account: AccountView): SignInGate | null {
  const { info } = account
  if (!info.signInRequired) return null
  switch (info.state) {
    case 'signedIn':
    case 'offline':
      return null
    case 'checking':
      return { headline: 'Checking sign-in', subline: 'Making sure you’re still signed in to Microsoft.', button: { label: 'CHECKING', busy: true } }
    case 'signingIn':
      return { headline: 'Signing in', subline: 'Finish signing in in your browser, then come back here.', button: { label: 'SIGNING IN', busy: true } }
    case 'awaitingApproval':
      return {
        headline: 'Almost ready',
        subline: `You’re signed in${info.gamertag ? ` as ${info.gamertag}` : ''}, but Mojang hasn’t approved this launcher’s sign-in yet. PLAY works as soon as they do.`,
        button: { label: 'WAITING FOR MOJANG' }
      }
    case 'problem':
      return { headline: 'Sign in to play', subline: info.message, button: { label: 'SIGN IN', onClick: account.signIn } }
    case 'signedOut':
      return {
        headline: 'Sign in to play',
        subline: info.message ?? 'Use the Microsoft account that owns Minecraft: Java Edition.',
        button: { label: 'SIGN IN', onClick: account.signIn }
      }
  }
}

const HEADLINES: Record<Phase, string> = {
  checking: 'Checking for updates',
  repairing: 'Repairing files',
  needsInstall: 'Ready to install',
  installing: 'Installing Minecraft',
  updating: 'Updating your mods',
  idle: 'Ready to play',
  launching: 'Starting Minecraft',
  playing: 'You’re in game',
  offline: 'Ready to play offline',
  error: 'Something went wrong'
}

const COLORS = {
  good: '#46d399',
  busy: '#46c2b8',
  gold: '#e4c084',
  muted: '#9aaba9',
  bad: '#ef5a7a'
}

interface Status {
  text: string
  color: string
  blink: boolean
  /** Offer "Retry" for a failed or offline update check. */
  retry?: boolean
}

function clock(seconds: number): string {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function ago(ms: number): string {
  const minutes = Math.round((Date.now() - ms) / 60_000)
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`
  const days = Math.round(hours / 24)
  return `${days} day${days === 1 ? '' : 's'} ago`
}

export function Home({ launcher, account, onPlay, onRepair, onRetryUpdate, target, onTarget, modCount, onOpenMods }: Props) {
  const { phase, demo, pack } = launcher
  // Only when nothing else is going on: downloads and errors keep their own screen.
  const gate = phase === 'idle' || phase === 'offline' || phase === 'needsInstall' ? signInGate(account) : null
  const game = `Minecraft ${pack.minecraft}`
  const java = `Java ${pack.java ?? 25}`

  const idleLine = (() => {
    if (pack.problem) return `${pack.problem} You’ll play with the mods you already have.`
    if (launcher.justUpdated && pack.whatsNew) return `Just updated: ${pack.whatsNew}. Everything is ready.`
    if (pack.version) return 'All your CubeCraft mods are installed and up to date.'
    return `${game} with Fabric is installed and ready.`
  })()

  const sublines: Record<Phase, string> = {
    checking: 'Making sure your mods and game files are up to date.',
    repairing: 'Checking every mod and game file, and downloading anything that’s damaged.',
    needsInstall: `PLAY downloads ${game}, Fabric and ${java} once (about ${Math.round(launcher.installMb)} MB), then starts the game.`,
    installing: `${game}, Fabric and ${java} are downloading. This only happens once.`,
    updating: demo
      ? `${pack.whatsNew} updates are downloading. PLAY unlocks in a moment.`
      : 'Getting the newest mods from the pack. PLAY unlocks in a moment.',
    idle: idleLine,
    launching: target === 'cubecraft' ? 'Loading the game, then joining CubeCraft.' : 'Loading the game.',
    playing: pack.updateReady
      ? 'A mod update is ready. It installs when you close the game.'
      : 'Have fun. Updates wait until you close the game.',
    offline: 'Can’t reach the update server, so you’ll play with the mods you already have.',
    error: launcher.error
  }

  const status: Status | null = (() => {
    switch (phase) {
      case 'idle':
        if (pack.problem) return { text: 'Update failed', color: COLORS.gold, blink: false, retry: true }
        return pack.version
          ? { text: `Up to date · ${modCount} mods · pack ${pack.version}`, color: COLORS.good, blink: false }
          : { text: `Up to date · ${game} · Fabric ${pack.fabricLoader}`, color: COLORS.good, blink: false }
      case 'needsInstall':
        return { text: `${game} isn’t installed yet`, color: COLORS.gold, blink: false }
      case 'checking':
        return { text: 'Checking for updates', color: COLORS.busy, blink: true }
      case 'repairing':
        return { text: `Checking files · ${launcher.percent}%`, color: COLORS.busy, blink: true }
      case 'launching':
        return { text: `Starting ${game}`, color: COLORS.busy, blink: true }
      case 'playing':
        return {
          text: `In game for ${clock(launcher.playSeconds)}${pack.updateReady ? ' · update ready' : ''}`,
          color: COLORS.gold,
          blink: true
        }
      case 'offline':
        return {
          text: pack.checkedAtMs ? `Offline · last synced ${ago(pack.checkedAtMs)}` : 'Offline · not synced yet',
          color: COLORS.muted,
          blink: false,
          retry: true
        }
      case 'error':
        return { text: 'Press TRY AGAIN to retry', color: COLORS.bad, blink: false }
      default:
        return null
    }
  })()

  const downloading = phase === 'installing' || phase === 'updating'
  const showWhatsNew = Boolean(pack.whatsNew) && (phase === 'idle' || phase === 'offline') && !pack.problem

  return (
    <div className="mx-auto flex w-full max-w-[1240px] flex-1 flex-wrap-reverse content-center items-center justify-center gap-x-14 gap-y-4 px-8 pb-6 pt-2">
      <section className="flex min-w-0 max-w-[540px] flex-[1_1_420px] flex-col items-start gap-[30px]">
        <div>
          <p className="text-[13px] font-bold tracking-[0.18em] text-gold">MINECRAFT {pack.minecraft} · CUBECRAFT READY</p>
          <h1 className="mt-3.5 text-[60px] font-light leading-[66px] tracking-[-0.03em]">{gate?.headline ?? HEADLINES[phase]}</h1>
          <p
            className={`mt-3.5 max-w-[440px] text-[17px] leading-[26px] wrap-anywhere ${phase === 'error' ? 'select-text text-[#f2a3b4]' : 'text-text-2'}`}
          >
            {gate?.subline ?? sublines[phase]}
          </p>
        </div>

        <div className="flex w-full flex-col items-start gap-3.5">
          <PlayButton phase={phase} percent={launcher.percent} onPlay={onPlay} override={gate?.button} />
          <label className="flex items-center gap-2.5 text-sm text-text-2">
            Launch into
            <select
              value={target}
              onChange={(e) => onTarget(e.target.value as LaunchTarget)}
              className="h-9 cursor-pointer rounded-[9px] border border-white/10 bg-surface-2 px-2.5 text-sm text-text"
            >
              <option value="cubecraft">CubeCraft ({config.quickPlayServer})</option>
              <option value="title">Title screen</option>
            </select>
          </label>
        </div>

        <div className="flex w-full flex-col gap-3.5">
          {downloading ? (
            <div className="w-full max-w-[420px]">
              <div className="mb-2 flex justify-between gap-3 text-[13px] text-text-2">
                <span className="truncate">{launcher.totalMb > 0 ? `Downloading ${launcher.currentFile}` : launcher.currentFile}</span>
                {launcher.totalMb > 0 && (
                  <span className="whitespace-nowrap tabular-nums">
                    {launcher.doneMb.toFixed(1)} / {launcher.totalMb.toFixed(1)} MB
                  </span>
                )}
              </div>
              <StripedProgress percent={launcher.percent} label={phase === 'installing' ? 'Install progress' : 'Update progress'} />
            </div>
          ) : (
            status && (
              <div className="inline-flex items-center gap-2.5 text-sm text-[#c9d3d2]">
                <span
                  aria-hidden="true"
                  className={`block size-2 rounded-full ${status.blink ? 'animate-blink' : ''}`}
                  style={{ background: status.color, boxShadow: `0 0 10px ${status.color}` }}
                />
                <span>{status.text}</span>
                {status.retry && (
                  <button
                    type="button"
                    onClick={onRetryUpdate}
                    className="inline-flex cursor-pointer items-center gap-1.5 font-semibold text-gold-bright hover:text-[#f1d7a6]"
                  >
                    <RotateCw aria-hidden="true" className="size-3.5" />
                    Retry
                  </button>
                )}
              </div>
            )
          )}
          {phase === 'error' && !demo && (
            <button
              type="button"
              onClick={onRepair}
              className="inline-flex cursor-pointer items-center gap-2 self-start text-sm font-semibold text-gold-bright hover:text-[#f1d7a6]"
            >
              <Wrench aria-hidden="true" className="size-4" />
              Repair files
            </button>
          )}
          {showWhatsNew && (
            <button
              type="button"
              onClick={onOpenMods}
              className="inline-flex cursor-pointer items-center gap-2 self-start text-sm font-semibold text-gold-bright hover:text-[#f1d7a6]"
            >
              New in this update: {pack.whatsNew}
              <ArrowRight aria-hidden="true" className="size-4" />
            </button>
          )}
        </div>
      </section>

      <CharacterStage
        playerName={account.name}
        skinUrl={account.skin}
        slim={account.slim}
        note={
          account.skin
            ? 'Skin from your Microsoft account'
            : account.info.state === 'signedIn'
              ? 'Default skin'
              : account.info.state === 'awaitingApproval'
                ? 'Your skin shows once Mojang approves sign-in'
                : 'Sign in to see your own skin'
        }
      />
    </div>
  )
}
