# Document-Type Extraction (Phase 1)

HiveMem klassifiziert Cells, die durch den Auto-Summarizer laufen, nach
Document-Type und extrahiert dafür typisierte Fakten in die `facts`-Tabelle.

## Pipeline

```
Cell mit Tag needs_summary
  → SummarizerService
      → PreClassifier.guessType(mime, filename, head200) → Type-Hint
      → ExtractionProfileRegistry.resolve(hint) → Profile (YAML)
      → AnthropicSummarizer.summarize(content, profile)
          → {summary, document_type, facts[], ...}
      → reviseCell(content, summary)              [bestehend]
      → cells.document_type = result.documentType  [neu]
      → kg_add(subject=cellId, predicate, object, confidence) pro fact  [neu]
```

## Phase-1-Profile

`src/main/resources/extraction-profiles/`

| Profil | Required Facts | Trigger (PreClassifier) |
|---|---|---|
| `invoice`  | vendor, amount_total, currency, document_date | filename matches `Rechnung\|Invoice\|Receipt\|Beleg`; head matches `Rechnungsnummer\|Invoice No` |
| `contract` | party (mehrfach), start_date | filename matches `Vertrag\|Contract\|AGB`; head matches `Kündigungsfrist\|notice period` |
| `other`    | topic | Fallback |

Neue Profile: einfach eine YAML-Datei in `src/main/resources/extraction-profiles/`
ergänzen. PreClassifier-Heuristik muss zusätzlich erweitert werden, damit der Type
auch aus Filename/Head erraten wird — sonst muss der LLM ihn aus der `other`-Kette
selbst korrekt setzen.

## Konfiguration

```yaml
hivemem:
  extraction:
    enabled: true               # default
    default-fallback-type: other
```

Extraction läuft nur mit, wenn `hivemem.summarize.enabled=true` ist.

## Schema

V0027 fügt `cells.document_type TEXT` plus partiellen Index hinzu.
Fakten landen unverändert in der bestehenden `facts`-Tabelle (`subject = cellId`,
`source_id = cellId`).

## Bekannte Einschränkungen Phase 1

- `object` ist immer `TEXT` — Datums-/Betrags-Vergleiche client-seitig
- Kein Backfill für vor V0027 erstellte Cells
- Drei Profile; weitere folgen iterativ
- Bei Fakten-Schreibfehlern wird die Cell still geloggt — kein Auto-Retry
