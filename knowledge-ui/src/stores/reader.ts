import { defineStore } from 'pinia'

export type ReaderTab = 'markdown' | string

export const useReaderStore = defineStore('reader', {
  state: () => ({
    open: false,
    drawerId: null as string | null,
    activeTab: 'markdown' as ReaderTab,
    pdfPage: 1,
    pdfZoom: 1
  }),
  actions: {
    openReader(drawerId: string) { this.drawerId = drawerId; this.open = true; this.activeTab = 'markdown' },
    close() { this.open = false }
  }
})
