import { mat4, rotateX, rotateY, rotateZ, scale, translate, type Mat4 } from './math'
import { PART_NAMES, type PartName, type Pose } from './pose'

interface BoxDef {
  /** Where the box's texture starts in the 64×64 skin. */
  u: number
  v: number
  /** Corner and size relative to the part's pivot, in skin pixels. */
  from: readonly [number, number, number]
  size: readonly [number, number, number]
  /** How far the box grows on every side. The outer layer floats over the inner one. */
  inflate: number
  outer: boolean
}

interface PartDef {
  name: PartName
  parent: PartName
  /** The joint, relative to the parent's joint. */
  pivot: readonly [number, number, number]
  boxes: BoxDef[]
}

/** Root rotations (flips, spins) turn around this height: about the middle of the body. */
const ROOT_PIVOT_Y = 15

/**
 * Minecraft's player model, standing with its feet at y = 0. The torso bends at
 * the hips and carries the head and arms. Each outer layer grows by a slightly
 * different amount so overlapping layers (sleeve over jacket) never share a plane.
 */
function playerParts(slim: boolean): PartDef[] {
  const arm = slim ? 3 : 4
  const shoulder = slim ? 9.5 : 10
  const box = (u: number, v: number, from: BoxDef['from'], size: BoxDef['size'], inflate = 0): BoxDef => ({
    u,
    v,
    from,
    size,
    inflate,
    outer: inflate > 0
  })
  return [
    { name: 'torso', parent: 'root', pivot: [0, 12, 0], boxes: [box(16, 16, [-4, 0, -2], [8, 12, 4]), box(16, 32, [-4, 0, -2], [8, 12, 4], 0.25)] },
    { name: 'head', parent: 'torso', pivot: [0, 12, 0], boxes: [box(0, 0, [-4, 0, -4], [8, 8, 8]), box(32, 0, [-4, 0, -4], [8, 8, 8], 0.5)] },
    {
      name: 'rightArm',
      parent: 'torso',
      pivot: [-5, shoulder, 0],
      boxes: [box(40, 16, [1 - arm, -10, -2], [arm, 12, 4]), box(40, 32, [1 - arm, -10, -2], [arm, 12, 4], 0.26)]
    },
    {
      name: 'leftArm',
      parent: 'torso',
      pivot: [5, shoulder, 0],
      boxes: [box(32, 48, [-1, -10, -2], [arm, 12, 4]), box(48, 48, [-1, -10, -2], [arm, 12, 4], 0.26)]
    },
    { name: 'rightLeg', parent: 'root', pivot: [-2, 12, 0], boxes: [box(0, 16, [-2, -12, -2], [4, 12, 4]), box(0, 32, [-2, -12, -2], [4, 12, 4], 0.255)] },
    { name: 'leftLeg', parent: 'root', pivot: [2, 12, 0], boxes: [box(16, 48, [-2, -12, -2], [4, 12, 4]), box(0, 48, [-2, -12, -2], [4, 12, 4], 0.265)] }
  ]
}

type Vec3 = readonly [number, number, number]

interface Face {
  normal: Vec3
  /** Corners matching the texture region's top-left, top-right, bottom-right, bottom-left. */
  corners: readonly [Vec3, Vec3, Vec3, Vec3]
  /** x, y, width, height in the skin. */
  region: readonly [number, number, number, number]
}

/**
 * The six faces of a box in Minecraft's skin layout: the texture unfolds as
 * [right side][front][left side][back] with top and bottom above the front.
 */
function boxFaces(b: BoxDef): Face[] {
  const [w, h, d] = b.size
  const g = b.inflate
  const [x0, y0, z0] = [b.from[0] - g, b.from[1] - g, b.from[2] - g]
  const [x1, y1, z1] = [b.from[0] + w + g, b.from[1] + h + g, b.from[2] + d + g]
  const { u, v } = b
  return [
    { normal: [0, 0, 1], corners: [[x0, y1, z1], [x1, y1, z1], [x1, y0, z1], [x0, y0, z1]], region: [u + d, v + d, w, h] },
    { normal: [0, 0, -1], corners: [[x1, y1, z0], [x0, y1, z0], [x0, y0, z0], [x1, y0, z0]], region: [u + 2 * d + w, v + d, w, h] },
    { normal: [-1, 0, 0], corners: [[x0, y1, z0], [x0, y1, z1], [x0, y0, z1], [x0, y0, z0]], region: [u, v + d, d, h] },
    { normal: [1, 0, 0], corners: [[x1, y1, z1], [x1, y1, z0], [x1, y0, z0], [x1, y0, z1]], region: [u + d + w, v + d, d, h] },
    // Top and bottom both have the back edge at the top of their texture region.
    { normal: [0, 1, 0], corners: [[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]], region: [u + d, v, w, d] },
    { normal: [0, -1, 0], corners: [[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]], region: [u + d + w, v, w, d] }
  ]
}

/** One box to draw: a range of the index buffer, moved by its part's matrix. */
export interface DrawItem {
  part: PartName
  outer: boolean
  first: number
  count: number
}

export interface PlayerGeometry {
  /** Interleaved position (3), normal (3) and texture coordinate (2). */
  vertices: Float32Array
  indices: Uint16Array
  /** Inner layer first, then the outer layer. */
  items: DrawItem[]
  parts: PartDef[]
}

export const FLOATS_PER_VERTEX = 8

export function buildPlayer(slim: boolean): PlayerGeometry {
  const parts = playerParts(slim)
  const vertices: number[] = []
  const indices: number[] = []
  const items: DrawItem[] = []
  for (const layer of [false, true]) {
    for (const part of parts) {
      for (const b of part.boxes) {
        if (b.outer !== layer) continue
        const first = indices.length
        for (const face of boxFaces(b)) {
          const base = vertices.length / FLOATS_PER_VERTEX
          const [ru, rv, rw, rh] = face.region
          const uv = [
            [ru, rv],
            [ru + rw, rv],
            [ru + rw, rv + rh],
            [ru, rv + rh]
          ]
          face.corners.forEach((c, i) => vertices.push(c[0], c[1], c[2], ...face.normal, uv[i][0] / 64, uv[i][1] / 64))
          // Counter-clockwise as seen from outside, so back faces can be culled.
          const [a, b2, c] = face.corners
          const e1 = [b2[0] - a[0], b2[1] - a[1], b2[2] - a[2]]
          const e2 = [c[0] - a[0], c[1] - a[1], c[2] - a[2]]
          const cross = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]]
          const outward = cross[0] * face.normal[0] + cross[1] * face.normal[1] + cross[2] * face.normal[2] > 0
          if (outward) indices.push(base, base + 1, base + 2, base, base + 2, base + 3)
          else indices.push(base, base + 2, base + 1, base, base + 3, base + 2)
        }
        items.push({ part: part.name, outer: b.outer, first, count: indices.length - first })
      }
    }
  }
  return { vertices: new Float32Array(vertices), indices: new Uint16Array(indices), items, parts }
}

export type PartMatrices = Record<PartName, Mat4>

export function createMatrices(): PartMatrices {
  const out = {} as PartMatrices
  for (const name of PART_NAMES) out[name] = mat4()
  return out
}

/** World matrices for every part in this pose. */
export function poseMatrices(pose: Pose, parts: PartDef[], out: PartMatrices): PartMatrices {
  const root = out.root
  const r = pose.root
  root.fill(0)
  root[0] = root[5] = root[10] = root[15] = 1
  translate(root, r.x, r.y + ROOT_PIVOT_Y, r.z)
  rotateY(root, r.ry)
  rotateX(root, r.rx)
  rotateZ(root, r.rz)
  translate(root, 0, -ROOT_PIVOT_Y, 0)
  // Squash from the feet, keeping roughly the same volume.
  scale(root, 1 + pose.squash * 0.5, 1 - pose.squash, 1 + pose.squash * 0.5)
  for (const part of parts) {
    const m = out[part.name]
    const j = pose[part.name]
    m.set(out[part.parent])
    translate(m, part.pivot[0] + j.x, part.pivot[1] + j.y, part.pivot[2] + j.z)
    // Minecraft's order: x first, then y, then z.
    rotateZ(m, j.rz)
    rotateY(m, j.ry)
    rotateX(m, j.rx)
  }
  return out
}

/** A point on a part, for placing effects: above the head, or at a hand. */
export function anchorPoint(anchor: 'head' | 'rightHand' | 'leftHand', slim: boolean): { part: PartName; at: Vec3 } {
  const armCenter = slim ? 0.5 : 0
  switch (anchor) {
    case 'head':
      return { part: 'head', at: [0, 11, 0] }
    case 'rightHand':
      return { part: 'rightArm', at: [-1 + armCenter, -10.5, 0] }
    case 'leftHand':
      return { part: 'leftArm', at: [1 - armCenter, -10.5, 0] }
  }
}
