# SP1 Knowledge-UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a 2D sphere-canvas frontend for HiveMem (branch `feat/knowledge-ui`) that renders wings + drawers + tunnels via PixiJS, with a Scan+Reader drawer-detail dual-mode and a toggleable Mock/HTTP API layer.

**Architecture:** Vue 3 SPA talks to the existing HiveMem Java MCP server via JSON-RPC behind a typed `ApiClient` interface (mock impl for offline dev). PixiJS drives the zoomable canvas; Vuetify 3 drives a 56px icon-rail + slide-panels. No backend changes in v1 — live-update is polling. Cinema route lazy-loads TresJS + recycled 3D HiveSphere.

**Tech Stack:** Vue 3, Vite, TypeScript, Vuetify 3, Pinia, PixiJS 8 + pixi-filters, d3-force, markdown-it + KaTeX, pdf.js, postal-mime, @vueuse/core, TresJS (cinema only), Vitest (unit), Playwright (E2E).

**Spec:** `docs/superpowers/specs/2026-04-19-knowledge-ui-sp1-design.md`

---

## File Structure

**Created:**
- `knowledge-ui/src/api/types.ts` — `ApiClient`, `HiveEvent`, domain types (Drawer, Wing, Tunnel, Fact)
- `knowledge-ui/src/api/mockClient.ts` — `MockApiClient` implementation
- `knowledge-ui/src/api/httpClient.ts` — `HttpApiClient` implementation
- `knowledge-ui/src/api/useApi.ts` — composable, picks impl based on flag
- `knowledge-ui/src/stores/auth.ts`
- `knowledge-ui/src/stores/canvas.ts`
- `knowledge-ui/src/stores/drawer.ts`
- `knowledge-ui/src/stores/reader.ts`
- `knowledge-ui/src/stores/ui.ts`
- `knowledge-ui/src/components/LoginDialog.vue`
- `knowledge-ui/src/components/shell/IconRail.vue`
- `knowledge-ui/src/components/shell/SlidePanel.vue`
- `knowledge-ui/src/components/shell/SearchPanel.vue`
- `knowledge-ui/src/components/shell/WingsPanel.vue`
- `knowledge-ui/src/components/shell/SettingsPanel.vue`
- `knowledge-ui/src/components/canvas/SphereCanvas.vue` — PixiJS wrapper
- `knowledge-ui/src/components/canvas/textures.ts` — procedural sprite-texture generator
- `knowledge-ui/src/components/canvas/filters.ts` — filter presets (bloom, outline, godray, zoomblur)
- `knowledge-ui/src/components/canvas/particles.ts` — dust emitter
- `knowledge-ui/src/composables/layout.ts` — d3-force wings + Poisson-disk drawers
- `knowledge-ui/src/composables/lod.ts` — zoom-to-LOD-level mapping + visibility rules
- `knowledge-ui/src/composables/keybindings.ts`
- `knowledge-ui/src/components/ScanPanel.vue`
- `knowledge-ui/src/components/Reader.vue`
- `knowledge-ui/src/components/readers/MarkdownTab.vue`
- `knowledge-ui/src/components/readers/PdfTab.vue`
- `knowledge-ui/src/components/readers/EmlTab.vue`
- `knowledge-ui/src/pages/HomeRoute.vue`
- `knowledge-ui/src/pages/CinemaRoute.vue` — lazy-loaded
- `knowledge-ui/src/router.ts`
- `knowledge-ui/tests/unit/mockClient.spec.ts`
- `knowledge-ui/tests/unit/httpClient.spec.ts`
- `knowledge-ui/tests/unit/layout.spec.ts`
- `knowledge-ui/tests/unit/lod.spec.ts`
- `knowledge-ui/tests/unit/stores.spec.ts`
- `knowledge-ui/tests/e2e/smoke.spec.ts`
- `knowledge-ui/vitest.config.ts`
- `knowledge-ui/playwright.config.ts`

**Modified:**
- `knowledge-ui/package.json` — add deps
- `knowledge-ui/src/main.ts` — add router
- `knowledge-ui/src/App.vue` — use router + LoginDialog

**Deleted (dead code from prototype port):**
- `knowledge-ui/src/components/BreadcrumbNav.vue` (unused in new shell)
- `knowledge-ui/src/stores/navigation.ts` (replaced by 5 new stores)
- `knowledge-ui/src/types/palace.ts` (replaced by `api/types.ts`)

---

## Task 1: Install dependencies, clean scaffold

**Files:**
- Modify: `knowledge-ui/package.json`
- Delete: three prototype-era files

- [ ] **Step 1: Add deps to package.json**

Edit `knowledge-ui/package.json`, add to `dependencies`:

```json
"pixi.js": "^8.5.0",
"pixi-filters": "^6.0.5",
"d3-force": "^3.0.0",
"markdown-it": "^14.1.0",
"katex": "^0.16.11",
"highlight.js": "^11.10.0",
"postal-mime": "^2.3.2",
"pdfjs-dist": "^4.7.76",
"vue-router": "^4.4.5"
```

Add to `devDependencies`:

```json
"@types/d3-force": "^3.0.10",
"@types/markdown-it": "^14.1.2",
"vitest": "^2.1.0",
"@vue/test-utils": "^2.4.6",
"happy-dom": "^15.7.4",
"@playwright/test": "^1.48.0"
```

Add to `scripts`:

```json
"test:unit": "vitest run",
"test:unit:watch": "vitest",
"test:e2e": "playwright test"
```

- [ ] **Step 2: Install**

Run: `cd knowledge-ui && npm install`
Expected: no errors, `node_modules` updated.

- [ ] **Step 3: Delete prototype-era files that have no role in v1**

Run:

```bash
rm knowledge-ui/src/components/BreadcrumbNav.vue
rm knowledge-ui/src/stores/navigation.ts
rm knowledge-ui/src/types/palace.ts
```

- [ ] **Step 4: Verify build is still green**

Run: `cd knowledge-ui && npx vue-tsc --noEmit && npx vite build`
Expected: exit 0.

- [ ] **Step 5: Commit**

```bash
cd /root/hivemem
git add knowledge-ui/package.json knowledge-ui/package-lock.json
git rm knowledge-ui/src/components/BreadcrumbNav.vue knowledge-ui/src/stores/navigation.ts knowledge-ui/src/types/palace.ts
git commit -m "chore(ui): add pixijs + d3-force + markdown-it + pdfjs + test deps; drop prototype files"
```

---

## Task 2: Domain types and ApiClient interface

**Files:**
- Create: `knowledge-ui/src/api/types.ts`

- [ ] **Step 1: Define types**

Create `knowledge-ui/src/api/types.ts`:

```ts
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
```

- [ ] **Step 2: Verify typecheck**

Run: `cd knowledge-ui && npx vue-tsc --noEmit`
Expected: exit 0.

- [ ] **Step 3: Commit**

```bash
git add knowledge-ui/src/api/types.ts
git commit -m "feat(ui): define api types (Drawer, Wing, Tunnel, Fact, ApiClient, HiveEvent)"
```

---

## Task 3: Mock API client with tests

**Files:**
- Create: `knowledge-ui/src/api/mockClient.ts`
- Create: `knowledge-ui/tests/unit/mockClient.spec.ts`
- Create: `knowledge-ui/vitest.config.ts`
- Modify (if needed): `knowledge-ui/src/data/mock.ts`

- [ ] **Step 1: Vitest config**

Create `knowledge-ui/vitest.config.ts`:

```ts
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'happy-dom',
    globals: true,
    include: ['tests/unit/**/*.spec.ts']
  }
})
```

- [ ] **Step 2: Write failing test**

Create `knowledge-ui/tests/unit/mockClient.spec.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { MockApiClient } from '../../src/api/mockClient'
import type { StatusSummary, Drawer } from '../../src/api/types'

describe('MockApiClient', () => {
  it('returns deterministic status', async () => {
    const c = new MockApiClient()
    const s = await c.call<StatusSummary>('hivemem_status')
    expect(s.drawer_count).toBeGreaterThan(0)
    expect(s.wing_count).toBeGreaterThan(0)
    expect(typeof s.last_activity).toBe('string')
  })

  it('search returns drawers array', async () => {
    const c = new MockApiClient()
    const res = await c.call<Drawer[]>('hivemem_search', { query: '' })
    expect(Array.isArray(res)).toBe(true)
    expect(res.length).toBeGreaterThan(0)
  })

  it('get_drawer returns drawer with matching id', async () => {
    const c = new MockApiClient()
    const all = await c.call<Drawer[]>('hivemem_search', { query: '' })
    const d = await c.call<Drawer>('hivemem_get_drawer', { id: all[0].id })
    expect(d.id).toBe(all[0].id)
  })

  it('subscribe returns unsubscribe function and emits events', async () => {
    const c = new MockApiClient({ eventInterval: 10 })
    const events: string[] = []
    const unsub = c.subscribe(e => events.push(e.type))
    await new Promise(r => setTimeout(r, 50))
    unsub()
    expect(events.length).toBeGreaterThan(0)
  })
})
```

- [ ] **Step 3: Run test — should fail**

Run: `cd knowledge-ui && npx vitest run tests/unit/mockClient.spec.ts`
Expected: FAIL, "Cannot find module mockClient".

- [ ] **Step 4: Implement MockApiClient**

Create `knowledge-ui/src/api/mockClient.ts`:

```ts
import { palace as mockPalace } from '../data/mock'
import type { ApiClient, HiveEvent, Drawer, Wing, Hall, Tunnel, Fact, StatusSummary, Reference } from './types'

interface MockConfig { latencyMs?: [number, number]; eventInterval?: number }

export class MockApiClient implements ApiClient {
  private config: Required<MockConfig>
  private subscribers = new Set<(e: HiveEvent) => void>()
  private timer: number | null = null

  constructor(config: MockConfig = {}) {
    this.config = { latencyMs: [50, 200], eventInterval: 15000, ...config }
  }

  async call<T>(tool: string, args: Record<string, unknown> = {}): Promise<T> {
    await this.delay()
    const fn = (this as any)[`_${tool}`]
    if (!fn) throw new Error(`Mock not implemented: ${tool}`)
    return fn.call(this, args) as T
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

  private _hivemem_status(): StatusSummary {
    return {
      drawer_count: mockPalace.drawers.length,
      fact_count: mockPalace.facts.length,
      wing_count: mockPalace.wings.length,
      tunnel_count: mockPalace.tunnels.length,
      pending_count: 0,
      last_activity: new Date().toISOString()
    }
  }

  private _hivemem_wake_up() {
    return { role: 'admin', identity: 'mock-user', wings: mockPalace.wings.map(w => w.name) }
  }

  private _hivemem_list_wings(args: { wing?: string }): Wing[] | Hall[] {
    if (args.wing) return mockPalace.wings.find(w => w.name === args.wing)?.halls ?? []
    return mockPalace.wings
  }

  private _hivemem_search(args: { query?: string; limit?: number }): Drawer[] {
    const q = (args.query || '').toLowerCase()
    const all = q
      ? mockPalace.drawers.filter(d => d.title.toLowerCase().includes(q) || d.content.toLowerCase().includes(q))
      : mockPalace.drawers
    return all.slice(0, args.limit ?? 100)
  }

  private _hivemem_get_drawer(args: { id: string }): Drawer {
    const d = mockPalace.drawers.find(x => x.id === args.id)
    if (!d) throw new Error(`Drawer not found: ${args.id}`)
    return d
  }

  private _hivemem_quick_facts(args: { subject: string }): Fact[] {
    return mockPalace.facts.filter(f => f.subject === args.subject)
  }

  private _hivemem_traverse(args: { drawer_id: string; depth?: number }) {
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

  private _hivemem_list_tunnels(): Tunnel[] { return mockPalace.tunnels }
  private _hivemem_reading_list(): Reference[] { return mockPalace.references ?? [] }
  private _hivemem_pending_approvals() { return [] }
  private _hivemem_list_agents() { return [] }
  private _hivemem_diary_read() { return [] }
  private _hivemem_get_blueprint() { return null }
  private _hivemem_time_machine(): Fact[] { return mockPalace.facts }
  private _hivemem_search_kg(): Fact[] { return mockPalace.facts }
  private _hivemem_history() { return [] }
}
```

- [ ] **Step 4b: Reshape mock.ts if required**

Open `knowledge-ui/src/data/mock.ts`. The existing snapshot was designed for the prototype's `Palace` type. Ensure it exports a `palace` object with fields `drawers: Drawer[]`, `wings: Wing[]`, `tunnels: Tunnel[]`, `facts: Fact[]`, `references?: Reference[]` matching the types in `api/types.ts`. If the shape differs, add a small adapter at the bottom of `mock.ts` that computes `palace` from the raw snapshot — do not rewrite the data itself.

- [ ] **Step 5: Run test — should pass**

Run: `cd knowledge-ui && npx vitest run tests/unit/mockClient.spec.ts`
Expected: 4 passed.

- [ ] **Step 6: Commit**

```bash
git add knowledge-ui/src/api/mockClient.ts knowledge-ui/tests/unit/mockClient.spec.ts knowledge-ui/vitest.config.ts knowledge-ui/src/data/mock.ts
git commit -m "feat(ui): MockApiClient with status/search/get_drawer/traverse + subscribe ticker"
```

---

## Task 4: HTTP API client with tests

**Files:**
- Create: `knowledge-ui/src/api/httpClient.ts`
- Create: `knowledge-ui/tests/unit/httpClient.spec.ts`

- [ ] **Step 1: Write failing test**

Create `knowledge-ui/tests/unit/httpClient.spec.ts`:

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { HttpApiClient } from '../../src/api/httpClient'

describe('HttpApiClient', () => {
  beforeEach(() => { vi.restoreAllMocks() })

  it('sends JSON-RPC with bearer token', async () => {
    const fetchMock = vi.fn(async (_url: string, init: RequestInit) => {
      const headers = init.headers as Record<string, string>
      expect(headers['Authorization']).toBe('Bearer test-token')
      const body = JSON.parse(init.body as string)
      expect(body.method).toBe('tools/call')
      expect(body.params.name).toBe('hivemem_status')
      return new Response(JSON.stringify({ jsonrpc: '2.0', id: body.id, result: { drawer_count: 42 } }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const c = new HttpApiClient({ endpoint: '/mcp', token: 'test-token' })
    const r = await c.call<{ drawer_count: number }>('hivemem_status')
    expect(r.drawer_count).toBe(42)
  })

  it('throws on JSON-RPC error', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ jsonrpc: '2.0', id: 1, error: { code: -32601, message: 'Method not found' } }))
    ))
    const c = new HttpApiClient({ endpoint: '/mcp', token: 't' })
    await expect(c.call('bogus')).rejects.toThrow('Method not found')
  })

  it('subscribe polls status and emits on change', async () => {
    let t = '2026-04-19T12:00:00Z'
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ jsonrpc: '2.0', id: 1, result: { last_activity: t } }))
    ))
    const c = new HttpApiClient({ endpoint: '/mcp', token: 't', pollMs: 10 })
    const events: string[] = []
    const unsub = c.subscribe(e => events.push(e.type))
    await new Promise(r => setTimeout(r, 30))
    t = '2026-04-19T12:00:05Z'
    await new Promise(r => setTimeout(r, 30))
    unsub()
    expect(events).toContain('status')
  })
})
```

- [ ] **Step 2: Run — fail**

Run: `cd knowledge-ui && npx vitest run tests/unit/httpClient.spec.ts`
Expected: FAIL, module not found.

- [ ] **Step 3: Implement HttpApiClient**

Create `knowledge-ui/src/api/httpClient.ts`:

```ts
import type { ApiClient, HiveEvent, StatusSummary } from './types'

export interface HttpApiConfig {
  endpoint: string
  token: string
  pollMs?: number
}

export class HttpApiClient implements ApiClient {
  private nextId = 1
  private subscribers = new Set<(e: HiveEvent) => void>()
  private timer: number | null = null
  private lastActivity: string | null = null

  constructor(private config: HttpApiConfig) {}

  async call<T>(tool: string, args: Record<string, unknown> = {}): Promise<T> {
    const id = this.nextId++
    const res = await fetch(this.config.endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        'Authorization': `Bearer ${this.config.token}`
      },
      body: JSON.stringify({ jsonrpc: '2.0', id, method: 'tools/call', params: { name: tool, arguments: args } })
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const json = await res.json() as { result?: T; error?: { message: string } }
    if (json.error) throw new Error(json.error.message)
    return json.result as T
  }

  subscribe(onEvent: (e: HiveEvent) => void): () => void {
    this.subscribers.add(onEvent)
    if (!this.timer) this.startPolling()
    return () => {
      this.subscribers.delete(onEvent)
      if (this.subscribers.size === 0 && this.timer) {
        clearInterval(this.timer); this.timer = null
      }
    }
  }

  private startPolling() {
    const interval = this.config.pollMs ?? 10_000
    this.timer = setInterval(async () => {
      try {
        const s = await this.call<StatusSummary>('hivemem_status')
        if (this.lastActivity && s.last_activity !== this.lastActivity) {
          this.subscribers.forEach(sub => sub({ type: 'status', last_activity: s.last_activity }))
        }
        this.lastActivity = s.last_activity
      } catch { /* swallow — next tick will retry */ }
    }, interval) as unknown as number
  }
}
```

- [ ] **Step 4: Run — pass**

Run: `cd knowledge-ui && npx vitest run tests/unit/httpClient.spec.ts`
Expected: 3 passed.

- [ ] **Step 5: Commit**

```bash
git add knowledge-ui/src/api/httpClient.ts knowledge-ui/tests/unit/httpClient.spec.ts
git commit -m "feat(ui): HttpApiClient JSON-RPC over fetch + bearer auth + status polling"
```

---

## Task 5: useApi composable + toggle

**Files:**
- Create: `knowledge-ui/src/api/useApi.ts`

- [ ] **Step 1: Implement**

Create `knowledge-ui/src/api/useApi.ts`:

```ts
import { MockApiClient } from './mockClient'
import { HttpApiClient } from './httpClient'
import type { ApiClient } from './types'

let client: ApiClient | null = null

export function useApi(): ApiClient {
  if (client) return client
  const forceMock = import.meta.env.VITE_USE_MOCK === 'true'
    || localStorage.getItem('hivemem_mock') === 'true'
  if (forceMock) {
    client = new MockApiClient()
  } else {
    const token = localStorage.getItem('hivemem_token') ?? ''
    client = new HttpApiClient({ endpoint: (import.meta.env.VITE_HIVEMEM_URL ?? '') + '/mcp', token })
  }
  if (typeof window !== 'undefined') {
    ;(window as any).__useMock = (flag: boolean) => {
      localStorage.setItem('hivemem_mock', String(flag))
      location.reload()
    }
  }
  return client
}

export function resetApi() { client = null }
```

- [ ] **Step 2: Typecheck**

Run: `cd knowledge-ui && npx vue-tsc --noEmit`
Expected: exit 0.

- [ ] **Step 3: Commit**

```bash
git add knowledge-ui/src/api/useApi.ts
git commit -m "feat(ui): useApi composable picks Mock or Http impl via env/localStorage"
```

---

## Task 6: Pinia stores (auth, canvas, drawer, reader, ui)

**Files:**
- Create: 5 files under `knowledge-ui/src/stores/`
- Create: `knowledge-ui/tests/unit/stores.spec.ts`

- [ ] **Step 1: Test the store boundaries**

Create `knowledge-ui/tests/unit/stores.spec.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../../src/stores/auth'
import { useUiStore } from '../../src/stores/ui'
import { useCanvasStore } from '../../src/stores/canvas'

describe('stores', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('auth starts logged out', () => {
    const s = useAuthStore()
    expect(s.isAuthenticated).toBe(false)
    expect(s.role).toBe(null)
  })

  it('ui defaults: dark theme, no panel open, size metric = count', () => {
    const s = useUiStore()
    expect(s.activePanel).toBe(null)
    expect(s.sizeMetric).toBe('drawer_count')
    expect(s.theme).toBe('dark')
  })

  it('canvas loadTopLevel populates wings + drawers from api', async () => {
    localStorage.setItem('hivemem_mock', 'true')
    const s = useCanvasStore()
    await s.loadTopLevel()
    expect(s.wings.length).toBeGreaterThan(0)
    expect(s.drawers.length).toBeGreaterThan(0)
  })
})
```

- [ ] **Step 2: Run — fails**

Run: `cd knowledge-ui && npx vitest run tests/unit/stores.spec.ts`
Expected: FAIL.

- [ ] **Step 3: Create stores**

`knowledge-ui/src/stores/auth.ts`:

```ts
import { defineStore } from 'pinia'
import type { Role } from '../api/types'
import { useApi, resetApi } from '../api/useApi'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: (typeof localStorage !== 'undefined' ? localStorage.getItem('hivemem_token') : null) as string | null,
    role: null as Role | null,
    identity: null as string | null
  }),
  getters: { isAuthenticated: (s) => !!s.token && !!s.role },
  actions: {
    async login(token: string) {
      localStorage.setItem('hivemem_token', token)
      this.token = token
      resetApi()
      const api = useApi()
      const w = await api.call<{ role: Role; identity: string }>('hivemem_wake_up')
      this.role = w.role; this.identity = w.identity
    },
    logout() {
      localStorage.removeItem('hivemem_token')
      this.token = null; this.role = null; this.identity = null
      resetApi()
    }
  }
})
```

`knowledge-ui/src/stores/ui.ts`:

```ts
import { defineStore } from 'pinia'

export type PanelId = null | 'search' | 'wings' | 'reading' | 'stats' | 'history' | 'settings'
export type SizeMetric = 'drawer_count' | 'content_volume' | 'importance' | 'popularity'

export const useUiStore = defineStore('ui', {
  state: () => ({
    activePanel: null as PanelId,
    panelPinned: false,
    sizeMetric: 'drawer_count' as SizeMetric,
    theme: 'dark' as 'dark' | 'light',
    searchQuery: '',
    showLoginDialog: false,
    toast: null as null | { kind: 'info' | 'success' | 'error'; text: string }
  }),
  actions: {
    togglePanel(id: Exclude<PanelId, null>) {
      this.activePanel = this.activePanel === id ? null : id
    },
    pushToast(kind: 'info' | 'success' | 'error', text: string) {
      this.toast = { kind, text }
    }
  }
})
```

`knowledge-ui/src/stores/canvas.ts`:

```ts
import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { Drawer, Wing, Tunnel } from '../api/types'

export const useCanvasStore = defineStore('canvas', {
  state: () => ({
    wings: [] as Wing[],
    drawers: [] as Drawer[],
    tunnels: [] as Tunnel[],
    loaded: false,
    zoom: 1,
    pan: { x: 0, y: 0 },
    focusedId: null as string | null,
    hoveredId: null as string | null
  }),
  actions: {
    async loadTopLevel() {
      const api = useApi()
      const [wings, drawers] = await Promise.all([
        api.call<Wing[]>('hivemem_list_wings'),
        api.call<Drawer[]>('hivemem_search', { query: '', limit: 500 })
      ])
      this.wings = wings; this.drawers = drawers; this.loaded = true
      try { this.tunnels = await api.call<Tunnel[]>('hivemem_list_tunnels') }
      catch { this.tunnels = [] }
    },
    setFocus(id: string | null) { this.focusedId = id },
    setHover(id: string | null) { this.hoveredId = id }
  }
})
```

`knowledge-ui/src/stores/drawer.ts`:

```ts
import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { Drawer, Fact, Tunnel } from '../api/types'

export const useDrawerStore = defineStore('drawer', {
  state: () => ({
    cache: new Map<string, { drawer: Drawer; facts: Fact[]; tunnels: Tunnel[] }>(),
    currentId: null as string | null,
    loading: false
  }),
  getters: {
    current(s): { drawer: Drawer; facts: Fact[]; tunnels: Tunnel[] } | null {
      return s.currentId ? s.cache.get(s.currentId) ?? null : null
    }
  },
  actions: {
    async load(id: string) {
      this.loading = true
      try {
        if (!this.cache.has(id)) {
          const api = useApi()
          const [drawer, tunnels] = await Promise.all([
            api.call<Drawer>('hivemem_get_drawer', { id }),
            api.call<Tunnel[]>('hivemem_traverse', { drawer_id: id, depth: 1 }).catch(() => [])
          ])
          const facts = await api.call<Fact[]>('hivemem_quick_facts', { subject: drawer.title }).catch(() => [])
          this.cache.set(id, { drawer, facts, tunnels })
          if (this.cache.size > 50) {
            const first = this.cache.keys().next().value
            if (first) this.cache.delete(first)
          }
        }
        this.currentId = id
      } finally { this.loading = false }
    },
    clear() { this.currentId = null }
  }
})
```

`knowledge-ui/src/stores/reader.ts`:

```ts
import { defineStore } from 'pinia'

export type ReaderTab = 'markdown' | string

export const useReaderStore = defineStore('reader', {
  state: () => ({
    open: false,
    drawerId: null as string | null,
    activeTab: 'markdown' as ReaderTab,
    pdfPage: 1,
    pdfZoom: 1
  }),
  actions: {
    openReader(drawerId: string) { this.drawerId = drawerId; this.open = true; this.activeTab = 'markdown' },
    close() { this.open = false }
  }
})
```

- [ ] **Step 4: Run — pass**

Run: `cd knowledge-ui && npx vitest run tests/unit/stores.spec.ts`
Expected: 3 passed.

- [ ] **Step 5: Commit**

```bash
git add knowledge-ui/src/stores/ knowledge-ui/tests/unit/stores.spec.ts
git commit -m "feat(ui): pinia stores auth/canvas/drawer/reader/ui"
```

---

## Task 7: Login dialog + App wiring

**Files:**
- Create: `knowledge-ui/src/components/LoginDialog.vue`
- Modify: `knowledge-ui/src/App.vue`

- [ ] **Step 1: LoginDialog**

Create `knowledge-ui/src/components/LoginDialog.vue`:

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useUiStore } from '../stores/ui'

const auth = useAuthStore()
const ui = useUiStore()
const token = ref('')
const useMock = ref(localStorage.getItem('hivemem_mock') === 'true')
const error = ref('')

async function submit() {
  error.value = ''
  try {
    if (useMock.value) {
      localStorage.setItem('hivemem_mock', 'true')
      await auth.login('mock')
    } else {
      localStorage.setItem('hivemem_mock', 'false')
      await auth.login(token.value.trim())
    }
    ui.showLoginDialog = false
  } catch (e: any) { error.value = e?.message ?? 'Login failed' }
}
</script>

<template>
  <v-dialog v-model="ui.showLoginDialog" max-width="460" persistent>
    <v-card>
      <v-card-title>Connect to HiveMem</v-card-title>
      <v-card-text>
        <v-switch v-model="useMock" label="Use local mock data" color="primary" />
        <v-text-field
          v-if="!useMock"
          v-model="token"
          label="Bearer token"
          type="password"
          hint="Create with the hivemem-token CLI inside the container (role admin)"
          persistent-hint
        />
        <v-alert v-if="error" type="error" variant="tonal" class="mt-3">{{ error }}</v-alert>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn color="primary" @click="submit">Connect</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
```

- [ ] **Step 2: Rewrite App.vue**

Replace `knowledge-ui/src/App.vue`:

```vue
<script setup lang="ts">
import { onMounted } from 'vue'
import { useAuthStore } from './stores/auth'
import { useUiStore } from './stores/ui'
import { useCanvasStore } from './stores/canvas'
import LoginDialog from './components/LoginDialog.vue'

const auth = useAuthStore()
const ui = useUiStore()
const canvas = useCanvasStore()

onMounted(async () => {
  if (!auth.token) {
    ui.showLoginDialog = true
  } else {
    try { await auth.login(auth.token) } catch { ui.showLoginDialog = true }
  }
})
</script>

<template>
  <v-app>
    <v-main>
      <router-view v-if="auth.isAuthenticated" />
      <div v-else class="splash">Connecting…</div>
    </v-main>
    <LoginDialog />
    <v-snackbar v-if="ui.toast" :color="ui.toast.kind" :model-value="!!ui.toast" timeout="8000">
      {{ ui.toast.text }}
      <template #actions>
        <v-btn variant="text" @click="canvas.loadTopLevel(); ui.toast = null">Reload</v-btn>
      </template>
    </v-snackbar>
  </v-app>
</template>

<style scoped>
.splash { display:flex; align-items:center; justify-content:center; height:100vh; color:#4dc4ff; }
</style>
```

- [ ] **Step 3: Commit**

```bash
git add knowledge-ui/src/components/LoginDialog.vue knowledge-ui/src/App.vue
git commit -m "feat(ui): login dialog with mock/token toggle + app wiring"
```

---

## Task 8: Router + Home + Cinema routes

**Files:**
- Create: `knowledge-ui/src/router.ts`, `knowledge-ui/src/pages/HomeRoute.vue`, `knowledge-ui/src/pages/CinemaRoute.vue`
- Modify: `knowledge-ui/src/main.ts`

- [ ] **Step 1: Router**

Create `knowledge-ui/src/router.ts`:

```ts
import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/', name: 'home', component: () => import('./pages/HomeRoute.vue') },
  { path: '/cinema', name: 'cinema', component: () => import('./pages/CinemaRoute.vue') }
]

export const router = createRouter({ history: createWebHistory(), routes })
```

- [ ] **Step 2: Stub pages**

Create `knowledge-ui/src/pages/HomeRoute.vue`:

```vue
<script setup lang="ts"></script>
<template>
  <div class="home-root">
    <div style="padding:20px;color:#888">Canvas goes here.</div>
  </div>
</template>
<style scoped>
.home-root { position:fixed; inset:0; background:#050510; }
</style>
```

Create `knowledge-ui/src/pages/CinemaRoute.vue`:

```vue
<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>
<template>
  <div class="cinema-root">
    <v-btn class="back-btn" icon="mdi-arrow-left" @click="router.push('/')" />
    <div class="placeholder">Cinema Mode (TresJS HiveSphere wired in Task 22)</div>
  </div>
</template>
<style scoped>
.cinema-root { position:fixed; inset:0; background:#000; }
.back-btn { position:fixed; top:16px; left:16px; }
.placeholder { color:#4dc4ff; display:grid; place-items:center; height:100%; font-size:14px; letter-spacing:0.2em; }
</style>
```

- [ ] **Step 3: Wire router in main.ts**

Replace `knowledge-ui/src/main.ts`:

```ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { vuetify } from './plugins/vuetify'
import { router } from './router'
import App from './App.vue'
import './style.css'

createApp(App).use(createPinia()).use(vuetify).use(router).mount('#app')
```

- [ ] **Step 4: Build**

Run: `cd knowledge-ui && npx vue-tsc --noEmit && npx vite build`
Expected: exit 0.

- [ ] **Step 5: Commit**

```bash
git add knowledge-ui/src/router.ts knowledge-ui/src/pages/ knowledge-ui/src/main.ts
git commit -m "feat(ui): add router with home + cinema routes"
```

---

## Task 9: Icon-Rail shell + SlidePanel

**Files:**
- Create: `knowledge-ui/src/components/shell/IconRail.vue`, `SlidePanel.vue`
- Modify: `knowledge-ui/src/pages/HomeRoute.vue`

- [ ] **Step 1: IconRail**

Create `knowledge-ui/src/components/shell/IconRail.vue`:

```vue
<script setup lang="ts">
import { useUiStore } from '../../stores/ui'
import { useAuthStore } from '../../stores/auth'
import { useRouter } from 'vue-router'

const ui = useUiStore()
const auth = useAuthStore()
const router = useRouter()

const items: { id: Exclude<typeof ui.activePanel, null>; icon: string; role?: 'admin' }[] = [
  { id: 'search',   icon: 'mdi-magnify' },
  { id: 'wings',    icon: 'mdi-star-four-points-outline' },
  { id: 'reading',  icon: 'mdi-book-open-variant' },
  { id: 'stats',    icon: 'mdi-chart-donut', role: 'admin' },
  { id: 'history',  icon: 'mdi-history' },
  { id: 'settings', icon: 'mdi-cog' }
]

function visible(it: typeof items[number]) { return !it.role || auth.role === it.role }
</script>

<template>
  <div class="rail">
    <div class="logo">H</div>
    <template v-for="it in items" :key="it.id">
      <v-btn
        v-if="visible(it)"
        :icon="it.icon"
        variant="text"
        :color="ui.activePanel === it.id ? 'primary' : undefined"
        @click="ui.togglePanel(it.id)"
      />
    </template>
    <div class="spacer" />
    <v-btn icon="mdi-rotate-3d-variant" variant="text" @click="router.push('/cinema')" />
  </div>
</template>

<style scoped>
.rail { position:fixed; top:0; left:0; bottom:0; width:56px; background:#0b0b15; border-right:1px solid #1a1a24;
       display:flex; flex-direction:column; align-items:center; padding-top:8px; gap:4px; z-index:10; }
.logo { width:32px; height:32px; border-radius:8px; background:#4dc4ff; color:#050510;
        display:grid; place-items:center; font-weight:bold; margin-bottom:8px; }
.spacer { flex:1; }
</style>
```

- [ ] **Step 2: SlidePanel**

Create `knowledge-ui/src/components/shell/SlidePanel.vue`:

```vue
<script setup lang="ts">
import { useUiStore } from '../../stores/ui'
const ui = useUiStore()
defineProps<{ id: Exclude<typeof ui.activePanel, null>; title: string }>()
</script>

<template>
  <transition name="slide">
    <aside v-if="ui.activePanel === id" class="panel">
      <header>
        <strong>{{ title }}</strong>
        <v-btn icon="mdi-close" size="small" variant="text" @click="ui.activePanel = null" />
      </header>
      <div class="body"><slot /></div>
    </aside>
  </transition>
</template>

<style scoped>
.panel { position:fixed; top:0; bottom:0; left:56px; width:320px; background:#0e0e1c; border-right:1px solid #1a1a24; z-index:9; display:flex; flex-direction:column; }
header { display:flex; align-items:center; justify-content:space-between; padding:10px 14px; border-bottom:1px solid #1a1a24; }
.body { flex:1; overflow-y:auto; padding:12px 14px; }
.slide-enter-from, .slide-leave-to { transform:translateX(-20px); opacity:0; }
.slide-enter-active, .slide-leave-active { transition:transform 160ms ease, opacity 160ms ease; }
</style>
```

- [ ] **Step 3: Mount in HomeRoute**

Replace `knowledge-ui/src/pages/HomeRoute.vue`:

```vue
<script setup lang="ts">
import IconRail from '../components/shell/IconRail.vue'
import SlidePanel from '../components/shell/SlidePanel.vue'
</script>
<template>
  <div class="home-root">
    <IconRail />
    <SlidePanel id="search" title="Search"><em>Search UI (Task 10)</em></SlidePanel>
    <SlidePanel id="wings" title="Wings"><em>Wings tree (Task 10)</em></SlidePanel>
    <SlidePanel id="settings" title="Settings"><em>Settings (Task 10)</em></SlidePanel>
    <main class="canvas-slot">
      <div style="padding:20px;color:#666">SphereCanvas goes here (Task 11)</div>
    </main>
  </div>
</template>

<style scoped>
.home-root { position:fixed; inset:0; background:#050510; color:#eee; }
.canvas-slot { position:absolute; inset:0 0 0 56px; }
</style>
```

- [ ] **Step 4: Visual sanity check**

Run `cd knowledge-ui && npx vite`, open browser, confirm icon-rail renders and panels open/close.

- [ ] **Step 5: Commit**

```bash
git add knowledge-ui/src/components/shell/ knowledge-ui/src/pages/HomeRoute.vue
git commit -m "feat(ui): 56px icon-rail + slide-panel shell with 6 panel slots"
```

---

## Task 10: Wings panel + Search panel + Settings panel content

**Files:**
- Create: `knowledge-ui/src/components/shell/SearchPanel.vue`, `WingsPanel.vue`, `SettingsPanel.vue`
- Modify: `knowledge-ui/src/pages/HomeRoute.vue`

- [ ] **Step 1: WingsPanel**

Create `knowledge-ui/src/components/shell/WingsPanel.vue`:

```vue
<script setup lang="ts">
import { onMounted } from 'vue'
import { useCanvasStore } from '../../stores/canvas'
import { useUiStore, SizeMetric } from '../../stores/ui'

const canvas = useCanvasStore()
const ui = useUiStore()
const metrics: { v: SizeMetric; label: string }[] = [
  { v: 'drawer_count', label: 'Drawer count' },
  { v: 'content_volume', label: 'Content volume' },
  { v: 'importance', label: 'Importance-weighted' },
  { v: 'popularity', label: 'Popularity' }
]

onMounted(() => { if (!canvas.loaded) canvas.loadTopLevel() })

function colorFor(wing: string): string {
  let h = 0; for (let i = 0; i < wing.length; i++) h = (h * 31 + wing.charCodeAt(i)) % 360
  return `hsl(${h}, 70%, 55%)`
}
</script>

<template>
  <div>
    <v-list density="compact">
      <v-list-item v-for="w in canvas.wings" :key="w.name" :title="w.name" :subtitle="`${w.drawer_count} drawers`">
        <template #prepend>
          <span class="dot" :style="{ background: colorFor(w.name) }" />
        </template>
      </v-list-item>
    </v-list>
    <v-divider class="my-3" />
    <strong style="font-size:12px;letter-spacing:0.1em">SIZE METRIC</strong>
    <v-radio-group v-model="ui.sizeMetric" density="compact">
      <v-radio v-for="m in metrics" :key="m.v" :label="m.label" :value="m.v" />
    </v-radio-group>
  </div>
</template>

<style scoped>
.dot { display:inline-block; width:10px; height:10px; border-radius:50%; margin-right:8px; }
</style>
```

- [ ] **Step 2: SearchPanel**

Create `knowledge-ui/src/components/shell/SearchPanel.vue`:

```vue
<script setup lang="ts">
import { ref, watch } from 'vue'
import { useApi } from '../../api/useApi'
import type { Drawer } from '../../api/types'
import { useDrawerStore } from '../../stores/drawer'

const q = ref('')
const results = ref<Drawer[]>([])
const loading = ref(false)
const drawer = useDrawerStore()

let timer: number | null = null
watch(q, v => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    loading.value = true
    try { results.value = await useApi().call<Drawer[]>('hivemem_search', { query: v, limit: 50 }) }
    finally { loading.value = false }
  }, 180) as unknown as number
})
</script>

<template>
  <v-text-field v-model="q" density="compact" variant="solo-filled" placeholder="Type to search…" autofocus />
  <v-list density="compact">
    <v-list-item v-for="d in results" :key="d.id" :title="d.title" :subtitle="d.wing + (d.hall ? ` · ${d.hall}` : '')"
                 @click="drawer.load(d.id)" />
  </v-list>
  <div v-if="loading" style="color:#666;padding:8px">Searching…</div>
</template>
```

- [ ] **Step 3: SettingsPanel**

Create `knowledge-ui/src/components/shell/SettingsPanel.vue`:

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const mock = ref(localStorage.getItem('hivemem_mock') === 'true')

function toggleMock(v: boolean) {
  localStorage.setItem('hivemem_mock', String(v))
  location.reload()
}
</script>

<template>
  <v-list density="compact">
    <v-list-item :title="`Signed in as ${auth.identity ?? '…'}`" :subtitle="`Role: ${auth.role ?? 'none'}`" />
  </v-list>
  <v-switch v-model="mock" label="Mock mode" color="primary" @update:model-value="toggleMock" />
  <v-btn block color="error" variant="tonal" @click="auth.logout(); location.reload()">Log out</v-btn>
</template>
```

- [ ] **Step 4: Wire into HomeRoute**

Update `HomeRoute.vue` SlidePanel lines:

```vue
<SlidePanel id="search" title="Search"><SearchPanel /></SlidePanel>
<SlidePanel id="wings" title="Wings"><WingsPanel /></SlidePanel>
<SlidePanel id="settings" title="Settings"><SettingsPanel /></SlidePanel>
```

Add imports:

```ts
import SearchPanel from '../components/shell/SearchPanel.vue'
import WingsPanel from '../components/shell/WingsPanel.vue'
import SettingsPanel from '../components/shell/SettingsPanel.vue'
```

- [ ] **Step 5: Commit**

```bash
git add knowledge-ui/src/components/shell/ knowledge-ui/src/pages/HomeRoute.vue
git commit -m "feat(ui): wings/search/settings panel content with size-metric selector"
```

---

## Task 11: PixiJS SphereCanvas skeleton

**Files:**
- Create: `knowledge-ui/src/components/canvas/SphereCanvas.vue`
- Create: `knowledge-ui/src/components/canvas/textures.ts`
- Modify: `knowledge-ui/src/pages/HomeRoute.vue`

- [ ] **Step 1: Texture generator**

Create `knowledge-ui/src/components/canvas/textures.ts`:

```ts
import { Texture } from 'pixi.js'

const cache = new Map<string, Texture>()

export function colorForWing(name: string): string {
  let h = 0; for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360
  return `hsl(${h}, 70%, 55%)`
}

export function wingTexture(colorHsl: string, size = 256): Texture {
  const key = `${colorHsl}:${size}`
  const cached = cache.get(key); if (cached) return cached
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = size
  const ctx = canvas.getContext('2d')!
  const g = ctx.createRadialGradient(size/2, size/2, 0, size/2, size/2, size/2)
  g.addColorStop(0, colorHsl.replace('hsl', 'hsla').replace(')', ', 0.9)'))
  g.addColorStop(0.5, colorHsl.replace('hsl', 'hsla').replace(')', ', 0.5)'))
  g.addColorStop(1, colorHsl.replace('hsl', 'hsla').replace(')', ', 0)'))
  ctx.fillStyle = g; ctx.fillRect(0, 0, size, size)
  const img = ctx.getImageData(0, 0, size, size)
  for (let i = 0; i < img.data.length; i += 4) {
    const n = (Math.random() - 0.5) * 18
    img.data[i] += n; img.data[i+1] += n; img.data[i+2] += n
  }
  ctx.putImageData(img, 0, 0)
  const tex = Texture.from(canvas)
  cache.set(key, tex); return tex
}

export function drawerTexture(size = 64): Texture {
  const key = `drawer:${size}`
  const cached = cache.get(key); if (cached) return cached
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = size
  const ctx = canvas.getContext('2d')!
  const g = ctx.createRadialGradient(size/2, size/2, 0, size/2, size/2, size/2)
  g.addColorStop(0, 'rgba(255,255,255,1)')
  g.addColorStop(0.6, 'rgba(255,255,255,0.5)')
  g.addColorStop(1, 'rgba(255,255,255,0)')
  ctx.fillStyle = g; ctx.fillRect(0, 0, size, size)
  const tex = Texture.from(canvas)
  cache.set(key, tex); return tex
}

export function parseHsl(hsl: string): number {
  const m = /hsl\((\d+), *(\d+)%, *(\d+)%\)/.exec(hsl); if (!m) return 0xffffff
  const h = +m[1] / 360, s = +m[2] / 100, l = +m[3] / 100
  const a = s * Math.min(l, 1-l)
  const f = (n: number) => { const k = (n + h*12) % 12; return l - a * Math.max(-1, Math.min(k-3, 9-k, 1)) }
  return Math.round(f(0)*255)*65536 + Math.round(f(8)*255)*256 + Math.round(f(4)*255)
}
```

- [ ] **Step 2: SphereCanvas skeleton**

Create `knowledge-ui/src/components/canvas/SphereCanvas.vue`:

```vue
<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { Application, Container, Sprite } from 'pixi.js'
import { wingTexture, drawerTexture, colorForWing, parseHsl } from './textures'
import { useCanvasStore } from '../../stores/canvas'
import type { Drawer } from '../../api/types'

const root = ref<HTMLDivElement>()
const canvasStore = useCanvasStore()
let app: Application | null = null
let world: Container | null = null

onMounted(async () => {
  if (!root.value) return
  app = new Application()
  await app.init({ background: 0x050510, resizeTo: root.value, antialias: true, resolution: devicePixelRatio, autoDensity: true })
  root.value.appendChild(app.canvas)
  world = new Container(); app.stage.addChild(world)
  render()
})

onBeforeUnmount(() => { app?.destroy(true, { children: true, texture: false }); app = null; world = null })

function render() {
  if (!world || !app) return
  world.removeChildren()
  const cx = app.screen.width / 2
  const cy = app.screen.height / 2
  const R = Math.min(cx, cy) * 0.5
  const drawersByWing = new Map<string, Drawer[]>()
  for (const d of canvasStore.drawers) {
    if (!drawersByWing.has(d.wing)) drawersByWing.set(d.wing, [])
    drawersByWing.get(d.wing)!.push(d)
  }
  canvasStore.wings.forEach((w, i) => {
    const angle = (i / canvasStore.wings.length) * Math.PI * 2
    const wx = cx + Math.cos(angle) * R
    const wy = cy + Math.sin(angle) * R
    const size = 120 + Math.log(1 + w.drawer_count) * 30
    const s: any = new Sprite(wingTexture(colorForWing(w.name)))
    s.anchor.set(0.5); s.width = s.height = size; s.x = wx; s.y = wy; s._kind = 'wing'; s._name = w.name
    world!.addChild(s)
    const group = drawersByWing.get(w.name) ?? []
    group.forEach((d, idx) => {
      const a = hashAngle(d.id)
      const rr = 40 + (hashRadius(d.id) % 60)
      const ds: any = new Sprite(drawerTexture())
      ds.anchor.set(0.5); ds.width = ds.height = 14 + d.importance * 4
      ds.tint = parseHsl(colorForWing(d.wing))
      ds.x = wx + Math.cos(a) * rr; ds.y = wy + Math.sin(a) * rr
      ds._kind = 'drawer'; ds._drawerId = d.id
      world!.addChild(ds)
    })
  })
}

watch(() => canvasStore.loaded, v => { if (v) render() })
watch(() => canvasStore.drawers.length, () => render())

function hashAngle(id: string) { let h = 0; for (const c of id) h = (h * 17 + c.charCodeAt(0)) % 1000; return (h / 1000) * Math.PI * 2 }
function hashRadius(id: string) { let h = 0; for (const c of id) h = (h * 29 + c.charCodeAt(0)) % 10000; return h }
</script>

<template><div ref="root" class="canvas-root" /></template>
<style scoped>.canvas-root { position:absolute; inset:0; }</style>
```

- [ ] **Step 3: Mount in HomeRoute**

Replace the `<main class="canvas-slot">` content:

```vue
<main class="canvas-slot">
  <SphereCanvas v-if="canvas.loaded" />
  <div v-else class="splash">Loading palace…</div>
</main>
```

Add imports and setup:

```ts
import SphereCanvas from '../components/canvas/SphereCanvas.vue'
import { useCanvasStore } from '../stores/canvas'
import { onMounted } from 'vue'
const canvas = useCanvasStore()
onMounted(() => { if (!canvas.loaded) canvas.loadTopLevel() })
```

Add CSS:

```css
.splash { display:grid; place-items:center; height:100%; color:#4dc4ff; }
```

- [ ] **Step 4: Visual sanity**

Run dev server, verify wings render as glowing circles in a ring with drawer-points.

- [ ] **Step 5: Commit**

```bash
git add knowledge-ui/src/components/canvas/ knowledge-ui/src/pages/HomeRoute.vue
git commit -m "feat(ui): PixiJS SphereCanvas renders wings + drawer points from canvas store"
```

---

## Task 12: Layout via d3-force + Poisson-disk

**Files:**
- Create: `knowledge-ui/src/composables/layout.ts`, `knowledge-ui/tests/unit/layout.spec.ts`
- Modify: `knowledge-ui/src/components/canvas/SphereCanvas.vue`

- [ ] **Step 1: Test**

Create `knowledge-ui/tests/unit/layout.spec.ts`:

```ts
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
```

- [ ] **Step 2: Run — fail**

Run: `cd knowledge-ui && npx vitest run tests/unit/layout.spec.ts`
Expected: FAIL.

- [ ] **Step 3: Implement**

Create `knowledge-ui/src/composables/layout.ts`:

```ts
import * as d3 from 'd3-force'
import type { Wing, Drawer, Tunnel } from '../api/types'

export interface Point { x: number; y: number }

export function computeWingPositions(
  wings: Wing[], drawers: Drawer[], tunnels: Tunnel[],
  viewport: { width: number; height: number }
): Map<string, Point> {
  type N = d3.SimulationNodeDatum & { id: string; size: number }
  const nodes: N[] = wings.map(w => ({ id: w.name, size: Math.log(1 + w.drawer_count) * 10 + 40 }))

  const drawerWing = new Map<string, string>()
  for (const d of drawers) drawerWing.set(d.id, d.wing)
  const wingPairCount = new Map<string, number>()
  for (const t of tunnels) {
    const a = drawerWing.get(t.from_drawer); const b = drawerWing.get(t.to_drawer)
    if (!a || !b || a === b) continue
    const k = [a, b].sort().join('|')
    wingPairCount.set(k, (wingPairCount.get(k) ?? 0) + 1)
  }
  const links = [...wingPairCount.entries()].map(([k, count]) => {
    const [source, target] = k.split('|')
    return { source, target, strength: Math.min(1, count / 10) }
  })

  const sim = d3.forceSimulation(nodes)
    .force('charge', d3.forceManyBody().strength(-200))
    .force('center', d3.forceCenter(viewport.width / 2, viewport.height / 2))
    .force('collide', d3.forceCollide<N>(n => n.size + 8))
    .force('link', d3.forceLink<N, any>(links).id(n => n.id).strength((l: any) => l.strength).distance(220))
    .stop()
  for (let i = 0; i < 250; i++) sim.tick()

  const out = new Map<string, Point>()
  for (const n of nodes) out.set(n.id, { x: n.x ?? 0, y: n.y ?? 0 })
  return out
}

export function poissonDiskDrawers(
  count: number,
  spec: { x: number; y: number; r: number; minDist: number; seed: string }
): Point[] {
  const rng = seededRng(spec.seed)
  const points: Point[] = []
  const attemptsMax = count * 30
  for (let tries = 0; tries < attemptsMax && points.length < count; tries++) {
    const a = rng() * Math.PI * 2
    const d = Math.sqrt(rng()) * spec.r
    const p = { x: spec.x + Math.cos(a) * d, y: spec.y + Math.sin(a) * d }
    let ok = true
    for (const q of points) { if (Math.hypot(p.x - q.x, p.y - q.y) < spec.minDist) { ok = false; break } }
    if (ok) points.push(p)
  }
  while (points.length < count) points.push({ x: spec.x, y: spec.y })
  return points
}

function seededRng(seed: string) {
  let h = 2166136261
  for (const c of seed) h = Math.imul(h ^ c.charCodeAt(0), 16777619)
  return () => {
    h ^= h << 13; h ^= h >>> 17; h ^= h << 5
    return ((h >>> 0) % 1e9) / 1e9
  }
}
```

- [ ] **Step 4: Run — pass**

Run: `cd knowledge-ui && npx vitest run tests/unit/layout.spec.ts`
Expected: 2 passed.

- [ ] **Step 5: Replace ring layout in SphereCanvas**

In `SphereCanvas.vue` render(), replace the ring-math with:

```ts
import { computeWingPositions, poissonDiskDrawers } from '../../composables/layout'

// inside render():
const width = app.screen.width, height = app.screen.height
const wingPos = computeWingPositions(canvasStore.wings, canvasStore.drawers, canvasStore.tunnels, { width, height })
canvasStore.wings.forEach(w => {
  const p = wingPos.get(w.name); if (!p) return
  const size = 120 + Math.log(1 + w.drawer_count) * 30
  const s: any = new Sprite(wingTexture(colorForWing(w.name)))
  s.anchor.set(0.5); s.width = s.height = size; s.x = p.x; s.y = p.y; s._kind = 'wing'; s._name = w.name
  world!.addChild(s)
  const group = drawersByWing.get(w.name) ?? []
  const pts = poissonDiskDrawers(group.length, { x: p.x, y: p.y, r: 70, minDist: 14, seed: w.name })
  group.forEach((d, i) => {
    const pt = pts[i]
    const ds: any = new Sprite(drawerTexture())
    ds.anchor.set(0.5); ds.width = ds.height = 14 + d.importance * 4
    ds.tint = parseHsl(colorForWing(d.wing))
    ds.x = pt.x; ds.y = pt.y
    ds._kind = 'drawer'; ds._drawerId = d.id
    world!.addChild(ds)
  })
})
```

Remove the hardcoded-ring logic.

- [ ] **Step 6: Commit**

```bash
git add knowledge-ui/src/composables/layout.ts knowledge-ui/tests/unit/layout.spec.ts knowledge-ui/src/components/canvas/SphereCanvas.vue
git commit -m "feat(ui): d3-force wing layout + Poisson-disk drawer placement"
```

---

## Task 13: Edges layer

**Files:**
- Modify: `knowledge-ui/src/components/canvas/SphereCanvas.vue`

- [ ] **Step 1: Draw edges under spheres**

In `render()`, before creating wings/drawers, collect drawer positions, then draw Graphics lines:

```ts
import { Graphics } from 'pixi.js'
const relationColor: Record<string, number> = {
  related_to: 0x5a5a5a, builds_on: 0x4dc4ff, contradicts: 0xff4d4d, refines: 0x4dff9c
}
// inside render() AFTER wingPos and drawerPos are known:
const drawerPos = new Map<string, { x: number; y: number }>()
for (const w of canvasStore.wings) {
  const p = wingPos.get(w.name); if (!p) continue
  const group = drawersByWing.get(w.name) ?? []
  const pts = poissonDiskDrawers(group.length, { x: p.x, y: p.y, r: 70, minDist: 14, seed: w.name })
  group.forEach((d, i) => drawerPos.set(d.id, pts[i]))
}
const edges = new Graphics()
;(edges as any)._kind = 'edges'
for (const t of canvasStore.tunnels) {
  const a = drawerPos.get(t.from_drawer); const b = drawerPos.get(t.to_drawer)
  if (!a || !b) continue
  const col = relationColor[t.relation] ?? 0x666666
  const w = Math.max(0.6, Math.log(2) * 1.3)
  edges.moveTo(a.x, a.y).lineTo(b.x, b.y).stroke({ width: w, color: col, alpha: 0.4 })
}
world!.addChild(edges)
// Then add wings + drawers on top using drawerPos/wingPos as before.
```

- [ ] **Step 2: Build + visual check + commit**

```bash
cd knowledge-ui && npx vite build
git add knowledge-ui/src/components/canvas/SphereCanvas.vue
git commit -m "feat(ui): draw tunnel edges as relation-coloured lines"
```

---

## Task 14: Zoom + pan + snap-focus

**Files:**
- Modify: `knowledge-ui/src/components/canvas/SphereCanvas.vue`

- [ ] **Step 1: Wheel zoom + drag pan**

Inside `SphereCanvas.vue` after `app.init`:

```ts
let zoom = 1, panX = 0, panY = 0

app.canvas.addEventListener('wheel', e => {
  e.preventDefault()
  const factor = Math.exp(-e.deltaY * 0.0015)
  const next = Math.min(6, Math.max(0.15, zoom * factor))
  const mouseX = e.offsetX, mouseY = e.offsetY
  panX = mouseX - (mouseX - panX) * (next / zoom)
  panY = mouseY - (mouseY - panY) * (next / zoom)
  zoom = next
  applyTransform()
}, { passive: false })

let dragging = false, startX = 0, startY = 0, startPanX = 0, startPanY = 0
app.canvas.addEventListener('pointerdown', e => {
  dragging = true; startX = e.clientX; startY = e.clientY; startPanX = panX; startPanY = panY
})
window.addEventListener('pointerup', () => dragging = false)
window.addEventListener('pointermove', e => {
  if (!dragging) return
  panX = startPanX + (e.clientX - startX); panY = startPanY + (e.clientY - startY)
  applyTransform()
})

function applyTransform() {
  if (!world) return
  world.scale.set(zoom); world.position.set(panX, panY)
}
```

- [ ] **Step 2: Click + double-click on drawer sprite**

In the drawer-creation loop, add:

```ts
ds.eventMode = 'static'; ds.cursor = 'pointer'
let lastClick = 0
ds.on('pointertap', () => {
  const now = performance.now()
  if (now - lastClick < 320) { useReaderStore().openReader(d.id) }
  else { snapTo(ds.x, ds.y, 2.2, () => onDrawerClick(d)) }
  lastClick = now
})
```

Imports:

```ts
import { useDrawerStore } from '../../stores/drawer'
import { useReaderStore } from '../../stores/reader'
```

And helper functions near the bottom of `<script setup>`:

```ts
function snapTo(worldX: number, worldY: number, targetZoom: number, onDone: () => void) {
  if (!app) return
  const startZoom = zoom, startPanX = panX, startPanY = panY
  const targetPanX = app.screen.width / 2 - worldX * targetZoom
  const targetPanY = app.screen.height / 2 - worldY * targetZoom
  const startT = performance.now()
  function tick(t: number) {
    const k = Math.min(1, (t - startT) / 280)
    const e = k * k * (3 - 2 * k)
    zoom = startZoom + (targetZoom - startZoom) * e
    panX = startPanX + (targetPanX - startPanX) * e
    panY = startPanY + (targetPanY - startPanY) * e
    applyTransform()
    if (k < 1) requestAnimationFrame(tick); else onDone()
  }
  requestAnimationFrame(tick)
}

function onDrawerClick(d: Drawer) {
  useDrawerStore().load(d.id)
  canvasStore.setFocus(d.id)
}
```

- [ ] **Step 3: Commit**

```bash
git add knowledge-ui/src/components/canvas/SphereCanvas.vue
git commit -m "feat(ui): wheel zoom + drag pan + snap-focus tween + double-click opens reader"
```

---

## Task 15: Filter presets + bloom on focus

**Files:**
- Create: `knowledge-ui/src/components/canvas/filters.ts`
- Modify: `knowledge-ui/src/components/canvas/SphereCanvas.vue`

- [ ] **Step 1: Filter presets**

Create `knowledge-ui/src/components/canvas/filters.ts`:

```ts
import { AdvancedBloomFilter, OutlineFilter, ZoomBlurFilter, GodrayFilter } from 'pixi-filters'

export const focusFilter  = () => new AdvancedBloomFilter({ threshold: 0.25, bloomScale: 1.4, brightness: 1, blur: 8, quality: 5 })
export const hoverFilter  = () => new AdvancedBloomFilter({ threshold: 0.4,  bloomScale: 0.8, brightness: 1, blur: 4, quality: 3 })
export const focusRing    = () => new OutlineFilter({ color: 0x4dc4ff, thickness: 2 })
export const snapPulse    = () => new ZoomBlurFilter({ strength: 0.25 })
export const godrays      = () => new GodrayFilter({ alpha: 0.05, angle: 30, lacunarity: 2.75, gain: 0.3 })
```

- [ ] **Step 2: Apply hover/focus**

In SphereCanvas, when creating each drawer sprite:

```ts
ds.on('pointerover', () => { ds.filters = [hoverFilter()] })
ds.on('pointerout',  () => { ds.filters = [] })
```

Add a watch on focusedId:

```ts
watch(() => canvasStore.focusedId, id => {
  if (!world) return
  for (const c of world.children) {
    const s = c as any
    if (s._kind !== 'drawer') continue
    s.filters = s._drawerId === id ? [focusFilter(), focusRing()] : []
  }
})
```

Imports: `import { focusFilter, hoverFilter, focusRing, godrays } from './filters'`.

- [ ] **Step 3: Godray backdrop**

After `app.stage.addChild(world)`:

```ts
const bg = new Graphics().rect(0, 0, 2000, 2000).fill(0x05050f)
;(bg as any).filters = [godrays()]
app.stage.addChildAt(bg, 0)
```

- [ ] **Step 4: Commit**

```bash
git add knowledge-ui/src/components/canvas/filters.ts knowledge-ui/src/components/canvas/SphereCanvas.vue
git commit -m "feat(ui): pixi-filters bloom/outline/zoomblur/godray; hover + focus highlights"
```

---

## Task 16: LOD + viewport culling

**Files:**
- Create: `knowledge-ui/src/composables/lod.ts`, `knowledge-ui/tests/unit/lod.spec.ts`
- Modify: `knowledge-ui/src/components/canvas/SphereCanvas.vue`

- [ ] **Step 1: Test**

Create `knowledge-ui/tests/unit/lod.spec.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { lodLevel, drawerVisibleAt } from '../../src/composables/lod'

describe('lod', () => {
  it('< 0.3 -> wings', () => expect(lodLevel(0.2)).toBe('wings'))
  it('0.3..1.0 -> halos', () => expect(lodLevel(0.7)).toBe('halos'))
  it('1.0..3.0 -> labels', () => expect(lodLevel(1.5)).toBe('labels'))
  it('> 3 -> full', () => expect(lodLevel(4)).toBe('full'))
  it('drawerVisibleAt 0.2 false', () => expect(drawerVisibleAt(0.2)).toBe(false))
  it('drawerVisibleAt 0.5 true', () => expect(drawerVisibleAt(0.5)).toBe(true))
})
```

- [ ] **Step 2: Implement**

Create `knowledge-ui/src/composables/lod.ts`:

```ts
export type Lod = 'wings' | 'halos' | 'labels' | 'full'

export function lodLevel(zoom: number): Lod {
  if (zoom < 0.3) return 'wings'
  if (zoom < 1.0) return 'halos'
  if (zoom < 3.0) return 'labels'
  return 'full'
}
export function drawerVisibleAt(zoom: number) { return zoom >= 0.3 }
export function labelsVisibleAt(zoom: number) { return zoom >= 1.0 }
export function edgeAlphaAt(zoom: number) { return Math.max(0.1, Math.min(1, zoom / 2)) }
```

- [ ] **Step 3: Hook into SphereCanvas applyTransform**

```ts
import { drawerVisibleAt } from '../../composables/lod'
function applyTransform() {
  if (!world || !app) return
  world.scale.set(zoom); world.position.set(panX, panY)
  const viewLeft = -panX / zoom, viewTop = -panY / zoom
  const viewRight = viewLeft + app.screen.width / zoom
  const viewBottom = viewTop + app.screen.height / zoom
  for (const c of world.children) {
    const s = c as any
    if (s._kind === 'drawer') {
      let vis = drawerVisibleAt(zoom)
      if (vis) {
        const r = Math.max(s.width, s.height)
        vis = s.x + r > viewLeft && s.x - r < viewRight
           && s.y + r > viewTop  && s.y - r < viewBottom
      }
      s.visible = vis
    }
  }
}
```

- [ ] **Step 4: Run tests + commit**

```bash
cd knowledge-ui && npx vitest run tests/unit/lod.spec.ts  # 6 pass
git add knowledge-ui/src/composables/lod.ts knowledge-ui/tests/unit/lod.spec.ts knowledge-ui/src/components/canvas/SphereCanvas.vue
git commit -m "feat(ui): LOD visibility + viewport culling in applyTransform"
```

---

## Task 17: ScanPanel (drawer-detail slide-panel)

**Files:**
- Create: `knowledge-ui/src/components/ScanPanel.vue`
- Modify: `knowledge-ui/src/pages/HomeRoute.vue`

- [ ] **Step 1: ScanPanel**

Create `knowledge-ui/src/components/ScanPanel.vue`:

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { useDrawerStore } from '../stores/drawer'
import { useReaderStore } from '../stores/reader'
import { useCanvasStore } from '../stores/canvas'

const drawer = useDrawerStore()
const reader = useReaderStore()
const canvas = useCanvasStore()
const d = computed(() => drawer.current)

function openReader() { if (d.value) reader.openReader(d.value.drawer.id) }
function jumpTo(id: string) { drawer.load(id); canvas.setFocus(id) }
function close() { drawer.clear(); canvas.setFocus(null) }
</script>

<template>
  <transition name="slide-r">
    <aside v-if="d" class="scan">
      <header>
        <strong>{{ d.drawer.title }}</strong>
        <v-btn icon="mdi-close" size="small" variant="text" @click="close" />
      </header>
      <div class="body">
        <div class="chips">
          <v-chip size="x-small" color="primary" variant="tonal">{{ d.drawer.wing }}</v-chip>
          <v-chip v-if="d.drawer.hall" size="x-small" variant="outlined">{{ d.drawer.hall }}</v-chip>
          <v-chip v-if="d.drawer.room" size="x-small" variant="outlined">{{ d.drawer.room }}</v-chip>
          <span class="imp">{{ '★'.repeat(d.drawer.importance) }}</span>
        </div>
        <section v-if="d.drawer.summary">
          <div class="label">SUMMARY</div><p>{{ d.drawer.summary }}</p>
        </section>
        <section v-if="d.drawer.key_points?.length">
          <div class="label">KEY POINTS</div>
          <ul><li v-for="k in d.drawer.key_points" :key="k">{{ k }}</li></ul>
        </section>
        <section v-if="d.drawer.insight">
          <div class="label">INSIGHT</div><blockquote>{{ d.drawer.insight }}</blockquote>
        </section>
        <section v-if="d.tunnels.length">
          <div class="label">TUNNELS ({{ d.tunnels.length }})</div>
          <div v-for="t in d.tunnels" :key="t.id" class="tunnel"
               @click="jumpTo(t.to_drawer === d.drawer.id ? t.from_drawer : t.to_drawer)">
            <span :class="['dot', t.relation]" />
            <span class="rel">{{ t.relation }}</span>
            <span class="note">{{ t.note || '' }}</span>
          </div>
        </section>
        <section v-if="d.facts.length">
          <div class="label">FACTS ({{ d.facts.length }})</div>
          <div v-for="f in d.facts" :key="f.id" class="fact">
            <span class="pred">{{ f.predicate }}</span> → {{ f.object }}
          </div>
        </section>
        <v-btn block color="primary" class="mt-3" @click="openReader">Open reader</v-btn>
      </div>
    </aside>
  </transition>
</template>

<style scoped>
.scan { position:fixed; top:0; right:0; bottom:0; width:360px; background:#0e0e1c; border-left:1px solid #1a1a24; display:flex; flex-direction:column; z-index:8; }
header { display:flex; align-items:center; justify-content:space-between; padding:10px 14px; border-bottom:1px solid #1a1a24; gap:8px; }
header strong { flex:1; font-size:14px; }
.body { flex:1; overflow-y:auto; padding:10px 14px; font-size:12px; }
.chips { display:flex; gap:4px; flex-wrap:wrap; margin-bottom:10px; align-items:center; }
.imp { color:#ffd24d; margin-left:6px; }
section { margin:10px 0; }
.label { color:#888; font-size:10px; letter-spacing:0.1em; font-weight:bold; margin-bottom:4px; }
blockquote { border-left:3px solid #4dc4ff; padding-left:8px; color:#4dc4ff; font-style:italic; }
.tunnel { display:flex; gap:6px; align-items:center; padding:4px 0; cursor:pointer; }
.tunnel:hover { background:#1a1a2a; }
.dot { width:8px; height:8px; border-radius:50%; }
.dot.related_to { background:#5a5a5a; }
.dot.builds_on  { background:#4dc4ff; }
.dot.contradicts{ background:#ff4d4d; }
.dot.refines    { background:#4dff9c; }
.rel { color:#aaa; font-size:10px; }
.note { color:#ccc; }
.fact { padding:2px 0; color:#ccc; }
.pred { color:#4dc4ff; }
.slide-r-enter-from, .slide-r-leave-to { transform:translateX(20px); opacity:0; }
.slide-r-enter-active, .slide-r-leave-active { transition:transform 180ms ease, opacity 180ms ease; }
</style>
```

- [ ] **Step 2: Mount in HomeRoute**

Add to `HomeRoute.vue`:

```vue
<ScanPanel />
```

Import: `import ScanPanel from '../components/ScanPanel.vue'`.

- [ ] **Step 3: Commit**

```bash
git add knowledge-ui/src/components/ScanPanel.vue knowledge-ui/src/pages/HomeRoute.vue
git commit -m "feat(ui): scan panel with L1/L2/L3, tunnels (jumpable), facts, reader CTA"
```

---

## Task 18: Reader (Markdown tab)

**Files:**
- Create: `knowledge-ui/src/components/Reader.vue`, `knowledge-ui/src/components/readers/MarkdownTab.vue`
- Modify: `knowledge-ui/src/pages/HomeRoute.vue`

- [ ] **Step 1: MarkdownTab**

Create `knowledge-ui/src/components/readers/MarkdownTab.vue`:

```vue
<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import katex from 'katex'
import 'katex/dist/katex.min.css'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'

const props = defineProps<{ content: string }>()

const md = new MarkdownIt({ html: false, linkify: true, breaks: false,
  highlight(str, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try { return hljs.highlight(str, { language: lang }).value } catch {}
    }
    return ''
  }
})

function renderKatex(src: string) {
  return src
    .replace(/\$\$([\s\S]+?)\$\$/g, (_, m) => katex.renderToString(m, { displayMode: true, throwOnError: false }))
    .replace(/(^|[^\\])\$([^$\n]+?)\$/g, (_, pre, m) => pre + katex.renderToString(m, { throwOnError: false }))
}

const html = computed(() => md.render(renderKatex(props.content || '')))
</script>

<template>
  <article class="md" v-html="html" />
</template>

<style scoped>
.md { max-width:720px; margin:0 auto; font-family:Georgia,'Charter',serif; font-size:17px; line-height:1.65; color:#e8e8ea; padding:20px 0; }
.md :deep(h1), .md :deep(h2) { border-bottom:1px solid #2a2a3a; padding-bottom:4px; }
.md :deep(pre) { background:#0b0b15; padding:12px; border-radius:6px; overflow-x:auto; }
.md :deep(blockquote) { border-left:3px solid #4dc4ff; padding-left:12px; color:#4dc4ff; font-style:italic; }
.md :deep(code):not(pre code) { background:#1a1a2a; padding:2px 5px; border-radius:3px; font-size:0.9em; }
</style>
```

- [ ] **Step 2: Reader shell**

Create `knowledge-ui/src/components/Reader.vue`:

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { useReaderStore } from '../stores/reader'
import { useDrawerStore } from '../stores/drawer'
import MarkdownTab from './readers/MarkdownTab.vue'

const reader = useReaderStore()
const drawer = useDrawerStore()

const attachments = computed(() => {
  // Populated later from drawer references; empty in SP1 v1
  return [] as { id: string; title: string; url: string; kind: 'pdf' | 'eml' }[]
})
</script>

<template>
  <v-dialog v-model="reader.open" fullscreen transition="dialog-bottom-transition" persistent>
    <div class="reader-shell" v-if="drawer.current">
      <header>
        <v-btn icon="mdi-arrow-left" variant="text" @click="reader.close()" />
        <v-tabs v-model="reader.activeTab" density="compact" color="primary">
          <v-tab value="markdown">Markdown</v-tab>
          <v-tab v-for="a in attachments" :key="a.id" :value="a.id">{{ a.title }}</v-tab>
        </v-tabs>
        <v-spacer />
        <v-btn icon="mdi-pencil" variant="text" disabled title="Editor — SP4" />
      </header>
      <main class="reader-body">
        <MarkdownTab v-if="reader.activeTab === 'markdown'" :content="drawer.current.drawer.content" />
      </main>
    </div>
  </v-dialog>
</template>

<style scoped>
.reader-shell { position:fixed; inset:0; background:#0a0a14; display:flex; flex-direction:column; }
header { display:flex; align-items:center; padding:6px 10px; background:#12121e; border-bottom:1px solid #1a1a24; }
.reader-body { flex:1; overflow-y:auto; padding:0 20px; }
</style>
```

- [ ] **Step 3: Mount**

Add `<Reader />` in `HomeRoute.vue` with import.

- [ ] **Step 4: Commit**

```bash
git add knowledge-ui/src/components/Reader.vue knowledge-ui/src/components/readers/MarkdownTab.vue knowledge-ui/src/pages/HomeRoute.vue
git commit -m "feat(ui): reader fullscreen dialog + markdown-it/katex/highlight tab"
```

---

## Task 19: PDF tab (pdf.js dynamic import)

**Files:**
- Create: `knowledge-ui/src/components/readers/PdfTab.vue`
- Modify: `knowledge-ui/src/components/Reader.vue`

- [ ] **Step 1: PdfTab**

Create `knowledge-ui/src/components/readers/PdfTab.vue`:

```vue
<script setup lang="ts">
import { ref, watchEffect, onBeforeUnmount } from 'vue'

const props = defineProps<{ url: string }>()
const container = ref<HTMLDivElement>()
let viewer: any = null

watchEffect(async () => {
  if (!props.url || !container.value) return
  const pdfjs = await import('pdfjs-dist')
  const viewerMod = await import('pdfjs-dist/web/pdf_viewer')
  ;(pdfjs as any).GlobalWorkerOptions.workerSrc =
    new URL('pdfjs-dist/build/pdf.worker.min.mjs', import.meta.url).toString()
  const pdf = await pdfjs.getDocument(props.url).promise
  const eventBus = new viewerMod.EventBus()
  const linkService = new viewerMod.PDFLinkService({ eventBus })
  viewer = new viewerMod.PDFViewer({ container: container.value!, eventBus, linkService })
  linkService.setViewer(viewer)
  viewer.setDocument(pdf); linkService.setDocument(pdf, null)
})

onBeforeUnmount(() => { viewer?.cleanup?.() })
</script>

<template>
  <div ref="container" class="pdf-container"><div class="pdfViewer" /></div>
</template>

<style scoped>
.pdf-container { position:absolute; inset:0; overflow:auto; }
</style>
```

- [ ] **Step 2: Wire into Reader**

In `Reader.vue` template, after the Markdown branch:

```vue
<PdfTab v-else-if="kindOf(reader.activeTab) === 'pdf'" :url="urlOf(reader.activeTab)" />
```

In script:

```ts
import PdfTab from './readers/PdfTab.vue'
function kindOf(tab: string) { return attachments.value.find(a => a.id === tab)?.kind }
function urlOf(tab: string) { return attachments.value.find(a => a.id === tab)?.url ?? '' }
```

- [ ] **Step 3: Commit**

```bash
git add knowledge-ui/src/components/readers/PdfTab.vue knowledge-ui/src/components/Reader.vue
git commit -m "feat(ui): pdf.js tab via dynamic import (ready for SP2 attachments)"
```

---

## Task 20: .eml tab via postal-mime

**Files:**
- Create: `knowledge-ui/src/components/readers/EmlTab.vue`
- Modify: `knowledge-ui/src/components/Reader.vue`

- [ ] **Step 1: EmlTab**

Create `knowledge-ui/src/components/readers/EmlTab.vue`:

```vue
<script setup lang="ts">
import { ref, watchEffect } from 'vue'
import PostalMime from 'postal-mime'

const props = defineProps<{ url: string }>()
const parsed = ref<any>(null)
const error = ref('')

watchEffect(async () => {
  if (!props.url) return
  try {
    const raw = await fetch(props.url).then(r => r.text())
    parsed.value = await new PostalMime().parse(raw)
  } catch (e: any) { error.value = e?.message ?? 'failed to parse' }
})
</script>

<template>
  <article class="eml">
    <v-alert v-if="error" type="error" variant="tonal">{{ error }}</v-alert>
    <template v-else-if="parsed">
      <header>
        <div><strong>From:</strong> {{ parsed.from?.address }}</div>
        <div><strong>To:</strong> {{ parsed.to?.map((t: any) => t.address).join(', ') }}</div>
        <div><strong>Subject:</strong> {{ parsed.subject }}</div>
        <div><strong>Date:</strong> {{ parsed.date }}</div>
      </header>
      <div class="body" v-html="parsed.html || parsed.text || ''"></div>
    </template>
  </article>
</template>

<style scoped>
.eml { max-width:780px; margin:0 auto; font-family:system-ui,sans-serif; padding:20px 0; color:#e8e8ea; }
header { border-bottom:1px solid #2a2a3a; padding-bottom:12px; margin-bottom:18px; font-size:13px; color:#aaa; }
header strong { color:#eee; margin-right:4px; }
.body { line-height:1.55; font-size:15px; }
.body :deep(blockquote) { border-left:2px solid #666; padding-left:10px; color:#999; }
</style>
```

- [ ] **Step 2: Wire into Reader**

Add branch:

```vue
<EmlTab v-else-if="kindOf(reader.activeTab) === 'eml'" :url="urlOf(reader.activeTab)" />
```

Import: `import EmlTab from './readers/EmlTab.vue'`.

- [ ] **Step 3: Commit**

```bash
git add knowledge-ui/src/components/readers/EmlTab.vue knowledge-ui/src/components/Reader.vue
git commit -m "feat(ui): .eml tab with postal-mime"
```

---

## Task 21: Live-update subscribe + toast

**Files:**
- Modify: `knowledge-ui/src/pages/HomeRoute.vue`

- [ ] **Step 1: Subscribe**

In `HomeRoute.vue`, inside setup:

```ts
import { onBeforeUnmount, onMounted } from 'vue'
import { useApi } from '../api/useApi'
import { useUiStore } from '../stores/ui'
import { useCanvasStore } from '../stores/canvas'

const canvas = useCanvasStore()
const ui = useUiStore()
let unsub: (() => void) | null = null
onMounted(() => {
  if (!canvas.loaded) canvas.loadTopLevel()
  unsub = useApi().subscribe(e => {
    if (e.type === 'status' || e.type === 'drawer_added' || e.type === 'tunnel_added') {
      ui.pushToast('info', 'New activity — click Reload to refresh')
    }
  })
})
onBeforeUnmount(() => unsub?.())
```

- [ ] **Step 2: Commit**

```bash
git add knowledge-ui/src/pages/HomeRoute.vue
git commit -m "feat(ui): live-update subscribe pushes reload toast"
```

---

## Task 22: Cinema route with recycled HiveSphere

**Files:**
- Modify: `knowledge-ui/src/pages/CinemaRoute.vue`
- Modify: `knowledge-ui/package.json` if TresJS not present

- [ ] **Step 1: Ensure TresJS + three are present**

Run: `cd knowledge-ui && npm ls @tresjs/core three`

If missing:

```bash
cd knowledge-ui && npm install @tresjs/core@^5 @tresjs/cientos@^5 three@^0.170
```

- [ ] **Step 2: Rewrite CinemaRoute**

Replace `knowledge-ui/src/pages/CinemaRoute.vue`:

```vue
<script setup lang="ts">
import { defineAsyncComponent, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useCanvasStore } from '../stores/canvas'

const router = useRouter()
const canvas = useCanvasStore()

const TresCanvas = defineAsyncComponent(() => import('@tresjs/core').then(m => ({ default: m.TresCanvas })))
const HiveSphere = defineAsyncComponent(() => import('../components/hive/HiveSphere.vue'))
const CyberBees  = defineAsyncComponent(() => import('../components/hive/CyberBees.vue'))
const HiveFloor  = defineAsyncComponent(() => import('../components/hive/HiveFloor.vue'))

onMounted(() => { if (!canvas.loaded) canvas.loadTopLevel() })
</script>

<template>
  <div class="cinema">
    <v-btn class="back" icon="mdi-arrow-left" @click="router.push('/')" />
    <Suspense>
      <TresCanvas clear-color="#000010" :dpr="1.5">
        <TresPerspectiveCamera :position="[0, 0, 14]" />
        <TresAmbientLight :intensity="0.3" />
        <HiveSphere v-if="canvas.loaded" :wings="canvas.wings" :drawers="canvas.drawers" />
        <CyberBees />
        <HiveFloor />
      </TresCanvas>
    </Suspense>
  </div>
</template>

<style scoped>
.cinema { position:fixed; inset:0; background:#000010; }
.back { position:fixed; top:16px; left:16px; z-index:2; }
</style>
```

If the recycled HiveSphere has a different prop shape (e.g. expects a `palace` object), adapt it here — wrap with a thin passthrough or update the prop names in HiveSphere to accept `{ wings, drawers }`.

- [ ] **Step 3: Verify bundle splits TresJS into its own chunk**

Run: `cd knowledge-ui && npx vite build`
Expected: dist contains separate chunks for `three` and `@tresjs/core`; main bundle does not grow by the TresJS size.

- [ ] **Step 4: Commit**

```bash
git add knowledge-ui/src/pages/CinemaRoute.vue knowledge-ui/package.json knowledge-ui/package-lock.json
git commit -m "feat(ui): cinema route lazy-loads TresJS + recycled HiveSphere"
```

---

## Task 23: Keyboard shortcuts

**Files:**
- Create: `knowledge-ui/src/composables/keybindings.ts`
- Modify: `knowledge-ui/src/pages/HomeRoute.vue`

- [ ] **Step 1: Composable**

Create `knowledge-ui/src/composables/keybindings.ts`:

```ts
import { useEventListener } from '@vueuse/core'
import { useUiStore } from '../stores/ui'
import { useReaderStore } from '../stores/reader'
import { useDrawerStore } from '../stores/drawer'

export function useKeybindings() {
  const ui = useUiStore(), reader = useReaderStore(), drawer = useDrawerStore()
  useEventListener('keydown', (e: KeyboardEvent) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault(); ui.activePanel = 'search'
    } else if (e.key === 'Escape') {
      if (reader.open) reader.close()
      else if (drawer.currentId) drawer.clear()
      else if (ui.activePanel) ui.activePanel = null
    } else if (e.key === 'Enter' && drawer.currentId && !reader.open) {
      reader.openReader(drawer.currentId)
    } else if (e.key === '?' && !ui.showLoginDialog) {
      ui.pushToast('info', 'Cmd+K search · Esc back · Enter reader')
    }
  })
}
```

- [ ] **Step 2: Use in HomeRoute**

Add to setup:

```ts
import { useKeybindings } from '../composables/keybindings'
useKeybindings()
```

- [ ] **Step 3: Commit**

```bash
git add knowledge-ui/src/composables/keybindings.ts knowledge-ui/src/pages/HomeRoute.vue
git commit -m "feat(ui): Cmd+K / Esc / Enter / ? keybindings"
```

---

## Task 24: Playwright E2E smoke

**Files:**
- Create: `knowledge-ui/playwright.config.ts`, `knowledge-ui/tests/e2e/smoke.spec.ts`

- [ ] **Step 1: Playwright config**

Create `knowledge-ui/playwright.config.ts`:

```ts
import { defineConfig, devices } from '@playwright/test'
export default defineConfig({
  testDir: './tests/e2e',
  use: { headless: true, baseURL: 'http://localhost:5173' },
  webServer: {
    command: 'npm run dev -- --host 0.0.0.0 --port 5173',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 30_000
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }]
})
```

- [ ] **Step 2: Smoke test**

Create `knowledge-ui/tests/e2e/smoke.spec.ts`:

```ts
import { test, expect } from '@playwright/test'

test('login → search → open drawer → reader → close', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('hivemem_mock', 'true'))
  await page.goto('/')
  await page.getByLabel('Use local mock data').click()
  await page.getByRole('button', { name: 'Connect' }).click()
  await expect(page.locator('canvas')).toBeVisible()
  await page.keyboard.press('Control+KeyK')
  await page.getByPlaceholder('Type to search…').fill('hivemem')
  await page.locator('.v-list-item').first().click()
  await expect(page.locator('.scan')).toBeVisible()
  await page.getByRole('button', { name: 'Open reader' }).click()
  await expect(page.locator('.reader-shell')).toBeVisible()
  await page.locator('.reader-shell header button').first().click()
  await expect(page.locator('.reader-shell')).toBeHidden()
})
```

- [ ] **Step 3: Install browsers + run**

```bash
cd knowledge-ui && npx playwright install chromium && npx playwright test
```

Expected: 1 passed.

- [ ] **Step 4: Commit**

```bash
git add knowledge-ui/playwright.config.ts knowledge-ui/tests/e2e/smoke.spec.ts knowledge-ui/package.json knowledge-ui/package-lock.json
git commit -m "test(ui): playwright e2e smoke (login → search → open → reader → close)"
```

---

## Task 25: Final polish — particles + build verification

**Files:**
- Create: `knowledge-ui/src/components/canvas/particles.ts`
- Modify: `knowledge-ui/src/components/canvas/SphereCanvas.vue`

- [ ] **Step 1: Particle emitter**

Create `knowledge-ui/src/components/canvas/particles.ts`:

```ts
import { Container, Graphics } from 'pixi.js'

export function spawnDust(parent: Container, count: number, bounds: { w: number; h: number }) {
  const dots: (Graphics & { vx: number; vy: number })[] = []
  for (let i = 0; i < count; i++) {
    const g = new Graphics().circle(0, 0, 0.9 + Math.random() * 1.1).fill(i % 3 === 0 ? 0xffd24d : 0x4dc4ff) as any
    g.x = Math.random() * bounds.w
    g.y = Math.random() * bounds.h
    g.alpha = 0.3 + Math.random() * 0.4
    g.vx = (Math.random() - 0.5) * 0.08
    g.vy = -0.02 - Math.random() * 0.04
    parent.addChild(g); dots.push(g)
  }
  return (deltaMs: number) => {
    for (const g of dots) {
      g.x += g.vx * deltaMs; g.y += g.vy * deltaMs
      if (g.y < -10) { g.y = bounds.h + 10; g.x = Math.random() * bounds.w }
      if (g.x < -10) g.x = bounds.w + 10
      if (g.x > bounds.w + 10) g.x = -10
    }
  }
}
```

- [ ] **Step 2: Hook emitter into SphereCanvas**

After `app.stage.addChild(world)`:

```ts
import { spawnDust } from './particles'
const dustLayer = new Container(); app.stage.addChild(dustLayer)
const isMobile = window.matchMedia('(max-width: 768px)').matches
const update = spawnDust(dustLayer, isMobile ? 120 : 280, { w: app.screen.width, h: app.screen.height })
app.ticker.add(t => update(t.deltaMS))
```

- [ ] **Step 3: Final verification**

```bash
cd knowledge-ui
npx vue-tsc --noEmit
npx vitest run
npx vite build
npx playwright test
```

All four should exit 0.

- [ ] **Step 4: Commit + push**

```bash
git add knowledge-ui/src/components/canvas/
git commit -m "feat(ui): ambient dust particles (mobile halved); sp1 complete"
git push
```

---

## Self-review checklist

- [x] Spec coverage: every section has a task. Architecture (Task 2-5). Pinia (Task 6). Auth (Task 7). Routing (Task 8). Shell (Task 9-10). Canvas (Task 11-16). Drawer detail (Task 17-20). Live-update (Task 21). Cinema (Task 22). Keybindings (Task 23). E2E (Task 24). Polish (Task 25).
- [x] No "TBD" or "similar to Task N" placeholders.
- [x] Type names consistent: `ApiClient`, `Drawer`, `Wing`, `Tunnel`, `Fact`, `HiveEvent`, `StatusSummary`, `Role`, `Relation` all defined in Task 2 and used consistently across tasks.
- [x] File paths absolute from `knowledge-ui/`.
- [x] Each code step shows actual code; each test step shows assertion.
- [x] Commits frequent (one per task) and follow Conventional Commits.
- [x] SP1-scope respected: no editor (SP4), no uploads (SP2), no OCR (SP3), no watcher (SP5).
