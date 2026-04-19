import { describe, it, expect } from 'vitest'
import { lodLevel, drawerVisibleAt } from '../../src/composables/lod'

describe('lod', () => {
  it('< 0.3 -> wings', () => expect(lodLevel(0.2)).toBe('wings'))
  it('0.3..1.0 -> halos', () => expect(lodLevel(0.7)).toBe('halos'))
  it('1.0..3.0 -> labels', () => expect(lodLevel(1.5)).toBe('labels'))
  it('> 3 -> full', () => expect(lodLevel(4)).toBe('full'))
  it('drawerVisibleAt 0.2 false', () => expect(drawerVisibleAt(0.2)).toBe(false))
  it('drawerVisibleAt 0.5 true', () => expect(drawerVisibleAt(0.5)).toBe(true))
})
