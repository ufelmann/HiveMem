---
name: hivemem-archive
description: Use when archiving a completed session, decision, or discovery into HiveMem.
---

# HiveMem Archive Skill

## Overview
This skill guides the agent in systematically persisting knowledge into the HiveMem long-term memory. It extracts facts, decisions, and context from the session.

## When to Use
- **End of session:** When the user says "archive", "save", or "done".
- **Significant discovery:** When a root cause is found or a feature is implemented.
- **Architectural decision:** When a choice (e.g., "PostgreSQL over ChromaDB") is made.

## Workflow

1.  **Analyze Session:** Summarize the core achievement and its rationale.
2.  **Classify (Wing/Hall/Room):**
    - `Wing`: Top-level (e.g., `projects`, `private`).
    - `Hall`: Category (e.g., `software`, `finance`).
    - `Room`: Specific topic (e.g., `hivemem-auth`).
3.  **Store Drawer:** Pass `dedupe_threshold` (e.g. `0.92`) to `hivemem_add_drawer` so the server encodes once and refuses to insert if a near-duplicate exists. Fill all L0-L3 layers.
    - L0: Content (Verbatim)
    - L1: Summary
    - L2: Key Points
    - L3: Insight (The "Why")
4.  **Extract KG Facts:** For every atomic fact (Subject-Predicate-Object), call `hivemem_kg_add`.
    - Use `on_conflict="return"` on `hivemem_kg_add` to detect contradicting active facts without inserting.
    - If conflict: Invalidate old fact with `hivemem_kg_invalidate` before adding new.
5.  **Establish Links:** Link the new drawer to related knowledge via `hivemem_add_tunnel`.

## Security & Transparency
- **Bounded Scope:** This skill only uses `hivemem_*` tools and the current project context.
- **Human-in-the-Loop:** Agents must use the `agent` token role, which sends all writes to the `pending` queue for admin approval.

---
