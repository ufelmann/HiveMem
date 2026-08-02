import { setActivePinia, createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useQueenStore } from '../../src/stores/queen'

const call = vi.fn()
vi.mock('../../src/api/useApi', () => ({ useApi: () => ({ call }) }))

// Follows the queenArchivistLog.spec.ts pattern: mock useApi directly rather than routing
// through MockApiClient, so refresh()'s Promise.all shape (queen_runs / pending_approvals /
// consumption_queue) is exercised without depending on mock-client wiring.
function mockRefreshCalls(ingestQueue: unknown) {
  call.mockImplementation(async (tool: string) => {
    if (tool === 'queen_runs') return { items: [], total: 0, costAvailable: false }
    if (tool === 'pending_approvals') return []
    if (tool === 'consumption_queue') return ingestQueue
    throw new Error(`unexpected tool: ${tool}`)
  })
}

describe('queen store — ingest queue', () => {
  beforeEach(() => { setActivePinia(createPinia()); call.mockReset() })

  it('loads the ingest queue alongside runs and pending approvals', async () => {
    mockRefreshCalls({
      failedFiles: [{ sha256: 'aaaa0001', filename: 'scan-0001.pdf', state: 'failed', attempts: 2,
        lastError: 'page count 240 exceeds max-pages 200' }],
      degradedBatches: [],
      reconciliation: { orphansRestaged: 0, rowsWithoutFile: 0, misplacedFailed: 0 },
      stateCounts: { done: 20, failed: 1 },
    })
    const store = useQueenStore()
    await store.refresh()
    expect(store.ingestQueue).not.toBeNull()
    expect(Array.isArray(store.ingestQueue!.failedFiles)).toBe(true)
    expect(store.ingestQueue!.failedFiles[0].filename).toBe('scan-0001.pdf')
  })

  it('marks the unavailable state distinctly from a healthy empty queue', async () => {
    mockRefreshCalls({
      failedFiles: [], degradedBatches: [],
      reconciliation: { orphansRestaged: 0, rowsWithoutFile: 0, misplacedFailed: 0 },
      stateCounts: {}, unavailable: true,
    })
    const store = useQueenStore()
    await store.refresh()
    expect(store.ingestQueue!.unavailable).toBe(true)
    expect(store.ingestQueue!.failedFiles).toEqual([])
  })

  it('a failed retry leaves the entry in place and surfaces the error', async () => {
    mockRefreshCalls({
      failedFiles: [{ sha256: 'aaaa0001', filename: 'scan-0001.pdf', state: 'failed', attempts: 2,
        lastError: 'page count 240 exceeds max-pages 200' }],
      degradedBatches: [],
      reconciliation: { orphansRestaged: 0, rowsWithoutFile: 0, misplacedFailed: 0 },
      stateCounts: { done: 20, failed: 1 },
    })
    const store = useQueenStore()
    await store.refresh()
    const before = store.ingestQueue!.failedFiles.length

    call.mockResolvedValueOnce({ sha256: 'unknown-hash', restaged: false, error: 'unknown sha256' })
    const res = await store.retryIngest('unknown-hash')

    expect(res.restaged).toBe(false)
    expect(res.error).toBe('unknown sha256')
    expect(store.ingestQueue!.failedFiles.length).toBe(before)
  })
})
