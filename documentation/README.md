# HiveMem Documentation

Welcome. Pick the topic you need:

---

### [Getting Started](getting-started.md)
Prerequisites, Docker Compose setup, first token, connecting to Claude Code and Claude Desktop, and the CLAUDE.md snippet that teaches your agent to use HiveMem.

### [The Structure](structure.md)
The four-level knowledge hierarchy — Realms, Signals, Topics, Cells — plus Tunnels, Facts, and Blueprints. Start here if you want to understand how HiveMem organizes knowledge.

### [Tools](tools.md)
All 30 MCP tools with descriptions, the 6 search signals and their weights, and the progressive summarization layers (content → summary → key points → insight).

### [Architecture](architecture.md)
System architecture diagram, PostgreSQL data model (ER), security and capability matrix, environment variable reference, and compliance details.

### [Authentication](auth.md)
The four roles (admin / writer / reader / agent), the approval workflow for agent writes, the `hivemem-token` CLI, and security implementation details.

### [Hook Integration](hook/)
Auto-inject relevant memory cells into every Claude Code session before you even ask. Includes setup, the 6-stage filtering pipeline, configuration reference, output format, and roadmap.

### [Operations](operations.md)
Backups, deploying changes, adding Flyway migrations, and debugging.

### [Vision](vision.md)
The cognitive science behind HiveMem — Working Memory, Cognitive Load Theory, the Extended Mind Thesis — and how Zettelkasten and PARA shaped the design.
