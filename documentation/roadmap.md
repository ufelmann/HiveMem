# Roadmap

This page describes work that is **planned or partial** — features the README
references aspirationally or where the current implementation does not yet
match the full vision. Anything not listed here is considered stable; see the
Feature Status table in the [README](../README.md#feature-status).

## 🟡 Partial — already useful, not yet complete

### Temporal Knowledge Graph: contradiction detection

**Today.** Facts are bi-temporal (`valid_from` / `valid_until` /
`ingested_at`). Multi-hop graph traversal works. The schema knows a
`contradicts` relation type, and users (or agents) can label conflicting
facts manually.

**Missing.** No automatic detector. Two facts that disagree about the same
entity attribute will both sit in the graph until somebody notices.

**Planned.** A periodic sweep over recently-changed facts that flags
candidate contradictions for review (writing them as `pending` so the
existing approval workflow handles them).

### Privacy by Realm: per-realm model routing

**Today.** Realms (`legal`, `medical`, `private`, `work`, …) cleanly
separate cells, facts, tunnels, and search results. The `agents` table has
a `model_routing` JSONB column that can express "use Ollama for legal".

**Missing.** Nothing in the LLM call path actually consults `model_routing`.
A worker invoked in the `legal` realm can still reach a cloud model.

**Planned.** A routing interceptor in front of every LLM client
(`AnthropicSummarizer`, `VisionClient`, `EmbeddingClient`, …) that resolves
the realm of the current task and refuses or rewrites the call when the
configured policy forbids cloud models.

## 🔴 Planned — described in the README, not yet built

### Queen + Bees periodic agent

The README's "Knowledge doesn't rot here" section describes a long-running
agent (the **Queen**) that wakes on a schedule, surveys knowledge, and
dispatches specialized worker agents (**Bees**) — with a kill-switch in
config, a conversation UI for teaching preferences, and an `agent_tasks`
audit table.

**Today.** The pieces below the Queen exist:

- An `agents` table with `name`, `focus`, `autonomy`, `schedule`, and
  `model_routing` columns.
- The `register_agent` MCP tool to declare new agents.
- An approval workflow that gates every agent write (`pending` → admin
  approves → `committed`).
- An `agent_diary` for free-form notes per agent.

**Missing.** Everything that turns the schema into an actually-running
fleet:

- No scheduler / background runner that wakes the Queen.
- No Bee dispatch logic — no code that translates a Queen finding
  ("this cell has zero tunnels") into a Bee task.
- No `agent_tasks` audit table tracking each run, the model used, and
  the outcome.
- No conversation UI in `knowledge-ui/` for teaching realm-level
  preferences in natural language.
- No enforcement of the documented kill-switch / dry-run / pause flags.

**Planned (rough order).**

1. `agent_tasks` table + repository (run id, agent, started/finished,
   model, status, result summary).
2. A single Spring `@Scheduled` Queen runner with a kill-switch config
   property (`hivemem.queen.enabled`, default `false`).
3. One concrete Bee — the **isolated-cell Bee** — that finds cells
   without tunnels and proposes candidate tunnels via existing
   `add_tunnel` (as `pending`).
4. UI: a "Queen log" page that reads from `agent_tasks` and the
   approval queue.
5. Conversation UI for preferences (likely a small textarea per realm
   that gets stitched into Bee prompts).

Each of those is intended to ship as its own PR. Until the first three
land, the Queen narrative in the README should be read as design intent,
not running code.

## How this page stays honest

Every change that flips a row from 🔴 → 🟡 → ✅ in the README's Feature
Status table must update the corresponding entry here in the same commit.
When an entry reaches ✅ it is removed from this page entirely.
