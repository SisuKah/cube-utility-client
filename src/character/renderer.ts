import { mat4, normalMatrix, rotateY, scale, translate, type Mat4 } from './math'
import { buildPlayer, createMatrices, FLOATS_PER_VERTEX, poseMatrices, type PartMatrices, type PlayerGeometry } from './model'
import type { Pose } from './pose'

const PLAYER_VERTEX = `
attribute vec3 aPosition;
attribute vec3 aNormal;
attribute vec2 aUv;
uniform mat4 uViewProjection;
uniform mat4 uModel;
uniform mat3 uNormal;
varying vec2 vUv;
varying vec3 vNormal;
void main() {
  vUv = aUv;
  vNormal = uNormal * aNormal;
  gl_Position = uViewProjection * uModel * vec4(aPosition, 1.0);
}`

// A warm key light from the upper left and a teal fill from the right, like the stage behind it.
const PLAYER_FRAGMENT = `
precision mediump float;
uniform sampler2D uSkin;
uniform vec3 uKey;
uniform vec3 uFill;
varying vec2 vUv;
varying vec3 vNormal;
void main() {
  vec4 color = texture2D(uSkin, vUv);
  if (color.a < 0.1) discard;
  vec3 n = normalize(vNormal);
  if (!gl_FrontFacing) n = -n;
  float key = max(dot(n, uKey), 0.0);
  float fill = max(dot(n, uFill), 0.0);
  vec3 light = vec3(0.52) + vec3(1.0, 0.96, 0.88) * (0.56 * key) + vec3(0.55, 0.9, 0.86) * (0.3 * fill);
  gl_FragColor = vec4(color.rgb * light * color.a, color.a);
}`

const SHADOW_VERTEX = `
attribute vec2 aCorner;
uniform mat4 uViewProjection;
uniform mat4 uModel;
varying vec2 vCorner;
void main() {
  vCorner = aCorner;
  gl_Position = uViewProjection * uModel * vec4(aCorner.x, 0.0, aCorner.y, 1.0);
}`

const SHADOW_FRAGMENT = `
precision mediump float;
uniform float uStrength;
varying vec2 vCorner;
void main() {
  float a = (1.0 - smoothstep(0.0, 1.0, length(vCorner))) * uStrength;
  gl_FragColor = vec4(0.0, 0.0, 0.0, a);
}`

function normalize(v: readonly [number, number, number]): Float32Array {
  const len = Math.hypot(v[0], v[1], v[2])
  return new Float32Array([v[0] / len, v[1] / len, v[2] / len])
}

const KEY_LIGHT = normalize([-0.5, 0.8, 0.65])
const FILL_LIGHT = normalize([0.85, 0.2, 0.45])

function compile(gl: WebGLRenderingContext, type: number, source: string): WebGLShader {
  const shader = gl.createShader(type)
  if (!shader) throw new Error('Could not create a shader')
  gl.shaderSource(shader, source)
  gl.compileShader(shader)
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS) && !gl.isContextLost()) {
    throw new Error(`Shader didn't compile: ${gl.getShaderInfoLog(shader)}`)
  }
  return shader
}

function link(gl: WebGLRenderingContext, vertex: string, fragment: string, attributes: string[]): WebGLProgram {
  const program = gl.createProgram()
  if (!program) throw new Error('Could not create a shader program')
  const vs = compile(gl, gl.VERTEX_SHADER, vertex)
  const fs = compile(gl, gl.FRAGMENT_SHADER, fragment)
  gl.attachShader(program, vs)
  gl.attachShader(program, fs)
  attributes.forEach((name, i) => gl.bindAttribLocation(program, i, name))
  gl.linkProgram(program)
  gl.deleteShader(vs)
  gl.deleteShader(fs)
  if (!gl.getProgramParameter(program, gl.LINK_STATUS) && !gl.isContextLost()) {
    throw new Error(`Shader program didn't link: ${gl.getProgramInfoLog(program)}`)
  }
  return program
}

function locate(gl: WebGLRenderingContext, player: WebGLProgram, shadow: WebGLProgram) {
  return {
    viewProjection: gl.getUniformLocation(player, 'uViewProjection'),
    model: gl.getUniformLocation(player, 'uModel'),
    normal: gl.getUniformLocation(player, 'uNormal'),
    skin: gl.getUniformLocation(player, 'uSkin'),
    key: gl.getUniformLocation(player, 'uKey'),
    fill: gl.getUniformLocation(player, 'uFill'),
    shadowViewProjection: gl.getUniformLocation(shadow, 'uViewProjection'),
    shadowModel: gl.getUniformLocation(shadow, 'uModel'),
    shadowStrength: gl.getUniformLocation(shadow, 'uStrength')
  }
}

interface Mesh {
  geometry: PlayerGeometry
  vertices: WebGLBuffer | null
  indices: WebGLBuffer | null
}

/** Draws the player model and its floor shadow with WebGL. */
export class CharacterRenderer {
  private readonly playerProgram: WebGLProgram
  private readonly shadowProgram: WebGLProgram
  private readonly u: ReturnType<typeof locate>
  private readonly shadowCorners: WebGLBuffer | null
  private readonly meshes: { classic?: Mesh; slim?: Mesh } = {}
  private texture: WebGLTexture | null = null
  private readonly normal = new Float32Array(9)
  private readonly shadowModel = mat4()
  /** World matrices of every part, as last drawn. */
  readonly matrices: PartMatrices = createMatrices()

  constructor(private readonly gl: WebGLRenderingContext) {
    this.playerProgram = link(gl, PLAYER_VERTEX, PLAYER_FRAGMENT, ['aPosition', 'aNormal', 'aUv'])
    this.shadowProgram = link(gl, SHADOW_VERTEX, SHADOW_FRAGMENT, ['aCorner'])
    this.u = locate(gl, this.playerProgram, this.shadowProgram)
    this.shadowCorners = gl.createBuffer()
    gl.bindBuffer(gl.ARRAY_BUFFER, this.shadowCorners)
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW)
  }

  /** The skin, already converted to Minecraft's 64×64 layout. */
  setSkin(pixels: ImageData): void {
    const gl = this.gl
    this.texture ??= gl.createTexture()
    gl.bindTexture(gl.TEXTURE_2D, this.texture)
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false)
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false)
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, pixels)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
  }

  private mesh(slim: boolean): Mesh {
    const key = slim ? 'slim' : 'classic'
    const existing = this.meshes[key]
    if (existing) return existing
    const gl = this.gl
    const geometry = buildPlayer(slim)
    const vertices = gl.createBuffer()
    gl.bindBuffer(gl.ARRAY_BUFFER, vertices)
    gl.bufferData(gl.ARRAY_BUFFER, geometry.vertices, gl.STATIC_DRAW)
    const indices = gl.createBuffer()
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, indices)
    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, geometry.indices, gl.STATIC_DRAW)
    const mesh = { geometry, vertices, indices }
    this.meshes[key] = mesh
    return mesh
  }

  render(pose: Pose, slim: boolean, viewProjection: Mat4, width: number, height: number): void {
    const gl = this.gl
    gl.viewport(0, 0, width, height)
    gl.clearColor(0, 0, 0, 0)
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
    if (!this.texture) return
    const mesh = this.mesh(slim)
    const m = poseMatrices(pose, mesh.geometry.parts, this.matrices)

    // The floor shadow: smaller and fainter the higher the character jumps.
    const lift = Math.max(0, pose.root.y)
    const near = Math.max(0, 1 - lift / 20)
    const s = this.shadowModel
    s.fill(0)
    s[0] = s[5] = s[10] = s[15] = 1
    translate(s, pose.root.x, 0.02, pose.root.z)
    rotateY(s, pose.root.ry)
    scale(s, 12 * (0.7 + 0.3 * near), 1, 7 * (0.7 + 0.3 * near))
    gl.useProgram(this.shadowProgram)
    gl.disable(gl.DEPTH_TEST)
    gl.depthMask(false)
    gl.disable(gl.CULL_FACE)
    gl.enable(gl.BLEND)
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA)
    gl.uniformMatrix4fv(this.u.shadowViewProjection, false, viewProjection)
    gl.uniformMatrix4fv(this.u.shadowModel, false, s)
    gl.uniform1f(this.u.shadowStrength, 0.8 * (0.35 + 0.65 * near))
    gl.bindBuffer(gl.ARRAY_BUFFER, this.shadowCorners)
    gl.enableVertexAttribArray(0)
    gl.disableVertexAttribArray(1)
    gl.disableVertexAttribArray(2)
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0)
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)

    // The character: the opaque inner layer, then the outer layer with its see-through pixels.
    gl.useProgram(this.playerProgram)
    gl.enable(gl.DEPTH_TEST)
    gl.depthFunc(gl.LEQUAL)
    gl.depthMask(true)
    gl.uniformMatrix4fv(this.u.viewProjection, false, viewProjection)
    gl.uniform3fv(this.u.key, KEY_LIGHT)
    gl.uniform3fv(this.u.fill, FILL_LIGHT)
    gl.activeTexture(gl.TEXTURE0)
    gl.bindTexture(gl.TEXTURE_2D, this.texture)
    gl.uniform1i(this.u.skin, 0)
    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.vertices)
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, mesh.indices)
    const stride = FLOATS_PER_VERTEX * 4
    gl.enableVertexAttribArray(0)
    gl.enableVertexAttribArray(1)
    gl.enableVertexAttribArray(2)
    gl.vertexAttribPointer(0, 3, gl.FLOAT, false, stride, 0)
    gl.vertexAttribPointer(1, 3, gl.FLOAT, false, stride, 12)
    gl.vertexAttribPointer(2, 2, gl.FLOAT, false, stride, 24)
    let outer: boolean | null = null
    for (const item of mesh.geometry.items) {
      if (item.outer !== outer) {
        outer = item.outer
        if (outer) {
          gl.disable(gl.CULL_FACE)
          gl.enable(gl.BLEND)
        } else {
          gl.enable(gl.CULL_FACE)
          gl.cullFace(gl.BACK)
          gl.disable(gl.BLEND)
        }
      }
      const model = m[item.part]
      gl.uniformMatrix4fv(this.u.model, false, model)
      gl.uniformMatrix3fv(this.u.normal, false, normalMatrix(this.normal, model))
      gl.drawElements(gl.TRIANGLES, item.count, gl.UNSIGNED_SHORT, item.first * 2)
    }
  }

  dispose(): void {
    const gl = this.gl
    for (const mesh of [this.meshes.classic, this.meshes.slim]) {
      if (!mesh) continue
      gl.deleteBuffer(mesh.vertices)
      gl.deleteBuffer(mesh.indices)
    }
    gl.deleteBuffer(this.shadowCorners)
    gl.deleteTexture(this.texture)
    gl.deleteProgram(this.playerProgram)
    gl.deleteProgram(this.shadowProgram)
  }
}
