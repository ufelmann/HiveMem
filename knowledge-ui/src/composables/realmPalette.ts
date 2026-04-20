export interface RealmPalette {
  glow: string
  base: string
  tint: string
  hueDeg: number
}

function hexToHsl(hex: string): { h: number; s: number; l: number } {
  const c = hex.replace('#', '')
  const n = parseInt(c.length === 3 ? c.split('').map((x) => x + x).join('') : c, 16)
  const r = ((n >> 16) & 255) / 255
  const g = ((n >> 8) & 255) / 255
  const b = (n & 255) / 255
  const max = Math.max(r, g, b), min = Math.min(r, g, b)
  const l = (max + min) / 2
  let h = 0, s = 0
  if (max !== min) {
    const d = max - min
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
    switch (max) {
      case r: h = ((g - b) / d + (g < b ? 6 : 0)); break
      case g: h = (b - r) / d + 2; break
      case b: h = (r - g) / d + 4; break
    }
    h *= 60
  }
  return { h, s, l }
}

function hsl(h: number, s: number, l: number): string {
  const clampedH = ((h % 360) + 360) % 360
  const clampedS = Math.max(0, Math.min(1, s))
  const clampedL = Math.max(0, Math.min(1, l))
  return `hsl(${clampedH.toFixed(1)}, ${(clampedS * 100).toFixed(1)}%, ${(clampedL * 100).toFixed(1)}%)`
}

function hslToHex(h: number, s: number, l: number): string {
  const a = s * Math.min(l, 1 - l)
  const f = (n: number) => {
    const k = (n + h / 30) % 12
    const colour = l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))
    return Math.round(colour * 255).toString(16).padStart(2, '0')
  }
  return `#${f(0)}${f(8)}${f(4)}`
}

export function paletteForRealm(index: number, declaredColor?: string): RealmPalette {
  const hex = declaredColor ?? (() => {
    const h = (200 + index * 137.5) % 360
    return hslToHex(h, 0.65, 0.55)
  })()
  const { h, s, l } = hexToHsl(hex)
  return {
    glow: hsl(h, Math.min(1, s + 0.1), Math.min(0.95, l + 0.2)),
    base: hex,
    tint: hsl(h, Math.max(0, s - 0.2), Math.max(0.05, l - 0.3)),
    hueDeg: h,
  }
}
