import { describe, it, expect } from 'vitest'
import { mapCanvasToForceGraph } from '../../src/graph/mapCanvasToForceGraph'

describe('mapCanvasToForceGraph', () => {
  it('maps cells and tunnels into node/link structures', () => {
    const result = mapCanvasToForceGraph({
      cells: [
        { id: 'a', title: 'A', realm: 'Systems', signal: 'Consensus', topic: 'Architecture', importance: 3 },
        { id: 'b', title: 'B', realm: 'Systems', importance: 0 },
        { id: 'c', title: 'C', realm: 'Systems', signal: undefined, topic: undefined, importance: 2 }
      ] as any,
      tunnels: [
        { id: 't1', from_cell: 'a', to_cell: 'b', relation: 'builds_on' },
        { id: 't2', from_cell: 'a', to_cell: 'missing', relation: 'contradicts' }
      ] as any
    })

    expect(result.nodes).toHaveLength(3)
    expect(result.nodes).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: 'a', signal: 'Consensus', topic: 'Architecture', val: 3 }),
      expect.objectContaining({ id: 'b', signal: null, topic: null, val: 1 }),
      expect.objectContaining({ id: 'c', signal: null, topic: null, val: 2 })
    ]))
    expect(result.links).toEqual([
      expect.objectContaining({ source: 'a', target: 'b', relation: 'builds_on' })
    ])
  })
})
