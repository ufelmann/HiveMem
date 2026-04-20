import { describe, it, expect } from 'vitest'
import { lodLevel, cellVisibleAt } from '../../src/composables/lod'

describe('lod', () => {
  it('< 0.3 -> realms', () => expect(lodLevel(0.2)).toBe('realms'))
  it('0.3..1.0 -> halos', () => expect(lodLevel(0.7)).toBe('halos'))
  it('1.0..3.0 -> labels', () => expect(lodLevel(1.5)).toBe('labels'))
  it('> 3 -> full', () => expect(lodLevel(4)).toBe('full'))
  it('cellVisibleAt 0.2 false', () => expect(cellVisibleAt(0.2)).toBe(false))
  it('cellVisibleAt 0.5 true', () => expect(cellVisibleAt(0.5)).toBe(true))
})
