import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { QueenRun, QueenRunList, QueenRunDetail, PendingApproval, ArchivistLogEntry, IngestQueue } from '../api/types'

export const useQueenStore = defineStore('queen', {
  state: () => ({
    runs: [] as QueenRun[],
    total: 0,
    costAvailable: false,
    unavailable: false,
    selectedRun: null as QueenRunDetail | null,
    pending: [] as PendingApproval[],
    archivistLog: [] as ArchivistLogEntry[],
    ingestQueue: null as IngestQueue | null,
    loading: false,
    // Monotonic token: only the latest selectRun() may commit selectedRun, so a
    // slower earlier queen_run_detail response can't overwrite a later selection
    // (mirrors cell.ts/scans.ts's loadSeq guard).
    selectSeq: 0,
  }),
  actions: {
    async refresh() {
      this.loading = true
      try {
        const api = useApi()
        const [list, pending, ingestQueue] = await Promise.all([
          api.call<QueenRunList>('queen_runs'),
          api.call<PendingApproval[]>('pending_approvals'),
          api.call<IngestQueue>('consumption_queue'),
        ])
        this.runs = list.items
        this.total = list.total
        this.costAvailable = list.costAvailable
        this.unavailable = !!list.unavailable
        this.pending = pending.filter(p => p.created_by === 'queen')
        this.ingestQueue = ingestQueue
      } finally {
        this.loading = false
      }
    },
    async selectRun(runId: string) {
      const seq = ++this.selectSeq
      const detail = await useApi().call<QueenRunDetail>('queen_run_detail', { run_id: runId })
      if (seq !== this.selectSeq) return // stale — a newer selectRun() owns the state
      this.selectedRun = detail
    },
    async loadArchivistLog() {
      const res = await useApi().call<{ entries: ArchivistLogEntry[] }>('archivist_log')
      this.archivistLog = res.entries
    },
    async approve(id: string, approved: boolean) {
      // Backend `approve_pending` expects a UUID list + a decision enum, not {id, approved}.
      await useApi().call('approve_pending', { ids: [id], decision: approved ? 'committed' : 'rejected' })
      this.pending = this.pending.filter(p => p.id !== id)
    },
    // Returns the full result (not just the boolean) so the caller can surface *why* a retry
    // did not work — consumption_retry answers `restaged: false` with an error for an unknown
    // hash, a file that no longer exists, or a disabled pipeline, and a retry button that looks
    // like it worked when it did not is exactly the failure class this queue exists to catch.
    async retryIngest(sha256: string) {
      const api = useApi()
      const res = await api.call<{ sha256: string; restaged: boolean; error?: string }>(
        'consumption_retry', { sha256 })
      if (res.restaged) await this.refresh()
      return res
    },
  },
})
