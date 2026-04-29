# Architecture

```mermaid
graph TB
    Client["Claude / MCP Client"]

    subgraph Container["Docker Container (eclipse-temurin:25-jre)"]
        Auth["AuthFilter<br/><i>Token auth + role check + rate limit</i>"]
        ToolGate["ToolPermissionService<br/><i>Filter tools/list by role</i>"]
        Identity["Identity Injection<br/><i>created_by from token</i>"]
        MCP["McpController<br/>:8421<br/><i>30 tools, Streamable HTTP</i>"]
    end

    EmbSvc["External Embeddings Service<br/><i>HTTP API</i>"]
    PG["External PostgreSQL<br/><i>pgvector, Flyway-managed schema</i>"]
    SeaweedFS["External SeaweedFS<br/><i>S3-compatible object storage</i>"]

    Client -->|"MCP over HTTP"| Auth
    Auth --> ToolGate
    ToolGate --> Identity
    Identity --> MCP
    MCP -->|"HTTP"| EmbSvc
    MCP -->|"JDBC"| PG
    MCP -->|"S3 API"| SeaweedFS
```

## Data Model

```mermaid
erDiagram
    cells {
        UUID id PK
        UUID parent_id FK
        TEXT content
        vector embedding
        TEXT realm
        TEXT signal
        TEXT topic
        TEXT summary
        TEXT[] key_points
        TEXT insight
        TEXT actionability
        SMALLINT importance
        TEXT status
        TIMESTAMPTZ valid_from
        TIMESTAMPTZ valid_until
    }
    facts {
        UUID id PK
        UUID parent_id FK
        TEXT subject
        TEXT predicate
        TEXT object
        REAL confidence
        UUID source_id FK
        TEXT status
        TIMESTAMPTZ valid_from
        TIMESTAMPTZ valid_until
    }
    tunnels {
        UUID id PK
        UUID from_cell FK
        UUID to_cell FK
        TEXT relation
        TEXT note
        TEXT status
        TEXT created_by
        TIMESTAMPTZ valid_from
        TIMESTAMPTZ valid_until
    }
    blueprints {
        UUID id PK
        TEXT realm
        TEXT title
        TEXT narrative
        TEXT[] signal_order
        UUID[] key_cells
        TIMESTAMPTZ valid_from
        TIMESTAMPTZ valid_until
    }
    api_tokens {
        UUID id PK
        TEXT token_hash
        TEXT name
        TEXT role
        TIMESTAMPTZ expires_at
        TIMESTAMPTZ revoked_at
    }
    agents {
        TEXT name PK
        TEXT focus
        JSONB autonomy
        TEXT schedule
    }
    references_ {
        UUID id PK
        TEXT title
        TEXT url
        TEXT ref_type
        TEXT status
        SMALLINT importance
    }
    attachments {
        UUID id PK
        TEXT file_hash
        TEXT mime_type
        TEXT original_filename
        BIGINT size_bytes
        TEXT s3_key_original
        TEXT s3_key_thumbnail
        TEXT uploaded_by
        TIMESTAMPTZ created_at
        TIMESTAMPTZ deleted_at
    }
    cell_attachments {
        UUID id PK
        UUID cell_id FK
        UUID attachment_id FK
        BOOL extraction_source
        TIMESTAMPTZ created_at
    }

    cells ||--o{ facts : "source_id"
    cells ||--o{ cells : "parent_id (revision chain)"
    facts ||--o{ facts : "parent_id (revision chain)"
    cells ||--o{ cell_references : "links"
    references_ ||--o{ cell_references : "links"
    agents ||--o{ agent_diary : "writes"
    cells ||--o{ access_log : "tracked"
    cells ||--o{ cell_attachments : "linked"
    attachments ||--o{ cell_attachments : "linked"
```

### Attachment ingestion

Each file upload (via `upload_attachment` or `POST /api/attachments`) automatically creates a new `pending` Cell. The cell content is set to the text extracted from the file; if no text could be extracted, the original filename is used as a fallback. The Classifier agent picks up `pending` cells asynchronously and enriches them with summary, key points, insight, and tags. The link between the attachment and its extraction cell is recorded in `cell_attachments` with `extraction_source = true`. If the caller also supplies an existing `cell_id`, a second `cell_attachments` row (or a `related_to` tunnel) is created to express that relationship.

## Security & Capability Matrix

Every HiveMem tool is mapped to a specific role to ensure least privilege. Write operations (excluding agents) and admin functions are protected by RBAC.

| Category | Tools | Access Role | Data Flow | HITL Required? | Description |
|---|---|---|---|---|---|
| **Search** | `search`, `search_kg`, `quick_facts`, `time_machine` | `reader` | Read Only | No | 6-signal semantic & keyword search. |
| **Read** | `status`, `get_cell`, `list_realms`, `traverse`, `wake_up`, `get_blueprint`, `history` | `reader` | Read Only | No | Navigation and context retrieval. |
| **Write** | `add_cell`, `kg_add`, `kg_invalidate`, `revise_cell`, `revise_fact`, `update_identity`, `update_blueprint` | `agent` | Propose Change | Yes (for Agents) | Append-only knowledge capture. |
| **Tunnels** | `add_tunnel`, `remove_tunnel` | `agent` | Link Discovery | Yes | Cell-to-cell semantic linking. |
| **Approval** | `approve_pending` | `admin` | Commit Change | Yes | Batch approve or reject pending agent writes. |
| **Agent** | `register_agent`, `list_agents`, `diary_write`, `diary_read` | `admin` | Fleet Management | Yes | Autonomous fleet orchestration. |
| **References** | `add_reference`, `link_reference`, `reading_list` | `agent` | Metadata | No | Source and citation tracking. |
| **Admin** | `health` | `admin` | System Management | Yes | DB connection, extensions, counts, disk. |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `HIVEMEM_JDBC_URL` | (required) | JDBC connection string to PostgreSQL |
| `HIVEMEM_DB_USER` | (required) | PostgreSQL username |
| `HIVEMEM_DB_PASSWORD` | (required) | PostgreSQL password |
| `HIVEMEM_EMBEDDING_URL` | `http://localhost:8081` | URL of the external embeddings service |
| `HIVEMEM_EMBEDDING_TIMEOUT` | `PT5S` | HTTP timeout for embedding requests (ISO 8601 duration) |
| `SERVER_PORT` | `8421` | Port for the MCP server |

## Security & Compliance

- **Privacy First:** HiveMem is 100% self-hosted. Your data never leaves your infrastructure.
- **Auditability:** All tool calls and authentication events are logged to `/data/audit.log`.
- **SafeSkill Score:** **100/100 (Verified Safe)**. See [SafeSkill Report](https://safeskill.dev/scan/ufelmann-hivemem).
- **Transparency:** 7/7 points. See [SAFE.md](../SAFE.md) for the security manifest.
- **Human-in-the-Loop:** All agent writes require manual approval via `approve_pending`.
