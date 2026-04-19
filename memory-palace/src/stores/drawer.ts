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
