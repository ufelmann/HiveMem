import type { Cell, Tunnel } from '../api/types'
import type { GraphLink, GraphNode } from './types'
import { colorForRelation, colorForRealm } from './colors'

export function mapCanvasToForceGraph(input: { cells: Cell[]; tunnels: Tunnel[] }) {
  const nodes: GraphNode[] = input.cells.map(cell => ({
    id: cell.id,
    label: cell.title,
    realm: cell.realm,
    signal: cell.signal ?? null,
    topic: cell.topic ?? null,
    importance: cell.importance,
    val: Math.max(cell.importance, 1),
    color: colorForRealm(cell.realm)
  }))

  const nodeIds = new Set(nodes.map(node => node.id))
  const links: GraphLink[] = input.tunnels
    .filter(tunnel => nodeIds.has(tunnel.from_cell) && nodeIds.has(tunnel.to_cell))
    .map(tunnel => ({
      id: tunnel.id,
      source: tunnel.from_cell,
      target: tunnel.to_cell,
      relation: tunnel.relation,
      color: colorForRelation(tunnel.relation)
    }))

  return { nodes, links }
}
