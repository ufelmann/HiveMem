import type { Drawer, Palace } from '../types/palace'

const d = (
  id: string, title: string, wing: string, hall: string, room: string,
  summary: string, importance: number,
  status: 'committed' | 'pending' = 'committed',
  tunnels: Drawer['tunnels'] = [],
  insight = '',
  keyPoints: string[] = [],
): Drawer => ({
  id, title, wing, hall, room,
  content: summary, summary, keyPoints, insight, importance, status,
  validFrom: '2026-04-10T00:00:00Z', facts: [], tunnels,
})

const drawers: Drawer[] = [
  d('hivemem-core', 'HiveMem Core Architecture', 'Projects', 'Software', 'HiveMem',
    'PostgreSQL + pgvector backend with jOOQ, 30 MCP tools, append-only data model.', 5, 'committed',
    [{ targetId: 'hivemem-search', relation: 'builds_on' }, { targetId: 'hivemem-auth', relation: 'related_to' }],
    'Single DB (PG) covers embeddings, graph, temporal, and backup.',
    ['30 MCP tools', '11 tables, append-only', '5-signal ranked search']),
  d('hivemem-search', '5-Signal Ranked Search', 'Projects', 'Software', 'HiveMem',
    'Semantic + keyword + recency + importance + popularity, HNSW-indexed.', 4, 'committed',
    [{ targetId: 'hivemem-core', relation: 'refines' }, { targetId: 'embedding-service', relation: 'builds_on' }],
    'MAX hoisted into CTE; queries drawers directly for HNSW compatibility.',
    ['semantic=0.5', 'keyword=0.2', 'recency=0.15', 'importance=0.1', 'popularity=0.05']),
  d('hivemem-auth', 'Token-Based Auth', 'Projects', 'Software', 'HiveMem',
    'SHA-256 hashed bearer tokens, 4 roles, Caffeine 60s cache, rate limiting.', 4, 'committed',
    [{ targetId: 'hivemem-core', relation: 'related_to' }],
    'Role filtering on tools/list AND tools/call (defense in depth).',
    ['admin: 30 tools', 'writer: 28', 'reader: 15', 'agent: 28 pending']),
  d('embedding-service', 'ONNX Embedding Service', 'Projects', 'Software', 'HiveMem',
    'Standalone Python container, MiniLM-L12-v2 (384 dims), model-change auto-reencoding.', 3, 'committed',
    [{ targetId: 'hivemem-search', relation: 'related_to' }],
    'Decoupled so GPU offloading is optional.'),
  d('rtclaw-async', 'Async Reasoning via OpenClaw', 'Projects', 'Software', 'RealtimeClaw',
    'request_reasoning returns immediately; answer injected when Gemini completes.', 4, 'committed',
    [{ targetId: 'rtclaw-prompt-bug', relation: 'refines' }],
    '15s WebSocket idle would disconnect synchronous waits.',
    ['reasoningGeneration drops stale replies', 'pendingReasoning pauses idle timer']),
  d('rtclaw-prompt-bug', 'Silent Prompt Composition Bug', 'Projects', 'Software', 'RealtimeClaw',
    'buildInstructions dropped REALTIME_INSTRUCTIONS whenever SOUL was supplied.', 3, 'committed',
    [{ targetId: 'rtclaw-async', relation: 'related_to' }],
    'Rules must always append, not mutually exclude with SOUL.'),
  d('rtclaw-voice-pe', 'Voice PE Auto-Reconnect', 'Projects', 'Software', 'RealtimeClaw',
    'ESPHome wyoming_tcp_client needs USB-C power-cycle on ERROR state.', 2, 'pending',
    [{ targetId: 'rtclaw-async', relation: 'related_to' }],
    'on_error path does not trigger reconnect.'),
  d('proxmox-ct102', 'CT 102 Portainer Host', 'Projects', 'Infrastructure', 'Proxmox',
    'LXC running portainer + HiveMem + embedding service.', 4, 'committed',
    [{ targetId: 'hivemem-core', relation: 'related_to' }],
    'apparmor=unconfined required.'),
  d('proxmox-backup', 'Daily pg_dump Backup', 'Projects', 'Infrastructure', 'Proxmox',
    'Crontab 45 1 * * * before vzdump at 02:00.', 3, 'committed',
    [{ targetId: 'proxmox-ct102', relation: 'builds_on' }],
    'App-level dump before VM-level backup.'),
  d('cloudflare-tunnel', 'Cloudflare Tunnel', 'Projects', 'Infrastructure', 'Proxmox',
    'Remote access to Mac Mini Proxmox via Cloudflare Tunnel.', 3, 'committed', [],
    '', ['No public IP required', 'Zero Trust on hostnames']),
  d('docker-net', 'hivemem-net Docker Network', 'Projects', 'Infrastructure', 'Networking',
    'Internal bridge network for hivemem and hivemem-embeddings.', 2, 'committed',
    [{ targetId: 'proxmox-ct102', relation: 'related_to' }]),
  d('mdns-internal', 'Internal mDNS Discovery', 'Projects', 'Infrastructure', 'Networking',
    'Pending: multicast forwarding across VLANs.', 2, 'pending'),
  d('claude-4-7', 'Claude Opus 4.7', 'Knowledge', 'AI', 'LLMs',
    'Current flagship model (Jan 2026 cutoff), 1M context.', 4, 'committed',
    [{ targetId: 'gemini-flash', relation: 'contradicts' }, { targetId: 'prompt-caching', relation: 'related_to' }],
    'Opus for deep reasoning; Sonnet for speed default.'),
  d('gemini-flash', 'Gemini 2.5 Flash', 'Knowledge', 'AI', 'LLMs',
    'Google free tier (OpenClaw). 28-63s latency on knowledge prompts.', 3, 'committed',
    [{ targetId: 'claude-4-7', relation: 'contradicts' }],
    'Cheap but slow; bad for synchronous voice.'),
  d('prompt-caching', 'Anthropic Prompt Caching', 'Knowledge', 'AI', 'LLMs',
    'Cache breakpoints reduce cost and latency; 5-min TTL.', 3, 'committed',
    [{ targetId: 'claude-4-7', relation: 'builds_on' }]),
  d('rag-semantic', 'Semantic + Keyword Hybrid Search', 'Knowledge', 'AI', 'RAG Patterns',
    'Pure vector search misses acronyms; combine with BM25/tsvector.', 4, 'committed',
    [{ targetId: 'hivemem-search', relation: 'builds_on' }, { targetId: 'rag-chunking', relation: 'related_to' }],
    'Hybrid search is almost always better for technical content.'),
  d('rag-chunking', 'Chunking Strategies', 'Knowledge', 'AI', 'RAG Patterns',
    'Fixed-size vs semantic vs structural chunks — structural wins for docs.', 3, 'committed',
    [{ targetId: 'rag-semantic', relation: 'related_to' }]),
  d('rag-rerank', 'Cross-Encoder Reranking', 'Knowledge', 'AI', 'RAG Patterns',
    'Top-K from bi-encoder, then rerank top-20 with cross-encoder.', 2, 'pending',
    [{ targetId: 'rag-semantic', relation: 'refines' }]),
  d('pgvector', 'pgvector Extension', 'Knowledge', 'Databases', 'PostgreSQL',
    'Vector similarity search via HNSW/IVFFlat.', 5, 'committed',
    [{ targetId: 'hivemem-search', relation: 'builds_on' }, { targetId: 'pg-hnsw', relation: 'related_to' }],
    'One DB for relational + vector is strictly simpler.'),
  d('pg-hnsw', 'HNSW vs IVFFlat', 'Knowledge', 'Databases', 'PostgreSQL',
    'HNSW: higher memory, faster recall. IVFFlat: smaller, needs training.', 3, 'committed',
    [{ targetId: 'pgvector', relation: 'refines' }]),
  d('pg-append-only', 'Append-Only Versioning Pattern', 'Knowledge', 'Databases', 'PostgreSQL',
    'parent_id + valid_from/valid_until beats UPDATE for history.', 4, 'committed',
    [{ targetId: 'hivemem-core', relation: 'related_to' }, { targetId: 'pgvector', relation: 'related_to' }],
    'UPDATE...RETURNING gives atomic revise in one query.'),
  d('flyway', 'Flyway Migrations', 'Knowledge', 'Databases', 'Migrations',
    'Versioned SQL files (V0001__...sql), tracked via schema_version table.', 3, 'committed',
    [{ targetId: 'pg-append-only', relation: 'related_to' }]),
  d('testcontainers', 'Testcontainers with pgvector', 'Knowledge', 'Databases', 'Migrations',
    'Ephemeral pgvector/pgvector:pg17 for integration tests.', 3, 'committed',
    [{ targetId: 'flyway', relation: 'related_to' }]),
]

const wingsSpec = [
  { name: 'Projects', color: '#00BFFF', halls: [
    { name: 'Software', rooms: ['HiveMem', 'RealtimeClaw'] },
    { name: 'Infrastructure', rooms: ['Proxmox', 'Networking'] },
  ]},
  { name: 'Knowledge', color: '#00FF88', halls: [
    { name: 'AI', rooms: ['LLMs', 'RAG Patterns'] },
    { name: 'Databases', rooms: ['PostgreSQL', 'Migrations'] },
  ]},
]

export const mockPalace: Palace = {
  wings: wingsSpec.map((wing) => {
    const wingDrawers = drawers.filter((x) => x.wing === wing.name)
    return {
      name: wing.name,
      color: wing.color,
      hallCount: wing.halls.length,
      drawerCount: wingDrawers.length,
      halls: wing.halls.map((hall) => {
        const hallDrawers = wingDrawers.filter((x) => x.hall === hall.name)
        return {
          name: hall.name,
          roomCount: hall.rooms.length,
          drawerCount: hallDrawers.length,
          rooms: hall.rooms.map((roomName) => {
            const roomDrawers = hallDrawers.filter((x) => x.room === roomName)
            return { name: roomName, drawerCount: roomDrawers.length, drawers: roomDrawers }
          }),
        }
      }),
    }
  }),
}

export const mockDrawersById: Record<string, Drawer> = Object.fromEntries(
  drawers.map((x) => [x.id, x]),
)
