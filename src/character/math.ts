// Just enough 4×4 matrix math for the character: column-major, like WebGL expects.

export type Mat4 = Float32Array

export function mat4(): Mat4 {
  const m = new Float32Array(16)
  m[0] = m[5] = m[10] = m[15] = 1
  return m
}

const scratch = new Float32Array(16)

/** out = a · b */
export function multiply(out: Mat4, a: Mat4, b: Mat4): Mat4 {
  for (let c = 0; c < 4; c++) {
    const b0 = b[c * 4]
    const b1 = b[c * 4 + 1]
    const b2 = b[c * 4 + 2]
    const b3 = b[c * 4 + 3]
    scratch[c * 4] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3
    scratch[c * 4 + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3
    scratch[c * 4 + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3
    scratch[c * 4 + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3
  }
  out.set(scratch)
  return out
}

/** m = m · translation */
export function translate(m: Mat4, x: number, y: number, z: number): Mat4 {
  m[12] += m[0] * x + m[4] * y + m[8] * z
  m[13] += m[1] * x + m[5] * y + m[9] * z
  m[14] += m[2] * x + m[6] * y + m[10] * z
  m[15] += m[3] * x + m[7] * y + m[11] * z
  return m
}

/** m = m · rotation about x (positive turns +y toward +z). */
export function rotateX(m: Mat4, angle: number): Mat4 {
  if (angle === 0) return m
  const s = Math.sin(angle)
  const c = Math.cos(angle)
  for (let i = 0; i < 4; i++) {
    const y = m[4 + i]
    const z = m[8 + i]
    m[4 + i] = y * c + z * s
    m[8 + i] = z * c - y * s
  }
  return m
}

/** m = m · rotation about y (positive turns +z toward +x). */
export function rotateY(m: Mat4, angle: number): Mat4 {
  if (angle === 0) return m
  const s = Math.sin(angle)
  const c = Math.cos(angle)
  for (let i = 0; i < 4; i++) {
    const x = m[i]
    const z = m[8 + i]
    m[i] = x * c - z * s
    m[8 + i] = x * s + z * c
  }
  return m
}

/** m = m · rotation about z (positive turns +x toward +y). */
export function rotateZ(m: Mat4, angle: number): Mat4 {
  if (angle === 0) return m
  const s = Math.sin(angle)
  const c = Math.cos(angle)
  for (let i = 0; i < 4; i++) {
    const x = m[i]
    const y = m[4 + i]
    m[i] = x * c + y * s
    m[4 + i] = y * c - x * s
  }
  return m
}

/** m = m · scale */
export function scale(m: Mat4, x: number, y: number, z: number): Mat4 {
  for (let i = 0; i < 4; i++) {
    m[i] *= x
    m[4 + i] *= y
    m[8 + i] *= z
  }
  return m
}

export function perspective(out: Mat4, fovY: number, aspect: number, near: number, far: number): Mat4 {
  const f = 1 / Math.tan(fovY / 2)
  const nf = 1 / (near - far)
  out.fill(0)
  out[0] = f / aspect
  out[5] = f
  out[10] = (far + near) * nf
  out[11] = -1
  out[14] = 2 * far * near * nf
  return out
}

/** A camera at `eye` looking at `target`, with +y up. */
export function lookAt(out: Mat4, eye: readonly number[], target: readonly number[]): Mat4 {
  let zx = eye[0] - target[0]
  let zy = eye[1] - target[1]
  let zz = eye[2] - target[2]
  let len = 1 / Math.hypot(zx, zy, zz)
  zx *= len
  zy *= len
  zz *= len
  // x = up × z, with up = (0, 1, 0)
  let xx = zz
  let xz = -zx
  len = 1 / Math.hypot(xx, xz)
  xx *= len
  xz *= len
  const yx = zy * xz
  const yy = zz * xx - zx * xz
  const yz = -zy * xx
  out[0] = xx
  out[1] = yx
  out[2] = zx
  out[3] = 0
  out[4] = 0
  out[5] = yy
  out[6] = zy
  out[7] = 0
  out[8] = xz
  out[9] = yz
  out[10] = zz
  out[11] = 0
  out[12] = -(xx * eye[0] + xz * eye[2])
  out[13] = -(yx * eye[0] + yy * eye[1] + yz * eye[2])
  out[14] = -(zx * eye[0] + zy * eye[1] + zz * eye[2])
  out[15] = 1
  return out
}

/**
 * The matrix for normals: the inverse transpose of m's rotation part, up to a
 * positive factor (the shader normalizes anyway).
 */
export function normalMatrix(out: Float32Array, m: Mat4): Float32Array {
  const m00 = m[0]
  const m10 = m[1]
  const m20 = m[2]
  const m01 = m[4]
  const m11 = m[5]
  const m21 = m[6]
  const m02 = m[8]
  const m12 = m[9]
  const m22 = m[10]
  // Cofactor matrix, stored column-major.
  out[0] = m11 * m22 - m12 * m21
  out[1] = -(m01 * m22 - m02 * m21)
  out[2] = m01 * m12 - m02 * m11
  out[3] = -(m10 * m22 - m12 * m20)
  out[4] = m00 * m22 - m02 * m20
  out[5] = -(m00 * m12 - m02 * m10)
  out[6] = m10 * m21 - m11 * m20
  out[7] = -(m00 * m21 - m01 * m20)
  out[8] = m00 * m11 - m01 * m10
  return out
}

/** Where a point ends up after m (an affine transform). */
export function transformPoint(m: Mat4, x: number, y: number, z: number): [number, number, number] {
  return [
    m[0] * x + m[4] * y + m[8] * z + m[12],
    m[1] * x + m[5] * y + m[9] * z + m[13],
    m[2] * x + m[6] * y + m[10] * z + m[14]
  ]
}
