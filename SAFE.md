# SAFE.md - HiveMem
**Security Rating:** Transparency 7/7 (Verified)

## 1. Explicit Intent
HiveMem provides 38 MCP tools for personal knowledge management. Each tool has a clear, singular purpose:
- **Search:** Semantic (pgvector) and keyword search over local data.
- **Knowledge Graph:** Managing atomic facts with temporal validity.
- **Agent Fleet:** Allowing AI agents to suggest knowledge with a human-in-the-loop approval workflow.
- **Admin:** Health checks and role-based token management.
Activation is always explicit via MCP tool calls; there are no background autonomous actions without a direct prompt.

## 2. Visible Planning
HiveMem supports a structured `agent` role. Agents assigned to this role are instructed to propose changes via the `pending` status. No modifications to the primary knowledge base occur without an explicit review of the planned changes by an `admin` user via `approve_pending`.

## 3. Bounded Scope
- **File System:** Restricted to `/data/imports` and `/tmp` for mining tools. No access to sensitive system directories.
- **Network:** HiveMem communicates only with its self-hosted PostgreSQL database and a self-hosted ONNX embedding service. No external API calls.
- **Database:** Access is scoped to the PostgreSQL database configured via `HIVEMEM_JDBC_URL`.

## 4. Traceable Logic
Every tool execution, authentication attempt, and data modification is logged in JSON format to `/data/audit.log`. This log includes the token ID, the tool called, and the outcome, providing 100% auditability for every agent action.

## 5. Verifiable Outcomes
HiveMem includes 250 automated tests (unit, integration, and E2E) using Testcontainers. All changes must pass CI (GitHub Actions) before release. The `status` and `health` tools provide real-time verification of the system's integrity.

## 6. Human-in-the-Loop (HITL)
Critical operations, specifically the promotion of knowledge from "pending" to "committed", require manual approval via the `approve_pending` tool. Deletion or invalidation of facts is restricted to `admin` and `writer` roles.

## 7. Cryptographic Integrity
The integrity of the core logic in the `java-server/src/main/java/` directory is verified by the following SHA256 hash (v4.0.0):
`2578645bc38f8f69e7ed1dea58f07d92687035c8b9e908c003fe7b0bdaa1d1dd`

---
