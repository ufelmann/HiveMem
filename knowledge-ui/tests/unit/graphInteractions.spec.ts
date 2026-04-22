import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCanvasStore } from '../../src/stores/canvas'
import { useCellStore } from '../../src/stores/cell'

describe('graph interactions', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('hover and click update shared stores', async () => {
    const canvas = useCanvasStore()
    const cell = useCellStore()
    cell.load = vi.fn().mockResolvedValue(undefined)

    canvas.setHover('node-1')
    canvas.setFocus('node-1')
    await cell.load('node-1')

    expect(canvas.hoveredId).toBe('node-1')
    expect(canvas.focusedId).toBe('node-1')
    expect(cell.load).toHaveBeenCalledWith('node-1')
  })
})
