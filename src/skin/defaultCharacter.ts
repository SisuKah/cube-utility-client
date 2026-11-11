/** [x, y, width, height, color] on the 16×32 front view. */
export type PixelRect = readonly [number, number, number, number, string]

const HAIR = '#2B1D16'
const HAIR_LIGHT = '#3A281E'
const SKIN = '#D9A983'
const SKIN_SHADE = '#C4926B'
const HOODIE = '#1F6F69'
const HOODIE_LIGHT = '#2F9C95'
const HOODIE_SHADE = '#195955'
const HOODIE_DARK = '#174F4B'
const GOLD = '#E4C084'
const PANTS = '#252E37'
const PANTS_DARK = '#1C232A'
const SHOE = '#E9EFEE'
const SHOE_SHADE = '#C9D3D2'
const SOLE = '#8C9A99'

/**
 * A made-up default character (teal hoodie with gold details), shown until the
 * player's own skin loads.
 */
export const DEFAULT_CHARACTER: readonly PixelRect[] = [
  // Head
  [4, 0, 8, 8, SKIN],
  [4, 0, 8, 2, HAIR],
  [4, 2, 1, 1, HAIR],
  [7, 2, 2, 1, HAIR],
  [11, 2, 1, 1, HAIR],
  [4, 3, 1, 1, SKIN_SHADE],
  [11, 3, 1, 1, SKIN_SHADE],
  [5, 4, 1, 1, '#F4F1EC'],
  [6, 4, 1, 1, '#2F7F6E'],
  [9, 4, 1, 1, '#2F7F6E'],
  [10, 4, 1, 1, '#F4F1EC'],
  [7, 5, 2, 1, '#B9845F'],
  [6, 6, 4, 1, '#7A3E33'],
  // Hoodie
  [4, 8, 8, 12, HOODIE],
  [4, 8, 8, 1, HOODIE_LIGHT],
  [4, 9, 1, 11, HOODIE_SHADE],
  [11, 9, 1, 11, HOODIE_SHADE],
  [6, 9, 1, 3, GOLD],
  [9, 9, 1, 3, GOLD],
  [7, 12, 2, 2, GOLD],
  [5, 15, 6, 2, HOODIE_SHADE],
  [4, 19, 8, 1, HOODIE_DARK],
  // Arms
  [0, 8, 4, 10, HOODIE],
  [12, 8, 4, 10, HOODIE],
  [0, 8, 1, 10, HOODIE_SHADE],
  [15, 8, 1, 10, HOODIE_SHADE],
  [0, 8, 4, 1, HOODIE_LIGHT],
  [12, 8, 4, 1, HOODIE_LIGHT],
  [0, 17, 4, 1, HOODIE_DARK],
  [12, 17, 4, 1, HOODIE_DARK],
  [0, 18, 4, 2, SKIN],
  [12, 18, 4, 2, SKIN],
  [0, 19, 4, 1, SKIN_SHADE],
  [12, 19, 4, 1, SKIN_SHADE],
  // Legs and shoes
  [4, 20, 8, 9, PANTS],
  [4, 20, 8, 1, PANTS_DARK],
  [7, 21, 1, 8, PANTS_DARK],
  [4, 29, 8, 2, SHOE],
  [7, 29, 1, 2, SHOE_SHADE],
  [5, 29, 1, 1, GOLD],
  [10, 29, 1, 1, GOLD],
  [4, 31, 8, 1, SOLE]
]

/**
 * The rest of the default character's head and body for the 3D view: sides,
 * back, top and bottom, as [x, y, width, height, color] in the 64×64 skin.
 */
const HEAD_AND_BODY: readonly PixelRect[] = [
  // Head: top, chin, right side, left side, back
  [8, 0, 8, 8, HAIR],
  [10, 1, 2, 1, HAIR_LIGHT],
  [13, 3, 1, 2, HAIR_LIGHT],
  [9, 5, 2, 1, HAIR_LIGHT],
  [16, 0, 8, 8, SKIN_SHADE],
  [0, 8, 8, 8, SKIN],
  [0, 8, 8, 3, HAIR],
  [0, 11, 3, 4, HAIR],
  [3, 11, 1, 3, HAIR],
  [4, 12, 1, 2, SKIN_SHADE],
  [0, 15, 3, 1, SKIN_SHADE],
  [16, 8, 8, 8, SKIN],
  [16, 8, 8, 3, HAIR],
  [21, 11, 3, 4, HAIR],
  [20, 11, 1, 3, HAIR],
  [19, 12, 1, 2, SKIN_SHADE],
  [21, 15, 3, 1, SKIN_SHADE],
  [24, 8, 8, 7, HAIR],
  [26, 10, 2, 1, HAIR_LIGHT],
  [29, 12, 1, 1, HAIR_LIGHT],
  [24, 15, 8, 1, SKIN_SHADE],
  // Body: shoulders, bottom, sides, and a back with a hood and a small gold logo
  [20, 16, 8, 4, HOODIE_LIGHT],
  [22, 16, 4, 1, HOODIE_SHADE],
  [28, 16, 8, 4, HOODIE_DARK],
  [16, 20, 4, 12, HOODIE_SHADE],
  [16, 20, 4, 1, HOODIE_LIGHT],
  [16, 31, 4, 1, HOODIE_DARK],
  [28, 20, 4, 12, HOODIE_SHADE],
  [28, 20, 4, 1, HOODIE_LIGHT],
  [28, 31, 4, 1, HOODIE_DARK],
  [32, 20, 8, 12, HOODIE],
  [32, 20, 8, 1, HOODIE_LIGHT],
  [32, 21, 1, 11, HOODIE_SHADE],
  [39, 21, 1, 11, HOODIE_SHADE],
  [33, 21, 6, 1, HOODIE_SHADE],
  [34, 22, 4, 1, HOODIE_SHADE],
  [35, 23, 2, 1, HOODIE_SHADE],
  [35, 26, 2, 2, GOLD],
  [32, 31, 8, 1, HOODIE_DARK]
]

/** Row colors from top to bottom: the outer side and back, then the darker inner side. */
const SLEEVE = [HOODIE_LIGHT, ...Array<string>(8).fill(HOODIE), HOODIE_DARK, SKIN, SKIN_SHADE]
const SLEEVE_INNER = [HOODIE_LIGHT, ...Array<string>(8).fill(HOODIE_SHADE), HOODIE_DARK, SKIN_SHADE, SKIN_SHADE]
const LEG = [PANTS_DARK, ...Array<string>(8).fill(PANTS), SHOE, SHOE, SOLE]
const LEG_INNER = [PANTS_DARK, ...Array<string>(8).fill(PANTS_DARK), SHOE_SHADE, SHOE_SHADE, SOLE]

/**
 * Paints a 4-pixel-wide limb's top, bottom, sides and back, starting at its
 * texture corner (u, v). `outerSide` faces away from the body: right for right limbs.
 */
function paintLimb(
  ctx: CanvasRenderingContext2D,
  u: number,
  v: number,
  outerSide: 'right' | 'left',
  rows: readonly string[],
  inner: readonly string[],
  top: string,
  bottom: string
): void {
  ctx.fillStyle = top
  ctx.fillRect(u + 4, v, 4, 4)
  ctx.fillStyle = bottom
  ctx.fillRect(u + 8, v, 4, 4)
  rows.forEach((color, row) => {
    const y = v + 4 + row
    ctx.fillStyle = outerSide === 'right' ? color : inner[row]
    ctx.fillRect(u, y, 4, 1)
    ctx.fillStyle = outerSide === 'left' ? color : inner[row]
    ctx.fillRect(u + 8, y, 4, 1)
    ctx.fillStyle = color
    ctx.fillRect(u + 12, y, 4, 1)
  })
}

/** Everything but the front faces, painted straight into the 64×64 skin. */
export function paintDefaultSides(ctx: CanvasRenderingContext2D): void {
  for (const [x, y, w, h, color] of HEAD_AND_BODY) {
    ctx.fillStyle = color
    ctx.fillRect(x, y, w, h)
  }
  paintLimb(ctx, 40, 16, 'right', SLEEVE, SLEEVE_INNER, HOODIE_LIGHT, SKIN_SHADE)
  paintLimb(ctx, 32, 48, 'left', SLEEVE, SLEEVE_INNER, HOODIE_LIGHT, SKIN_SHADE)
  paintLimb(ctx, 0, 16, 'right', LEG, LEG_INNER, PANTS, SOLE)
  paintLimb(ctx, 16, 48, 'left', LEG, LEG_INNER, PANTS, SOLE)
}
