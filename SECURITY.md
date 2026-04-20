# Security & Privacy Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 4.x.x   | Yes       |
| 3.x.x   | Yes       |
| 2.x.x   | Limited   |

## Reporting a Vulnerability

If you discover a security vulnerability, please report it via [GitHub Issues](https://github.com/ufelmann/HiveMem/issues) or directly to the maintainer. We take security seriously and will investigate all reports.

## Privacy & Data Sovereignty

- **Local Storage:** All your knowledge (cells, facts, tunnels) is stored locally in your PostgreSQL instance. No data is sent to external APIs for storage or processing.
- **Embeddings:** Vector embeddings are computed by an external ONNX-based embedding service that you self-host. The embedding model runs entirely within your infrastructure.
- **Network Access:** HiveMem communicates only with your PostgreSQL database and your self-hosted embedding service. There are no outbound calls to external APIs or cloud services.
- **Telemetry:** HiveMem does **not** collect any telemetry or usage statistics.

## Security Architecture

HiveMem is built with multiple layers of protection:
- **Role-Based Access Control (RBAC):** 4 roles (admin, writer, reader, agent) restrict tool visibility and execution.
- **Token Security:** API tokens are SHA-256 hashed. Plaintext tokens are shown only once and never stored.
- **SQL Integrity:** All database interactions use jOOQ with parameterized queries to prevent SQL injection.
- **Path Protection:** File import tools are restricted to `/data/imports` and `/tmp` to prevent path traversal attacks.
- **Audit Logging:** Every access and modification is logged to `/data/audit.log` for transparency.
- **Rate Limiting:** Brute-force protection on the API endpoint.
