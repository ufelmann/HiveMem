import { describe, it, expect } from 'vitest'
import { computeWingPositions, poissonDiskDrawers } from '../../src/composables/layout'
import type { Wing, Drawer, Tunnel } from '../../src/api/types'

describe('layout', () => {
  it('wing positions settle to non-overlapping centres', () => {
    const wings: Wing[] = [
      { name: 'a', drawer_count: 10, halls: [] },
      { name: 'b', drawer_count: 20, halls: [] },
      { name: 'c', drawer_count: 5, halls: [] }
    ]
    const pos = computeWingPositions(wings, [] as Drawer[], [] as Tunnel[], { width: 1000, height: 800 })
    expect(pos.size).toBe(3)
  })

  it('poissonDiskDrawers emits N points inside radius with min spacing', () => {
    const pts = poissonDiskDrawers(20, { x: 100, y: 100, r: 80, minDist: 12, seed: 'wing-a' })
    expect(pts.length).toBe(20)
    for (const p of pts) expect(Math.hypot(p.x - 100, p.y - 100)).toBeLessThanOrEqual(80)
  })
})
