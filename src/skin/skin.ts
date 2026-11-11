import { DEFAULT_CHARACTER } from './defaultCharacter'

/** A loaded skin PNG, or a canvas holding one. */
export type SkinImage = HTMLImageElement | HTMLCanvasElement

interface Part {
  /** Where the front face sits in the skin PNG. */
  sx: number
  sy: number
  w: number
  h: number
  /** Where it goes on the 16×32 front view. */
  dx: number
  dy: number
  layer: 'base' | 'overlay'
  mirror?: boolean
}

/**
 * The front face of every body part in the standard skin layout. The player's
 * right side appears on the viewer's left. Slim ("Alex") skins have 3-pixel arms.
 * Old 64×32 skins have no separate left limbs and no overlays except the hat,
 * so their left arm and leg mirror the right ones.
 */
export function frontParts(slim: boolean, legacy: boolean): Part[] {
  const arm = slim ? 3 : 4
  const parts: Part[] = [
    { sx: 8, sy: 8, w: 8, h: 8, dx: 4, dy: 0, layer: 'base' }, // head
    { sx: 20, sy: 20, w: 8, h: 12, dx: 4, dy: 8, layer: 'base' }, // body
    { sx: 44, sy: 20, w: arm, h: 12, dx: 4 - arm, dy: 8, layer: 'base' }, // right arm
    { sx: 4, sy: 20, w: 4, h: 12, dx: 4, dy: 20, layer: 'base' } // right leg
  ]
  if (legacy) {
    parts.push(
      { sx: 44, sy: 20, w: arm, h: 12, dx: 12, dy: 8, layer: 'base', mirror: true }, // left arm
      { sx: 4, sy: 20, w: 4, h: 12, dx: 8, dy: 20, layer: 'base', mirror: true }, // left leg
      { sx: 40, sy: 8, w: 8, h: 8, dx: 4, dy: 0, layer: 'overlay' } // hat
    )
    return parts
  }
  parts.push(
    { sx: 36, sy: 52, w: arm, h: 12, dx: 12, dy: 8, layer: 'base' }, // left arm
    { sx: 20, sy: 52, w: 4, h: 12, dx: 8, dy: 20, layer: 'base' }, // left leg
    { sx: 40, sy: 8, w: 8, h: 8, dx: 4, dy: 0, layer: 'overlay' }, // hat
    { sx: 20, sy: 36, w: 8, h: 12, dx: 4, dy: 8, layer: 'overlay' }, // jacket
    { sx: 44, sy: 36, w: arm, h: 12, dx: 4 - arm, dy: 8, layer: 'overlay' }, // right sleeve
    { sx: 52, sy: 52, w: arm, h: 12, dx: 12, dy: 8, layer: 'overlay' }, // left sleeve
    { sx: 4, sy: 36, w: 4, h: 12, dx: 4, dy: 20, layer: 'overlay' }, // right pants leg
    { sx: 4, sy: 52, w: 4, h: 12, dx: 8, dy: 20, layer: 'overlay' } // left pants leg
  )
  return parts
}

function heightOf(skin: SkinImage): number {
  return skin instanceof HTMLImageElement ? skin.naturalHeight : skin.height
}

/** Draws the full-body front view onto a 16×32 canvas. */
export function drawFront(ctx: CanvasRenderingContext2D, skin: SkinImage, slim: boolean): void {
  ctx.imageSmoothingEnabled = false
  ctx.clearRect(0, 0, 16, 32)
  for (const p of frontParts(slim, heightOf(skin) === 32)) {
    if (p.mirror) {
      ctx.save()
      ctx.translate(p.dx + p.w, p.dy)
      ctx.scale(-1, 1)
      ctx.drawImage(skin, p.sx, p.sy, p.w, p.h, 0, 0, p.w, p.h)
      ctx.restore()
    } else {
      ctx.drawImage(skin, p.sx, p.sy, p.w, p.h, p.dx, p.dy, p.w, p.h)
    }
  }
}

/** Draws just the face (head plus hat layer) onto an 8×8 canvas. */
export function drawFace(ctx: CanvasRenderingContext2D, skin: SkinImage): void {
  ctx.imageSmoothingEnabled = false
  ctx.clearRect(0, 0, 8, 8)
  ctx.drawImage(skin, 8, 8, 8, 8, 0, 0, 8, 8)
  ctx.drawImage(skin, 40, 8, 8, 8, 0, 0, 8, 8)
}

let defaultSkin: HTMLCanvasElement | null = null

/**
 * The default character as a real 64×64 skin, so it goes through exactly the
 * same drawing code as a player's own skin.
 */
export function getDefaultSkin(): HTMLCanvasElement {
  if (defaultSkin) return defaultSkin
  const front = document.createElement('canvas')
  front.width = 16
  front.height = 32
  const skin = document.createElement('canvas')
  skin.width = 64
  skin.height = 64
  const f = front.getContext('2d')
  const s = skin.getContext('2d')
  if (!f || !s) return skin
  for (const [x, y, w, h, color] of DEFAULT_CHARACTER) {
    f.fillStyle = color
    f.fillRect(x, y, w, h)
  }
  s.imageSmoothingEnabled = false
  for (const p of frontParts(false, false)) {
    if (p.layer === 'base') s.drawImage(front, p.dx, p.dy, p.w, p.h, p.sx, p.sy, p.w, p.h)
  }
  defaultSkin = skin
  return skin
}
