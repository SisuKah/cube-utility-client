import { useRef, useState } from 'react'
import { AnimatePresence, MotionConfig, motion } from 'motion/react'
import { config } from './config'
import { DevPanel } from './components/DevPanel'
import { TopBar, type Tab } from './components/TopBar'
import { UpdateToast } from './components/UpdateToast'
import { Home } from './screens/Home'
import { Mods } from './screens/Mods'
import { Settings } from './screens/Settings'
import { useAccount } from './state/useAccount'
import { useLauncher } from './state/useLauncher'
import { useLauncherUpdate } from './state/useLauncherUpdate'
import { countMods, useMods } from './state/useMods'
import { useSettings } from './state/useSettings'

export function App() {
  const [tab, setTab] = useState<Tab>('home')
  const scrollArea = useRef<HTMLDivElement>(null)
  const { settings, update, maxMemoryGb } = useSettings()
  const { state: launcher, play, repair, retryUpdate, preview } = useLauncher()
  const launcherUpdate = useLauncherUpdate()
  const account = useAccount()
  const mods = useMods()
  // The app counts what the sync installed; the browser preview counts its sample pack.
  const modCount = launcher.demo ? (mods.view ? countMods(mods.view) : 0) : launcher.pack.modCount

  return (
    <MotionConfig reducedMotion="user">
      <div className="app-backdrop flex h-full flex-col overflow-hidden">
        <TopBar tab={tab} onTab={setTab} account={account} />

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
                  account={account}
                  onPlay={play}
                  onRepair={repair}
                  onRetryUpdate={retryUpdate}
                  target={settings.launchTarget}
                  onTarget={(target) => update('launchTarget', target)}
                  modCount={modCount}
                  onOpenMods={() => setTab('mods')}
                />
              )}
              {tab === 'mods' && <Mods mods={mods} modCount={modCount} />}
              {tab === 'settings' && (
                <Settings settings={settings} maxMemoryGb={maxMemoryGb} onChange={update} account={account} pack={launcher.pack} onRepair={repair} />
              )}
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
