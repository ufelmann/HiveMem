import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useQueenStore } from '../../src/stores/queen'

const call = vi.fn()
vi.mock('../../src/api/useApi', () => ({ useApi: () => ({ call }) }))

describe('queen store — approveAll', () => {
  beforeEach(() => { setActivePinia(createPinia()); call.mockReset() })

  it('commits every listed proposal in one call and returns the count', async () => {
    const store = useQueenStore()
    store.pending = [
      { type: 'tunnel', id: 'p-1', title: 'a/b -[related_to]-> c/d', description: 'why',
        realm: 'a', signal: null, from_cell: 'f-1', to_cell: 't-1',
        created_by: 'queen', created_at: '2026-06-02T03:00:13Z' },
      { type: 'cell', id: 'p-2', title: 'a/e', description: 'summary',
        realm: 'a', signal: null, from_cell: null, to_cell: null,
        created_by: 'queen', created_at: '2026-06-02T03:00:14Z' },
    ]
    call.mockImplementation(async (tool: string) => {
      if (tool === 'approve_pending') return { decision: 'committed', count: 2 }
      if (tool === 'queen_runs') return { items: [], total: 0, costAvailable: false }
      if (tool === 'pending_approvals') return []
      if (tool === 'consumption_queue') return { failedFiles: [], degradedBatches: [], stalledRows: [],
        reconciliation: { orphansRestaged: 0, rowsWithoutFile: 0, misplacedFailed: 0 }, stateCounts: {} }
      throw new Error(`unexpected tool: ${tool}`)
    })

    const count = await store.approveAll()

    expect(count).toBe(2)
    const approveCalls = call.mock.calls.filter((c: unknown[]) => c[0] === 'approve_pending')
    // One call, not one per proposal: the backend applies the whole list in one transaction.
    expect(approveCalls).toHaveLength(1)
    expect(approveCalls[0][1]).toEqual({ ids: ['p-1', 'p-2'], decision: 'committed' })
  })

  it('leaves the list untouched when the call fails', async () => {
    const store = useQueenStore()
    store.pending = [
      { type: 'cell', id: 'p-2', title: 'a/e', description: 'summary', realm: 'a', signal: null,
        from_cell: null, to_cell: null, created_by: 'queen', created_at: '2026-06-02T03:00:14Z' },
    ]
    call.mockRejectedValue(new Error('boom'))

    await expect(store.approveAll()).rejects.toThrow()
    // Nothing was committed, so nothing may disappear from the user's list.
    expect(store.pending).toHaveLength(1)
  })
})
