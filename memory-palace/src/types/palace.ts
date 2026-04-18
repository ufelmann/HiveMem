export type DrawerStatus = 'committed' | 'pending'
export type TunnelRelation = 'related_to' | 'builds_on' | 'contradicts' | 'refines'

export interface Fact {
  id: string
  subject: string
  predicate: string
  object: string
}

export interface Tunnel {
  targetId: string
  relation: TunnelRelation
  note?: string
}

export interface Drawer {
  id: string
  title: string
  wing: string
  hall: string
  room: string
  content: string // L0
  summary: string // L1
  keyPoints: string[] // L2
  insight: string // L3
  importance: number // 1-5
  status: DrawerStatus
  validFrom: string
  facts: Fact[]
  tunnels: Tunnel[]
}

export interface Room {
  name: string
  drawerCount: number
  drawers: Drawer[]
}

export interface Hall {
  name: string
  roomCount: number
  drawerCount: number
  rooms: Room[]
}

export interface Wing {
  name: string
  color: string
  hallCount: number
  drawerCount: number
  halls: Hall[]
}

export interface Palace {
  wings: Wing[]
}

export type NavigationLevel = 'building' | 'corridor' | 'room' | 'drawer'
