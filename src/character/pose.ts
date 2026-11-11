/** The character's moving parts, parents before children. */
export const PART_NAMES = ['root', 'torso', 'head', 'rightArm', 'leftArm', 'rightLeg', 'leftLeg'] as const
export type PartName = (typeof PART_NAMES)[number]

/**
 * How far one part is turned (radians) and moved (skin pixels) from where it
 * normally sits. The character faces +z (toward you), +y is up, and its right
 * side is -x (your left when it faces you).
 *
 * rx: arms and legs swing back when positive (forward when negative); the head
 * nods down and the torso bends forward. ry: turns toward the character's left.
 * rz: tilts the top toward -x, so the right arm lifts out with a negative value
 * and the left arm with a positive one.
 */
export interface Joint {
  rx: number
  ry: number
  rz: number
  x: number
  y: number
  z: number
}

export type Pose = Record<PartName, Joint> & {
  /** Squashes the whole character (0.1 is 10% shorter and a bit wider). */
  squash: number
}

export function createPose(): Pose {
  const pose = { squash: 0 } as Pose
  for (const name of PART_NAMES) pose[name] = { rx: 0, ry: 0, rz: 0, x: 0, y: 0, z: 0 }
  return pose
}

export function resetPose(pose: Pose): void {
  pose.squash = 0
  for (const name of PART_NAMES) {
    const j = pose[name]
    j.rx = j.ry = j.rz = j.x = j.y = j.z = 0
  }
}
