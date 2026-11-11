import { useEffect, useRef } from 'react'
import { drawFace, drawFront, getDefaultSkin, type SkinImage } from '../skin/skin'

interface Props {
  mode: 'body' | 'face'
  /** The player's skin URL from their Minecraft profile. Leave empty for the default character. */
  skinUrl?: string
  slim?: boolean
  className?: string
  /** Accessible name. Leave empty when the picture is decorative. */
  label?: string
}

/** Draws a skin pixel-for-pixel; CSS scales it up with `image-rendering: pixelated`. */
export function SkinCanvas({ mode, skinUrl, slim = false, className, label }: Props) {
  const ref = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const ctx = ref.current?.getContext('2d')
    if (!ctx) return
    let cancelled = false
    const paint = (skin: SkinImage, isSlim: boolean) => {
      if (cancelled) return
      if (mode === 'face') drawFace(ctx, skin)
      else drawFront(ctx, skin, isSlim)
    }
    paint(getDefaultSkin(), false)
    if (skinUrl) {
      const img = new Image()
      img.crossOrigin = 'anonymous'
      img.onload = () => paint(img, slim)
      img.src = skinUrl
    }
    return () => {
      cancelled = true
    }
  }, [mode, skinUrl, slim])

  const size = mode === 'face' ? { width: 8, height: 8 } : { width: 16, height: 32 }
  return (
    <canvas
      ref={ref}
      {...size}
      className={className}
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    />
  )
}
