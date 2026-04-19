import type { Drawer, Palace } from '../types/palace'
import { mockDrawersById, mockPalace } from '../data/mock'

export interface HivememApi {
  getPalace(): Promise<Palace>
  getDrawer(id: string): Promise<Drawer>
  search(query: string, limit?: number): Promise<Drawer[]>
}

export function createMockClient(): HivememApi {
  return {
    async getPalace() { return structuredClone(mockPalace) },
    async getDrawer(id: string) {
      const drawer = mockDrawersById[id]
      if (!drawer) throw new Error(`Drawer not found: ${id}`)
      return structuredClone(drawer)
    },
    async search(query: string, limit = 20) {
      const q = query.toLowerCase()
      return Object.values(mockDrawersById)
        .filter((d) => d.title.toLowerCase().includes(q) || d.summary.toLowerCase().includes(q))
        .slice(0, limit)
        .map((d) => structuredClone(d))
    },
  }
}

export function createHttpClient(_url: string, _token: string): HivememApi {
  throw new Error('HTTP client is deferred to Phase 2')
}

function selectClient(): HivememApi {
  const url = import.meta.env.VITE_HIVEMEM_URL
  const token = import.meta.env.VITE_HIVEMEM_TOKEN
  if (url && token) {
    console.warn('[hivemem] HTTP client not implemented in Phase 1, using mock')
  }
  return createMockClient()
}

export const api: HivememApi = selectClient()
