import { resetPose, type Pose } from './pose'

/** Something that pops out of the character partway through an emote. */
export interface Cue {
  /** Seconds into the emote. */
  at: number
  /** Floating text, a heart blown at you, or a knock on the glass. */
  kind: 'text' | 'heart' | 'knock'
  text?: string
  from: 'head' | 'rightHand' | 'leftHand'
}

export interface Emote {
  id: string
  name: string
  /** Seconds. Every emote ends back in the standing pose. */
  duration: number
  /** Bends the standing pose `p` into the emote at `t` seconds. */
  pose: (t: number, p: Pose) => void
  cues?: readonly Cue[]
}

const TAU = Math.PI * 2

const clamp01 = (x: number) => (x < 0 ? 0 : x > 1 ? 1 : x)
const smooth = (x: number) => {
  const c = clamp01(x)
  return c * c * (3 - 2 * c)
}
const lerp = (a: number, b: number, k: number) => a + (b - a) * k
/** 0 before a, 1 after b. */
const ramp = (t: number, a: number, b: number) => smooth((t - a) / (b - a))
/** Rises over [a, b], holds, falls over [c, d]. */
const env = (t: number, a: number, b: number, c: number, d: number) => ramp(t, a, b) * (1 - ramp(t, c, d))
/** Like ramp, but overshoots a little before settling: for snappy moves. */
const snap = (t: number, a: number, b: number) => {
  const x = clamp01((t - a) / (b - a)) - 1
  return 1 + 2.70158 * x * x * x + 1.70158 * x * x
}
/** A sine wave at `hz`. */
const wave = (t: number, hz: number) => Math.sin(t * hz * TAU)
/** 0 → 1 → 0 between a and b. */
const bump = (t: number, a: number, b: number) => (t <= a || t >= b ? 0 : Math.sin(((t - a) / (b - a)) * Math.PI))

/** Standing slightly turned toward the left of the window, head a little toward you. */
export const BASE_YAW = -0.42
const HEAD_TURN = 0.16

/**
 * Standing still, with Minecraft's own idle arm sway (the same numbers as
 * AnimationUtils.bobArms, in seconds instead of ticks).
 */
export function stance(time: number, p: Pose): void {
  resetPose(p)
  p.root.ry = BASE_YAW
  p.head.ry = HEAD_TURN
  const out = Math.cos(time * 1.8) * 0.05 + 0.05
  const swing = Math.sin(time * 1.34) * 0.05
  p.rightArm.rz = -out
  p.rightArm.rx = swing
  p.leftArm.rz = out
  p.leftArm.rx = -swing
}

/** Turns body and head toward you: k = 1 looks straight at you. */
function face(p: Pose, k: number): void {
  p.root.ry *= 1 - k
  p.head.ry *= 1 - k
}

/** Turns just the head toward you, whichever way the body faces. */
function look(p: Pose, k: number): void {
  p.head.ry = lerp(p.head.ry, -p.root.ry - p.torso.ry, k)
}

const KNOCKS = [1.0, 1.2, 1.4, 2.35, 2.55]

export const EMOTES: readonly Emote[] = [
  {
    id: 'wave',
    name: 'Wave',
    duration: 2.6,
    pose(t, p) {
      face(p, env(t, 0, 0.35, 2.2, 2.6))
      const up = env(t, 0.12, 0.42, 1.95, 2.4)
      const w = wave(t - 0.42, 2.8) * 0.4 * env(t, 0.42, 0.55, 1.8, 1.95)
      p.rightArm.rz = lerp(p.rightArm.rz, -2.6, up) + w
      p.rightArm.rx = lerp(p.rightArm.rx, -0.2, up)
      p.head.rz += 0.1 * up
      p.head.rx -= 0.06 * up
      p.torso.rz -= 0.04 * up
      p.leftArm.rz += 0.04 * up
    },
    cues: [{ at: 0.55, kind: 'text', text: 'hi!', from: 'head' }]
  },
  {
    id: 'laugh',
    name: 'Point and laugh',
    duration: 3.4,
    pose(t, p) {
      // Half turned, so the pointing arm shows side-on, with the face toward you.
      const k = env(t, 0, 0.3, 3.0, 3.4)
      face(p, 0.45 * k)
      look(p, k)
      const point = env(t, 0.15, 0.38, 2.75, 3.2)
      const laugh = env(t, 0.45, 0.65, 2.7, 3.05)
      const shake = wave(t, 5.5) * laugh
      const lean = env(t, 0.45, 0.7, 1.45, 1.75)
      const over = env(t, 1.5, 1.8, 2.6, 2.95)
      p.torso.rx += -0.22 * lean + 0.42 * over + 0.04 * shake
      p.head.rx += -0.45 * lean - 0.32 * over + 0.06 * shake
      p.root.y += 0.4 * Math.abs(shake)
      // Keeps pointing at you while the body rocks, angled in so the arm shows.
      p.rightArm.rx = lerp(p.rightArm.rx, -1.45, point) + 0.22 * lean - 0.42 * over + 0.07 * shake
      p.rightArm.ry = 0.5 * point
      p.rightArm.rz = lerp(p.rightArm.rz, 0, point)
      // The other hand holds the belly.
      p.leftArm.rx = lerp(p.leftArm.rx, -0.7, laugh)
      p.leftArm.rz = lerp(p.leftArm.rz, -0.45, laugh)
    },
    cues: [
      { at: 0.75, kind: 'text', text: 'HA', from: 'head' },
      { at: 1.15, kind: 'text', text: 'HA', from: 'head' },
      { at: 1.6, kind: 'text', text: 'HA!', from: 'head' },
      { at: 2.1, kind: 'text', text: 'HA', from: 'head' }
    ]
  },
  {
    id: 'crouch',
    name: 'Crouch spam',
    duration: 2.6,
    pose(t, p) {
      // Half turned, so the crouch shows from the side, but looking right at you.
      const k = env(t, 0, 0.25, 2.25, 2.6)
      face(p, 0.5 * k)
      look(p, k)
      const on = env(t, 0.25, 0.3, 2.05, 2.15)
      const phase = (t - 0.3) / 0.3
      const frac = phase - Math.floor(phase)
      const c = ramp(frac, 0, 0.18) * (1 - ramp(frac, 0.5, 0.68)) * on
      p.torso.rx += 0.5 * c
      p.torso.y -= 1.6 * c
      p.head.rx -= 0.42 * c
      p.rightArm.rx -= 0.1 * c
      p.leftArm.rx -= 0.1 * c
    }
  },
  {
    id: 'dab',
    name: 'Dab',
    duration: 2.1,
    pose(t, p) {
      face(p, env(t, 0, 0.18, 1.75, 2.1))
      p.root.y -= 0.7 * env(t, 0, 0.14, 0.16, 0.26)
      const d = snap(t, 0.2, 0.36) * (1 - ramp(t, 1.4, 1.75))
      p.rightArm.rz = lerp(p.rightArm.rz, -2.2, d)
      p.rightArm.rx = lerp(p.rightArm.rx, -0.3, d)
      p.leftArm.rx = lerp(p.leftArm.rx, -1.85, d)
      p.leftArm.ry = -0.9 * d
      p.leftArm.rz = lerp(p.leftArm.rz, 0, d)
      p.head.rx += 0.5 * d
      p.head.ry += 0.5 * d
      p.head.rz -= 0.15 * d
      p.torso.rx += 0.12 * d
      p.torso.ry += 0.15 * d
    }
  },
  {
    id: 'floss',
    name: 'Floss',
    duration: 3.4,
    pose(t, p) {
      face(p, env(t, 0, 0.3, 3.05, 3.4))
      const f = env(t, 0.25, 0.45, 2.85, 3.15)
      const s = wave(t - 0.25, 2.1) * f
      // Both arms swing to one side, the crossing arm in front; the hips go the other way.
      p.rightArm.rz = lerp(p.rightArm.rz, 0, f) + 0.82 * s
      p.leftArm.rz = lerp(p.leftArm.rz, 0, f) + 0.82 * s
      p.rightArm.rx = lerp(p.rightArm.rx, 0, f) - 0.6 * s
      p.leftArm.rx = lerp(p.leftArm.rx, 0, f) + 0.6 * s
      p.root.x -= 2 * s
      p.root.y -= 0.4 * (f - Math.abs(s))
      p.rightLeg.rz += 0.165 * s
      p.leftLeg.rz += 0.165 * s
      p.torso.rz -= 0.16 * s
      p.head.rz += 0.12 * s
    }
  },
  {
    id: 'tpose',
    name: 'T-pose',
    duration: 3.3,
    pose(t, p) {
      // No easing on purpose: it snaps in, glides at you dead still, and snaps back.
      const on = clamp01(t / 0.07) * (1 - clamp01((t - 3.15) / 0.07))
      p.root.ry = lerp(p.root.ry, 0, on)
      p.head.ry = lerp(p.head.ry, 0, on)
      p.rightArm.rz = lerp(p.rightArm.rz, -Math.PI / 2, on)
      p.rightArm.rx = lerp(p.rightArm.rx, 0, on)
      p.leftArm.rz = lerp(p.leftArm.rz, Math.PI / 2, on)
      p.leftArm.rx = lerp(p.leftArm.rx, 0, on)
      const glide = clamp01((t - 0.5) / 2.3) * on
      p.root.z += 5 * glide
      p.root.y += 0.8 * glide
    }
  },
  {
    id: 'knock',
    name: 'Knock on the screen',
    duration: 4.2,
    pose(t, p) {
      face(p, env(t, 0, 0.35, 3.8, 4.2))
      const lean = env(t, 0.3, 0.8, 3.25, 3.8)
      p.root.z += 5.5 * lean
      p.torso.rx += 0.42 * lean
      p.head.rx -= 0.38 * lean
      p.rightLeg.rx -= 0.18 * lean
      p.leftLeg.rx += 0.14 * lean
      p.leftArm.rz = lerp(p.leftArm.rz, 0.12, lean)
      const arm = env(t, 0.6, 0.95, 2.95, 3.35)
      let jab = 0
      for (const k of KNOCKS) jab += bump(t, k - 0.06, k + 0.08)
      p.rightArm.rx = lerp(p.rightArm.rx, -1.96, arm) - 0.1 * jab
      p.rightArm.ry = 0.18 * arm
      p.rightArm.rz = lerp(p.rightArm.rz, 0, arm)
      p.rightArm.z += 1.4 * jab
      // "Hello? Anyone in there?"
      p.head.rz += 0.3 * env(t, 1.55, 1.75, 2.05, 2.25)
    },
    cues: [...KNOCKS.map((at): Cue => ({ at, kind: 'knock', from: 'rightHand' })), { at: 1.7, kind: 'text', text: '?', from: 'head' }]
  },
  {
    id: 'owl',
    name: 'Owl head',
    duration: 3.0,
    pose(t, p) {
      // The body stays put; the head looks at you, then turns all the way around.
      const look = env(t, 0, 0.3, 2.55, 2.95)
      p.head.ry = lerp(p.head.ry, -BASE_YAW, look) + TAU * smooth((t - 0.55) / 1.7)
      p.head.rz += 0.12 * bump(t, 2.25, 2.75)
    }
  },
  {
    id: 'facepalm',
    name: 'Facepalm',
    duration: 2.9,
    pose(t, p) {
      face(p, env(t, 0, 0.3, 2.5, 2.9))
      const palm = env(t, 0.2, 0.6, 2.05, 2.5)
      p.rightArm.rx = lerp(p.rightArm.rx, -2.4, palm)
      p.rightArm.ry = 0.55 * palm
      p.rightArm.rz = lerp(p.rightArm.rz, 0, palm)
      p.head.rx += 0.32 * palm
      p.torso.rx += 0.08 * palm
      p.head.ry += 0.15 * wave(t - 0.75, 1.3) * env(t, 0.75, 0.95, 1.8, 2.0)
      p.leftArm.rz += 0.04 * palm
    }
  },
  {
    id: 'doze',
    name: 'Dozing off',
    duration: 4.4,
    pose(t, p) {
      const droop = ramp(t, 0.1, 1.3) * (1 - ramp(t, 2.8, 2.88))
      p.head.rx += 0.58 * droop + 0.06 * wave(t, 0.8) * droop
      p.torso.rx += 0.1 * droop
      p.root.rz += 0.025 * wave(t, 0.45) * droop
      p.rightArm.rz *= 1 - droop
      p.leftArm.rz *= 1 - droop
      p.rightArm.rx *= 1 - droop
      p.leftArm.rx *= 1 - droop
      // Wakes with a start, then looks around to see if you noticed.
      const jolt = env(t, 2.8, 2.88, 3.0, 3.35)
      p.root.y += 2.6 * jolt
      p.rightArm.rz -= 1.0 * jolt
      p.leftArm.rz += 1.0 * jolt
      p.rightLeg.rz -= 0.12 * jolt
      p.leftLeg.rz += 0.12 * jolt
      p.head.rx -= 0.25 * jolt
      face(p, env(t, 2.85, 3.05, 4.0, 4.4))
      p.head.ry += 0.38 * wave(t - 3.2, 1.7) * env(t, 3.2, 3.3, 3.75, 3.95)
    },
    cues: [
      { at: 0.8, kind: 'text', text: 'z', from: 'head' },
      { at: 1.35, kind: 'text', text: 'z', from: 'head' },
      { at: 1.9, kind: 'text', text: 'Z', from: 'head' },
      { at: 2.45, kind: 'text', text: 'Z', from: 'head' },
      { at: 2.84, kind: 'text', text: '!', from: 'head' }
    ]
  },
  {
    id: 'chicken',
    name: 'Chicken dance',
    duration: 3.0,
    pose(t, p) {
      // Mostly side-on, so the pecking neck shows.
      const k = env(t, 0, 0.3, 2.65, 3.0)
      face(p, 0.3 * k)
      look(p, 0.5 * k)
      const f = env(t, 0.2, 0.4, 2.45, 2.75)
      const flap = (wave(t - 0.2, 3.4) + 1) / 2
      p.rightArm.rz = lerp(p.rightArm.rz, -(0.3 + 0.5 * flap), f)
      p.leftArm.rz = lerp(p.leftArm.rz, 0.3 + 0.5 * flap, f)
      p.rightArm.rx = lerp(p.rightArm.rx, 0.45, f)
      p.leftArm.rx = lerp(p.leftArm.rx, 0.45, f)
      // The neck pecks back and forth.
      const peck = wave(t - 0.2, 1.7) * f
      p.head.z += 2.4 * peck
      p.head.rx += 0.12 * peck
      p.torso.rx += 0.14 * f
      p.root.y -= 0.4 * flap * f
      p.rightLeg.rx += 0.18 * peck
      p.leftLeg.rx -= 0.18 * peck
    }
  },
  {
    id: 'flip',
    name: 'Side flip',
    duration: 2.3,
    pose(t, p) {
      face(p, env(t, 0, 0.25, 1.95, 2.3))
      const crouch = env(t, 0.15, 0.4, 0.42, 0.5)
      const land = env(t, 1.08, 1.14, 1.2, 1.45)
      p.squash += 0.1 * crouch + 0.12 * land
      p.torso.rx += 0.2 * crouch + 0.2 * land
      p.rightArm.rx += 0.6 * crouch
      p.leftArm.rx += 0.6 * crouch
      const air = clamp01((t - 0.45) / 0.65)
      const mid = Math.sin(Math.PI * air)
      p.root.y += 26 * air * (1 - air)
      p.root.rz += TAU * smooth(air)
      // Tucked in the air, so the flip stays inside the stage.
      p.rightArm.rz -= 0.5 * mid
      p.leftArm.rz += 0.5 * mid
      p.rightLeg.rx -= 1.1 * mid
      p.leftLeg.rx -= 1.1 * mid
      p.torso.rx += 0.25 * mid
      // Ta-da!
      const tada = env(t, 1.2, 1.4, 1.8, 2.1)
      p.rightArm.rz -= 2.3 * tada
      p.leftArm.rz += 2.3 * tada
      p.head.rx -= 0.12 * tada
    }
  },
  {
    id: 'kiss',
    name: 'Blow a kiss',
    duration: 2.7,
    pose(t, p) {
      face(p, env(t, 0, 0.3, 2.3, 2.7))
      const mouth = env(t, 0.2, 0.55, 0.78, 0.95)
      const fling = env(t, 0.8, 0.98, 1.75, 2.2)
      p.rightArm.rx = lerp(lerp(p.rightArm.rx, -2.0, mouth), -1.65, fling)
      p.rightArm.ry = 0.6 * mouth - 0.45 * fling
      p.rightArm.rz = lerp(p.rightArm.rz, 0, Math.max(mouth, fling))
      p.head.rx += 0.14 * mouth - 0.1 * fling
      p.head.rz += 0.2 * fling
      p.torso.rx += 0.06 * mouth - 0.08 * fling
      const hip = env(t, 0.2, 0.5, 2.0, 2.4)
      p.leftArm.rz = lerp(p.leftArm.rz, 0.35, hip)
      p.leftArm.rx = lerp(p.leftArm.rx, -0.2, hip)
    },
    cues: [{ at: 0.98, kind: 'heart', from: 'rightHand' }]
  },
  {
    id: 'guitar',
    name: 'Air guitar',
    duration: 3.3,
    pose(t, p) {
      // A little side-on, so the headbanging shows.
      const k = env(t, 0, 0.3, 2.95, 3.3)
      face(p, 0.6 * k)
      look(p, 0.6 * k)
      const g = env(t, 0.15, 0.45, 2.7, 3.05)
      // Left hand up the neck, right hand strumming across the body, head banging.
      p.leftArm.rx = lerp(p.leftArm.rx, -1.3, g)
      p.leftArm.ry = 0.45 * g
      p.leftArm.rz = lerp(p.leftArm.rz, 0, g)
      const strum = wave(t - 0.45, 4.4) * env(t, 0.45, 0.55, 2.6, 2.75)
      p.rightArm.rx = lerp(p.rightArm.rx, -0.8, g) + 0.25 * strum
      p.rightArm.ry = 0.75 * g
      p.rightArm.rz = lerp(p.rightArm.rz, 0, g)
      const bang = Math.max(0, wave(t - 0.45, 2.2)) * env(t, 0.45, 0.6, 2.55, 2.75)
      p.head.rx += 0.5 * bang - 0.08 * g
      p.torso.rx += -0.16 * g + 0.12 * bang
      p.root.y -= 0.3 * bang
      p.rightLeg.rz -= 0.1 * g
      p.leftLeg.rz += 0.1 * g
    },
    cues: [
      { at: 0.6, kind: 'text', text: '♪', from: 'head' },
      { at: 1.15, kind: 'text', text: '♫', from: 'head' },
      { at: 1.7, kind: 'text', text: '♪', from: 'head' },
      { at: 2.25, kind: 'text', text: '♫', from: 'head' }
    ]
  },
  {
    id: 'default',
    name: 'Default dance',
    duration: 4.75,
    pose(t, p) {
      // The well-known default dance, two beats a second: arms swing side to side
      // in front with a bounce, then hops from foot to foot, kicking the free leg out.
      face(p, env(t, 0, 0.3, 4.4, 4.75))
      const a = env(t, 0.25, 0.45, 2.15, 2.4)
      const b = env(t, 2.3, 2.5, 4.15, 4.5)
      const swing = Math.sin((t - 0.35) * TAU)
      const pump = Math.sin((t - 2.4) * TAU)
      const rest = Math.max(0, 1 - a - b)

      // Part one: both arms out in front, swinging together, a step toward each side.
      const reach = -0.85 - 0.35 * Math.abs(swing)
      // Part two: fists pump in turn, opposite the kicking leg.
      p.rightArm.rx = p.rightArm.rx * rest + reach * a + (-0.5 - 0.8 * pump) * b
      p.leftArm.rx = p.leftArm.rx * rest + reach * a + (-0.5 + 0.8 * pump) * b
      p.rightArm.ry = 0.75 * swing * a
      p.leftArm.ry = 0.75 * swing * a
      p.rightArm.rz = p.rightArm.rz * rest - 0.25 * b
      p.leftArm.rz = p.leftArm.rz * rest + 0.25 * b
      p.root.x += 1.1 * swing * a - 0.9 * pump * b
      p.root.y += -0.5 * (1 - Math.abs(swing)) * a + 1.3 * Math.abs(pump) * b
      p.torso.ry += 0.22 * swing * a
      p.torso.rz += 0.12 * pump * b
      p.head.rz += -0.08 * swing * a - 0.08 * pump * b
      p.head.rx += 0.08 * Math.abs(swing) * a

      const kickLeft = Math.max(pump, 0) * b
      const kickRight = Math.max(-pump, 0) * b
      p.leftLeg.rz += 0.6 * kickLeft
      p.leftLeg.rx -= 0.25 * kickLeft
      p.rightLeg.rz -= 0.6 * kickRight
      p.rightLeg.rx -= 0.25 * kickRight
    },
    cues: [
      { at: 0.5, kind: 'text', text: '♪', from: 'head' },
      { at: 1.5, kind: 'text', text: '♫', from: 'head' },
      { at: 2.5, kind: 'text', text: '♪', from: 'head' },
      { at: 3.5, kind: 'text', text: '♫', from: 'head' }
    ]
  },
  {
    id: 'shrug',
    name: 'Shrug',
    duration: 2.0,
    pose(t, p) {
      face(p, env(t, 0, 0.25, 1.65, 2.0))
      const s = env(t, 0.15, 0.38, 1.2, 1.55)
      p.rightArm.y += 1.0 * s
      p.leftArm.y += 1.0 * s
      p.rightArm.rz = lerp(p.rightArm.rz, -0.55, s)
      p.leftArm.rz = lerp(p.leftArm.rz, 0.55, s)
      p.rightArm.rx = lerp(p.rightArm.rx, -0.5, s)
      p.leftArm.rx = lerp(p.leftArm.rx, -0.5, s)
      p.head.rz += 0.22 * s
      p.head.rx -= 0.05 * s
    }
  }
]
