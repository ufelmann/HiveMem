# Hook Configuration

All properties under `hivemem.hooks.*` in `application.yml`:

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | Master switch |
| `relevance-threshold` | `0.65` | Minimum `score_total` to inject |
| `min-semantic-score` | `0.35` | Minimum `score_semantic` to inject |
| `max-cells` | `3` | Maximum cells injected per turn |
| `dedup-window-turns` | `5` | Turns before a cell can be re-injected |

Example `application.yml`:

```yaml
hivemem:
  hooks:
    enabled: true
    relevance-threshold: 0.65
    min-semantic-score: 0.35
    max-cells: 3
    dedup-window-turns: 5
```

`threshold` and `maxCells` can also be overridden per-request via query params on the `/hooks/context` endpoint:

```
POST /hooks/context?threshold=0.70&maxCells=5
```
