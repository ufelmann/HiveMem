# HiveMem

<img width="1637" height="811" alt="image" src="https://github.com/user-attachments/assets/b9ceda91-0678-4d9b-bae8-2b5ba69d53d4" />

> **Your second brain — and it stays yours. Forever. Local.**
>
> A sovereign personal knowledge system. The conversations, decisions, documents, and
> half-formed thoughts you produce across Claude, ChatGPT, Gemini, Copilot — and
> the files you accumulate in real life — all come home to one place that
> outlives any vendor and obeys only you.

---

## Why HiveMem exists

When you think hard today, you often think with an LLM in the loop. School,
work, authorities, court cases, taxes, family, health, relationships — these
conversations contain your most private thinking. More intimate than any diary.

And then they evaporate:

- Your subscription lapses or you switch providers → **history gone**
- The provider retires a model or rewrites their ToS → **answers no longer reproducible**
- An account ban, a provider going under, a country blocking the service → **everything lost**
- The data sits on a vendor's servers, fed into training, served on subpoena, exposed in the next breach

HiveMem is built around the opposite stance:

1. **Sovereignty** — Your data lives in your instance. Postgres + SeaweedFS,
   on hardware you control. No vendor sees the contents unless you explicitly
   route a single LLM call through them.
2. **Persistence** — Everything is append-only with `valid_from`/`valid_until`.
   No subscription change can revoke access. No retention policy you didn't
   author can delete what's yours.
3. **Portability** — A HiveMem instance packs into one encrypted archive
   (Postgres dump + binary store + config) and restores anywhere.
   Vendor lock-in: zero.
4. **Aggregation** — What you write in Claude.ai, ChatGPT, Gemini, Claude
   Code, Copilot lands in HiveMem too. Those tools become front-ends;
   HiveMem holds the truth.
5. **Privacy by realm** — Strict separation per life area
   (`legal`, `medical`, `private`, `work`). Per-realm routing rules: anything
   touching authorities or health stays on local models, never reaches a
   cloud provider.

## Knowledge doesn't rot here — there's a Queen, and you control her

A long-running periodic agent — the **Queen** — wakes on a schedule, surveys
your knowledge, and dispatches specialized worker agents (**Bees**) to keep
the garden tended. She watches for incomplete extractions, isolated cells
without connections, stale facts that haven't been reconfirmed in months,
duplicate candidates, recurring entities that deserve their own cell, and
realms whose blueprint has fallen behind activity.

She works under your terms:

- **You control her.** Pause, dry-run, or kill-switch in config.
  Anything risky she *proposes*, never executes silently.
- **You teach her.** A conversation interface in the web UI lets you say
  in natural language what matters: *"For images I care about whiteboard
  contents"*, *"For utility bills I want consumption versus prior period"*,
  *"In legal realm extract minimum, prioritize privacy."*
  These become persistent preferences folded into every Bee prompt.
- **You watch her.** Every run, every Bee task, every model used, every
  decision to downgrade a tier because subscription quota was running low —
  all logged in an `agent_tasks` audit table.
- **She respects your money.** Smart model-tier routing — Opus for her own
  reasoning, Haiku-class for tunnel suggestions, local Tesseract / Ollama
  for OCR and bulk classification when budget is tight.

→ **[Read the full vision](documentation/vision.md)** — sovereignty argument,
the Queen architecture, scientific foundations.

---

[![CI](https://github.com/ufelmann/HiveMem/actions/workflows/ci.yml/badge.svg)](https://github.com/ufelmann/HiveMem/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/ufelmann/HiveMem/graph/badge.svg)](https://codecov.io/gh/ufelmann/HiveMem)
[![GitHub release](https://img.shields.io/github/v/tag/ufelmann/HiveMem?label=release)](https://github.com/ufelmann/HiveMem/releases)
[![GHCR](https://img.shields.io/badge/ghcr.io-ufelmann%2Fhivemem-blue)](https://github.com/ufelmann/HiveMem/pkgs/container/hivemem)
[![Java](https://img.shields.io/badge/java-25-blue)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/spring%20boot-4.0.5-6DB33F)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/postgresql-17-336791)](https://postgresql.org)
[![Tests](https://img.shields.io/badge/tests-JUnit%20%2B%20Testcontainers-brightgreen)](https://github.com/ufelmann/HiveMem/actions/workflows/ci.yml)
[![MCP Tools](https://img.shields.io/badge/MCP%20tools-31-orange)](documentation/tools.md)
[![License: Sustainable Use](https://img.shields.io/badge/license-Sustainable%20Use-blue)](https://github.com/ufelmann/HiveMem/blob/main/LICENSE)
[![SafeSkill](https://safeskill.dev/api/badge/ufelmann-hivemem)](https://safeskill.dev/scan/ufelmann-hivemem)

**Docker images:** [`ghcr.io/ufelmann/hivemem:main`](https://github.com/ufelmann/HiveMem/pkgs/container/hivemem) for the rolling `main` branch, plus semver tags such as `ghcr.io/ufelmann/hivemem:8.1.0` for cut releases.

## Highlights

- **[6-Signal Ranked Search](documentation/tools.md#search-signals)** — Semantic similarity, keyword, recency, importance, popularity, and graph proximity — combined into one ranked result.
- **[Temporal Knowledge Graph](documentation/architecture.md#data-model)** — Facts with `valid_from`/`valid_until`, contradiction detection, and multi-hop graph traversal.
- **[Progressive Summarization](documentation/tools.md#progressive-summarization)** — Four layers per cell: content, summary, key points, and insight. Never lose nuance.
- **[Append-Only Versioning + Time Machine](documentation/structure.md)** — No data is ever deleted. Query your knowledge at any point in time.
- **[Agent Fleet + Approval Workflow](documentation/auth.md)** — Agents write pending suggestions; only admins approve. Every write is human-gated.
- **[Auto-Inject Hook for Claude Code](documentation/hook/)** — Relevant memories injected into every session automatically, before you even ask.

→ **[Get started](documentation/getting-started.md)**

## Documentation

| | |
|---|---|
| [Vision](documentation/vision.md) | **Why HiveMem exists, the Queen, scientific foundations** |
| [Getting Started](documentation/getting-started.md) | Prerequisites, embedding service, token creation, connect to Claude |
| [The Structure](documentation/structure.md) | Realms, signals, topics, cells, tunnels — the knowledge hierarchy |
| [Architecture](documentation/architecture.md) | System diagram, data model, security matrix |
| [Tools](documentation/tools.md) | All 31 MCP tools, search signals, progressive summarization |
| [Authentication](documentation/auth.md) | Roles, token management, security details |
| [OAuth + Custom Connector](documentation/oauth.md) | Add HiveMem as a Claude.ai/ChatGPT Custom Connector |
| [Hook Integration](documentation/hook/) | Auto-inject context into Claude Code sessions |
| [Operations](documentation/operations.md) | Backups, deployment, migrations, debugging |

## License

HiveMem is fair-code licensed under the [Sustainable Use License](LICENSE). Free for personal and internal business use. See [LICENSING.md](LICENSING.md) for details.
