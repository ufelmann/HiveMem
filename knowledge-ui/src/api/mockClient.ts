import { palace as mockPalace } from '../data/mock'
import type { ApiClient, HiveEvent, Drawer, Wing, Hall, Tunnel, Fact, StatusSummary, Reference } from './types'

interface MockConfig { latencyMs?: [number, number]; eventInterval?: number }

type Handler = (args: any) => unknown

export class MockApiClient implements ApiClient {
  private config: Required<MockConfig>
  private subscribers = new Set<(e: HiveEvent) => void>()
  private timer: number | null = null
  private handlers: Record<string, Handler>

  constructor(config: MockConfig = {}) {
    this.config = { latencyMs: [50, 200], eventInterval: 15000, ...config }
    this.handlers = {
      hivemem_status: () => this.status(),
      hivemem_wake_up: () => this.wakeUp(),
      hivemem_list_wings: (args: { wing?: string }) => this.listWings(args),
      hivemem_search: (args: { query?: string; limit?: number }) => this.search(args),
      hivemem_get_drawer: (args: { id: string }) => this.getDrawer(args),
      hivemem_quick_facts: (args: { subject: string }) => this.quickFacts(args),
      hivemem_traverse: (args: { drawer_id: string; depth?: number }) => this.traverse(args),
      hivemem_list_tunnels: () => mockPalace.tunnels,
      hivemem_reading_list: () => mockPalace.references ?? [],
      hivemem_pending_approvals: () => [],
      hivemem_list_agents: () => [],
      hivemem_diary_read: () => [],
      hivemem_get_blueprint: () => null,
      hivemem_time_machine: () => mockPalace.facts,
      hivemem_search_kg: () => mockPalace.facts,
      hivemem_history: () => [],
    }
  }

  async call<T>(tool: string, args: Record<string, unknown> = {}): Promise<T> {
    await this.delay()
    const fn = this.handlers[tool]
    if (!fn) throw new Error(`Mock not implemented: ${tool}`)
    return fn(args) as T
  }

  subscribe(onEvent: (e: HiveEvent) => void): () => void {
    this.subscribers.add(onEvent)
    if (!this.timer) this.startTicker()
    return () => {
      this.subscribers.delete(onEvent)
      if (this.subscribers.size === 0 && this.timer) {
        clearInterval(this.timer); this.timer = null
      }
    }
  }

  private startTicker() {
    this.timer = setInterval(() => {
      const existing = mockPalace.drawers[Math.floor(Math.random() * mockPalace.drawers.length)]
      const ev: HiveEvent = { type: 'drawer_added', drawer: existing }
      this.subscribers.forEach(s => s(ev))
    }, this.config.eventInterval) as unknown as number
  }

  private delay() {
    const [a, b] = this.config.latencyMs
    return new Promise(r => setTimeout(r, a + Math.random() * (b - a)))
  }

  private status(): StatusSummary {
    return {
      drawer_count: mockPalace.drawers.length,
      fact_count: mockPalace.facts.length,
      wing_count: mockPalace.wings.length,
      tunnel_count: mockPalace.tunnels.length,
      pending_count: 0,
      last_activity: new Date().toISOString(),
    }
  }

  private wakeUp() {
    return { role: 'admin', identity: 'mock-user', wings: mockPalace.wings.map(w => w.name) }
  }

  private listWings(args: { wing?: string }): Wing[] | Hall[] {
    if (args.wing) return mockPalace.wings.find(w => w.name === args.wing)?.halls ?? []
    return mockPalace.wings
  }

  private search(args: { query?: string; limit?: number }): Drawer[] {
    const q = (args.query || '').toLowerCase()
    const all = q
      ? mockPalace.drawers.filter(d => d.title.toLowerCase().includes(q) || d.content.toLowerCase().includes(q))
      : mockPalace.drawers
    return all.slice(0, args.limit ?? 100)
  }

  private getDrawer(args: { id: string }): Drawer {
    const d = mockPalace.drawers.find(x => x.id === args.id)
    if (!d) throw new Error(`Drawer not found: ${args.id}`)
    return d
  }

  private quickFacts(args: { subject: string }): Fact[] {
    return mockPalace.facts.filter(f => f.subject === args.subject)
  }

  private traverse(args: { drawer_id: string; depth?: number }): Tunnel[] {
    const depth = args.depth ?? 1
    const seen = new Set<string>([args.drawer_id])
    const frontier = [args.drawer_id]
    const result: Tunnel[] = []
    for (let d = 0; d < depth; d++) {
      const next: string[] = []
      for (const id of frontier) {
        for (const t of mockPalace.tunnels) {
          if (t.from_drawer === id && !seen.has(t.to_drawer)) { seen.add(t.to_drawer); next.push(t.to_drawer); result.push(t) }
          if (t.to_drawer === id && !seen.has(t.from_drawer)) { seen.add(t.from_drawer); next.push(t.from_drawer); result.push(t) }
        }
      }
      frontier.splice(0, frontier.length, ...next)
    }
    return result
  }
}

// Silence unused-import warnings for Reference — retained for when references are populated
export type { Reference }
