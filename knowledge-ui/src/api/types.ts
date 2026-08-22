export type Role = 'admin' | 'writer' | 'reader' | 'agent'
export type Relation = 'related_to' | 'builds_on' | 'contradicts' | 'refines'
export type CellStatus = 'committed' | 'pending' | 'rejected'

export interface Attachment {
  id: string
  mime_type: string
  original_filename: string
  size_bytes: number
}

export interface Cell {
  id: string
  realm: string | null
  signal: string | null
  topic: string | null
  title: string
  content: string
  summary: string | null
  key_points: string[]
  insight: string | null
  tags: string[]
  importance: 1 | 2 | 3
  status: CellStatus
  created_by: string
  created_at: string
  valid_from: string
  valid_until: string | null
  attachments?: Attachment[]
}

/**
 * The passage that produced the score, when a chunk (not the cell vector) supplied it.
 * `page_from`/`page_to` are omitted when the chunk carried no `[page=N]` marker — most
 * chunked cells don't have one (design doc §3.7: 101 of 407). `excerpt` is always present
 * once `match` itself is present.
 */
export interface SearchMatch {
  page_from?: number
  page_to?: number
  excerpt: string
}

export interface SearchResult extends Cell {
  score_total: number
  score_semantic?: number
  score_keyword?: number
  score_recency?: number
  score_importance?: number
  score_popularity?: number
  score_graph_proximity?: number
  confidence_level?: string
  match?: SearchMatch
}

export interface Realm { name: string; cell_count: number; signals: Signal[] }
export interface Signal { name: string; cell_count: number; topics: Topic[] }
export interface Topic { name: string; cell_count: number }

export interface Tunnel {
  id: string
  from_cell: string
  to_cell: string
  relation: Relation
  note: string | null
  status: CellStatus
  created_at: string
  valid_until: string | null
}

export interface Fact {
  id: string
  subject: string
  predicate: string
  object: string
  valid_from: string
  valid_until: string | null
}

export interface Reference {
  id: string
  title: string
  url: string | null
  ref_type: 'article' | 'paper' | 'book' | 'attachment' | 'other'
  status: 'unread' | 'reading' | 'done'
}

export interface StatusSummary {
  cell_count: number
  fact_count: number
  realm_count: number
  tunnel_count: number
  pending_count: number
  last_activity: string
}

export type HiveEvent =
  | { type: 'cell_added'; cell: Cell }
  | { type: 'cell_revised'; id: string; parent_id: string }
  | { type: 'tunnel_added'; tunnel: Tunnel }
  | { type: 'status'; last_activity: string }

export interface ApiClient {
  call<T>(tool: string, args?: Record<string, unknown>): Promise<T>
  subscribe(onEvent: (e: HiveEvent) => void): () => void
}

export interface QueenRun {
  id: string
  agent: string
  trigger: string | null
  status: string
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
  llmCalls: number | null
  costMicros: number | null
}

export interface QueenRunList {
  items: QueenRun[]
  total: number
  costAvailable: boolean
  unavailable?: boolean
}

export interface QueenRunEvent {
  type: string
  [key: string]: unknown
}

export interface QueenRunDetail {
  run: Record<string, unknown>
  events: QueenRunEvent[]
  unavailable?: boolean
}

export interface PendingApproval {
  type: string
  id: string
  /** Short human label built by the pending_approvals view — never null. */
  title: string
  /** The proposing agent's rationale: a cell summary or a tunnel note. Null for facts. */
  description: string | null
  realm: string | null
  signal: string | null
  /** Endpoint cell ids — tunnels only, null for cells and facts. */
  from_cell: string | null
  to_cell: string | null
  created_by: string | null
  created_at: string
}

export interface DocumentRow {
  id: string
  realm: string
  signal: string | null
  topic: string | null
  summary: string | null
  tags: string[]
  importance: number
  status: string
  created_at: string
  attachment_id?: string | null
  mime_type?: string | null
  page_count?: number | null
  has_thumbnail?: boolean
  confidence?: number | null
  /** Derived client-side from fact:vendor / fact:party */
  correspondent?: string | null
}

/**
 * A DocumentRow shape as actually returned by the `search` tool: only
 * id/realm/signal/topic plus whatever was requested via `include` are
 * guaranteed. `status`, `attachment_id`, `mime_type`, `page_count`,
 * `has_thumbnail`, `confidence` and `correspondent` are never present —
 * casting search results straight to DocumentRow (as scans.ts used to)
 * lied about that. Card/table rendering must degrade gracefully for rows
 * shaped like this (M17).
 */
export type SearchDocumentRow = Pick<DocumentRow, 'id' | 'realm' | 'signal' | 'topic' | 'tags' | 'summary' | 'created_at'> &
  Partial<Pick<DocumentRow, 'status' | 'importance' | 'attachment_id' | 'mime_type' | 'page_count' | 'has_thumbnail' | 'confidence' | 'correspondent'>>

export interface MediaItem {
  cell_id: string
  attachment_id: string
  realm: string
  summary: string | null
  tags: string[]
  mime_type: string | null
  size_bytes: number | null
  created_at: string | null
  taken_at: string | null
  width: number | null
  height: number | null
  camera_make: string | null
  camera_model: string | null
  gps_lat: number | null
  gps_lon: number | null
  place_name: string | null
  thumbnail_uri: string | null
  content_uri: string | null
}

export interface FacetValue { value: string; count: number }
export type FacetCounts = Record<string, FacetValue[]>

export interface ArchivistLogEntry {
  op_type: 'reclassify_cell' | 'archivist_skip'
  at: string
  cell_id: string
  reason: string | null
  agent_id: string
  old_realm?: string | null
  old_topic?: string | null
  old_signal?: string | null
  new_realm?: string | null
  new_topic?: string | null
  new_signal?: string | null
}

/** One row from `consumption_queue`'s failedFiles list — mirrors
 *  ConsumptionFileRepository.Row (jOOQ/Jackson record serialization). */
export interface IngestFailedFile {
  sha256: string
  filename: string
  state: string
  attempts: number
  lastError: string | null
}

/** One row from `consumption_queue`'s degradedBatches list — mirrors
 *  ConsumptionFileRepository.DegradedBatch. `blankPages` is nullable: it counts all pages
 *  recognised as blank (LLM-voted or pixel-skipped) and dropped before assembly, but rows
 *  recorded before the column existed have no value rather than an invented 0. */
export interface IngestDegradedBatch {
  sha256: string
  filename: string
  totalPages: number
  degradedPages: number
  blankPages: number | null
  updatedAt: string
}

/** One row from `consumption_queue`'s stalledRows list — mirrors
 *  ConsumptionFileRepository.StalledRow. A file that neither finished nor failed: still
 *  `staged` or `processing` past the recovery stale threshold. */
export interface IngestStalledRow {
  sha256: string
  filename: string
  state: string
  updatedAt: string
  ageSeconds: number
}

/** Reconciliation counters, cumulative since process start — mirrors
 *  ConsumptionRecoverySweep.Reconciliation. Field names match the backend record exactly:
 *  no `doneLeftovers` (that name never existed on the backend). */
export interface IngestReconciliation {
  orphansRestaged: number
  rowsWithoutFile: number
  misplacedFailed: number
}

/** `consumption_queue` response — mirrors ConsumptionQueueService.Queue. When the
 *  consumption pipeline is disabled, ConsumptionQueueToolHandler returns this same shape
 *  with all collections empty/zero and `unavailable: true`, so an off pipeline never reads
 *  as a healthy empty queue. */
export interface IngestQueue {
  failedFiles: IngestFailedFile[]
  degradedBatches: IngestDegradedBatch[]
  stalledRows: IngestStalledRow[]
  reconciliation: IngestReconciliation
  stateCounts: Record<string, number>
  unavailable?: boolean
}

export interface SavedSearch {
  id: string
  name: string
  filter: Record<string, unknown>
  created_at?: string
}
