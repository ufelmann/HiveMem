# OCR for Scanned PDFs

HiveMem extracts text from scanned PDFs (no text layer) via Tesseract OCR. The
extracted text is written back to the cell, where the auto-summarizer (feature B)
turns it into a curated summary that is embedded for semantic search.

## Why OCR matters

Most PDFs that arrive in HiveMem from a personal-document workflow (Briefkasten-Scans,
Steuerbescheide, Verträge, Rechnungsfotos) have **no text layer** — they're images
inside a PDF wrapper. Without OCR, `PDFTextStripper` returns an empty string, the
cell's content falls back to the filename, and the document becomes invisible to
search. OCR fixes that structurally.

## Pipeline

1. PDF is uploaded; `PdfAttachmentParser` runs `PDFTextStripper`.
2. `ScanDetector` flags the PDF as scan-like when avg chars per page < 50.
3. The cell is created with the filename as content and tagged `ocr_pending`.
4. `OcrRequestedEvent` fires after commit. `OcrService` (async) downloads the PDF,
   rasterizes each page at 300 DPI, runs `tesseract -l deu+eng` per page, and
   aggregates the text with `[page=N]` markers.
5. `WriteToolService.reviseCell` writes the OCR text into the cell. The summarizer
   then takes over (it sees long content + no summary, fires Claude Haiku, writes
   a summary, and the embedding is recomputed against the summary).

## Enabling

Both features should be enabled together for the full pipeline:

    HIVEMEM_OCR_ENABLED=true
    HIVEMEM_SUMMARIZE_ENABLED=true
    ANTHROPIC_API_KEY=sk-ant-...

OCR alone (without the summarizer) gives you searchable raw text via the keyword
index but not via semantic embedding for long cells. Both together is the full
experience.

## Configuration reference

| Property | Default | Purpose |
|----------|---------|---------|
| `hivemem.ocr.enabled` | `false` | Master switch |
| `hivemem.ocr.tesseract-path` | `tesseract` | Path to the binary (env-PATH lookup) |
| `hivemem.ocr.languages` | `deu+eng` | Tesseract `-l` argument |
| `hivemem.ocr.scan-detection-threshold` | `50` | Min avg chars/page to be "not a scan" |
| `hivemem.ocr.render-dpi` | `300` | DPI for page rasterization |
| `hivemem.ocr.call-timeout-seconds` | `60` | Per-page tesseract timeout |
| `hivemem.ocr.backfill-interval` | `PT1H` | Documentation only — see note below |
| `hivemem.ocr.backfill-batch-size` | `5` | Cells per backfill run |
| `hivemem.ocr.max-pages` | `50` | Hard cap on pages OCR'd per PDF |

The actual scheduler interval is set via `HIVEMEM_OCR_BACKFILL_INTERVAL_MS`
(milliseconds). Default is `3600000` (1 hour).

## Adding more languages

The container image installs `deu` and `eng` by default. To add more:

    docker exec <container> apt-get install -y tesseract-ocr-fra

Or extend the Dockerfile in your fork. Then set `HIVEMEM_OCR_LANGUAGES=deu+eng+fra`.

## Performance expectations

- Per page: 1-3 s on modest hardware (CPU-bound).
- Backfill of an existing 1000-page archive: hours, not seconds. The hourly
  scheduler with `backfill-batch-size=5` processes ~120 pages/hour at the default
  DPI.

## Monitoring

Cells waiting for OCR:

    SELECT count(*) FROM cells WHERE 'ocr_pending' = ANY(tags);

Cells where OCR failed:

    SELECT count(*) FROM cells WHERE 'ocr_failed' = ANY(tags);

The backfill retries `ocr_failed` cells older than 1 hour automatically.

## Troubleshooting

**OCR produces gibberish:** language pack missing or wrong. Check
`tesseract --list-langs` inside the container; install missing packs.

**OCR returns empty text:** image quality too low (faded scan, dark photo) or
DPI too low. Try `HIVEMEM_OCR_DPI=400`. If still empty, try Vision OCR (Phase 2).

**`ocr_pending` stuck on many cells:** check application logs for tesseract
errors, or whether the attachment can be downloaded from SeaweedFS.

**PDF is password-protected:** PDFBox throws on load; cell gets `ocr_failed`.
Operator must remove the password externally.

## Limits and what's next

Phase 1 is Tesseract-only and PDF-only. Complex layouts (tables, whiteboard photos,
handwritten text, gekippte Scans) need Vision-OCR — which is planned as Phase 2 once
the multi-provider routing (item I) lands. Until then, those documents will be in
HiveMem with limited OCR quality, but they remain in SeaweedFS and you can re-process
them by removing the `ocr_failed` tag and waiting for the next backfill.
