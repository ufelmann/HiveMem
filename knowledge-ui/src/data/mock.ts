// Synthetic demo data for the Mock API client.
// No real production content — every cell, fact, and tunnel below is
// illustrative only. Regenerate by hand or swap in HttpApiClient for real data.

import type { Cell, Realm, Tunnel, Fact, Reference } from '../api/types'

const t = (iso: string) => iso

const cells: Cell[] = [
  {
    id: '00000000-0000-4000-8000-000000000001',
    realm: 'Engineering',
    signal: 'Architecture',
    topic: 'Demo Project',
    title: 'Append-only knowledge model',
    content:
      '# Append-only knowledge model\n\nKnowledge entries are versioned by parent_id chains, with `valid_from` and `valid_until` timestamps. Revisions never overwrite — they insert a successor and close the predecessor.',
    summary: 'Knowledge entries are versioned via parent_id chains and bi-temporal validity.',
    key_points: [
      'Each cell has a parent_id linking to the prior version',
      'valid_from / valid_until define the temporal slice',
      'Status: pending | committed | rejected',
    ],
    insight: 'Append-only makes rollback trivial and audit-friendly.',
    tags: ['schema', 'design'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2026-01-15T09:00:00Z'),
    valid_from: t('2026-01-15T09:00:00Z'),
    valid_until: null,
  },
  {
    id: '00000000-0000-4000-8000-000000000002',
    realm: 'Engineering',
    signal: 'Architecture',
    topic: 'Demo Project',
    title: '5-signal ranked search',
    content:
      'Search blends semantic similarity, keyword match, recency, importance, and popularity into a single rank. Weights are tunable per query.',
    summary: 'Hybrid ranked search combining semantic, keyword, recency, importance, popularity.',
    key_points: ['Weights configurable per call', 'HNSW index on embedding column'],
    insight: 'Pure semantic search underweights freshly written material; the recency signal compensates.',
    tags: ['search', 'ranking'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2026-01-20T10:30:00Z'),
    valid_from: t('2026-01-20T10:30:00Z'),
    valid_until: null,
  },
  {
    id: '00000000-0000-4000-8000-000000000003',
    realm: 'Engineering',
    signal: 'Operations',
    topic: 'Deployment',
    title: 'Flyway migrations run on startup',
    content: 'Flyway scans `db/migration/` at Spring Boot boot and applies any unseen versioned files. Idempotent patterns (IF NOT EXISTS, DO $$ IF EXISTS $$) are preferred.',
    summary: 'Flyway auto-migrates on startup; no manual migration command.',
    key_points: ['Baseline-on-migrate supported', 'Migrations are transactional per file'],
    insight: null,
    tags: ['flyway', 'postgres'],
    importance: 2,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2026-02-02T14:00:00Z'),
    valid_from: t('2026-02-02T14:00:00Z'),
    valid_until: null,
  },
  {
    id: '00000000-0000-4000-8000-000000000004',
    realm: 'Research',
    signal: 'Papers',
    topic: 'Embeddings',
    title: 'Multilingual MiniLM embeddings',
    content: 'paraphrase-multilingual-MiniLM-L12-v2 produces 384-dim sentence embeddings across 50+ languages. Fast enough for CPU inference; quality trails larger models but fits in ~120 MB.',
    summary: 'MiniLM-L12-v2 — small multilingual embedding model, 384 dims.',
    key_points: ['384 dims fit pgvector HNSW', '~120 MB on disk quantized'],
    insight: 'Good default when GPU is unavailable; swap to larger model if recall drops.',
    tags: ['embeddings', 'nlp'],
    importance: 2,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2026-02-18T08:15:00Z'),
    valid_from: t('2026-02-18T08:15:00Z'),
    valid_until: null,
  },
  {
    id: '00000000-0000-4000-8000-000000000005',
    realm: 'Research',
    signal: 'Notes',
    topic: 'Bi-temporal',
    title: 'Event time vs transaction time',
    content: 'Event time (valid_from) is when a fact became true in the world. Transaction time (ingested_at) is when we learned it. Bi-temporal models keep both so you can query the past as it was known then.',
    summary: 'Bi-temporal = event time + transaction time.',
    key_points: ['valid_from: when the fact is true', 'ingested_at: when it was recorded'],
    insight: 'Without transaction time, retroactive edits silently rewrite history.',
    tags: ['bi-temporal', 'modeling'],
    importance: 3,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2026-03-04T16:45:00Z'),
    valid_from: t('2026-03-04T16:45:00Z'),
    valid_until: null,
  },
  {
    id: '00000000-0000-4000-8000-000000000006',
    realm: 'Personal',
    signal: 'Journal',
    topic: 'Reading',
    title: 'Paper: Graph traversal with recursive CTEs',
    content: 'Recursive CTEs in PostgreSQL express fixed-depth graph walks without stored procedures. A cycle-safe version uses a visited-set CTE column.',
    summary: 'Recursive CTEs give bounded graph traversal in pure SQL.',
    key_points: ['Cap depth with WHERE level < N', 'Avoid cycles via visited array'],
    insight: null,
    tags: ['sql', 'graph'],
    importance: 1,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2026-03-12T19:00:00Z'),
    valid_from: t('2026-03-12T19:00:00Z'),
    valid_until: null,
  },
  {
    id: '00000000-0000-4000-8000-000000000007',
    realm: 'Engineering',
    signal: 'Operations',
    topic: 'Backups',
    title: 'Daily pg_dump runs before filesystem snapshot',
    content: 'A nightly logical dump (pg_dump → gzip) runs shortly before the filesystem snapshot so the snapshot always contains a fresh, consistent SQL export.',
    summary: 'Logical dump runs before FS snapshot for consistent backups.',
    key_points: ['pg_dump at 01:45', 'FS snapshot at 02:00'],
    insight: 'Two independent restore paths (SQL + block-level) catch different failure modes.',
    tags: ['backup', 'ops'],
    importance: 2,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2026-03-25T06:30:00Z'),
    valid_from: t('2026-03-25T06:30:00Z'),
    valid_until: null,
  },
  {
    id: '00000000-0000-4000-8000-000000000008',
    realm: 'Engineering',
    signal: 'Architecture',
    topic: 'Demo Project',
    title: 'Terminology: cell / realm / signal / topic',
    content: 'A cell is one knowledge entry. Cells are grouped into realms (top-level section), each tagged with a signal (fixed enum) and an optional topic (free text).',
    summary: 'Cell = entry; Realm = section; Signal = enum tag; Topic = free-text tag.',
    key_points: [
      'Cell: one atomic knowledge entry',
      'Realm: top-level grouping',
      'Signal: categorical (enum)',
      'Topic: free-text subtopic',
    ],
    insight: null,
    tags: ['glossary'],
    importance: 2,
    status: 'committed',
    created_by: 'demo',
    created_at: t('2026-04-01T12:00:00Z'),
    valid_from: t('2026-04-01T12:00:00Z'),
    valid_until: null,
  },
]

const realms: Realm[] = (() => {
  const map = new Map<string, Map<string, number>>()
  for (const c of cells) {
    if (!map.has(c.realm)) map.set(c.realm, new Map())
    const sm = map.get(c.realm)!
    const s = c.signal ?? '(none)'
    sm.set(s, (sm.get(s) ?? 0) + 1)
  }
  return [...map.entries()].map(([name, sm]) => ({
    name,
    cell_count: [...sm.values()].reduce((a, b) => a + b, 0),
    signals: [...sm.entries()].map(([sn, sc]) => ({ name: sn, cell_count: sc, topics: [] })),
  }))
})()

const tunnels: Tunnel[] = [
  {
    id: 'tun-1',
    from_cell: '00000000-0000-4000-8000-000000000002',
    to_cell: '00000000-0000-4000-8000-000000000004',
    relation: 'builds_on',
    note: 'Ranked search depends on embedding quality',
    status: 'committed',
    created_at: t('2026-02-19T09:00:00Z'),
    valid_until: null,
  },
  {
    id: 'tun-2',
    from_cell: '00000000-0000-4000-8000-000000000001',
    to_cell: '00000000-0000-4000-8000-000000000005',
    relation: 'related_to',
    note: null,
    status: 'committed',
    created_at: t('2026-03-05T10:00:00Z'),
    valid_until: null,
  },
  {
    id: 'tun-3',
    from_cell: '00000000-0000-4000-8000-000000000008',
    to_cell: '00000000-0000-4000-8000-000000000001',
    relation: 'refines',
    note: 'Terminology applied to the append-only model',
    status: 'committed',
    created_at: t('2026-04-01T12:05:00Z'),
    valid_until: null,
  },
]

const facts: Fact[] = [
  {
    id: 'f-1',
    subject: 'Demo Project',
    predicate: 'uses_embedding_model',
    object: 'paraphrase-multilingual-MiniLM-L12-v2',
    valid_from: t('2026-02-18T08:15:00Z'),
    valid_until: null,
  },
  {
    id: 'f-2',
    subject: 'Demo Project',
    predicate: 'stores_vectors_in',
    object: 'pgvector HNSW index',
    valid_from: t('2026-01-15T09:00:00Z'),
    valid_until: null,
  },
  {
    id: 'f-3',
    subject: 'Demo Project',
    predicate: 'backup_schedule',
    object: 'daily pg_dump at 01:45 UTC',
    valid_from: t('2026-03-25T06:30:00Z'),
    valid_until: null,
  },
  {
    id: 'f-4',
    subject: 'Demo Project',
    predicate: 'schema_versioning',
    object: 'append-only with parent_id chains',
    valid_from: t('2026-01-15T09:00:00Z'),
    valid_until: null,
  },
]

const references: Reference[] = [
  {
    id: 'r-1',
    title: 'pgvector README',
    url: 'https://github.com/pgvector/pgvector',
    ref_type: 'article',
    status: 'done',
  },
  {
    id: 'r-2',
    title: 'Temporal tables: SQL:2011',
    url: null,
    ref_type: 'paper',
    status: 'reading',
  },
]

export const palace = { cells, realms, tunnels, facts, references }
