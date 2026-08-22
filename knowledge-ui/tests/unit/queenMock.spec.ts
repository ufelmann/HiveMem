import { describe, expect, it } from 'vitest'
import { MockApiClient } from '../../src/api/mockClient'
import type { QueenRunList, QueenRunDetail, PendingApproval, IngestQueue } from '../../src/api/types'

describe('MockApiClient queen tools', () => {
  it('returns a run list with costAvailable flag', async () => {
    const api = new MockApiClient({ latencyMs: [0, 0] })
    const out = await api.call<QueenRunList>('queen_runs')
    expect(out.items.length).toBeGreaterThan(0)
    expect(typeof out.costAvailable).toBe('boolean')
    expect(out.items[0].id).toBeTruthy()
  })

  it('returns run detail with an event timeline', async () => {
    const api = new MockApiClient({ latencyMs: [0, 0] })
    const list = await api.call<QueenRunList>('queen_runs')
    const detail = await api.call<QueenRunDetail>('queen_run_detail', { run_id: list.items[0].id })
    expect(Array.isArray(detail.events)).toBe(true)
    expect(detail.run).toBeTruthy()
  })

  it('returns pending queen proposals with a title, a rationale and endpoint ids', async () => {
    const api = new MockApiClient({ latencyMs: [0, 0] })
    const pending = await api.call<PendingApproval[]>('pending_approvals')
    expect(pending.some(p => p.created_by === 'queen')).toBe(true)
    const tunnel = pending.find(p => p.type === 'tunnel')!
    expect(tunnel.title).toBeTruthy()
    expect(tunnel.description).toBeTruthy()
    expect(tunnel.from_cell).toBeTruthy()
    expect(tunnel.to_cell).toBeTruthy()
  })

  it('returns an ingest queue with the real reconciliation field names', async () => {
    const api = new MockApiClient({ latencyMs: [0, 0] })
    const queue = await api.call<IngestQueue>('consumption_queue')
    expect(Array.isArray(queue.failedFiles)).toBe(true)
    expect(Array.isArray(queue.degradedBatches)).toBe(true)
    expect(queue.reconciliation).toEqual({ orphansRestaged: 0, rowsWithoutFile: 0, misplacedFailed: 0 })
    expect(queue.failedFiles[0].filename).toBe('scan-0001.pdf')
  })

  it('returns restaged:false with an error for consumption_retry against an unknown hash', async () => {
    const api = new MockApiClient({ latencyMs: [0, 0] })
    const res = await api.call<{ sha256: string; restaged: boolean; error?: string }>(
      'consumption_retry', { sha256: 'unknown-hash' })
    expect(res.restaged).toBe(false)
    expect(res.error).toBeTruthy()
  })
})
