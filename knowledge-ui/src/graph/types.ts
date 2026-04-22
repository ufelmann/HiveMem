export interface GraphNode {
  id: string
  label: string
  realm: string
  signal: string | null
  topic: string | null
  importance: number
  val: number
  color: string
}

export interface GraphLink {
  id: string
  source: string
  target: string
  relation: string
  color: string
}
