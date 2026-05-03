# HiveMem 9.0.0

The biggest release since the project started. HiveMem now ingests attachments,
extracts structured facts from documents, runs OCR/Vision on scans, and exposes
itself to claude.ai and ChatGPT as a fully OAuth-compliant Custom Connector.

## Highlights

- **OAuth 2.1 Custom Connector** — claude.ai and ChatGPT can connect natively
- **Attachment storage** — PDF/EML/image upload with dedup, thumbnails, and S3-compatible backend (SeaweedFS)
- **Document extraction Phase 1** — invoice/contract/other profiles produce typed facts in the knowledge graph
- **OCR Phase 1** — Tesseract for scanned PDFs with Claude Vision as fallback
- **Vision & Kroki enrichment** — diagrams render to PNG, images get descriptions, budget-capped
- **Auto-summarizer + embedding via summary** — long cells are summarised before embedding
- **Backup CLI** — operator-facing export/restore with move and clone modes

---

## OAuth 2.1 Custom Connector

HiveMem can now be added directly to claude.ai or ChatGPT as a Custom Connector
without a static API token.

- `/.well-known/oauth-authorization-server` and `/.well-known/oauth-protected-resource` discovery
- Dynamic Client Registration (RFC 7591) at `/oauth/register`
- `/oauth/authorize` with PKCE S256 (RFC 7636) — plain verifier rejected
- `/oauth/token` with refresh-token rotation; access tokens flow through `AuthFilter`
- Honors `X-Forwarded-Proto` / `X-Forwarded-Host` for Cloudflare Tunnel and reverse-proxy setups
- CORS configured for `claude.ai` and `chatgpt.com` origins
- Operator guide: `documentation/oauth/cloudflare-tunnel.md`, `documentation/oauth/custom-connector.md`

## Attachment Storage

Three new MCP tools and a full HTTP API for binary uploads.

- New tools: `upload_attachment`, `list_attachments`, `get_attachment_info` (tool count 31 → 34)
- Backend: SeaweedFS S3-compatible sidecar (pinned `chrislusf/seaweedfs:3.68`)
- Parsers: PDF (PDFBox + page-1 thumbnail), EML (angus-mail), images (resize), `text/plain`
- Content-addressed dedup on SHA-256; soft delete + reactivation
- Extracted text capped at 10 000 chars (surrogate-safe truncation, `text_truncated` flag)
- `realm` is required, `cell_id` is optional — uploads always create an extraction `Cell` if missing
- Permissions: enforced via existing `AuthFilter`; integration tests cover upload/dedup/permissions/soft-delete
- Schema: V0023 `attachments`, V0024 `cell_attachments` (replaces the old `references_` hack and drops `extracted_text` from cells)

## Document Extraction Phase 1

Cells produced from attachments now get a `document_type` and structured facts.

- New column: `cells.document_type` (V0027) with index
- `PreClassifier` heuristic routes content into `invoice`, `contract`, or `other` profiles
- Profiles are YAML on the classpath (`extraction/profiles/*.yaml`); `ExtractionProfileRegistry` falls back to `other`
- `AnthropicSummarizer` accepts a profile, returns `SummaryResult` with `(summary, facts[])`
- `SummarizerService` writes `document_type` and routes facts through `kg_add` with contradiction checks
- Operator docs: `documentation/extraction/phase-1.md`
- Integration test: invoice cell yields `document_type='invoice'` and at least one fact in the KG

## OCR Phase 1

Scanned PDFs no longer come out blank.

- `ScanDetector` flags PDFs whose extracted text is too sparse per page
- `PdfPageRasterizer` renders pages to PNG for OCR
- `TesseractRunner` shells out to system `tesseract` (deu+eng by default)
- `OcrService` orchestrates and merges OCR text back into the parser pipeline
- **Vision fallback**: pages where Tesseract returns near-empty text are re-described via Claude Vision (`OcrServiceVisionFallback`)
- Operator docs: `documentation/extraction/ocr.md`

## Enrichment: Vision & Kroki

Async, scheduled, budget-capped enrichment for already-stored attachments.

- `KrokiClient` renders Mermaid, PlantUML, GraphViz, and BlockDiag source to PNG
- `KrokiAttachmentParser` extracts diagram source from text-like attachments
- `VisionClient.describeImage` calls Claude Vision with size guard and image sub-type classification (`whiteboard`, `document-scan`, `photo-general`)
- `AnthropicVisionResponseParser` parses sub-type with fallback strategies
- `VisionBudgetTracker` enforces a daily cost cap on the new `vision_usage` table (V0028)
- `AttachmentEnrichmentService` runs async on `ThumbnailRequestedEvent` / `VisionDescriptionRequestedEvent` and sweeps `vision_pending` / `kroki_pending` tags via scheduled backfill
- Empty Vision responses tag the attachment with `vision_failed` instead of looping
- Operator docs: `documentation/enrichment.md`, `documentation/vision.md`

## Auto-Summarizer & Embedding via Summary

Long cells are summarised before embedding so vector quality stays high.

- `SummarizeBackfillStartupRunner` and scheduled backfill close existing gaps
- `SummarizeBudgetTracker` caps daily spend
- Embedding service consumes the summary instead of raw content for long cells
- See PR #61

## Backup CLI

Operator-facing CLI for full export/restore.

- `hivemem backup export` / `restore` with `--mode move|clone`
- Includes Postgres dump and SeaweedFS volume contents
- See PR #60

## Hook Improvements

- `SkipHeuristics` minimum prompt length raised 4 → 5 words
- Wider social-starter list (`hallo`, `guten morgen`, `wie geht's`, …)
- Extended meta-phrase list

## Documentation

Major restructure: the README is now a short overview, with the operator manual
living under `documentation/`:

- `documentation/architecture.md`, `tools.md`, `auth.md`, `operations.md`, `vision.md`
- `documentation/hook/{README,pipeline,configuration,output-format,roadmap}.md`
- `documentation/extraction/{phase-1,ocr}.md`
- `documentation/oauth/{cloudflare-tunnel,custom-connector}.md`
- README now shows an honest **Feature Status** matrix and a separate roadmap that links open GitHub issues
- `CONTRIBUTING.md` adds a documentation checklist

## Schema Changes

| Migration | Change |
|---|---|
| V0023 | `attachments` table |
| V0024 | `cell_attachments` table; **drop `cells.extracted_text`**; remove redundant index; document ON DELETE intent |
| V0025 | (reserved / parity bump) |
| V0026 | (reserved / parity bump) |
| V0027 | `cells.document_type` column + index |
| V0028 | `vision_usage` table for daily-cost budget |

Flyway parity test now expects 25+ migrations.

## Dependencies

- New: `software.amazon.awssdk:s3`, `org.apache.pdfbox:pdfbox`, `org.eclipse.angus:angus-mail`, `org.apache.commons:commons-compress`
- Dependabot bumps for all of the above merged on `main`

## Operations

- `docker-compose.yml` now ships a SeaweedFS sidecar — required for attachments
- New runtime dependencies (optional): `tesseract` binary, a reachable Kroki endpoint
- New config namespaces: `hivemem.attachments.*` (incl. `kroki.*`, `vision.*`), `hivemem.extraction.*`, `hivemem.oauth.*`, `hivemem.summarize.*`, `hivemem.ocr.*`

## Breaking Changes

- `cells.extracted_text` is gone — extracted text now lives on the linked attachment, surfaced via `cell_attachments`
- Old token-only auth still works, but Custom Connector setups should migrate to OAuth
- SeaweedFS is now part of the standard deployment; existing operators must add the sidecar before upgrading

## Tests

13.4k lines added across 197 files; new integration tests for OAuth end-to-end,
extraction, OCR (incl. Vision fallback), enrichment, attachments roundtrip,
backup CLI, embedding migration, and summarizer service.
