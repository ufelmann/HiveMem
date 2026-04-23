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
    hoveredId: null as string | null,
    streamActive: false,
    _streamAbort: false,
  }),
  actions: {
    async loadTopLevel() {
      const api = useApi()
      const rows = await api.call<Array<{ value: string; label?: string; cell_count: number }>>('hivemem_list')
      this.realms = rows.map(r => ({ name: r.value, cell_count: r.cell_count, signals: [] }))
      this.cells = []
      this.tunnels = []
      this.loaded = true
      this._streamAbort = true
      this.streamActive = false
    },
    async _longPoll() {
      // Streaming endpoint is not implemented server-side; top-level load is static.
      this._streamAbort = true
      this.streamActive = false
    },
    stopStream() { this._streamAbort = true },
    setFocus(id: string | null) { this.focusedId = id },
    setHover(id: string | null) { this.hoveredId = id }
  }
})
