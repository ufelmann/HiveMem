import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { Cell, Realm, Tunnel } from '../api/types'

export const useCanvasStore = defineStore('canvas', {
  state: () => ({
    realms: [] as Realm[],
    cells: [] as Cell[],
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
      const [realms, cells] = await Promise.all([
        api.call<Realm[]>('hivemem_list_realms'),
        api.call<Cell[]>('hivemem_search', { query: '', limit: 500 })
      ])
      this.realms = realms; this.cells = cells; this.loaded = true
      try { this.tunnels = await api.call<Tunnel[]>('hivemem_list_tunnels') }
      catch { this.tunnels = [] }
    },
    setFocus(id: string | null) { this.focusedId = id },
    setHover(id: string | null) { this.hoveredId = id }
  }
})
