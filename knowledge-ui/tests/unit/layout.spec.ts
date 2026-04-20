import { describe, it, expect } from 'vitest'
import { computeWingPositions, poissonDiskDrawers } from '../../src/composables/layout'
import type { Realm, Cell, Tunnel } from '../../src/api/types'

describe('layout', () => {
  it('realm positions settle to non-overlapping centres', () => {
    const realms: Realm[] = [
      { name: 'a', cell_count: 10, signals: [] },
      { name: 'b', cell_count: 20, signals: [] },
      { name: 'c', cell_count: 5, signals: [] }
    ]
    const pos = computeWingPositions(realms, [] as Cell[], [] as Tunnel[], { width: 1000, height: 800 })
    expect(pos.size).toBe(3)
  })

  it('poissonDiskDrawers emits N points inside radius with min spacing', () => {
    const pts = poissonDiskDrawers(20, { x: 100, y: 100, r: 80, minDist: 12, seed: 'realm-a' })
    expect(pts.length).toBe(20)
    for (const p of pts) expect(Math.hypot(p.x - 100, p.y - 100)).toBeLessThanOrEqual(80)
  })
})
