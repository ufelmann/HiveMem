import { describe, it, expect, vi } from 'vitest'
import { defineComponent, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import GraphRoute from '../../src/pages/GraphRoute.vue'

const loadTopLevel = vi.fn()
const loadCell = vi.fn()
const setHover = vi.fn()
const setFocus = vi.fn()
const canvasState = {
  cells: [],
  loaded: false,
  loadTopLevel,
  setFocus,
  setHover,
  tunnels: []
}

vi.mock('../../src/components/graph/ForceGraphBridge.vue', () => ({
  default: defineComponent({
    name: 'ForceGraphBridge',
    template: '<div data-testid="force-graph-bridge" class="graph-bridge-stub" />'
  })
}))

vi.mock('../../src/stores/ui', () => ({
  useUiStore: () => ({
    activePanel: 'search',
    sizeMetric: 'cell_count',
    theme: 'dark'
  })
}))

vi.mock('../../src/stores/canvas', () => ({
  useCanvasStore: () => canvasState
}))

vi.mock('../../src/stores/cell', () => ({
  useCellStore: () => ({
    load: loadCell
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
    canvasState.loaded = false
    canvasState.cells = []
    canvasState.tunnels = []
    loadTopLevel.mockClear()

    const wrapper = mount(GraphRoute, {
      global: {
        stubs: {
          IconRail: true,
          SearchPanel: true,
          RealmsPanel: true,
          SettingsPanel: true,
          ScanPanel: true,
          Reader: true,
          'v-btn': true
        }
      }
    })
    await nextTick()

    expect(wrapper.find('aside.panel').exists()).toBe(true)
    expect(wrapper.find('header strong').text()).toBe('Search')
    expect(wrapper.find('main.graph-slot').text()).toBe('Loading…')
    expect(loadTopLevel).toHaveBeenCalledTimes(1)
  })

  it('renders a graph mount surface', () => {
    canvasState.loaded = true
    canvasState.cells = []
    canvasState.tunnels = []

    const wrapper = mount(GraphRoute, {
      global: {
        stubs: {
          IconRail: true,
          SearchPanel: true,
          RealmsPanel: true,
          SettingsPanel: true,
          ScanPanel: true,
          Reader: true,
          'v-btn': true
        }
      }
    })

    expect(wrapper.find('[data-testid="force-graph-bridge"]').exists()).toBe(true)
  })
})
