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
  focusedDrawerId: string | null
  currentCardIndex: number
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
    focusedDrawerId: null,
    currentCardIndex: 0,
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
      if (this.currentWing) items.push({ title: this.currentWing, level: 'wing', payload: { wing: this.currentWing } })
      if (this.currentHall) items.push({ title: this.currentHall, level: 'hall', payload: { wing: this.currentWing!, hall: this.currentHall } })
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
      this.currentHall = null
      this.currentRoom = null
      this.selectedDrawerId = null
      this.level = 'wing'
    },
    enterHall(hall: string) {
      this.currentHall = hall
      this.currentRoom = null
      this.selectedDrawerId = null
      this.level = 'hall'
    },
    enterRoom(room: string) {
      this.currentRoom = room
      this.selectedDrawerId = null
      this.level = 'room'
    },
    selectDrawer(id: string) {
      this.selectedDrawerId = id
      this.focusedDrawerId = id
      this.currentCardIndex = 0
      this.level = 'drawer'
    },
    closeDrawer() {
      this.selectedDrawerId = null
      this.focusedDrawerId = null
      this.currentCardIndex = 0
      this.level = 'room'
    },
    goToTunnelTarget(drawerId: string) {
      const target = this.drawersById[drawerId]
      if (!target) return
      this.currentWing = target.wing
      this.currentHall = target.hall
      this.currentRoom = target.room
      this.selectedDrawerId = target.id
      this.focusedDrawerId = target.id
      this.currentCardIndex = 0
      this.level = 'drawer'
    },
    goBack() {
      if (this.level === 'drawer') {
        if (this.currentCardIndex > 0) { this.currentCardIndex--; return }
        this.level = 'room'
        this.selectedDrawerId = null
        this.focusedDrawerId = null
        return
      }
      if (this.level === 'room') { this.level = 'hall'; this.currentRoom = null; return }
      if (this.level === 'hall') { this.level = 'wing'; this.currentHall = null; return }
      if (this.level === 'wing') {
        this.level = 'building'
        this.currentWing = null
      }
    },
    setCurrentCard(idx: number) {
      this.currentCardIndex = Math.max(0, Math.min(5, idx))
    },
    nextCard() {
      if (this.currentCardIndex < 5) this.currentCardIndex++
    },
    prevCard() {
      if (this.currentCardIndex > 0) this.currentCardIndex--
    },
    goToLevel(item: BreadcrumbItem) {
      this.currentCardIndex = 0
      if (item.level !== 'drawer') this.focusedDrawerId = null
      switch (item.level) {
        case 'building':
          this.level = 'building'
          this.currentWing = null
          this.currentHall = null
          this.currentRoom = null
          this.selectedDrawerId = null
          break
        case 'wing':
          if (item.payload?.wing) this.currentWing = item.payload.wing
          this.currentHall = null
          this.currentRoom = null
          this.selectedDrawerId = null
          this.level = 'wing'
          break
        case 'hall':
          if (item.payload?.wing) this.currentWing = item.payload.wing
          if (item.payload?.hall) this.currentHall = item.payload.hall
          this.currentRoom = null
          this.selectedDrawerId = null
          this.level = 'hall'
          break
        case 'room':
          if (item.payload?.room) this.currentRoom = item.payload.room
          this.selectedDrawerId = null
          this.level = 'room'
          break
        case 'drawer':
          if (item.payload?.drawerId) {
            this.selectedDrawerId = item.payload.drawerId
            this.focusedDrawerId = item.payload.drawerId
          }
          this.level = 'drawer'
          break
      }
    },
    setTransitioning(flag: boolean) { this.isTransitioning = flag },
  },
})
