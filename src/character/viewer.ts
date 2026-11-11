import { spawnCue } from './cues'
import { EMOTES, stance, type Cue, type Emote } from './emotes'
import { lookAt, mat4, multiply, perspective, transformPoint } from './math'
import { anchorPoint } from './model'
import { createPose } from './pose'
import { CharacterRenderer } from './renderer'

const FOV = (30 * Math.PI) / 180
/** How much of the scene shows top to bottom, in skin pixels at the character (it is 32 tall). */
const VIEW_HEIGHT = 44.5
/** Camera height: chest level, which puts the feet near the bottom with room above the head. */
const EYE_Y = 18.2

/** The first emote comes soon after the screen opens, the rest at random. */
const FIRST_DELAY: readonly [number, number] = [2.5, 4.5]
const DELAY: readonly [number, number] = [6, 14]

const between = ([min, max]: readonly [number, number]) => min + Math.random() * (max - min)

function shuffled<T>(items: readonly T[]): T[] {
  const out = [...items]
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[out[i], out[j]] = [out[j], out[i]]
  }
  return out
}

/**
 * The 3D character on Home: stands still, and every so often does a random emote.
 * Owns the WebGL canvas, the animation loop and the effects layer.
 */
export class CharacterViewer {
  private renderer: CharacterRenderer
  /** Kept to upload again if the GPU context is lost and restored. */
  private skin: ImageData | null = null
  private contextLost = false
  private readonly pose = createPose()
  private readonly viewProjection = mat4()
  private readonly reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
  private readonly resizeObserver: ResizeObserver
  private slim = false
  private cssWidth = 0
  private cssHeight = 0
  private frame = 0
  private lastFrame = 0
  private lastDraw = 0
  private emote: Emote | null = null
  private emoteStart = 0
  private nextCue = 0
  private nextEmoteAt = 0
  private bag: Emote[] = []
  private lastEmote: Emote | null = null
  /** Dev tools: hold one moment of an emote on screen, or turn the character around. */
  private held: { emote: Emote; t: number } | null = null
  private devTurn = 0
  private devOrder = 0
  private disposed = false

  static create(canvas: HTMLCanvasElement, effects: HTMLElement): CharacterViewer | null {
    const gl = canvas.getContext('webgl', {
      alpha: true,
      antialias: true,
      premultipliedAlpha: true,
      depth: true,
      stencil: false,
      powerPreference: 'low-power'
    })
    if (!gl) return null
    try {
      return new CharacterViewer(canvas, gl, effects)
    } catch (error) {
      console.warn('3D character unavailable:', error)
      return null
    }
  }

  private constructor(
    private readonly canvas: HTMLCanvasElement,
    private readonly gl: WebGLRenderingContext,
    private readonly effects: HTMLElement
  ) {
    this.renderer = new CharacterRenderer(gl)
    canvas.addEventListener('webglcontextlost', this.onContextLost)
    canvas.addEventListener('webglcontextrestored', this.onContextRestored)
    this.resizeObserver = new ResizeObserver(() => this.resize())
    // Device pixels, so moving to a screen with another scale also re-sizes the canvas.
    this.resizeObserver.observe(canvas, { box: 'device-pixel-content-box' })
    this.resize()
    document.addEventListener('visibilitychange', this.onVisibility)
    this.reducedMotion.addEventListener('change', this.onMotionPreference)
    if (import.meta.env.DEV) {
      window.addEventListener('cuc:emote', this.onDevEmote)
      window.cucCharacter = this.devTools()
    }
    this.lastFrame = performance.now() / 1000
    this.nextEmoteAt = this.lastFrame + between(FIRST_DELAY)
    this.start()
  }

  /** The skin in Minecraft's 64×64 layout (see skinPixels). */
  setSkin(pixels: ImageData, slim: boolean): void {
    this.skin = pixels
    this.slim = slim
    if (this.contextLost) return
    this.renderer.setSkin(pixels)
    this.draw(performance.now() / 1000)
  }

  /** Starts an emote right away. */
  play(id: string): void {
    const emote = EMOTES.find((e) => e.id === id)
    if (!emote) return
    this.held = null
    this.begin(emote, performance.now() / 1000)
  }

  dispose(): void {
    this.disposed = true
    cancelAnimationFrame(this.frame)
    this.resizeObserver.disconnect()
    document.removeEventListener('visibilitychange', this.onVisibility)
    this.reducedMotion.removeEventListener('change', this.onMotionPreference)
    if (import.meta.env.DEV) {
      window.removeEventListener('cuc:emote', this.onDevEmote)
      delete window.cucCharacter
    }
    this.canvas.removeEventListener('webglcontextlost', this.onContextLost)
    this.canvas.removeEventListener('webglcontextrestored', this.onContextRestored)
    this.effects.replaceChildren()
    this.renderer.dispose()
    // Hand the GPU context back once the canvas is gone for good. (In development
    // React mounts twice and reuses the same canvas, so check after this tick.)
    const { canvas, gl } = this
    setTimeout(() => {
      if (!canvas.isConnected) gl.getExtension('WEBGL_lose_context')?.loseContext()
    })
  }

  private readonly onContextLost = (event: Event) => {
    // Without preventDefault the browser never restores the context.
    event.preventDefault()
    this.contextLost = true
    cancelAnimationFrame(this.frame)
  }

  private readonly onContextRestored = () => {
    this.contextLost = false
    this.renderer = new CharacterRenderer(this.gl)
    if (this.skin) this.renderer.setSkin(this.skin)
    this.start()
  }

  private get still(): boolean {
    return this.reducedMotion.matches
  }

  private start(): void {
    cancelAnimationFrame(this.frame)
    if (this.disposed || this.contextLost) return
    if (this.still || document.hidden) {
      this.draw(performance.now() / 1000)
      return
    }
    this.frame = requestAnimationFrame(this.tick)
  }

  private readonly onVisibility = () => this.start()

  private readonly onMotionPreference = () => {
    this.emote = null
    this.start()
  }

  private readonly tick = (ms: number) => {
    this.frame = requestAnimationFrame(this.tick)
    const now = ms / 1000
    // Coming back from a hidden window or a stalled tab: carry on where it left off.
    const gap = now - this.lastFrame
    if (gap > 0.25) {
      this.emoteStart += gap
      this.nextEmoteAt += gap
    }
    this.lastFrame = now

    if (!this.emote && !this.held && now >= this.nextEmoteAt) this.begin(this.pick(), now)
    // Standing still only needs about 30 frames a second.
    if (!this.emote && ms - this.lastDraw < 30) return
    this.lastDraw = ms
    this.draw(now)

    if (this.emote) {
      const t = now - this.emoteStart
      const cues = this.emote.cues ?? []
      while (this.nextCue < cues.length && cues[this.nextCue].at <= t) this.fire(cues[this.nextCue++])
      if (t >= this.emote.duration) {
        this.emote = null
        this.nextEmoteAt = now + between(DELAY)
      }
    }
  }

  private begin(emote: Emote, now: number): void {
    this.emote = emote
    this.lastEmote = emote
    this.emoteStart = now
    this.nextCue = 0
  }

  /** Every emote once, in a random order, before any repeats. */
  private pick(): Emote {
    if (this.bag.length === 0) {
      this.bag = shuffled(EMOTES)
      // The bag is used from the end; don't open with the emote that just played.
      const last = this.bag.length - 1
      if (this.bag[last] === this.lastEmote) [this.bag[0], this.bag[last]] = [this.bag[last], this.bag[0]]
    }
    return this.bag.pop() as Emote
  }

  private composePose(now: number): void {
    stance(this.still ? 0 : now, this.pose)
    if (this.held) this.held.emote.pose(this.held.t, this.pose)
    else if (this.emote) this.emote.pose(Math.min(now - this.emoteStart, this.emote.duration), this.pose)
    this.pose.root.ry += this.devTurn
  }

  private draw(now: number): void {
    if (this.disposed || this.contextLost || this.canvas.width === 0) return
    this.composePose(now)
    this.renderer.render(this.pose, this.slim, this.viewProjection, this.canvas.width, this.canvas.height)
  }

  private resize(): void {
    const rect = this.canvas.getBoundingClientRect()
    if (rect.width === 0 || rect.height === 0) return
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    this.cssWidth = rect.width
    this.cssHeight = rect.height
    this.canvas.width = Math.round(rect.width * dpr)
    this.canvas.height = Math.round(rect.height * dpr)
    const distance = VIEW_HEIGHT / 2 / Math.tan(FOV / 2)
    const projection = perspective(mat4(), FOV, rect.width / rect.height, 10, 400)
    const view = lookAt(mat4(), [0, EYE_Y, distance], [0, EYE_Y, 0])
    multiply(this.viewProjection, projection, view)
    this.draw(performance.now() / 1000)
  }

  /** Where a point in the scene lands on the canvas, in CSS pixels. */
  private project(x: number, y: number, z: number): [number, number] {
    const m = this.viewProjection
    const cx = m[0] * x + m[4] * y + m[8] * z + m[12]
    const cy = m[1] * x + m[5] * y + m[9] * z + m[13]
    const w = m[3] * x + m[7] * y + m[11] * z + m[15]
    return [(cx / w / 2 + 0.5) * this.cssWidth, (0.5 - cy / w / 2) * this.cssHeight]
  }

  private fire(cue: Cue): void {
    const anchor = anchorPoint(cue.from, this.slim)
    const [x, y, z] = transformPoint(this.renderer.matrices[anchor.part], anchor.at[0], anchor.at[1], anchor.at[2])
    const [sx, sy] = this.project(x, y, z)
    // A knock shakes the whole screen a little, as if the glass was hit.
    const shake = this.canvas.closest('main') ?? this.effects
    spawnCue(this.effects, shake, cue, sx, sy)
  }

  private readonly onDevEmote = (event: Event) => {
    const id = (event as CustomEvent<string | undefined>).detail
    const emote = (id && EMOTES.find((e) => e.id === id)) || EMOTES[this.devOrder++ % EMOTES.length]
    console.info(`Emote: ${emote.name}`)
    this.play(emote.id)
  }

  /** `window.cucCharacter` in the browser preview, for checking emotes by hand. */
  private devTools(): CharacterDevTools {
    // Lets release builds drop all of this.
    if (!import.meta.env.DEV) throw new Error('Dev tools only exist in the browser preview')
    return {
      emotes: EMOTES.map((e) => e.id),
      playing: () => this.emote?.id ?? null,
      play: (id) => this.play(id),
      skin: (pixels, slim) => this.setSkin(pixels, slim),
      hold: (id, t) => {
        const emote = EMOTES.find((e) => e.id === id)
        this.held = emote ? { emote, t } : null
        this.emote = null
        this.draw(performance.now() / 1000)
      },
      release: () => {
        this.held = null
        this.devTurn = 0
        this.nextEmoteAt = performance.now() / 1000 + between(DELAY)
      },
      turn: (radians) => {
        this.devTurn = radians
        this.draw(performance.now() / 1000)
      },
      storyboard: (id, count = 8) => {
        const emote = EMOTES.find((e) => e.id === id)
        if (!emote) return null
        const columns = 4
        const w = Math.round(this.cssWidth / 2)
        const h = Math.round(this.cssHeight / 2)
        const sheet = document.createElement('canvas')
        sheet.width = columns * w
        sheet.height = Math.ceil(count / columns) * h
        const ctx = sheet.getContext('2d')
        if (!ctx) return null
        ctx.fillStyle = '#0f1819'
        ctx.fillRect(0, 0, sheet.width, sheet.height)
        ctx.font = '600 12px system-ui'
        for (let i = 0; i < count; i++) {
          const t = (emote.duration * i) / (count - 1)
          this.held = { emote, t }
          this.draw(0)
          const x = (i % columns) * w
          const y = Math.floor(i / columns) * h
          ctx.drawImage(this.canvas, x, y, w, h)
          ctx.fillStyle = '#e4c084'
          ctx.fillText(`${emote.name} ${t.toFixed(2)}s`, x + 8, y + 18)
        }
        this.held = null
        this.draw(performance.now() / 1000)
        return sheet.toDataURL()
      }
    }
  }
}

export interface CharacterDevTools {
  emotes: string[]
  /** The emote playing right now, if any. */
  playing: () => string | null
  play: (id: string) => void
  /** Tries another skin (already in Minecraft's 64×64 layout). */
  skin: (pixels: ImageData, slim: boolean) => void
  /** Freezes the character `t` seconds into an emote. */
  hold: (id: string, t: number) => void
  release: () => void
  /** Turns the whole character, to look at its sides and back. */
  turn: (radians: number) => void
  /** A contact sheet of evenly spaced moments, as a data: URL. */
  storyboard: (id: string, count?: number) => string | null
}

declare global {
  interface Window {
    cucCharacter?: CharacterDevTools
  }
  interface WindowEventMap {
    /** Dev panel: play an emote (by id, or the next one in the list). */
    'cuc:emote': CustomEvent<string | undefined>
  }
}
