import { defineStore } from 'pinia'
import type { Drawer, NavigationLevel, Palace } from '../types/palace'
import { api } from '../api/hivemem'
import { mockDrawersById, mockPalace } from '../data/mock'

interface BreadcrumbItem {
  title: string
  level: NavigationLevel
  payload?: { wing?: string; hall?: string; room?: string; drawerId?: string }
}

interface State {
  level: NavigationLevel
  currentWing: string | null
  currentHall: string | null
  currentRoom: string | null
  selectedDrawerId: string | null
  focusedSheet: 0 | 1 | 2 | null
  palace: Palace
  drawersById: Record<string, Drawer>
  isTransitioning: boolean
  loaded: boolean
}

export const useNavigationStore = defineStore('navigation', {
  state: (): State => ({
    level: 'building',
    currentWing: null,
    currentHall: null,
    currentRoom: null,
    selectedDrawerId: null,
    focusedSheet: null,
    palace: { wings: [] },
    drawersById: {},
    isTransitioning: false,
    loaded: false,
  }),
  getters: {
    wingObj(): any {
      return this.palace.wings.find((w) => w.name === this.currentWing) ?? null
    },
    hallObj(): any {
      const w = this.wingObj
      return w ? w.halls.find((h: any) => h.name === this.currentHall) ?? null : null
    },
    roomObj(): any {
      const h = this.hallObj
      return h ? h.rooms.find((r: any) => r.name === this.currentRoom) ?? null : null
    },
    selectedDrawer(): Drawer | null {
      return this.selectedDrawerId ? this.drawersById[this.selectedDrawerId] ?? null : null
    },
    visibleDrawersInRoom(): Drawer[] {
      return this.roomObj?.drawers ?? []
    },
    breadcrumbItems(): BreadcrumbItem[] {
      const items: BreadcrumbItem[] = [{ title: 'Palace', level: 'building' }]
      if (this.currentWing) items.push({ title: this.currentWing, level: 'corridor', payload: { wing: this.currentWing } })
      if (this.currentHall) items.push({ title: this.currentHall, level: 'corridor', payload: { wing: this.currentWing!, hall: this.currentHall } })
      if (this.currentRoom) items.push({ title: this.currentRoom, level: 'room', payload: { wing: this.currentWing!, hall: this.currentHall!, room: this.currentRoom } })
      if (this.selectedDrawer) items.push({ title: this.selectedDrawer.title, level: 'drawer', payload: { drawerId: this.selectedDrawer.id } })
      return items
    },
  },
  actions: {
    async loadPalace() {
      try {
        this.palace = await api.getPalace()
      } catch (err) {
        console.warn('[palace] API failed, falling back to mock', err)
        this.palace = structuredClone(mockPalace)
      }
      this.drawersById = {}
      for (const w of this.palace.wings)
        for (const h of w.halls)
          for (const r of h.rooms)
            for (const d of r.drawers) this.drawersById[d.id] = d
      if (Object.keys(this.drawersById).length === 0) this.drawersById = structuredClone(mockDrawersById)
      this.loaded = true
    },
    enterWing(wing: string) {
      this.currentWing = wing
      const w = this.palace.wings.find((x) => x.name === wing)
      this.currentHall = w?.halls[0]?.name ?? null
      this.currentRoom = null
      this.selectedDrawerId = null
      this.focusedSheet = null
      this.level = 'corridor'
    },
    enterHall(hall: string) {
      this.currentHall = hall
      this.currentRoom = null
      this.selectedDrawerId = null
      this.focusedSheet = null
      this.level = 'corridor'
    },
    enterRoom(room: string) {
      this.currentRoom = room
      this.selectedDrawerId = null
      this.focusedSheet = null
      this.level = 'room'
    },
    selectDrawer(id: string) {
      this.selectedDrawerId = id
      this.focusedSheet = null
      this.level = 'drawer'
    },
    closeDrawer() {
      this.selectedDrawerId = null
      this.focusedSheet = null
      this.level = 'room'
    },
    goToTunnelTarget(drawerId: string) {
      const target = this.drawersById[drawerId]
      if (!target) return
      this.currentWing = target.wing
      this.currentHall = target.hall
      this.currentRoom = target.room
      this.selectedDrawerId = target.id
      this.focusedSheet = null
      this.level = 'drawer'
    },
    goBack() {
      if (this.level === 'drawer') {
        if (this.focusedSheet !== null) { this.focusedSheet = null; return }
        this.level = 'room'; this.selectedDrawerId = null; return
      }
      if (this.level === 'room') { this.level = 'corridor'; this.currentRoom = null; return }
      if (this.level === 'corridor') {
        this.level = 'building'
        this.currentWing = null
        this.currentHall = null
      }
    },
    focusSheet(idx: 0 | 1 | 2) { this.focusedSheet = idx },
    unfocusSheet() { this.focusedSheet = null },
    goToLevel(item: BreadcrumbItem) {
      switch (item.level) {
        case 'building':
          this.level = 'building'
          this.currentWing = null
          this.currentHall = null
          this.currentRoom = null
          this.selectedDrawerId = null
          break
        case 'corridor':
          if (item.payload?.wing) this.currentWing = item.payload.wing
          if (item.payload?.hall) this.currentHall = item.payload.hall
          else this.currentHall = this.wingObj?.halls[0]?.name ?? null
          this.currentRoom = null
          this.selectedDrawerId = null
          this.level = 'corridor'
          break
        case 'room':
          if (item.payload?.room) this.currentRoom = item.payload.room
          this.selectedDrawerId = null
          this.level = 'room'
          break
        case 'drawer':
          if (item.payload?.drawerId) this.selectedDrawerId = item.payload.drawerId
          this.level = 'drawer'
          break
      }
    },
    setTransitioning(flag: boolean) { this.isTransitioning = flag },
  },
})
