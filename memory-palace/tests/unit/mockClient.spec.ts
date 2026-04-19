import { describe, it, expect } from 'vitest'
import { MockApiClient } from '../../src/api/mockClient'
import type { StatusSummary, Drawer } from '../../src/api/types'

describe('MockApiClient', () => {
  it('returns deterministic status', async () => {
    const c = new MockApiClient()
    const s = await c.call<StatusSummary>('hivemem_status')
    expect(s.drawer_count).toBeGreaterThan(0)
    expect(s.wing_count).toBeGreaterThan(0)
    expect(typeof s.last_activity).toBe('string')
  })

  it('search returns drawers array', async () => {
    const c = new MockApiClient()
    const res = await c.call<Drawer[]>('hivemem_search', { query: '' })
    expect(Array.isArray(res)).toBe(true)
    expect(res.length).toBeGreaterThan(0)
  })

  it('get_drawer returns drawer with matching id', async () => {
    const c = new MockApiClient()
    const all = await c.call<Drawer[]>('hivemem_search', { query: '' })
    const d = await c.call<Drawer>('hivemem_get_drawer', { id: all[0].id })
    expect(d.id).toBe(all[0].id)
  })

  it('subscribe returns unsubscribe function and emits events', async () => {
    const c = new MockApiClient({ eventInterval: 10 })
    const events: string[] = []
    const unsub = c.subscribe(e => events.push(e.type))
    await new Promise(r => setTimeout(r, 50))
    unsub()
    expect(events.length).toBeGreaterThan(0)
  })
})
