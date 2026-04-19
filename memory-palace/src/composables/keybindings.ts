import { useEventListener } from '@vueuse/core'
import { useUiStore } from '../stores/ui'
import { useReaderStore } from '../stores/reader'
import { useDrawerStore } from '../stores/drawer'

export function useKeybindings() {
  const ui = useUiStore(), reader = useReaderStore(), drawer = useDrawerStore()
  useEventListener('keydown', (e: KeyboardEvent) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault(); ui.activePanel = 'search'
    } else if (e.key === 'Escape') {
      if (reader.open) reader.close()
      else if (drawer.currentId) drawer.clear()
      else if (ui.activePanel) ui.activePanel = null
    } else if (e.key === 'Enter' && drawer.currentId && !reader.open) {
      reader.openReader(drawer.currentId)
    } else if (e.key === '?' && !ui.showLoginDialog) {
      ui.pushToast('info', 'Cmd+K search · Esc back · Enter reader')
    }
  })
}
