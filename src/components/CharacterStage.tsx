import { Character3D } from './Character3D'

interface Props {
  playerName: string
  skinUrl?: string
  slim?: boolean
  /** The small line under the name. */
  note: string
}

/** The player's character in 3D, standing over a teal glow with a thin gold ring. */
export function CharacterStage({ playerName, skinUrl, slim, note }: Props) {
  return (
    <section aria-label="Your character" className="flex min-w-0 max-w-[480px] flex-[1_1_360px] flex-col items-center gap-3.5">
      <div className="relative h-[500px] w-full">
        <div
          aria-hidden="true"
          className="absolute left-1/2 top-[46%] -ml-[220px] -mt-[220px] size-[440px] animate-glow rounded-full"
          style={{ background: 'radial-gradient(circle, rgb(70 194 184 / 0.30) 0%, rgb(46 120 112 / 0.12) 42%, transparent 70%)' }}
        />
        <div aria-hidden="true" className="absolute left-1/2 top-[46%] -ml-[160px] -mt-[160px] size-[320px] rounded-full border border-gold/20" />
        <div
          aria-hidden="true"
          className="absolute left-1/2 top-[46%] -ml-[125px] -mt-[125px] size-[250px] rounded-full border border-dashed border-gold/10"
        />
        <Character3D skinUrl={skinUrl} slim={slim} label="Your character" />
      </div>
      <div className="text-center">
        <div className="text-lg font-semibold">{playerName}</div>
        <div className="mt-1 text-[13px] text-text-3">{note}</div>
      </div>
    </section>
  )
}
