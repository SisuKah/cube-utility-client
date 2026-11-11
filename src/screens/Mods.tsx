import { Switch } from '../components/Switch'
import { alwaysOn, extraLibraries, extraMods, ownMods, type ExtrasState } from '../data/pack'

interface Props {
  extras: ExtrasState
  onExtra: (id: string, on: boolean) => void
  modCount: number
}

function SectionLabel({ children }: { children: string }) {
  return <h2 className="mb-3 text-[13px] font-bold tracking-[0.16em] text-gold">{children}</h2>
}

export function Mods({ extras, onExtra, modCount }: Props) {
  const libraries = [...alwaysOn, ...extraLibraries(extras)]

  return (
    <div className="mx-auto flex w-full max-w-[1000px] flex-col gap-[30px] px-8 pb-8 pt-2">
      <header>
        <h1 className="text-[44px] font-light leading-[52px] tracking-[-0.02em]">Mods</h1>
        <p className="mt-2 text-base text-text-2">{modCount} mods, kept up to date automatically. Nobody has to download them by hand.</p>
      </header>

      <section>
        <SectionLabel>CUBE UTILITY MODS</SectionLabel>
        <div className="grid grid-cols-[repeat(auto-fill,minmax(280px,1fr))] gap-3">
          {ownMods.map((mod) => (
            <article key={mod.id} className="flex items-start gap-3.5 rounded-[14px] border border-line bg-surface-1 p-4 transition-colors hover:border-white/12">
              <span aria-hidden="true" className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-gold/15 text-[15px] font-extrabold text-gold-bright">
                {mod.mono}
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
                  <h3 className="text-[15px] font-semibold">{mod.name}</h3>
                  <span className="text-xs text-text-3">{mod.version}</span>
                </div>
                <p className="mt-1 text-[13px] leading-[19px] text-text-2">{mod.description}</p>
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

      <section>
        <SectionLabel>EXTRAS · YOU CHOOSE</SectionLabel>
        <div className="flex flex-col gap-2">
          {extraMods.map((mod) => (
            <div key={mod.id} className="flex items-center gap-3.5 rounded-[14px] border border-line bg-surface-1 px-4 py-3.5 transition-colors hover:border-white/12">
              <span aria-hidden="true" className="flex size-10 shrink-0 items-center justify-center rounded-[11px] bg-teal-bright/12 text-sm font-extrabold text-teal-bright">
                {mod.mono}
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
                  <h3 className="text-[15px] font-semibold">{mod.name}</h3>
                  <span className="text-xs text-text-3">{mod.version}</span>
                </div>
                <p className="mt-0.5 text-[13px] leading-[19px] text-text-2">{mod.description}</p>
              </div>
              <Switch checked={Boolean(extras[mod.id])} onChange={(on) => onExtra(mod.id, on)} label={mod.name} />
            </div>
          ))}
        </div>
        <p className="mt-2.5 text-[13px] text-text-3">Changes apply the next time you press PLAY.</p>
      </section>

      <section>
        <SectionLabel>PERFORMANCE AND LIBRARIES</SectionLabel>
        <div className="rounded-[14px] border border-line bg-surface-1 p-4">
          <ul className="flex flex-wrap gap-2">
            {libraries.map((name) => (
              <li key={name} className="inline-flex h-[30px] items-center rounded-lg border border-white/5 bg-surface-2 px-3 text-[13px] text-[#c9d3d2]">
                {name}
              </li>
            ))}
          </ul>
          <p className="mt-3 text-[13px] text-text-3">Always on. They keep the game fast and let the other mods run.</p>
        </div>
      </section>
    </div>
  )
}
