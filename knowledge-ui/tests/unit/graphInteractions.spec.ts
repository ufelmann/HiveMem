import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRoot } from 'react-dom/client'
import ForceGraphBridge from '../../src/components/graph/ForceGraphBridge.vue'
import ScanPanel from '../../src/components/ScanPanel.vue'
import { useKeybindings } from '../../src/composables/keybindings'
import { useCanvasStore } from '../../src/stores/canvas'
import { useCellStore } from '../../src/stores/cell'
import { useReaderStore } from '../../src/stores/reader'
import { useUiStore } from '../../src/stores/ui'

const reactRoot = {
  render: vi.fn(),
  unmount: vi.fn()
}

const resizeObserverState = {
  callback: null as ResizeObserverCallback | null,
  disconnect: vi.fn(),
  observe: vi.fn((target: Element) => {
    resizeObserverState.callback?.([
      {
        target,
        contentRect: {
          width: 640,
          height: 480
        }
      } as ResizeObserverEntry
    ], {} as ResizeObserver)
  })
}

vi.mock('react-dom/client', () => ({
  createRoot: vi.fn(() => reactRoot)
}))

class ResizeObserverMock {
  constructor(callback: ResizeObserverCallback) {
    resizeObserverState.callback = callback
  }

  observe = resizeObserverState.observe
  disconnect = resizeObserverState.disconnect
}

Object.defineProperty(globalThis, 'ResizeObserver', {
  value: ResizeObserverMock,
  configurable: true,
  writable: true
})

const TestKeybindingsHost = defineComponent({
  setup() {
    useKeybindings()
    return () => null
  }
})

function makeCell(id: string, title = 'Alpha') {
  return {
    id,
    title,
    realm: 'ops',
    signal: null,
    topic: null,
    importance: 3
  }
}

describe('graph interactions', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()

    const canvas = useCanvasStore()
    const cell = useCellStore()
    const reader = useReaderStore()
    const ui = useUiStore()

    canvas.cells = []
    canvas.tunnels = []
    canvas.setFocus(null)
    canvas.setHover(null)
    cell.cache.clear()
    cell.currentId = null
    cell.loading = false
    reader.$reset()
    ui.$reset()
  })

  it('wires bridge hover and click through shared interaction state', async () => {
    const canvas = useCanvasStore()
    const cell = useCellStore()
    cell.load = vi.fn().mockResolvedValue(undefined)

    canvas.cells = [makeCell('cell-1'), makeCell('cell-2', 'Beta')] as any
    canvas.tunnels = [
      {
        id: 'tunnel-1',
        from_cell: 'cell-1',
        to_cell: 'cell-2',
        relation: 'related_to'
      }
    ] as any

    const wrapper = mount(ForceGraphBridge)
    await nextTick()

    expect(createRoot).toHaveBeenCalledTimes(1)

    const initialReactElement = reactRoot.render.mock.calls.at(-1)?.[0]
    expect(initialReactElement?.props.focusedId).toBe(null)
    expect(initialReactElement?.props.hoveredId).toBe(null)

    initialReactElement.props.onNodeHover('cell-1')
    await nextTick()

    expect(canvas.hoveredId).toBe('cell-1')

    const hoveredReactElement = reactRoot.render.mock.calls.at(-1)?.[0]
    const hoveredForceGraphElement = hoveredReactElement.type(hoveredReactElement.props)
    const hoveredCtx = {
      beginPath: vi.fn(),
      arc: vi.fn(),
      fill: vi.fn(),
      fillStyle: ''
    }

    hoveredForceGraphElement.props.nodeCanvasObject({
      id: 'cell-1',
      x: 10,
      y: 20,
      color: '#123456'
    }, hoveredCtx, 1)

    expect(hoveredReactElement.props.hoveredId).toBe('cell-1')
    expect(hoveredCtx.arc).toHaveBeenCalledWith(10, 20, 6, 0, 2 * Math.PI)

    hoveredReactElement.props.onNodeClick('cell-1')
    await nextTick()

    expect(canvas.focusedId).toBe('cell-1')
    expect(cell.load).toHaveBeenCalledWith('cell-1')

    const focusedReactElement = reactRoot.render.mock.calls.at(-1)?.[0]
    const focusedForceGraphElement = focusedReactElement.type(focusedReactElement.props)
    const focusedCtx = {
      beginPath: vi.fn(),
      arc: vi.fn(),
      fill: vi.fn(),
      fillStyle: ''
    }

    focusedForceGraphElement.props.nodeCanvasObject({
      id: 'cell-1',
      x: 10,
      y: 20,
      color: '#123456'
    }, focusedCtx, 1)

    expect(focusedReactElement.props.focusedId).toBe('cell-1')
    expect(focusedCtx.arc).toHaveBeenCalledWith(10, 20, 8, 0, 2 * Math.PI)

    wrapper.unmount()
    expect(reactRoot.unmount).toHaveBeenCalledTimes(1)
    expect(resizeObserverState.disconnect).toHaveBeenCalledTimes(1)
  })

  it('scan panel close clears cell detail and shared hover/focus state', async () => {
    const canvas = useCanvasStore()
    const cell = useCellStore()

    cell.cache.set('cell-1', {
      cell: makeCell('cell-1'),
      facts: [],
      tunnels: []
    } as any)
    cell.currentId = 'cell-1'
    canvas.setFocus('cell-1')
    canvas.setHover('cell-2')

    const wrapper = mount(ScanPanel, {
      global: {
        stubs: {
          'v-btn': defineComponent({
            emits: ['click'],
            template: '<button data-testid="close" @click="$emit(\'click\')" />'
          }),
          'v-chip': true
        }
      }
    })
    await nextTick()

    expect(wrapper.find('aside.scan').exists()).toBe(true)

    await wrapper.get('[data-testid="close"]').trigger('click')

    expect(cell.currentId).toBe(null)
    expect(canvas.focusedId).toBe(null)
    expect(canvas.hoveredId).toBe(null)
  })

  it('Escape clears cell detail and shared graph interaction state', async () => {
    const canvas = useCanvasStore()
    const cell = useCellStore()

    cell.cache.set('cell-1', {
      cell: makeCell('cell-1'),
      facts: [],
      tunnels: []
    } as any)
    cell.currentId = 'cell-1'
    canvas.setFocus('cell-1')
    canvas.setHover('cell-2')

    const wrapper = mount(TestKeybindingsHost)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await nextTick()

    expect(cell.currentId).toBe(null)
    expect(canvas.focusedId).toBe(null)
    expect(canvas.hoveredId).toBe(null)

    wrapper.unmount()
  })
})
