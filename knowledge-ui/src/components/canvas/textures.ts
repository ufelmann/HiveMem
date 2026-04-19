import { Texture } from 'pixi.js'

const cache = new Map<string, Texture>()

export function colorForWing(name: string): string {
  let h = 0; for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360
  return `hsl(${h}, 70%, 55%)`
}

export function wingTexture(colorHsl: string, size = 256): Texture {
  const key = `${colorHsl}:${size}`
  const cached = cache.get(key); if (cached) return cached
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = size
  const ctx = canvas.getContext('2d')!
  const g = ctx.createRadialGradient(size/2, size/2, 0, size/2, size/2, size/2)
  g.addColorStop(0, colorHsl.replace('hsl', 'hsla').replace(')', ', 0.9)'))
  g.addColorStop(0.5, colorHsl.replace('hsl', 'hsla').replace(')', ', 0.5)'))
  g.addColorStop(1, colorHsl.replace('hsl', 'hsla').replace(')', ', 0)'))
  ctx.fillStyle = g; ctx.fillRect(0, 0, size, size)
  const img = ctx.getImageData(0, 0, size, size)
  for (let i = 0; i < img.data.length; i += 4) {
    const n = (Math.random() - 0.5) * 18
    img.data[i] += n; img.data[i+1] += n; img.data[i+2] += n
  }
  ctx.putImageData(img, 0, 0)
  const tex = Texture.from(canvas)
  cache.set(key, tex); return tex
}

export function drawerTexture(size = 64): Texture {
  const key = `drawer:${size}`
  const cached = cache.get(key); if (cached) return cached
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = size
  const ctx = canvas.getContext('2d')!
  const g = ctx.createRadialGradient(size/2, size/2, 0, size/2, size/2, size/2)
  g.addColorStop(0, 'rgba(255,255,255,1)')
  g.addColorStop(0.6, 'rgba(255,255,255,0.5)')
  g.addColorStop(1, 'rgba(255,255,255,0)')
  ctx.fillStyle = g; ctx.fillRect(0, 0, size, size)
  const tex = Texture.from(canvas)
  cache.set(key, tex); return tex
}

export function parseHsl(hsl: string): number {
  const m = /hsl\((\d+), *(\d+)%, *(\d+)%\)/.exec(hsl); if (!m) return 0xffffff
  const h = +m[1] / 360, s = +m[2] / 100, l = +m[3] / 100
  const a = s * Math.min(l, 1-l)
  const f = (n: number) => { const k = (n + h*12) % 12; return l - a * Math.max(-1, Math.min(k-3, 9-k, 1)) }
  return Math.round(f(0)*255)*65536 + Math.round(f(8)*255)*256 + Math.round(f(4)*255)
}
