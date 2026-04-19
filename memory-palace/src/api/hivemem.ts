// Legacy facade retained so existing hive/*.vue prototype files keep importing
// familiar names. Task 5 will replace this with the useApi composable, and
// Task 22 will rewire hive/*.vue to the new MockApiClient / HttpApiClient.
import { palace } from '../data/mock'
import type { Drawer } from './types'

export interface HivememApi {
  getPalace(): Promise<typeof palace>
  getDrawer(id: string): Promise<Drawer>
  search(query: string, limit?: number): Promise<Drawer[]>
}

export function createMockClient(): HivememApi {
  return {
    async getPalace() { return palace },
    async getDrawer(id: string) {
      const drawer = palace.drawers.find((d) => d.id === id)
      if (!drawer) throw new Error(`Drawer not found: ${id}`)
      return drawer
    },
    async search(query: string, limit = 20) {
      const q = query.toLowerCase()
      return palace.drawers
        .filter((d) => d.title.toLowerCase().includes(q) || (d.summary ?? '').toLowerCase().includes(q))
        .slice(0, limit)
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
