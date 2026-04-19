import { Container, Graphics } from 'pixi.js'

export function spawnDust(parent: Container, count: number, bounds: { w: number; h: number }) {
  const dots: (Graphics & { vx: number; vy: number })[] = []
  for (let i = 0; i < count; i++) {
    const g = new Graphics().circle(0, 0, 0.9 + Math.random() * 1.1).fill(i % 3 === 0 ? 0xffd24d : 0x4dc4ff) as any
    g.x = Math.random() * bounds.w
    g.y = Math.random() * bounds.h
    g.alpha = 0.3 + Math.random() * 0.4
    g.vx = (Math.random() - 0.5) * 0.08
    g.vy = -0.02 - Math.random() * 0.04
    parent.addChild(g); dots.push(g)
  }
  return (deltaMs: number) => {
    for (const g of dots) {
      g.x += g.vx * deltaMs; g.y += g.vy * deltaMs
      if (g.y < -10) { g.y = bounds.h + 10; g.x = Math.random() * bounds.w }
      if (g.x < -10) g.x = bounds.w + 10
      if (g.x > bounds.w + 10) g.x = -10
    }
  }
}
