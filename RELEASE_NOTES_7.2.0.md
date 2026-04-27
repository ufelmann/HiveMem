# HiveMem 7.2.0

## Hook: Precision Auto-Injection

The `UserPromptSubmit` hook now uses a dedicated precision weight profile optimised for auto-inject instead of the generic ranked-search weights.

### New: Precision Search Weights

The hook calls `ranked_search` with `semantic=0.70` (up from 0.30), reducing keyword-only false positives. Configurable via `hivemem.hooks.weights.*` without affecting UI search weights.

### New: Semantic Floor Filter

Cells with `scoreSemantic < 0.35` are rejected even when their total score exceeds the threshold. Eliminates topically-unrelated cells that score high on shared vocabulary alone.

### New: CWD-Based Promotion

The last component of the `cwd` field (e.g. `hivemem` from `/root/hivemem`) is extracted and used to soft-promote cells whose `realm`, `topic`, or `tags` match — placing project-relevant context ahead of unrelated high-scoring cells.

### Improved: Skip Heuristics

- Minimum prompt length raised to 5 words (from 4)
- Social/greeting starters (`wie geht `, `hallo`, `guten morgen`, …) skipped when ≤ 8 words
- Extended meta-phrase list: `danke schön`, `alles klar`, `wie geht's`, `what's up`, and others

### Configuration

`dedupWindowTurns` in `hivemem.hooks.dedup-window-turns` is now wired into `SessionInjectionCache` at startup (previously ignored).

## Internal

- `HookProperties` gains a nested `Weights` class (`hivemem.hooks.weights.*`) with precision defaults
- `HookContextService` no longer depends on `SearchWeightsProperties`
- `SessionInjectionCache` accepts `HookProperties` constructor for Spring wiring
- Hook architecture documented in `java-server/src/main/java/com/hivemem/hooks/README.md`
