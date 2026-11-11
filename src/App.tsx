import { useRef, useState } from 'react'
import { AnimatePresence, MotionConfig, motion } from 'motion/react'
import { config } from './config'
import { DevPanel } from './components/DevPanel'
import { TopBar, type Tab } from './components/TopBar'
import { UpdateToast } from './components/UpdateToast'
import { modCount } from './data/pack'
import { Home } from './screens/Home'
import { Mods } from './screens/Mods'
import { Settings } from './screens/Settings'
import { useLauncher } from './state/useLauncher'
import { useLauncherUpdate } from './state/useLauncherUpdate'
import { useSettings } from './state/useSettings'

// Sign-in arrives in milestone 4. Until then the UI shows a placeholder player.
const PLAYER = { name: 'Player', note: 'Not signed in yet' }

export function App() {
  const [tab, setTab] = useState<Tab>('home')
  const scrollArea = useRef<HTMLDivElement>(null)
  const { settings, update, setExtra, maxMemoryGb } = useSettings()
  const { state: launcher, play, repair, retryUpdate, preview } = useLauncher({
    target: settings.launchTarget,
    memoryGb: Math.min(settings.memoryGb, maxMemoryGb),
    hideWhilePlaying: settings.hideWhilePlaying
  })
  const launcherUpdate = useLauncherUpdate()
  // The Mods screen still shows sample data until milestone 6; Home counts the real mods in the app.
  const count = modCount(settings.extras)

  return (
    <MotionConfig reducedMotion="user">
      <div className="app-backdrop flex h-full flex-col overflow-hidden">
        <TopBar tab={tab} onTab={setTab} playerName={PLAYER.name} accountNote={PLAYER.note} />

        <div ref={scrollArea} className="scroll-area min-h-0 flex-1 overflow-y-auto">
          {/* Each screen starts at the top, once the previous one has faded out. */}
          <AnimatePresence mode="wait" initial={false} onExitComplete={() => scrollArea.current?.scrollTo({ top: 0 })}>
            <motion.main
              key={tab}
              className="flex min-h-full flex-col"
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -4 }}
              transition={{ duration: 0.3, ease: [0.2, 0.8, 0.2, 1] }}
            >
              {tab === 'home' && (
                <Home
                  launcher={launcher}
                  onPlay={play}
                  onRepair={repair}
                  onRetryUpdate={retryUpdate}
                  target={settings.launchTarget}
                  onTarget={(target) => update('launchTarget', target)}
                  modCount={launcher.demo ? count : launcher.pack.modCount}
                  playerName={PLAYER.name}
                  onOpenMods={() => setTab('mods')}
                />
              )}
              {tab === 'mods' && <Mods extras={settings.extras} onExtra={setExtra} modCount={count} />}
              {tab === 'settings' && <Settings settings={settings} maxMemoryGb={maxMemoryGb} onChange={update} />}
            </motion.main>
          </AnimatePresence>
        </div>

        <footer className="shrink-0 px-8 pb-4 pt-2 text-center text-xs leading-[18px] text-text-3">{config.disclaimer}</footer>
        <AnimatePresence>
          {launcherUpdate.update && (
            <UpdateToast
              key="launcher-update"
              version={launcherUpdate.update.version}
              error={launcherUpdate.error}
              onRestart={launcherUpdate.restart}
              onLater={launcherUpdate.dismiss}
            />
          )}
        </AnimatePresence>
        <DevPanel onPreview={preview} onPreviewLauncherUpdate={launcherUpdate.preview} />
      </div>
    </MotionConfig>
  )
}
