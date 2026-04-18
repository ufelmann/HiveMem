import * as THREE from 'three'

// Module-level singleton cache
let hexWallTexture: THREE.CanvasTexture | null = null
let stoneFloorTexture: THREE.CanvasTexture | null = null
let drawerFrontTexture: THREE.CanvasTexture | null = null

function buildDrawerFrontCanvas(): HTMLCanvasElement {
  const SIZE = 256
  const canvas = document.createElement('canvas')
  canvas.width = SIZE
  canvas.height = SIZE
  const ctx = canvas.getContext('2d')!

  // Base color: dark steel
  ctx.fillStyle = '#1c1c2e'
  ctx.fillRect(0, 0, SIZE, SIZE)

  // Subtle vertical brushed-metal streaks (20 faint lines)
  for (let i = 0; i < 20; i++) {
    const x = (i / 20) * SIZE + (Math.random() * SIZE) / 20
    const light = Math.random() > 0.5
    ctx.strokeStyle = light
      ? 'rgba(255,255,255,0.04)'
      : 'rgba(0,0,0,0.04)'
    ctx.lineWidth = 1 + Math.random() * 2
    ctx.beginPath()
    ctx.moveTo(x, 0)
    ctx.lineTo(x, SIZE)
    ctx.stroke()
  }

  // Thin horizontal groove at 30% height
  ctx.strokeStyle = 'rgba(0,191,255,0.25)'
  ctx.lineWidth = 1.5
  ctx.beginPath()
  ctx.moveTo(0, SIZE * 0.30)
  ctx.lineTo(SIZE, SIZE * 0.30)
  ctx.stroke()

  // Thin horizontal groove at 70% height
  ctx.beginPath()
  ctx.moveTo(0, SIZE * 0.70)
  ctx.lineTo(SIZE, SIZE * 0.70)
  ctx.stroke()

  // Small rectangular handle in center-bottom area (~y=0.75)
  const handleW = SIZE * 0.30
  const handleH = SIZE * 0.04
  const handleX = (SIZE - handleW) / 2
  const handleY = SIZE * 0.75
  ctx.fillStyle = '#3a3a55'
  ctx.beginPath()
  ctx.roundRect(handleX, handleY, handleW, handleH, 3)
  ctx.fill()

  return canvas
}

function buildHexWallCanvas(): HTMLCanvasElement {
  const SIZE = 512
  const canvas = document.createElement('canvas')
  canvas.width = SIZE
  canvas.height = SIZE
  const ctx = canvas.getContext('2d')!

  // Background sandstone (slightly lighter for visibility)
  ctx.fillStyle = '#5a4e3f'
  ctx.fillRect(0, 0, SIZE, SIZE)

  // Subtle vertical gradient for depth
  const grad = ctx.createLinearGradient(0, 0, 0, SIZE)
  grad.addColorStop(0, 'rgba(0,0,0,0)')
  grad.addColorStop(1, 'rgba(0,0,0,0.25)')
  ctx.fillStyle = grad
  ctx.fillRect(0, 0, SIZE, SIZE)

  // Hex grid parameters
  const R = 32          // outer radius
  const W = R * Math.sqrt(3)
  const H = R * 2
  const rowH = H * 0.75

  ctx.strokeStyle = 'rgba(0,191,255,0.55)'
  ctx.lineWidth = 2

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

  // Lighter base for visibility
  ctx.fillStyle = '#3a3a4e'
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

    ctx.fillStyle = `rgba(26,26,46,${0.6 + rand() * 0.3})`
    ctx.fill()

    ctx.strokeStyle = 'rgba(0,191,255,0.18)'
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

export function getDrawerFrontTexture(): THREE.CanvasTexture {
  if (drawerFrontTexture) return drawerFrontTexture
  drawerFrontTexture = makeCanvasTexture(buildDrawerFrontCanvas())
  return drawerFrontTexture
}

// Section A — Drawer Interior
let drawerInteriorTexture: THREE.CanvasTexture | null = null
function buildDrawerInteriorCanvas(): HTMLCanvasElement {
  const SIZE = 512
  const canvas = document.createElement('canvas')
  canvas.width = SIZE; canvas.height = SIZE
  const ctx = canvas.getContext('2d')!

  const base = ctx.createLinearGradient(0, 0, 0, SIZE)
  base.addColorStop(0, '#2a2217')
  base.addColorStop(1, '#1a1410')
  ctx.fillStyle = base
  ctx.fillRect(0, 0, SIZE, SIZE)

  ctx.fillStyle = 'rgba(10,8,5,0.55)'
  const rails = [0.15, 0.32, 0.48, 0.65, 0.82]
  for (const y of rails) {
    ctx.fillRect(0, y * SIZE - 3, SIZE, 6)
  }

  for (let i = 0; i < 400; i++) {
    const x = Math.random() * SIZE
    const y = Math.random() * SIZE
    const a = 0.02 + Math.random() * 0.03
    ctx.fillStyle = `rgba(210,180,140,${a})`
    ctx.fillRect(x, y, 1, 1 + Math.random() * 2)
  }
  return canvas
}
export function getDrawerInteriorTexture(): THREE.CanvasTexture {
  if (drawerInteriorTexture) return drawerInteriorTexture
  drawerInteriorTexture = makeCanvasTexture(buildDrawerInteriorCanvas())
  return drawerInteriorTexture
}

// Section B — Card Paper (exposed as both canvas factory + cached texture)
const cardPaperCache = new Map<string, THREE.CanvasTexture>()
function buildCardPaperCanvas(accentHex: string): HTMLCanvasElement {
  const W = 768, H = 1086
  const canvas = document.createElement('canvas')
  canvas.width = W; canvas.height = H
  const ctx = canvas.getContext('2d')!

  ctx.fillStyle = '#f4efe5'
  ctx.fillRect(0, 0, W, H)

  for (let i = 0; i < 800; i++) {
    const x = Math.random() * W
    const y = Math.random() * H
    const len = 3 + Math.random() * 5
    const ang = (Math.random() * 12 - 6) * Math.PI / 180
    const a = 0.03 + Math.random() * 0.03
    ctx.strokeStyle = `rgba(150,130,90,${a})`
    ctx.lineWidth = 0.8
    ctx.beginPath()
    ctx.moveTo(x, y)
    ctx.lineTo(x + Math.cos(ang) * len, y + Math.sin(ang) * len)
    ctx.stroke()
  }

  ctx.fillStyle = accentHex
  ctx.fillRect(0, 0, W, H * 0.08)

  const gL = ctx.createLinearGradient(0, 0, 16, 0)
  gL.addColorStop(0, 'rgba(0,191,255,0.55)'); gL.addColorStop(1, 'rgba(0,191,255,0)')
  ctx.fillStyle = gL; ctx.fillRect(0, 0, 16, H)
  const gR = ctx.createLinearGradient(W - 16, 0, W, 0)
  gR.addColorStop(0, 'rgba(0,191,255,0)'); gR.addColorStop(1, 'rgba(0,191,255,0.55)')
  ctx.fillStyle = gR; ctx.fillRect(W - 16, 0, 16, H)

  const gB = ctx.createLinearGradient(0, H - 32, 0, H)
  gB.addColorStop(0, 'rgba(0,0,0,0)'); gB.addColorStop(1, 'rgba(0,0,0,0.18)')
  ctx.fillStyle = gB; ctx.fillRect(0, H - 32, W, 32)

  return canvas
}
export function getCardPaperCanvas(accentHex: string): HTMLCanvasElement {
  return buildCardPaperCanvas(accentHex)
}
export function getCardPaperTexture(accentHex: string): THREE.CanvasTexture {
  const cached = cardPaperCache.get(accentHex)
  if (cached) return cached
  const tex = makeCanvasTexture(buildCardPaperCanvas(accentHex))
  cardPaperCache.set(accentHex, tex)
  return tex
}

// Section C — Card Back
let cardBackTexture: THREE.CanvasTexture | null = null
function buildCardBackCanvas(): HTMLCanvasElement {
  const W = 768, H = 1086
  const canvas = document.createElement('canvas')
  canvas.width = W; canvas.height = H
  const ctx = canvas.getContext('2d')!

  ctx.fillStyle = '#1a1a2e'
  ctx.fillRect(0, 0, W, H)

  const cx = W / 2, cy = H / 2, r = 140
  ctx.strokeStyle = 'rgba(0,191,255,0.08)'
  ctx.lineWidth = 2
  const drawHex = (rad: number) => {
    ctx.beginPath()
    for (let i = 0; i < 6; i++) {
      const a = i * Math.PI / 3
      const x = cx + rad * Math.cos(a)
      const y = cy + rad * Math.sin(a)
      if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y)
    }
    ctx.closePath(); ctx.stroke()
  }
  drawHex(r); drawHex(r * 0.75); drawHex(r * 0.5)
  return canvas
}
export function getCardBackTexture(): THREE.CanvasTexture {
  if (cardBackTexture) return cardBackTexture
  cardBackTexture = makeCanvasTexture(buildCardBackCanvas())
  return cardBackTexture
}

let goldParticleTex: THREE.CanvasTexture | null = null
function buildGoldParticleCanvas(): HTMLCanvasElement {
  const S = 64
  const canvas = document.createElement('canvas')
  canvas.width = S; canvas.height = S
  const ctx = canvas.getContext('2d')!
  const grad = ctx.createRadialGradient(S / 2, S / 2, 0, S / 2, S / 2, S / 2)
  grad.addColorStop(0, 'rgba(255, 220, 140, 1)')
  grad.addColorStop(0.4, 'rgba(212, 175, 55, 0.7)')
  grad.addColorStop(1, 'rgba(212, 175, 55, 0)')
  ctx.fillStyle = grad
  ctx.fillRect(0, 0, S, S)
  return canvas
}
export function getGoldParticleTexture(): THREE.CanvasTexture {
  if (goldParticleTex) return goldParticleTex
  goldParticleTex = makeCanvasTexture(buildGoldParticleCanvas())
  return goldParticleTex
}
