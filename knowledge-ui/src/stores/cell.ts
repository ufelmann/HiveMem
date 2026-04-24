import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { Cell, Fact, Tunnel } from '../api/types'

export const useCellStore = defineStore('cell', {
  state: () => ({
    cache: new Map<string, { cell: Cell; facts: Fact[]; tunnels: Tunnel[] }>(),
    currentId: null as string | null,
    loading: false
  }),
  getters: {
    current(s): { cell: Cell; facts: Fact[]; tunnels: Tunnel[] } | null {
      return s.currentId ? s.cache.get(s.currentId) ?? null : null
    }
  },
  actions: {
    async load(id: string) {
      this.loading = true
      try {
        if (!this.cache.has(id)) {
          const api = useApi()
          const [cell, tunnels] = await Promise.all([
            api.call<Cell>('get_cell', { cell_id: id }),
            api.call<Tunnel[]>('traverse', { cell_id: id, depth: 1 }).catch(() => [])
          ])
          const facts = await api.call<Fact[]>('quick_facts', { subject: cell.title }).catch(() => [])
          this.cache.set(id, { cell, facts, tunnels })
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
