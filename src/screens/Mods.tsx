import { TriangleAlert } from 'lucide-react'
import { Switch } from '../components/Switch'
import type { ModsState } from '../state/useMods'

interface Props {
  mods: ModsState
  modCount: number
}

function SectionLabel({ children }: { children: string }) {
  return <h2 className="mb-3 text-[13px] font-bold tracking-[0.16em] text-gold">{children}</h2>
}

/** Two letters for the icon: "Cube Utility" → "CU", "Zoomify (Zoom)" → "ZO". */
function monogram(name: string): string {
  const words = name
    .replace(/\(.*?\)/g, ' ')
    .split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean)
  if (words.length >= 2) return `${words[0]?.[0] ?? ''}${words[1]?.[0] ?? ''}`.toUpperCase()
  return (words[0] ?? '?').slice(0, 2).toUpperCase()
}

function listNames(names: string[]): string {
  if (names.length <= 1) return names.join('')
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

export function Mods({ mods, modCount }: Props) {
  const { view } = mods

  return (
    <div className="mx-auto flex w-full max-w-[1000px] flex-col gap-[30px] px-8 pb-8 pt-2">
      <header>
        <h1 className="text-[44px] font-light leading-[52px] tracking-[-0.02em]">Mods</h1>
        <p className="mt-2 text-base text-text-2">
          {view?.installed ? `${modCount} mods, kept up to date automatically. Nobody has to download them by hand.` : 'Kept up to date automatically.'}
        </p>
      </header>

      {!view && <p className="text-sm text-text-2">Loading your mods…</p>}
      {view && !view.installed && (
        <p className="rounded-[14px] border border-line bg-surface-1 p-5 text-sm text-text-2">
          Your mods show up here once the launcher has downloaded the pack.
        </p>
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
    </div>
  )
}
