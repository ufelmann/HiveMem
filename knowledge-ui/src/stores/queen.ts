import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { QueenRun, QueenRunList, QueenRunDetail, PendingApproval, ArchivistLogEntry, IngestQueue } from '../api/types'

// Same shape ConsumptionQueueToolHandler.unavailable() returns when the consumption pipeline is
// disabled: empty collections, zero counters, unavailable:true. Reused here as the fallback when
// the consumption_queue call itself throws (backend restart, network blip, ...), so a runtime
// failure on the ingest queue renders as its own "unavailable" section instead of taking the
// whole refresh() down with it.
function unavailableIngestQueue(): IngestQueue {
  return {
    failedFiles: [],
    degradedBatches: [],
    stalledRows: [],
    reconciliation: { orphansRestaged: 0, rowsWithoutFile: 0, misplacedFailed: 0 },
    stateCounts: {},
    unavailable: true,
  }
}

/** Marker for a section whose call rejected, so refresh() can tell "loaded nothing" from
 *  "could not load" without letting the first failure abandon the other two sections. */
const FAILED = Symbol('failed')
function tolerate<T>(p: Promise<T>): Promise<T | typeof FAILED> {
  return p.catch(() => FAILED)
}

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
        // Every section is tolerated individually, in BOTH directions: a Vistierie outage
        // (queen_runs) must not blank the ingest queue, and a consumption_queue error must not
        // blank the runs. Awaiting one call before assigning another's result — which is what
        // the previous `await Promise.all([...]); this.ingestQueue = await ingestPromise` did —
        // makes the isolation one-way and leaves ingestQueue null, so QueenRoute renders no
        // ingest section at all, not even the "unavailable" notice.
        const [list, pending, ingest] = await Promise.all([
          tolerate(api.call<QueenRunList>('queen_runs')),
          tolerate(api.call<PendingApproval[]>('pending_approvals')),
          tolerate(api.call<IngestQueue>('consumption_queue')),
        ])
        if (list !== FAILED) {
          this.runs = list.items
          this.total = list.total
          this.costAvailable = list.costAvailable
          this.unavailable = !!list.unavailable
        } else {
          // Same visible outcome the backend produces for a Vistierie outage.
          this.runs = []
          this.total = 0
          this.costAvailable = false
          this.unavailable = true
        }
        if (pending !== FAILED) this.pending = pending.filter(p => p.created_by === 'queen')
        this.ingestQueue = ingest === FAILED ? unavailableIngestQueue() : ingest
        // The per-section state above is already assigned and rendered; the throw only drives
        // QueenRoute's generic "could not load" notice, which must still appear.
        if (list === FAILED || pending === FAILED) throw new Error('queen refresh partially failed')
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
      // The retry itself succeeded; a refresh that partially fails afterwards must not turn that
      // into an error toast, so its rejection is swallowed here.
      if (res.restaged) await this.refresh().catch(() => {})
      return res
    },
  },
})
