import { useEffect, useRef, useState } from 'react'
import { CharacterViewer } from '../character/viewer'
import { getDefaultSkin, skinPixels } from '../skin/skin'
import { SkinCanvas } from './SkinCanvas'

interface Props {
  /** The player's skin URL from their Minecraft profile. Leave empty for the default character. */
  skinUrl?: string
  slim?: boolean
  label: string
}

/**
 * The player's character in 3D, standing on the stage and now and then doing a
 * random emote at you. Falls back to the flat front view without WebGL.
 */
export function Character3D({ skinUrl, slim = false, label }: Props) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const effects = useRef<HTMLDivElement>(null)
  const [viewer, setViewer] = useState<CharacterViewer | null>(null)
  const [flat, setFlat] = useState(false)

  useEffect(() => {
    if (!canvas.current || !effects.current) return
    const created = CharacterViewer.create(canvas.current, effects.current)
    if (!created) {
      setFlat(true)
      return
    }
    setViewer(created)
    return () => created.dispose()
  }, [])

  useEffect(() => {
    if (!viewer) return
    let cancelled = false
    const showDefault = () => viewer.setSkin(skinPixels(getDefaultSkin()), false)
    if (!skinUrl) {
      showDefault()
      return
    }
    const img = new Image()
    img.onload = () => !cancelled && viewer.setSkin(skinPixels(img), slim)
    img.onerror = () => !cancelled && showDefault()
    img.src = skinUrl
    return () => {
      cancelled = true
    }
  }, [viewer, skinUrl, slim])

  if (flat) {
    return (
      <>
        <div
          aria-hidden="true"
          className="absolute bottom-[22px] left-1/2 -ml-[95px] h-6 w-[190px] animate-floor-shadow rounded-full"
          style={{ background: 'radial-gradient(ellipse, rgb(0 0 0 / 0.6) 0%, transparent 70%)' }}
        />
        <div className="absolute left-1/2 top-[22px] -ml-[104px] w-[208px] animate-float">
          <SkinCanvas mode="body" skinUrl={skinUrl} slim={slim} label={label} className="pixelated block h-[416px] w-[208px]" />
        </div>
      </>
    )
  }

  return (
    <>
      <canvas ref={canvas} role="img" aria-label={label} className="absolute inset-0 block size-full" />
      <div ref={effects} aria-hidden="true" className="pointer-events-none absolute inset-0" />
    </>
  )
}
