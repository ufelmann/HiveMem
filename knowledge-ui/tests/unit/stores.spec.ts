import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../../src/stores/auth'
import { useUiStore } from '../../src/stores/ui'
import { useCanvasStore } from '../../src/stores/canvas'

describe('stores', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('auth starts logged out', () => {
    const s = useAuthStore()
    expect(s.isAuthenticated).toBe(false)
    expect(s.role).toBe(null)
  })

  it('ui defaults: dark theme, no panel open, size metric = count', () => {
    const s = useUiStore()
    expect(s.activePanel).toBe(null)
    expect(s.sizeMetric).toBe('cell_count')
    expect(s.theme).toBe('dark')
  })

  it('canvas loadTopLevel populates realms + cells from api', async () => {
    localStorage.setItem('hivemem_mock', 'true')
    const s = useCanvasStore()
    await s.loadTopLevel()
    expect(s.realms.length).toBeGreaterThan(0)
    expect(s.cells.length).toBeGreaterThan(0)
  })
})
