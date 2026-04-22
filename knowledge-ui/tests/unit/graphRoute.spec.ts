import { describe, it, expect, vi } from 'vitest'
import { nextTick } from 'vue'
import { shallowMount } from '@vue/test-utils'
import GraphRoute from '../../src/pages/GraphRoute.vue'

const loadTopLevel = vi.fn()

vi.mock('../../src/stores/canvas', () => ({
  useCanvasStore: () => ({
    loaded: false,
    loadTopLevel
  })
}))

vi.mock('../../src/composables/keybindings', () => ({
  useKeybindings: vi.fn()
}))

describe('graph route', () => {
  it('registers a /graph route', async () => {
    const { router } = await import('../../src/router')
    const match = router.resolve('/graph')
    expect(match.name).toBe('graph')
  })

  it('renders the graph skeleton and triggers the initial load', async () => {
    const wrapper = shallowMount(GraphRoute)
    await nextTick()

    expect(wrapper.find('main.graph-slot').text()).toBe('Graph view placeholder')
    expect(loadTopLevel).toHaveBeenCalledTimes(1)
  })
})
