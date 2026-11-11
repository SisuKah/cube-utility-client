import { useRef, useState, type KeyboardEvent } from 'react'
import { motion } from 'motion/react'
import { LoaderCircle, Trash2, TriangleAlert } from 'lucide-react'
import { ModIcon, ModrinthBrowser, monogram } from '../components/ModrinthBrowser'
import { Switch } from '../components/Switch'
import type { ModsState } from '../state/useMods'
import { listNames, useModrinth } from '../state/useModrinth'

interface Props {
  mods: ModsState
  modCount: number
}

type Panel = 'installed' | 'browse'

const PANELS: { id: Panel; label: string }[] = [
  { id: 'installed', label: 'Installed' },
  { id: 'browse', label: 'Browse Modrinth' }
]

function SectionLabel({ children }: { children: string }) {
  return <h2 className="mb-3 text-[13px] font-bold tracking-[0.16em] text-gold">{children}</h2>
}

/** Installed and Browse Modrinth, in the same pill as the main tabs. Arrow keys move between them. */
function PanelTabs({ panel, onPanel }: { panel: Panel; onPanel: (panel: Panel) => void }) {
  const buttons = useRef<(HTMLButtonElement | null)[]>([])
  const onKeyDown = (event: KeyboardEvent, index: number) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
    event.preventDefault()
    const next = (index + (event.key === 'ArrowRight' ? 1 : PANELS.length - 1)) % PANELS.length
    const target = PANELS[next]
    if (target) onPanel(target.id)
    buttons.current[next]?.focus()
  }
  return (
    <div role="tablist" aria-label="Mods" className="flex gap-1 rounded-xl border border-line bg-surface-2/75 p-1">
      {PANELS.map((p, i) => {
        const active = p.id === panel
        return (
          <button
            key={p.id}
            ref={(el) => {
              buttons.current[i] = el
            }}
            type="button"
            role="tab"
            id={`mods-tab-${p.id}`}
            aria-selected={active}
            aria-controls="mods-panel"
            tabIndex={active ? 0 : -1}
            onClick={() => onPanel(p.id)}
            onKeyDown={(e) => onKeyDown(e, i)}
            className={`h-[38px] cursor-pointer rounded-[9px] px-5 text-sm font-semibold transition-colors ${active ? 'bg-surface-3 text-gold-bright' : 'text-text-2 hover:text-text'}`}
          >
            {p.label}
          </button>
        )
      })}
    </div>
  )
}

export function Mods({ mods, modCount }: Props) {
  const { view } = mods
  const [panel, setPanel] = useState<Panel>('installed')
  const browser = useModrinth(panel === 'browse')
  // Packs can forbid extra mods; then there's nothing to browse for.
  const canBrowse = !view || view.allowExtraMods
  const shown: Panel = canBrowse ? panel : 'installed'
  const fromModrinth = view?.modrinth ?? []
  const picked = fromModrinth.filter((m) => m.picked)
  const installedIds = new Set(fromModrinth.map((m) => m.projectId))
  const remove = (projectId: string) => {
    browser.forget(projectId)
    mods.remove(projectId)
  }

  return (
    <div className="mx-auto flex w-full max-w-[1000px] flex-col gap-[30px] px-8 pb-8 pt-2">
      <header className="flex flex-wrap items-end justify-between gap-x-8 gap-y-4">
        <div className="min-w-0">
          <h1 className="text-[44px] font-light leading-[52px] tracking-[-0.02em]">Mods</h1>
          <p className="mt-2 text-base text-text-2">
            {shown === 'browse'
              ? 'Add more from Modrinth. One click puts a mod in your mods folder.'
              : view?.installed
                ? `${modCount} mods, kept up to date automatically. Nobody has to download them by hand.`
                : 'Kept up to date automatically.'}
          </p>
        </div>
        {canBrowse && <PanelTabs panel={shown} onPanel={setPanel} />}
      </header>

      <motion.div
        key={shown}
        id="mods-panel"
        role={canBrowse ? 'tabpanel' : undefined}
        aria-labelledby={canBrowse ? `mods-tab-${shown}` : undefined}
        className="flex flex-col gap-[30px]"
        initial={{ opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.25, ease: [0.2, 0.8, 0.2, 1] }}
      >
        {shown === 'browse' ? (
          <ModrinthBrowser browser={browser} installedIds={installedIds} />
        ) : (
          <>
            {!view && <p className="text-sm text-text-2">Loading your mods…</p>}
            {view && !view.installed && (
              <p className="rounded-[14px] border border-line bg-surface-1 p-5 text-sm text-text-2">Your mods show up here once the launcher has downloaded the pack.</p>
            )}

            {view?.installed && view.own.length > 0 && (
              <section>
                <SectionLabel>CUBE UTILITY MODS</SectionLabel>
                <div className="grid grid-cols-[repeat(auto-fill,minmax(280px,1fr))] gap-3">
                  {view.own.map((mod) => (
                    <article key={mod.id} className="flex items-start gap-3.5 rounded-[14px] border border-line bg-surface-1 p-4 transition-colors hover:border-white/12">
                      <span aria-hidden="true" className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-gold/15 text-[15px] font-extrabold text-gold-bright">
                        {monogram(mod.name)}
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
                          <h3 className="text-[15px] font-semibold">{mod.name}</h3>
                          <span className="text-xs text-text-3">{mod.version}</span>
                        </div>
                        {mod.description && <p className="mt-1 line-clamp-2 text-[13px] leading-[19px] text-text-2">{mod.description}</p>}
                        {mod.updatedFrom && (
                          <span className="mt-2.5 inline-flex h-[22px] items-center rounded-md border border-green/50 px-2 text-xs font-semibold text-green">
                            Updated from {mod.updatedFrom}
                          </span>
                        )}
                      </div>
                    </article>
                  ))}
                </div>
              </section>
            )}

            {view?.installed && view.extras.length > 0 && (
              <section>
                <SectionLabel>EXTRAS · YOU CHOOSE</SectionLabel>
                <div className="flex flex-col gap-2">
                  {view.extras.map((mod) => (
                    <div key={mod.id} className="flex items-center gap-3.5 rounded-[14px] border border-line bg-surface-1 px-4 py-3.5 transition-colors hover:border-white/12">
                      <span aria-hidden="true" className="flex size-10 shrink-0 items-center justify-center rounded-[11px] bg-teal-bright/12 text-sm font-extrabold text-teal-bright">
                        {monogram(mod.name)}
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
                          <h3 className="text-[15px] font-semibold">{mod.name}</h3>
                          <span className="text-xs text-text-3">{mod.version}</span>
                        </div>
                        {mod.description && <p className="mt-0.5 line-clamp-2 text-[13px] leading-[19px] text-text-2">{mod.description}</p>}
                        {mod.needs.length > 0 && <p className="mt-1 text-xs text-text-3">Comes with {listNames(mod.needs)}</p>}
                      </div>
                      <Switch checked={mod.enabled} onChange={(on) => mods.toggle(mod.id, on)} label={mod.name} />
                    </div>
                  ))}
                </div>
                <p className="mt-2.5 text-[13px] text-text-3">Changes apply right away, or when you close Minecraft if it’s running.</p>
                {mods.error && (
                  <p role="alert" className="mt-1.5 text-[13px] text-[#f2a3b4]">
                    {mods.error}
                  </p>
                )}
              </section>
            )}

            {picked.length > 0 && (
              <section>
                <SectionLabel>FROM MODRINTH</SectionLabel>
                <div className="flex flex-col gap-2">
                  {picked.map((mod) => {
                    const libraries = fromModrinth.filter((m) => !m.picked && m.neededBy.includes(mod.name)).map((m) => m.name)
                    const removing = mods.removing.includes(mod.projectId)
                    return (
                      <div key={mod.projectId} className="flex items-center gap-3.5 rounded-[14px] border border-line bg-surface-1 px-4 py-3.5 transition-colors hover:border-white/12">
                        <ModIcon url={mod.iconUrl} name={mod.name} className="size-10 rounded-[11px]" />
                        <div className="min-w-0 flex-1">
                          <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
                            <h3 className="text-[15px] font-semibold">{mod.name}</h3>
                            <span className="text-xs text-text-3">{mod.version}</span>
                          </div>
                          {libraries.length > 0 && <p className="mt-0.5 text-xs text-text-3">Comes with {listNames(libraries)}</p>}
                        </div>
                        <button
                          type="button"
                          onClick={() => remove(mod.projectId)}
                          disabled={removing}
                          aria-label={`Remove ${mod.name}`}
                          className="inline-flex h-9 shrink-0 cursor-pointer items-center gap-2 rounded-[10px] border border-white/10 bg-surface-2 px-3.5 text-sm text-text-2 enabled:hover:border-white/20 enabled:hover:text-text disabled:cursor-wait disabled:opacity-70"
                        >
                          {removing ? (
                            <LoaderCircle aria-hidden="true" className="size-4 animate-spin text-gold" />
                          ) : (
                            <Trash2 aria-hidden="true" className="size-4 text-gold" strokeWidth={1.6} />
                          )}
                          {removing ? 'Removing' : 'Remove'}
                        </button>
                      </div>
                    )
                  })}
                </div>
                <p className="mt-2.5 text-[13px] text-text-3">You added these from Modrinth. The pack doesn’t update them, and if it ever includes one itself, its copy takes over.</p>
                {mods.removeError && (
                  <p role="alert" className="mt-1.5 text-[13px] text-[#f2a3b4]">
                    {mods.removeError}
                  </p>
                )}
              </section>
            )}

            {view?.installed && view.always.length > 0 && (
              <section>
                <SectionLabel>PERFORMANCE AND LIBRARIES</SectionLabel>
                <div className="rounded-[14px] border border-line bg-surface-1 p-4">
                  <ul className="flex flex-wrap gap-2">
                    {view.always.map((name) => (
                      <li key={name} className="inline-flex h-[30px] items-center rounded-lg border border-white/5 bg-surface-2 px-3 text-[13px] text-[#c9d3d2]">
                        {name}
                      </li>
                    ))}
                  </ul>
                  <p className="mt-3 text-[13px] text-text-3">Always on. They keep the game fast and let the other mods run.</p>
                </div>
              </section>
            )}

            {view && (view.playerJars.length > 0 || view.disabledJars.length > 0) && (
              <section>
                <SectionLabel>YOUR EXTRA MODS</SectionLabel>
                <div className="rounded-[14px] border border-line bg-surface-1 p-4">
                  <ul className="flex flex-col gap-2">
                    {view.playerJars.map((path) => (
                      <li key={path} className="flex items-center gap-2.5 text-[13px] text-[#c9d3d2]">
                        <TriangleAlert aria-hidden="true" className="size-4 shrink-0 text-gold" />
                        <span className="min-w-0 truncate">{path.replace(/^mods\//, '')}</span>
                      </li>
                    ))}
                  </ul>
                  <p className="mt-3 text-[13px] text-text-3">
                    {view.disabledJars.length > 0
                      ? `This pack doesn’t allow extra mods, so ${listNames(view.disabledJars)} moved to the mods-disabled folder.`
                      : 'You added these yourself. They stay installed, but the pack doesn’t check them, so they might not work with it.'}
                  </p>
                </div>
              </section>
            )}
          </>
        )}
      </motion.div>
    </div>
  )
}
