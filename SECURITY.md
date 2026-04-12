# Security & Privacy Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 2.x.x   | Yes       |
| 0.1.x   | Limited   |

## Reporting a Vulnerability

If you discover a security vulnerability, please report it via [GitHub Issues](https://github.com/ufelmann/HiveMem/issues) or directly to the maintainer. We take security seriously and will investigate all reports.

## Privacy & Data Sovereignty

- **Local Storage:** All your knowledge (drawers, facts, tunnels) is stored locally in your PostgreSQL instance. No data is sent to external APIs for storage or processing.
- **Embeddings:** Vector embeddings are computed locally using the `sentence-transformers` library. 
- **Network Access:** HiveMem only accesses the internet to download the pre-trained embedding model from Hugging Face during initial setup. Once downloaded, it can run entirely offline if `HF_HUB_OFFLINE=1` is set.
- **Telemetry:** HiveMem does **not** collect any telemetry or usage statistics.

## Security Architecture

HiveMem is built with multiple layers of protection:
- **Role-Based Access Control (RBAC):** 4 roles (admin, writer, reader, agent) restrict tool visibility and execution.
- **Token Security:** API tokens are SHA-256 hashed. Plaintext tokens are shown only once and never stored.
- **SQL Integrity:** All database interactions use `psycopg` parameterized queries to prevent SQL injection.
- **Path Protection:** File import tools are restricted to `/data/imports` and `/tmp` to prevent path traversal attacks.
- **Audit Logging:** Every access and modification is logged to `/data/audit.log` for transparency.
- **Rate Limiting:** Brute-force protection on the API endpoint.
