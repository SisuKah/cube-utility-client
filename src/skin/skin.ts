import { DEFAULT_CHARACTER, paintDefaultSides } from './defaultCharacter'

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

function widthOf(skin: SkinImage): number {
  return skin instanceof HTMLImageElement ? skin.naturalWidth : skin.width
}

/**
 * The skin the way Minecraft prepares it for the 3D model: always 64×64, with old
 * 64×32 skins given a mirrored left arm and leg, and the inner layer fully opaque.
 */
export function skinPixels(skin: SkinImage): ImageData {
  const canvas = document.createElement('canvas')
  canvas.width = 64
  canvas.height = 64
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  if (!ctx) throw new Error('No 2D canvas')
  const legacy = heightOf(skin) * 2 === widthOf(skin)
  ctx.imageSmoothingEnabled = false
  ctx.drawImage(skin, 0, 0, 64, legacy ? 32 : 64)
  const image = ctx.getImageData(0, 0, 64, 64)
  // One pixel per entry, alpha in the top byte.
  const px = new Uint32Array(image.data.buffer)
  const region = (x0: number, y0: number, x1: number, y1: number, each: (i: number) => void) => {
    for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) each(y * 64 + x)
  }

  if (legacy) {
    // Minecraft's own conversion: copy each right-limb face to the left limb, flipped.
    const copy = (sx: number, sy: number, dx: number, dy: number, w: number, h: number) => {
      for (let y = 0; y < h; y++) {
        for (let x = 0; x < w; x++) px[(sy + dy + y) * 64 + sx + dx + (w - 1 - x)] = px[(sy + y) * 64 + sx + x]
      }
    }
    copy(4, 16, 16, 32, 4, 4)
    copy(8, 16, 16, 32, 4, 4)
    copy(0, 20, 24, 32, 4, 12)
    copy(4, 20, 16, 32, 4, 12)
    copy(8, 20, 8, 32, 4, 12)
    copy(12, 20, 16, 32, 4, 12)
    copy(44, 16, -8, 32, 4, 4)
    copy(48, 16, -8, 32, 4, 4)
    copy(40, 20, 0, 32, 4, 12)
    copy(44, 20, -8, 32, 4, 12)
    copy(48, 20, -16, 32, 4, 12)
    copy(52, 20, -8, 32, 4, 12)
    // Old skins often filled the hat layer with a solid color: no see-through pixel means no hat.
    let solidHat = true
    region(32, 0, 64, 16, (i) => {
      if (px[i] >>> 24 < 128) solidHat = false
    })
    if (solidHat) region(32, 0, 64, 16, (i) => (px[i] &= 0x00ffffff))
  }

  const opaque = (i: number) => (px[i] |= 0xff000000)
  region(0, 0, 32, 16, opaque)
  region(0, 16, 64, 32, opaque)
  region(16, 48, 48, 64, opaque)
  return image
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
 * same drawing code as a player's own skin, in 2D and in 3D.
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
  paintDefaultSides(s)
  defaultSkin = skin
  return skin
}
