import { describe, it, expect } from 'vitest'
import { mapCanvasToForceGraph } from '../../src/graph/mapCanvasToForceGraph'

describe('mapCanvasToForceGraph', () => {
  it('maps cells and tunnels into node/link structures', () => {
    const result = mapCanvasToForceGraph({
      cells: [
        { id: 'a', title: 'A', realm: 'Systems', signal: 'Consensus', importance: 3 },
        { id: 'b', title: 'B', realm: 'Systems', signal: 'Consensus', importance: 1 }
      ] as any,
      tunnels: [
        { id: 't1', from_cell: 'a', to_cell: 'b', relation: 'builds_on' }
      ] as any
    })

    expect(result.nodes).toHaveLength(2)
    expect(result.links).toEqual([
      expect.objectContaining({ source: 'a', target: 'b', relation: 'builds_on' })
    ])
  })
})
