# Authentication & Authorization

Tokens are stored as SHA-256 hashes in PostgreSQL. The plaintext is shown exactly once at creation and never stored. Auth responses are cached with Caffeine (60s TTL, max 1000 entries).

## Roles

Each token has one of four roles. The role controls which tools the client sees in `tools/list` and which it can call.

| Role | Visible tools | Write behavior | Can approve? |
|---|---|---|---|
| `admin` | All 30 | `status: committed` | Yes |
| `writer` | 28 (no admin tools) | `status: committed` | No |
| `reader` | 15 (read only) | Can't write | No |
| `agent` | 28 (same as writer) | `status: pending` | No |

The `agent` role is the key constraint: agents can add knowledge, but every write goes into a pending queue. Only an admin can approve or reject it. This prevents any agent from writing and self-approving in the same session.

`created_by` is set automatically from the token name. Clients can't override it.

## Token Management

The `hivemem-token` CLI is included in the Docker image:

```bash
docker exec hivemem hivemem-token create <name> --role admin|writer|reader|agent [--expires 90d]
```

Available commands:

```bash
hivemem-token create <name> --role admin|writer|reader|agent [--expires 90d]
hivemem-token list
hivemem-token revoke <name>
hivemem-token info <name>
```

## Security Details

- **Rate limiting** — 5 failed auth attempts per IP triggers a 15-minute ban
- **Audit log** — every request logged to `/data/audit.log`
- **Timing-safe** — token comparison uses SHA-256 hash lookup, not string comparison
- **Path traversal protection** — file import restricted to `/data/imports` and `/tmp`
- **Tool call enforcement** — `tools/call` checked against role permissions, not just `tools/list` filtering
