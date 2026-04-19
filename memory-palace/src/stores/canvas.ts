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
