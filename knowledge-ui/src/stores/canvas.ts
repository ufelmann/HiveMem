import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { Cell, Realm, Tunnel } from '../api/types'

interface StreamResponse { cells: Cell[]; tunnels: Tunnel[]; done: boolean }

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
      this.realms = await api.call<Realm[]>('list')
      this.cells = []
      this.tunnels = []
      this.loaded = true
      this._streamAbort = false
      void this._longPoll()
    },
    async _longPoll() {
      const api = useApi()
      this.streamActive = true
      try {
        while (!this._streamAbort) {
          let resp: StreamResponse
          try {
            resp = await api.call<StreamResponse>('hivemem_stream_next', { timeout_ms: 25000 })
          } catch {
            // Transient error — back off briefly and retry (long-poll recovery).
            await new Promise(r => setTimeout(r, 2000))
            continue
          }
          if (resp.cells?.length) this.cells = [...this.cells, ...resp.cells]
          if (resp.tunnels?.length) this.tunnels = [...this.tunnels, ...resp.tunnels]
          if (resp.done) break
        }
      } finally {
        this.streamActive = false
      }
    },
    stopStream() { this._streamAbort = true },
    setFocus(id: string | null) { this.focusedId = id },
    setHover(id: string | null) { this.hoveredId = id }
  }
})
