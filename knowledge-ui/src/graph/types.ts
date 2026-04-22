import type { Cell, Tunnel } from '../api/types'

export interface GraphNode {
  id: Cell['id']
  label: Cell['title']
  realm: Cell['realm']
  signal: Cell['signal']
  topic: Cell['topic']
  importance: Cell['importance']
  val: number
  color: string
}

export interface GraphLink {
  id: Tunnel['id']
  source: Tunnel['from_cell']
  target: Tunnel['to_cell']
  relation: Tunnel['relation']
  color: string
}
