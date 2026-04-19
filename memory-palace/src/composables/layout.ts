import * as d3 from 'd3-force'
import type { Wing, Drawer, Tunnel } from '../api/types'

export interface Point { x: number; y: number }

export function computeWingPositions(
  wings: Wing[], drawers: Drawer[], tunnels: Tunnel[],
  viewport: { width: number; height: number }
): Map<string, Point> {
  type N = d3.SimulationNodeDatum & { id: string; size: number }
  const nodes: N[] = wings.map(w => ({ id: w.name, size: Math.log(1 + w.drawer_count) * 10 + 40 }))

  const drawerWing = new Map<string, string>()
  for (const d of drawers) drawerWing.set(d.id, d.wing)
  const wingPairCount = new Map<string, number>()
  for (const t of tunnels) {
    const a = drawerWing.get(t.from_drawer); const b = drawerWing.get(t.to_drawer)
    if (!a || !b || a === b) continue
    const k = [a, b].sort().join('|')
    wingPairCount.set(k, (wingPairCount.get(k) ?? 0) + 1)
  }
  const links = [...wingPairCount.entries()].map(([k, count]) => {
    const [source, target] = k.split('|')
    return { source, target, strength: Math.min(1, count / 10) }
  })

  const sim = d3.forceSimulation(nodes)
    .force('charge', d3.forceManyBody().strength(-200))
    .force('center', d3.forceCenter(viewport.width / 2, viewport.height / 2))
    .force('collide', d3.forceCollide<N>(n => n.size + 8))
    .force('link', d3.forceLink<N, any>(links).id((n: any) => n.id).strength((l: any) => l.strength).distance(220))
    .stop()
  for (let i = 0; i < 250; i++) sim.tick()

  const out = new Map<string, Point>()
  for (const n of nodes) out.set(n.id, { x: n.x ?? 0, y: n.y ?? 0 })
  return out
}

export function poissonDiskDrawers(
  count: number,
  spec: { x: number; y: number; r: number; minDist: number; seed: string }
): Point[] {
  const rng = seededRng(spec.seed)
  const points: Point[] = []
  const attemptsMax = count * 30
  for (let tries = 0; tries < attemptsMax && points.length < count; tries++) {
    const a = rng() * Math.PI * 2
    const d = Math.sqrt(rng()) * spec.r
    const p = { x: spec.x + Math.cos(a) * d, y: spec.y + Math.sin(a) * d }
    let ok = true
    for (const q of points) { if (Math.hypot(p.x - q.x, p.y - q.y) < spec.minDist) { ok = false; break } }
    if (ok) points.push(p)
  }
  while (points.length < count) points.push({ x: spec.x, y: spec.y })
  return points
}

function seededRng(seed: string) {
  let h = 2166136261
  for (const c of seed) h = Math.imul(h ^ c.charCodeAt(0), 16777619)
  return () => {
    h ^= h << 13; h ^= h >>> 17; h ^= h << 5
    return ((h >>> 0) % 1e9) / 1e9
  }
}
