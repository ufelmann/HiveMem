import { defineStore } from 'pinia'

export type PanelId = null | 'search' | 'wings' | 'reading' | 'stats' | 'history' | 'settings'
export type SizeMetric = 'drawer_count' | 'content_volume' | 'importance' | 'popularity'

export const useUiStore = defineStore('ui', {
  state: () => ({
    activePanel: null as PanelId,
    panelPinned: false,
    sizeMetric: 'drawer_count' as SizeMetric,
    theme: 'dark' as 'dark' | 'light',
    searchQuery: '',
    showLoginDialog: false,
    toast: null as null | { kind: 'info' | 'success' | 'error'; text: string }
  }),
  actions: {
    togglePanel(id: Exclude<PanelId, null>) {
      this.activePanel = this.activePanel === id ? null : id
    },
    pushToast(kind: 'info' | 'success' | 'error', text: string) {
      this.toast = { kind, text }
    }
  }
})
