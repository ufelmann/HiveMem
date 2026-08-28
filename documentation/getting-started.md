# Getting Started

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) (v20+)
- An external PostgreSQL database with pgvector extension (e.g. `pgvector/pgvector:pg17`)
- An external embeddings service reachable via HTTP (see below)

## Embedding Service

HiveMem requires an external embedding service. An ONNX-based service is included in `embedding-service/` and can be configured via environment variables instead of code changes.

The service must expose:
- `POST /embeddings` — `{"text": "...", "mode": "document"}` → `{"vector": [...], "model": "...", "dimension": N}`
- `GET /info` — `{"model": "...", "dimension": N, "max_chars": N}` (used by HiveMem for model
  change detection and for the content-vs-summary embedding decision). `max_chars` is
  **mandatory**: HiveMem refuses to start embedding calls against a service that omits it or
  returns a value ≤ 0 (`IllegalStateException: Embedding service returned no usable
  max_chars`) — this guards against pointing a server at an older sidecar build.

**Automatic reencoding:** When HiveMem detects a model change at startup (different model name or dimension), it automatically backs up the database, re-encodes all cells, and rebuilds the HNSW index. Search is blocked (503) during reencoding.

Key environment variables:
- `MODEL_PATH` — mounted directory with local model files; preferred for manual installs
- `MODEL_REPO` — HF repo used when `MODEL_PATH` is unset
- `MODEL_NAME` — model identifier reported by `/info`
- `ONNX_FILE` / `TOKENIZER_FILE` — optional explicit filenames inside the model directory;
  setting `ONNX_FILE` also narrows the Hugging Face download to that file (plus its
  `.onnx_data` sibling and the tokenizer/config files) instead of the broad default set
- `POOLING` — `mean`, `cls`, or `last_token` (last-token pooling needs `eos_token_id` in the
  model directory's `config.json`; the service refuses to start without it)
- `MAX_LENGTH` — tokenizer truncation/padding length (default `128`)
- `EMBEDDING_MAX_CHARS` — character cap advertised via `/info` (default `8000`; same
  variable and default as the Ollama backend, see below). Multilingual text runs roughly
  3-4 characters per token, so `EMBEDDING_MAX_CHARS` must stay within `MAX_LENGTH * 4` or
  so to be honourable — at the image defaults (`MAX_LENGTH=128`, `EMBEDDING_MAX_CHARS=8000`)
  it is not: a long cell is embedded directly instead of falling back to its summary, then
  truncated at 128 tokens (~500 characters) with no error anywhere. The backend logs a loud
  `[bootstrap] WARNING` at startup when the two disagree this badly; raise `MAX_LENGTH` or
  lower `EMBEDDING_MAX_CHARS` so they agree
- `ORT_INTRA_OP_THREADS` — onnxruntime intra-op thread count. Defaults to the cgroup CPU
  quota (`/sys/fs/cgroup/cpu.max`) when unset, falling back to `os.cpu_count()` only if the
  cgroup quota can't be read either; set this explicitly in constrained containers where the
  cgroup quota is unavailable or wrong. The resolved value is reported in `/info` as
  `intra_op_threads`
- `QUERY_PREFIX` / `DOCUMENT_PREFIX` — optional retrieval prefixes

To build the embedding service:

```bash
cd embedding-service
docker build -t hivemem-embeddings .
```

### GPU embedding backend (optional)

The bundled embedding sidecar (`embedding-service/`) ships two backends, selected via
`EMBEDDING_BACKEND`:

- `onnx` (default) — CPU inference (onnxruntime), advertises up to `EMBEDDING_MAX_CHARS`
  characters per cell via `/info` (default `8000`). This is a ceiling the Java client trusts,
  not a guarantee the backend can honour on its own: the tokenizer still truncates at
  `MAX_LENGTH` tokens (default `128`), so an `EMBEDDING_MAX_CHARS` far beyond what
  `MAX_LENGTH` tokens can represent lets long cells through un-truncated on the Java side only
  to be silently cut short during encoding — see `MAX_LENGTH` below. Runs on any host, no GPU
  required — this is what a plain `docker compose up -d` uses.
- `ollama` — talks to a local Ollama server running a larger embedding model (the default
  is Qwen3-Embedding-8B), advertising up to `EMBEDDING_MAX_CHARS` characters per cell (default
  `8000`, same variable as the ONNX backend). The integration is
  provider-neutral: it only depends on Ollama's HTTP API, so a CUDA-based Ollama image
  works the same way as the ROCm one — swap the image tag in your compose override to
  match your GPU vendor.

To enable it:

```bash
export HIVEMEM_EMBEDDING_BACKEND=ollama
docker compose --profile gpu up -d
```

(`HIVEMEM_EMBEDDING_BACKEND` is the host-side variable; `docker-compose.yml` maps it to the
sidecar's own `EMBEDDING_BACKEND` env var — set the `HIVEMEM_`-prefixed one, both names refer
to the same switch.)

The `--profile gpu` flag is required. Without it, the `hivemem-ollama` service is not
created at all — the embedding sidecar's health check never turns green, and `hivemem`
(which depends on the sidecar being healthy) never starts. This is deliberate: a
GPU-less clone that never passes `--profile gpu` is unaffected by the Ollama service
definition.

Prerequisites:
- A GPU reachable from the Docker host, with device passthrough configured for the
  `hivemem-ollama` service (e.g. `/dev/kfd` + `/dev/dri` for ROCm; the NVIDIA Container
  Toolkit's device options for CUDA)
- Enough VRAM for the configured model — the default Qwen3-Embedding-8B model at Q8
  quantization needs on the order of 8-9 GB

Key environment variables for the `hivemem-embeddings` service:
- `EMBEDDING_BACKEND` — `onnx` (default) or `ollama`
- `OLLAMA_URL` — base URL of the Ollama server (default `http://hivemem-ollama:11434`)
- `OLLAMA_MODEL` — model tag to use (default `qwen3-embedding:8b-q8_0`)
- `EMBEDDING_DIMS` — vector width after Matryoshka (MRL) slicing (default `1024`)
- `EMBEDDING_MAX_TOKENS` — Ollama's context/truncation cap, `num_ctx` (default `2560`)
- `EMBEDDING_KEEP_ALIVE` — how long Ollama keeps the model resident after the last
  request (default `5m`); the model is fully unloaded from VRAM after this idle period
  and reloads in about 2 seconds on the next request, on the measured hardware
- `EMBEDDING_MAX_CHARS` — character cap advertised to HiveMem via `/info` (default `8000`)

**`EMBEDDING_MAX_CHARS` and `EMBEDDING_MAX_TOKENS` must be raised together.** Ollama is the
one that actually truncates — the backend sends `truncate: true` with
`options.num_ctx = EMBEDDING_MAX_TOKENS`, so anything beyond the token cap is silently cut
regardless of `EMBEDDING_MAX_CHARS`. Raising `EMBEDDING_MAX_CHARS` alone (e.g. to 16000)
without a matching `EMBEDDING_MAX_TOKENS` increase reintroduces silent truncation with no
error anywhere — `EMBEDDING_MAX_CHARS` must stay within what `EMBEDDING_MAX_TOKENS` tokens
can actually hold for your content's language.

`EMBEDDING_MAX_CHARS` is part of the model identity string both backends report via
`/info` (`c<chars>`, e.g. `.../c8000/contentfirst`), so changing it and restarting
triggers a full re-encode — the same as changing the model name or dimension. This is
intentional: the value decides whether a cell's content or its summary gets embedded, so
a change that were invisible to the identity would leave two vector generations mixed in
one index with no error anywhere.

A resident Ollama model does not cost measurable idle GPU power — an idle GPU sits at
its power/memory-clock floor whether or not a model is loaded — so `EMBEDDING_KEEP_ALIVE`
is a VRAM/reload-latency trade-off, not a power one.

## Quick Start

No clone needed. Save this as `docker-compose.yml` and run `docker compose up -d`:

```yaml
services:
  hivemem-db:
    image: pgvector/pgvector:pg17
    container_name: hivemem-db
    environment:
      POSTGRES_DB: hivemem
      POSTGRES_USER: hivemem
      POSTGRES_PASSWORD: ${HIVEMEM_DB_PASSWORD:-changeme}
    volumes:
      - hivemem-pgdata:/var/lib/postgresql/data
    networks:
      - hivemem-net
    restart: unless-stopped

  hivemem-embeddings:
    image: ghcr.io/visterion/hivemem-embeddings:latest
    container_name: hivemem-embeddings
    volumes:
      - hivemem-embeddings-models:/app/models
    networks:
      - hivemem-net
    restart: unless-stopped

  hivemem:
    image: ghcr.io/visterion/hivemem:latest
    container_name: hivemem
    ports:
      - "8421:8421"
    environment:
      HIVEMEM_JDBC_URL: jdbc:postgresql://hivemem-db:5432/hivemem
      HIVEMEM_DB_USER: hivemem
      HIVEMEM_DB_PASSWORD: ${HIVEMEM_DB_PASSWORD:-changeme}
      HIVEMEM_EMBEDDING_URL: http://hivemem-embeddings:80
      # The session cookie is Secure (HTTPS-only) by default; this quick start
      # serves plain HTTP on localhost, so relax it here. Remove this line (or
      # set "true") when running behind HTTPS.
      SESSION_COOKIE_SECURE: "false"
    depends_on:
      - hivemem-db
      - hivemem-embeddings
    networks:
      - hivemem-net
    restart: unless-stopped

networks:
  hivemem-net:

volumes:
  hivemem-pgdata:
  hivemem-embeddings-models:
```

```bash
# Set a password (or it defaults to "changeme")
export HIVEMEM_DB_PASSWORD=your-secret-here

# Start everything
docker compose up -d

# Wait for startup (Flyway migrations run automatically)
docker logs -f hivemem

# Create your first API token
docker exec hivemem hivemem-token create my-admin --role admin
# Save the printed token — it's shown once and never stored
```

That's it. Three containers, all images from GHCR, no build needed.

For a pinned production rollout, use the current release tags such as `:8.1.0`. Use `:main` only if you explicitly want the rolling branch build.

### Build from source (optional)

```bash
git clone https://github.com/visterion/HiveMem.git
cd HiveMem
docker build -t hivemem .
```

At startup, Spring Boot runs Flyway migrations against the configured PostgreSQL database. Check progress:

```bash
docker logs -f hivemem
```

Wait for the Spring Boot startup log and a successful `/mcp` response before proceeding.

## Required Environment Variables

| Variable | Description |
|---|---|
| `HIVEMEM_JDBC_URL` | JDBC connection string (e.g. `jdbc:postgresql://postgres:5432/hivemem`) |
| `HIVEMEM_DB_USER` | PostgreSQL username |
| `HIVEMEM_DB_PASSWORD` | PostgreSQL password |
| `HIVEMEM_EMBEDDING_URL` | URL of the external embeddings service |
| `SESSION_COOKIE_SECURE` | Session cookie `Secure` flag — defaults to `true`; set `false` for plain-HTTP local setups |

## Create an API Token

Use the `hivemem-token` CLI (included in the Docker image):

```bash
docker exec hivemem hivemem-token create my-admin --role admin
```

The plaintext token is printed once and never stored. Save it immediately.

See [Authentication](auth.md) for the full token management reference.

## Connect to Claude Code

**CLI (recommended):**

```bash
claude mcp add --scope user hivemem --transport http http://localhost:8421/mcp \
  --header "Authorization: Bearer YOUR_TOKEN_HERE"
```

Restart Claude Code. The 50 HiveMem tools are now available in every session.

**Manual config** (`~/.claude.json` for user-level, or `.mcp.json` for project-level):

```json
{
  "mcpServers": {
    "hivemem": {
      "type": "http",
      "url": "http://localhost:8421/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_TOKEN_HERE"
      }
    }
  }
}
```

**Without putting a token in the file:** install the [command-line
client](cli.md), run `hivemem login`, and point Claude Code at its stdio
bridge instead — the credential then lives in your operating system's secret
store, not in `~/.claude.json`.

```json
{ "mcpServers": { "hivemem": { "command": "hivemem", "args": ["mcp-serve"] } } }
```

## Connect to Claude Desktop

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "hivemem": {
      "type": "http",
      "url": "http://localhost:8421/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_TOKEN_HERE"
      }
    }
  }
}
```

The stdio bridge works here too, and keeps the token out of the file:

```json
{ "mcpServers": { "hivemem": { "command": "hivemem", "args": ["mcp-serve"] } } }
```

## Teach Your Agent to Use HiveMem

The MCP server ships instructions that tell the agent *how* to use the 50 tools. But the agent won't reliably *remember to archive* unless you tell it to in your own CLAUDE.md.

Add this to your **user-level** CLAUDE.md (`~/.claude/CLAUDE.md`) so it applies to every project:

```markdown
## HiveMem — Persistent Knowledge

You have a HiveMem MCP server available as your long-term memory. Use it
aggressively.

### Availability check

HiveMem tools are exposed under the `mcp__hivemem__*` namespace (e.g.
`mcp__hivemem__wake_up`, `mcp__hivemem__search`). If those tools are not
listed in the current session, skip this section entirely — do not mention
HiveMem, do not apologize for its absence.

### Session start (HARD RULE)

Call `wake_up` BEFORE your first response, BEFORE any other tool call,
BEFORE reading any file. No exceptions beyond the availability check above.

### During conversation — search proactively

Wake_up is a snapshot, not a subscription. Search actively on these signals:

- **Named reference.** User mentions a named project, person, decision, tool,
  or system not in wake_up → `search` BEFORE answering. Even if you
  think you remember.
- **Temporal reference.** "last week", "a while back", "we decided earlier",
  "remember when" → `search` with keywords, or `time_machine`
  for point-in-time queries.
- **Uncertainty.** About to say "I'm not sure" or hedge? Search FIRST. Only
  hedge if the search returns nothing.
- **Topic drift.** Conversation shifts to a new area not in wake_up → quick
  `search` before engaging.
- **Entity-specific.** User asks about a specific entity → `entity_overview`
  (add `depth=quick` for a fast facts-only lookup), `search_kg` for relationships.

**Anti-patterns — do NOT:**
- Hedge instead of searching ("I think we discussed...")
- Answer from wake_up when the topic wasn't in wake_up
- Batch searches for session end
- Wait for the user to prompt you

One `search` is cheap. Answering wrong is expensive.

### During work

After any significant action (bug fix, feature, design decision, deployment,
investigation), archive immediately — do not batch, do not wait.

Archiving:
1. `add_cell` with `dedupe_threshold: 0.92`
2. `kg_add` for each fact with `on_conflict=return` and `valid_from` set
3. `search` for related cells, then `add_tunnel` for the top
   2-3 matches

When a fact changes: `kg_invalidate` the old one FIRST, then
`kg_add` the new one.

### Session end

Archive anything significant not yet stored. When the user says "archive",
"save", or "persist": archive the full session.

### Classification

Realm = life/work area. Signal = nature of knowledge. Topic = specific subject.

Call `list` before inventing new realms — it navigates the
Realm→Signal→Topic→Cell hierarchy (omit all params for realms, add `realm` for
signals, add `realm`+`signal` for topics).

**Signals:** `facts` | `events` | `discoveries` | `preferences` | `advice`

Fill `content`, `summary`, `key_points`, and `insight` (when there is a
non-obvious takeaway). Every fact needs `valid_from`.

### What to archive
- Decisions + the "why" (not just the "what")
- Discoveries, surprises, lessons learned
- Infrastructure / deployment changes
- Bug root causes + fixes
- New patterns, conventions, processes

### What NOT to archive
- Routine code changes obvious from git history
- Temporary debugging steps
- Information already in project CLAUDE.md or README

### Precedence

Project-local CLAUDE.md overrides these rules if it says otherwise.
```

**Why user-level?** Project-level CLAUDE.md files describe the *project*. HiveMem is *your* memory across all projects. A user-level CLAUDE.md ensures every agent, in every repo, knows to persist knowledge — even in repos that have never heard of HiveMem.

**Why is the MCP protocol not enough?** The MCP `instructions` field tells the agent *how* to use the tools correctly. But it cannot force the agent to *decide* to archive — that decision depends on the conversation context, which only the CLAUDE.md can influence. The MCP protocol is the "API docs"; the CLAUDE.md is the "job description".
