export type Role = 'admin' | 'writer' | 'reader' | 'agent'
export type Relation = 'related_to' | 'builds_on' | 'contradicts' | 'refines'
export type DrawerStatus = 'committed' | 'pending' | 'rejected'

export interface Drawer {
  id: string
  wing: string
  hall: string | null
  room: string | null
  title: string
  content: string
  summary: string | null
  key_points: string[]
  insight: string | null
  tags: string[]
  importance: 1 | 2 | 3
  status: DrawerStatus
  created_by: string
  created_at: string
  valid_from: string
  valid_until: string | null
}

export interface Wing { name: string; drawer_count: number; halls: Hall[] }
export interface Hall { name: string; drawer_count: number; rooms: Room[] }
export interface Room { name: string; drawer_count: number }

export interface Tunnel {
  id: string
  from_drawer: string
  to_drawer: string
  relation: Relation
  note: string | null
  status: DrawerStatus
  created_at: string
  valid_until: string | null
}

export interface Fact {
  id: string
  subject: string
  predicate: string
  object: string
  valid_from: string
  valid_until: string | null
}

export interface Reference {
  id: string
  title: string
  url: string | null
  ref_type: 'article' | 'paper' | 'book' | 'attachment' | 'other'
  status: 'unread' | 'reading' | 'done'
}

export interface StatusSummary {
  drawer_count: number
  fact_count: number
  wing_count: number
  tunnel_count: number
  pending_count: number
  last_activity: string
}

export type HiveEvent =
  | { type: 'drawer_added'; drawer: Drawer }
  | { type: 'drawer_revised'; id: string; parent_id: string }
  | { type: 'tunnel_added'; tunnel: Tunnel }
  | { type: 'status'; last_activity: string }

export interface ApiClient {
  call<T>(tool: string, args?: Record<string, unknown>): Promise<T>
  subscribe(onEvent: (e: HiveEvent) => void): () => void
}
