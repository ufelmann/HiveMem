import * as THREE from 'three'

// Module-level singleton cache
let hexWallTexture: THREE.CanvasTexture | null = null
let stoneFloorTexture: THREE.CanvasTexture | null = null

function buildHexWallCanvas(): HTMLCanvasElement {
  const SIZE = 512
  const canvas = document.createElement('canvas')
  canvas.width = SIZE
  canvas.height = SIZE
  const ctx = canvas.getContext('2d')!

  // Background sandstone
  ctx.fillStyle = '#2a2520'
  ctx.fillRect(0, 0, SIZE, SIZE)

  // Hex grid parameters
  const R = 24          // outer radius of each hex
  const W = R * Math.sqrt(3)  // width of a flat-top hex
  const H = R * 2             // height of a flat-top hex
  const rowH = H * 0.75       // vertical step between row centres

  ctx.strokeStyle = 'rgba(0,191,255,0.12)'
  ctx.lineWidth = 1

  // Draw enough rows/cols to cover the canvas + a bit over for tileability
  const cols = Math.ceil(SIZE / W) + 2
  const rows = Math.ceil(SIZE / rowH) + 2

  for (let row = -1; row < rows; row++) {
    for (let col = -1; col < cols; col++) {
      const cx = col * W + (row % 2 === 0 ? 0 : W / 2)
      const cy = row * rowH
      drawFlatHex(ctx, cx, cy, R)
    }
  }

  return canvas
}

function drawFlatHex(ctx: CanvasRenderingContext2D, cx: number, cy: number, r: number) {
  ctx.beginPath()
  for (let i = 0; i < 6; i++) {
    // flat-top orientation: first vertex at 0° (right)
    const angle = (Math.PI / 3) * i
    const x = cx + r * Math.cos(angle)
    const y = cy + r * Math.sin(angle)
    if (i === 0) ctx.moveTo(x, y)
    else ctx.lineTo(x, y)
  }
  ctx.closePath()
  ctx.stroke()
}

function buildStoneFloorCanvas(): HTMLCanvasElement {
  const SIZE = 512
  const canvas = document.createElement('canvas')
  canvas.width = SIZE
  canvas.height = SIZE
  const ctx = canvas.getContext('2d')!

  // Dark base
  ctx.fillStyle = '#14141e'
  ctx.fillRect(0, 0, SIZE, SIZE)

  // Seed a deterministic PRNG so the pattern is consistent
  let seed = 42
  function rand(): number {
    seed = (seed * 1664525 + 1013904223) & 0xffffffff
    return (seed >>> 0) / 0xffffffff
  }

  // Build ~36 irregular polygonal cells by scattering sites and
  // drawing Voronoi-ish convex hulls manually — simplified: use
  // irregular polygons at scattered positions
  const CELL_COUNT = 35
  for (let c = 0; c < CELL_COUNT; c++) {
    const px = rand() * SIZE
    const py = rand() * SIZE
    const sides = 5 + Math.floor(rand() * 3)   // 5-7 sides
    const baseR = 22 + rand() * 30             // radius 22-52px

    ctx.beginPath()
    for (let i = 0; i < sides; i++) {
      const angle = (Math.PI * 2 * i) / sides + rand() * 0.4
      const r = baseR * (0.7 + rand() * 0.6)
      const x = px + r * Math.cos(angle)
      const y = py + r * Math.sin(angle)
      if (i === 0) ctx.moveTo(x, y)
      else ctx.lineTo(x, y)
    }
    ctx.closePath()

    ctx.fillStyle = '#1a1a2e'
    ctx.fill()

    ctx.strokeStyle = 'rgba(0,191,255,0.05)'
    ctx.lineWidth = 1
    ctx.stroke()
  }

  return canvas
}

function makeCanvasTexture(canvas: HTMLCanvasElement): THREE.CanvasTexture {
  const tex = new THREE.CanvasTexture(canvas)
  tex.wrapS = THREE.RepeatWrapping
  tex.wrapT = THREE.RepeatWrapping
  tex.anisotropy = 4
  tex.needsUpdate = true
  return tex
}

export function getHexagonWallTexture(): THREE.CanvasTexture {
  if (hexWallTexture) return hexWallTexture
  hexWallTexture = makeCanvasTexture(buildHexWallCanvas())
  return hexWallTexture
}

export function getStoneFloorTexture(): THREE.CanvasTexture {
  if (stoneFloorTexture) return stoneFloorTexture
  stoneFloorTexture = makeCanvasTexture(buildStoneFloorCanvas())
  return stoneFloorTexture
}

/**
 * Return a new texture instance sharing the same canvas but with its own
 * repeat vector. Useful when the same pattern needs different tiling on
 * different surfaces without mutating the shared singleton.
 */
export function makeHexWallTextureWithRepeat(repeatU: number, repeatV: number): THREE.CanvasTexture {
  const base = getHexagonWallTexture()
  const tex = makeCanvasTexture(base.image as HTMLCanvasElement)
  tex.repeat.set(repeatU, repeatV)
  return tex
}

export function makeStoneFloorTextureWithRepeat(repeatU: number, repeatV: number): THREE.CanvasTexture {
  const base = getStoneFloorTexture()
  const tex = makeCanvasTexture(base.image as HTMLCanvasElement)
  tex.repeat.set(repeatU, repeatV)
  return tex
}
