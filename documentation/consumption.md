# Consumption Folder — Automatic Scan Ingest

The consumption pipeline lets you drop a stack of scanned documents into a
watched folder and have HiveMem ingest them automatically — including splitting
a mixed batch into individual documents based on content, without any barcode
or separator sheets.

## Purpose

| | HiveMem consumption pipeline | Paperless-ngx |
|---|---|---|
| Document boundary detection | **Content-based** (LLM reads OCR text, detects letterhead/signature/page-counter changes) | Barcode / patch-code / ASN separator sheets only |
| Separator sheet required | No | Yes (unless using ASN barcodes) |
| LLM requirement | Vistierie `document-separator` agent (Sonnet via `model_purpose = "separator"`) | None |
| Low-confidence splits | Land as `pending` → approval queue | Not applicable |
| Works without Vistierie | Yes — multi-page PDFs are ingested as one `pending` document (graceful degradation) | Yes |

## Hardware / ingress setup

The typical setup uses a **Brother ADS-2400N** (or any network scanner that
supports Scan-to-Network-Folder / SMB):

1. On the HiveMem LXC, install Samba and export the consumption directory:

   ```ini
   # /etc/samba/smb.conf — minimal share stanza
   [scans]
       path = /data/consumption
       valid users = scanner
       read only = no
       create mask = 0644
   ```

2. Bind-mount the share directory into the container in `docker-compose.yml`:

   ```yaml
   services:
     hivemem:
       volumes:
         - /data/consumption:/data/consumption
   ```

   The default container path (`/data/consumption`) matches
   `hivemem.consumption.dir`. Change the right side of the bind-mount if you
   configured a different path.

3. Configure the scanner's Scan-to-Network-Folder destination to the Samba
   share (IP of the LXC, share name `scans`, credentials matching `valid users`
   above).

4. Enable the pipeline in your environment:

   ```
   HIVEMEM_CONSUMPTION_ENABLED=true
   ```

## How it works

### Polling and stability detection

`ConsumptionWatcher` polls the consumption directory every `poll-interval`
(default 10 s). A file is eligible for ingest only after it has been **stable**
for `stable-seconds` (default 5 s) — meaning its size and mtime were identical
across two consecutive polls and the mtime is at least `stable-seconds` old.
Dotfiles (`.*`) and the `processed/` / `failed/` / `processing/` subdirectories
are ignored (the watcher's directory scan is non-recursive and skips
non-regular files).

### Single-document path (M1)

Any file that is **not a multi-page PDF**, or any PDF whose page count is ≤ 1,
or any file when Vistierie is unavailable (Queen disabled), is ingested
directly:

- MIME type is guessed from the filename.
- `AttachmentService.ingest` creates a `committed` cell in the configured
  `realm` with `source = "consumption"`.
- The file moves to `processed/`.

### Multi-page PDF batch-separation path (M2)

If `hivemem.queen.enabled=true` AND the file is a multi-page PDF:

0. **Stage out of the watch path.** The source is moved into `processing/`
   **before** any work begins. Because the watcher's scan is non-recursive,
   the file is never re-scanned (and thus never re-dispatched) while in flight.
   The `consumption_jobs.source_path` records this staged path.
   If the real page count exceeds `max-pages`, the batch is **rejected**: it is
   logged and moved to `failed/` (no job, no dispatch) instead of being silently
   truncated and mis-split. Re-scan in smaller batches or raise `max-pages`.
1. **Rasterize + OCR.** Each page is rasterized at the configured DPI and run
   through Tesseract.
2. **Build page digests.** Each page is distilled into a `PageDigest`:
   `page` (1-based), `head` (first ~300 OCR chars), `tail` (last ~100 chars),
   `blank` (bool), `hasPageMarker` (`Seite X von Y` / `Page X of Y` found).
3. **Store batch.** The original PDF is uploaded to SeaweedFS under
   `consumption/batch-<correlationId>.pdf`. A row is inserted into
   `consumption_jobs` (status `awaiting`).
4. **Dispatch to Vistierie.** `VistierieSeparationClient` POSTs to
   `/agents/document-separator/run` with a body of
   `{payload, completion_webhook, completion_webhook_token}` — the page digests
   and the correlation id ride inside `payload`, and `completion_webhook` points
   back to `POST /vistierie/separation/done` on HiveMem. Vistierie returns a
   `run_id`, which HiveMem stores on the job (`vistierie_run_id`); the callback
   carries no correlation id, so the run id is what links it back.
5. **Webhook result.** When Vistierie finishes, it calls
   `POST /vistierie/separation/done` (authenticated with
   `hivemem.queen.separation-webhook-token`) with the envelope
   `{run_id, status, output, error, …}`, where the separator agent's
   `output_schema` shapes `output` as `{boundaries:[{afterPage,confidence}]}`.
   HiveMem looks up the awaiting job by `run_id` and:
   - Retrieves the batch PDF from SeaweedFS.
   - Applies the boundaries from `output` to split the PDF
     (`BatchSplitter` using PDFBox). An empty boundary list is a valid result:
     the whole stream becomes one document. A non-`done` status or missing
     `output` leaves the job awaiting for the reconcile sweep to degrade.
   - Ingests each part: the first part is always `committed`; subsequent
     parts are `committed` if the boundary confidence ≥ `confidence-threshold`
     (default 0.80), otherwise `pending` (lands in the approval queue).
   - Marks the job `done` (before the move), then moves the staged source from
     `processing/` to `processed/`. A move failure is logged only and does not
     re-fail the job — the sub-documents are already ingested.

If the dispatch call to Vistierie itself fails (Vistierie unreachable), the job
is left `awaiting` and the source stays in `processing/` with its batch already
in SeaweedFS; the reconcile sweep degrades it later. The file is **not** moved
to `failed/` in that case.

### Reassembly mode (non-contiguous pages)

The M2 separation path above assumes a document's pages are **contiguous** — it
only decides *where to cut* the page stream. That fails when one physical scan
interleaves several documents whose pages are scattered (e.g. a stack fed out of
order, or duplex pages landing apart). **Reassembly mode** regroups the pages of
one batch into individual documents **by content**, even when a document's pages
are non-contiguous or shuffled.

It is gated behind `hivemem.consumption.reassembly-enabled` (**default off**).
When off, behavior is exactly today's contiguous separation path. When on, and
the file is a multi-page PDF with Queen enabled, reassembly takes precedence over
the contiguous separation path.

How it works — a **3-pass pipeline**, all calls routed via the `reassembly-purpose`
(default `separator`) purpose, ~2·N+1 LLM calls per N-page batch (runs off the
consumption executor, never throws to the caller):

1. **Pass 1 — orientation, per page.** Every page is rendered at
   `reassembly-render-dpi` (default 150 — lower than OCR DPI, to keep the vision
   payload small). Before the vision call, a cheap pixel pre-check
   (`blank-filter-enabled` / `blank-skip-white-fraction`) looks at the rendered
   page's white-pixel fraction: a page this white gets **no orientation call at
   all** — a blank page has no meaningful orientation, so the call would be
   spent on nothing. Any page under the threshold is unaffected and always gets
   the call. For a page that *is* skipped, `PageOrienter` never runs: the page
   keeps its original rotation, and if it does turn out to carry real content
   (see the accepted trade-off below), it is stored without rotation
   correction. For every other page, `PageOrienter` shows the model the page
   twice — original (A) and rotated 180° (B) — and asks it to pick the upright
   one plus a blank verdict. The winning rotation is baked into the page image
   via PDF `/Rotate` so everything downstream (and the stored PDF) sees an
   upright page.

   **The pixel pre-check never deletes a page by itself.** It only suppresses
   the orientation call; the metadata call below still runs on every page
   regardless, and its verdict remains the sole authority that puts a page on
   the delete list. Its threshold is deliberately looser (fires on more pages)
   than the post-check described in step 5, precisely because it can only ever
   save a vision call, never cause a deletion.

   **Accepted trade-off.** A page that is genuinely near-white except for a
   small mark — a stamp, a signature, a short note — reads as pixel-blank by
   this check and skips orientation, but is still evaluated (and, if the model
   finds content on it, kept) by the metadata call in the next pass. Such a
   page is stored as-is, without rotation correction, and can end up upside
   down in the archive. A misoriented page is recoverable by re-inspection; a
   silently deleted one is not — so the design accepts occasional missed
   rotation in exchange for never letting a pixel measurement alone decide
   that a page is gone.
2. **Pass 2 — per-page metadata, on upright images.** `PageMetadataExtractor`
   reads each upright page alone (one image per call — no cross-page labeling
   ambiguity) and extracts sender, date, printed page label, doc type, reference
   and a one-line summary. If both extraction attempts fail to parse (the model
   occasionally answers a near-white page with prose instead of the expected
   structured output) *and* the page was pixel-blank per the pre-check above,
   the page is recorded as **not degraded** — closing the gap where a
   near-white page whose reply merely failed to parse used to be counted as a
   metadata loss and flood the review queue. It is **not** recorded as blank:
   `blank` is the delete list and no model verdict exists in this branch. So
   during a vision-provider outage, where every page fails both attempts, no
   page is deleted on a pixel judgement — a genuinely white backside is still
   caught by the post-check in step 5. The cost is an occasional blank page
   surviving into the archive, which is the same trade as the missed rotation
   above: recoverable, unlike a deletion.
3. **Pass 3 — assembly, text-only.** `MailingAssembler` sends all pages'
   extracted metadata (no images) and asks the model to group pages into
   mailings, in reading order within each mailing. Grouping is a reasoning
   task over already-extracted facts, not a vision task. To dampen the
   model's run-to-run variance on this step, the grouping is drawn
   `reassembly-draws` times (default 3) and the drawn partitions are merged
   by pairwise majority: for every page pair, the number of draws that put
   both pages in the same mailing must reach a strict majority
   (`draws / 2 + 1`) for the pair to be unioned, and union-find then closes
   the resulting chains. This is affordable because grouping is one text
   call per batch, while orientation (pass 1) and metadata extraction
   (pass 2) are each one vision call per page — three draws cost a small
   fraction more calls than one. Setting `reassembly-draws: 1` restores the
   old single-call behaviour exactly, with no vote.
   The grouping is requested as the input of a forced `submit_mailings` tool
   call (`CompleteClient.completeWithTool`), not parsed from free text: a
   model that is free to answer in prose spends part of its output budget on
   an analysis before the JSON, which on large batches ran the response
   budget or the clock out before the JSON was ever reached — measured at
   8 % of draws. Text parsing (`CompleteClient.complete` +
   `LlmJson.parseArray`) remains as a fallback for a provider response that
   carries no matching `tool_use` block, or whose `mailings` field is not an
   array — the tool call is announced but not enforced end-to-end, so
   `MailingAssembler.parseDraw` degrades to text parsing instead of failing
   the draw.
4. **Normalization, deterministic.** `MailingAssembler.assemble` runs pass 3's
   grouping through `MailingNormalizer` before returning it — this is plain Java,
   no LLM call, and enforces what the prompt can only ask for:
   - **Merge.** Two mailings whose first usable page shares a sender and an issue
     date are merged into one. The sender is compared case- and
     punctuation-insensitively; a date carrying a `Stand ...` prefix (the print
     date of a generic enclosure such as a Datenschutz notice) never anchors a
     mailing, and the contract/customer/tax reference is deliberately left out of
     the key — a differently-read Steuernummer would otherwise split one Bescheid
     into two. The reference is consulted separately, and only to REFUSE a merge:
     two mailings sharing sender and date stay apart when their references are
     *clearly* different, which is how an insurer's annual mailing (several
     separate letters, all sent on one day) survives as several documents. Clearly
     different tolerates OCR noise — confusable characters (O/0, I/1, L/1, S/5, B/8,
     Z/2, G/6) are folded together, a reference containing another counts as the same
     one so a label prefix cannot split a letter, and the edit distance must exceed a
     quarter of the reference length. The same containment test also runs on the digit
     runs alone: the extractor re-words the label per page, so one page carries
     `Konto-Nr. 6100000` and the next `Kontonummer 6100000`, and comparing digits
     survives a re-wording that the full strings would not. A reference shorter than
     four characters after normalization is treated as absent and can never split two
     letters. In doubt the pages stay in one mailing: a wrongly merged document is
     easy to spot and repair, a wrongly split one is not.
     The merged mailing's confidence is the minimum over all merged
     mailings, which biases a merge towards the `pending` review queue rather
     than guaranteeing it.
   - **Page placement on merge.** A page pulled in by a merge is inserted right
     behind its own printed-label family in the target mailing — not appended at
     the end — but only while that family stays unambiguous (no label number
     repeated on either side). Otherwise it is appended.
   - **Ordering.** Blank pages always sort last. The remaining pages are ordered
     by printed label only when the mailing is one complete printed document:
     every page labelled, all labels sharing one total, and the numbers exactly
     `1..N`. A mailing that mixes a letter with enclosures is never reordered —
     without a way to tell two printed sequences apart, reordering could splice
     one document into another.
5. **Blank drop.** A page is dropped if either of two signals calls it blank: a
   model verdict — the pass-1 orientation call's blank vote (on pages that got
   one) or the pass-2 metadata reply's `blank` field — or the pixel-based
   post-check (`blank-filter-enabled` / `blank-white-fraction`) applied to
   every page regardless of the pre-check outcome. This post-check is
   intentionally **stricter** (fires on fewer pages) than the pre-check in step 1 above — the
   pre-check only ever skips a vision call, while this one drops the page
   outright, so it is held to a tighter whiteness bar. A mailing whose pages
   are all blank never becomes a cell.
6. **Status.** A mailing is `committed` if its minimum confidence ≥
   `reassembly-confidence-threshold` (default **0.5** — aggressive, so most
   mailings commit), otherwise `pending`. With the pass-3 vote on
   (`reassembly-draws` > 1), that confidence is not the raw model number: it is
   `base confidence × agreement`, where `base` is the confidence of the
   best-matching draw group and `agreement` is how much the draws agreed on the
   mailing's page pairs — so a mailing the draws only agreed on 2 of 3 times
   commits at 0.6 even if every draw that mentioned it reported 0.9. With
   `reassembly-draws: 1` there is no vote, and the value is the raw model
   confidence as before.
7. **Split + ingest.** `BatchSplitter.assemble` builds one PDF per mailing
   (arbitrary page order supported, in the normalized order step 4 produced), and
   each is ingested with `source = "consumption:"`. The staged source moves to
   `processed/`.

**Degrade-safe (batch level, "degrade to pending").** On any error
(vision/completion call fails, JSON unparseable, etc.) the whole batch is ingested
as a single `pending` document and the source is moved to `processed/`. Nothing is
lost. Note that this document uses *degraded* in two unrelated senses, and they
are not interchangeable:

- **degrade to pending** — batch level, described here: reassembly gave up
  entirely, so no boundaries were applied and one document covers the whole scan.
- **degraded pages** — page level, described under *Page statistics* below: the
  batch WAS reassembled, but individual pages contributed no vision metadata to
  the boundary decision.

**Page statistics — `total_pages` / `degraded_pages` / `blank_pages`.** A page is
*degraded* when its metadata extraction failed both attempts and fell back to an
all-null row. Such a page contributes nothing to the boundary decision, and
nothing downstream can notice: the assembler scores its own grouping, not the
completeness of its input, so a batch cut from half-blind input can still report
high confidence. After the pass completes, `ReassemblyOrchestrator` therefore
records these counts on the batch's `consumption_file` row: `total_pages` and
`degraded_pages` (added in V0054), and `blank_pages` (added in V0055).
`blank_pages` counts every page recognised as blank by *any* of the three
signals listed under step 5 above — vision-voted and pixel-skipped pages alike —
so an operator can see when a batch is quietly losing most of its pages to the
blank-page filter, something that used to be visible only as a log line.

All three columns stay NULL when the batch never finished a pass — the
degrade-to-pending path above leaves them unset. NULL means *unknown*, never
*clean*: the review query requires `total_pages > 0` precisely so a NULL batch
cannot pass the filter as healthy in either direction. (Such a batch is still
visible, as a `pending` document in the approval queue.)

`consumption_queue` flags a batch for human review when **either** of two
independent conditions holds:

- a degraded-page branch: at least `min-degraded-pages` (configurable, default
  **1**) degraded pages, **and** more than **2 %** of the batch's pages
  degraded; or
- a blank-ratio branch: `blank_pages` / `total_pages` exceeds
  `blank-ratio-alert` (default **0.60**).

The degraded-page branch's two conditions guard opposite ends: the percentage
keeps a large batch from being flagged over a couple of pages, the floor keeps
a small batch from being flagged over a single one. The floor is deliberately
configurable rather than fixed, because how low it is safe to set depends on
how reliably blank pages are kept from producing spurious degradations — see
the blank-page pre-skip above. The blank-ratio branch exists because
`blank_pages` would otherwise never reach the queue for a batch with zero
degraded pages, which is exactly the batch this column was added to surface:
a batch losing most of its pages to the blank-page filter without a single
degraded page tripping the first branch. Its threshold sits well above the
blank-page ratio produced by ordinary duplex scanning (roughly half a
duplex batch's pages are blank backsides in the common case), so it fires only
when the blank-page filter itself looks like it has gone wrong, not on routine
duplex documents. A queue that is always full is a queue nobody reads, and one
degraded page in a hundred is normal wear, not a defect worth an operator's
attention.

**Operator note — `recovery-stale-threshold` vs. reassembly latency.** The 3-pass
pipeline makes ~2·N+1 sequential LLM calls per batch, so a very large batch's
worst-case latency (with retries) is roughly (2·N+1) × 2 × the queen call
timeout. `recovery-stale-threshold` (default 30 min) must comfortably exceed
that, or `ConsumptionRecoverySweep` can re-stage a still-running batch as
crash-stranded, causing double-processing. Defaults are safe by a wide margin
(a 17-page batch ≈ 3 min ≪ 30 min) — but raise the threshold if you raise
`max-pages` well beyond the default. `ReassemblyOrchestrator` also touches the
ledger row's `updated_at` once per page within its single streaming pass, as a
heartbeat, further reducing the risk for in-flight batches. This is a change
from the previous two-pass design, which touched twice per page (once per
pass): heartbeat frequency halved from 2·N to N touches per batch, so the
maximum gap between heartbeats is now up to two sequential LLM calls (orient +
extract for one page) rather than one. That still stays well inside the
recovery sweep's stale threshold, since the touch remains per page rather than
per batch.

**Memory / batch size.** The whole batch's `pdfBytes` and the assembled `parts`
(one PDF per mailing, from `BatchSplitter.assemble`) are held in memory for the
duration of one `ReassemblyOrchestrator.reassemble` call, and up to
`worker-threads` batches run concurrently. Page *rendering* itself streams:
`PdfPageRasterizer.rasterize` hands each page's PNG to a callback and discards it
before rendering the next, so the rasterizer's own footprint does not scale with
batch size — but two things still do: the original `pdfBytes` array and the
fully-assembled `parts` list are both held whole, for every concurrent batch.

`ReassemblyHeapProbeIT`
(`java-server/src/test/java/com/hivemem/consumption/ReassemblyHeapProbeIT.java`,
run with `./mvnw -Dit.test=ReassemblyHeapProbeIT verify`) measures this
directly by driving a full `reassemble(...)` call — not the rasterizer alone —
over synthetic PDFs, with `PageOrienter`/`PageMetadataExtractor`/`MailingAssembler`
mocked (no LLM calls) and the real `PdfPageRasterizer`/`PageReassembler`/
`BatchSplitter`. A first version of this probe sampled raw allocation
(`totalMemory() - freeMemory()` without an intervening GC) and divided by page
count; that recovered ~16.9 MB/page, which turned out to be almost exactly the
size of ONE in-flight rendered page image (1240×1754 px at the 150 DPI
reassembly setting, ~8.7 MB as an `int` raster) — i.e. the cost of one image
allocated and discarded 200 times, not a per-page *retention* cost. Since the
streaming rasterizer keeps exactly one page image alive at a time, that number
was measuring garbage-not-yet-collected, not memory the cap would actually need
to bound.
This paragraph reports the corrected measurement: `System.gc()` immediately
before every reading, so only what is still reachable counts, sampled at three
checkpoints (after the streaming render/orient/extract pass, after
`BatchSplitter.assemble` produces `parts`, and at the end of `reassemble`), at
page counts 50/100/200, under two page→document groupings.

Measured on 2026-08-02 (JDK 26.0.2, `reassembly-render-dpi=150`,
`blank-filter-enabled=false` so the synthetic blank pages reach
`BatchSplitter`/ingest instead of being dropped — see the test), retained heap
after `parts` is assembled (bytes, relative to a pre-call `System.gc()`
baseline), 3 runs per configuration after one unmeasured warm-up run:

| Pages | Grouping | Run 1 | Run 2 | Run 3 | Avg |
|---|---|---|---|---|---|
| 50  | every page its own doc (worst case) | 594,880 | 594,808 | 595,000 | 594,896 |
| 100 | every page its own doc (worst case) | 1,183,584 | 1,181,048 | 1,182,376 | 1,182,336 |
| 200 | every page its own doc (worst case) | 2,365,272 | 2,365,160 | 2,366,360 | 2,365,597 |
| 200 | ~10 pages/doc (realistic duplex batch, 20 docs) | 2,229,760 | 2,228,656 | 2,227,832 | 2,228,749 |

**Retained heap does grow roughly linearly with page count** — 594,896 →
1,182,336 → 2,365,597 for 50/100/200 pages is within 1% of exactly doubling
each time — but the slope is **≈11.8 KB/page**
(`(2,365,597 − 594,896) / (200 − 50) ≈ 11,805 bytes/page`, confirmed by the
50→100 and 100→200 pairs independently, both within 1% of that figure). That
is structural overhead only (the synthetic blank-page `pdfBytes` bytes
themselves, plus the small `PageMetadata` records) — it is NOT image data,
because the streaming rasterizer already keeps image data at O(1). **This means page count by
itself is not the binding constraint any more**, and deriving a page cap from
this slope would be nearly meaningless: at this rate, `0.5 × 4 GiB /
(4 workerThreads × 11,805 bytes/page) ≈ 45,500 pages` before hitting the
heap budget from structural overhead alone — orders of magnitude above any
plausible batch. (The grouping shape matters far less than expected too: 200
one-page documents vs. 20 ten-page documents differ by only ~137 KB, i.e.
~760 bytes of extra `BatchSplitter` container overhead per extra document —
also negligible next to real scan data.)

**The real constraint is batch FILE SIZE, not page count**, because
`pdfBytes` and `parts` scale with how much actual image data a real scanned
page carries — and these synthetic fixtures (blank vector A4 pages) carry
essentially none. Expressed as a file-size budget with the same formula, assuming
`worker-threads = 4` and `-Xmx4g` (the shipped default for `worker-threads` is 2,
so this is the more conservative of the two) and assuming worst case `parts` total
size ≈ `pdfBytes` size (the split re-partitions the same page data, so the
two terms are the same order of magnitude):

    fileSizeCap = (0.5 × Xmx) / (workerThreads × 2)
                = (0.5 × 4 GiB) / (4 × 2)
                = 2 GiB / 8
                = 256 MiB per batch

Translating that budget into a page count needs a per-page byte size, and that
figure is **specific to your scanner**, not to this software: resolution,
colour mode and compression change it by an order of magnitude. Text
correspondence at moderate resolution lands in the hundreds of KB per page;
photographic or colour-heavy originals can reach several MB. A synthetic
worst case — a full-page 300 DPI JPEG of dense pixel noise at quality 0.75 —
measures ~3.9 MB/page and is useful only as a pathological upper bound.

Measure your own instead of trusting either end of that range:

```sql
SELECT count(*) AS files,
       sum(page_count) AS pages,
       round(sum(size_bytes)::numeric / sum(page_count) / 1024) AS kb_per_page
FROM attachments
WHERE page_count IS NOT NULL;
```

Then `maxPages = fileSizeCap / bytesPerPage`. At 500 KB/page that is ~525
pages; at 2 MB/page it is ~128, which is below the shipped default.

**The number to watch when raising `max-pages` is total batch BYTES, not page
count** — `pdfBytes` and the assembled `parts` are both held whole per
concurrent batch, so a batch's memory cost follows its file size. Re-measure
after any scanner settings change, and re-run `ReassemblyHeapProbeIT` plus
this derivation if `-Xmx` or `worker-threads` change.

### File disposition

| Outcome | Destination |
|---|---|
| Single-doc ingest succeeded | `<dir>/processed/` |
| Multi-page PDF in flight (awaiting separation) | `<dir>/processing/` |
| Separation applied / degraded | `<dir>/processed/` |
| Read / single-doc-ingest / separation-prep error | `<dir>/failed/` |
| Page count > `max-pages` | `<dir>/failed/` |

Collision-safe: if a file with the same name already exists in the target
subdirectory, a monotonic counter suffix is appended (`scan-1.pdf`,
`scan-2.pdf`, …).

## Requirements for auto-split

Auto-split requires both pipelines to be active:

- `hivemem.consumption.enabled=true`
- `hivemem.queen.enabled=true` (with Vistierie base URL, `HIVEMEM_VISTIERIE_TOKEN`,
  `HIVEMEM_QUEEN_HIVEMEM_BASE_URL`, and `HIVEMEM_QUEEN_SEPARATION_WEBHOOK_TOKEN` set)

If the Queen is disabled, multi-page PDFs are ingested as a single document on
the direct path (no split attempted).

## Graceful degradation

If the Vistierie separation webhook never arrives (Vistierie down, agent
misconfigured, etc.), `SeparationReconcileSweep` runs every
`reconcile-interval-ms` (default 5 min) and picks up any `awaiting` job older
than 10 minutes. It ingests the whole batch PDF as a single `pending` document,
marks the job `done`, then moves the staged source from `processing/` to
`processed/` (a move failure is logged only). **Nothing is lost.**

Re-dispatch is not attempted because per-page digests are not persisted between
the initial dispatch and the sweep — the sweep degrades rather than retries.

## Configuration reference

### `hivemem.consumption.*`

| Property | Env var | Default | Description |
|---|---|---|---|
| `enabled` | `HIVEMEM_CONSUMPTION_ENABLED` | `false` | Master switch. Set to `true` to activate the watcher. |
| `dir` | `HIVEMEM_CONSUMPTION_DIR` | `/data/consumption` | Absolute path to the watched folder. |
| `realm` | `HIVEMEM_CONSUMPTION_REALM` | `documents` | HiveMem realm cells are created in. |
| `poll-interval` | `HIVEMEM_CONSUMPTION_POLL_INTERVAL` | `PT10S` | How often the watcher scans the directory (ISO 8601 duration). |
| `stable-seconds` | `HIVEMEM_CONSUMPTION_STABLE_SECONDS` | `5` | Seconds a file must be size+mtime-unchanged before ingest begins. |
| `max-pages` | `HIVEMEM_CONSUMPTION_MAX_PAGES` | `200` | Maximum pages rasterized + OCR'd per batch PDF. Pages beyond this limit are not included in the digest. |
| `confidence-threshold` | `HIVEMEM_CONSUMPTION_CONFIDENCE` | `0.80` | Minimum confidence for a split boundary to produce a `committed` cell. Below this value the part is `pending`. |
| `max-dispatch-retries` | `HIVEMEM_CONSUMPTION_MAX_RETRIES` | `3` | Reserved; re-dispatch is not implemented (see degradation note). |
| `reconcile-interval-ms` | `HIVEMEM_CONSUMPTION_RECONCILE_MS` | `300000` | Interval in ms for the stale-job reconcile sweep (default 5 min). |
| `worker-threads` | `HIVEMEM_CONSUMPTION_WORKER_THREADS` | `2` | Size of the bounded worker pool that runs ingest+OCR. The `@Scheduled` poll thread only detects a stable file, stages it to `processing/`, and submits it to this pool — so multi-page OCR never blocks the poll or other scans. Backpressure (`CallerRunsPolicy`) applies under a burst; nothing is dropped. |
| `reassembly-enabled` | `HIVEMEM_CONSUMPTION_REASSEMBLY_ENABLED` | `false` | Master switch for **reassembly mode** (content-based regrouping of non-contiguous pages). When off, the contiguous separation path is used. When on (with Queen + multi-page PDF), it takes precedence. |
| `reassembly-confidence-threshold` | `HIVEMEM_CONSUMPTION_REASSEMBLY_CONFIDENCE` | `0.5` | Minimum per-group confidence for a `committed` document; below it the group is `pending`. Aggressive default — most groups commit. |
| `reassembly-render-dpi` | `HIVEMEM_CONSUMPTION_REASSEMBLY_DPI` | `150` | DPI used to rasterize pages into the vision payload (downscaled vs. OCR DPI to keep requests small). |
| `reassembly-purpose` | `HIVEMEM_CONSUMPTION_REASSEMBLY_PURPOSE` | `separator` | Vistierie routing purpose for all 3-pass reassembly calls. Needs a routing rule pointing at a vision-capable model (Haiku works; Sonnet for harder visual grouping). |
| `reassembly-max-tokens` | `HIVEMEM_CONSUMPTION_REASSEMBLY_MAX_TOKENS` | `16384` | Max output tokens for each of the three passes' responses. Raised from `4096`, which was too tight for the mailing-assembly pass on large batches: the model reasons in prose before emitting its JSON, so a long batch could spend the whole budget on the reasoning and be cut off before the payload — surfacing as `no JSON payload in LLM output` and costing a retry. Only calls that exceeded the old budget ever failed this way; none below it did. |
| `reassembly-draws` | `HIVEMEM_CONSUMPTION_REASSEMBLY_DRAWS` | `3` | How many independent groupings pass 3 draws before the pairwise-majority vote. `1` disables the vote and reproduces the old single-call behaviour. |
| `blank-filter-enabled` | `HIVEMEM_CONSUMPTION_BLANK_FILTER_ENABLED` | `true` | Master switch for BOTH pixel-based signals: the pre-check that skips a page's orientation call (`blank-skip-white-fraction`) and the post-check that drops a page outright (`blank-white-fraction`). The LLM's own blank verdicts from passes 1 and 2 always apply regardless of this flag; disabling it just stops the pixel-based signals from also acting. A document whose pages are all blank (by any signal) is dropped entirely, so it never becomes a cell. |
| `blank-white-fraction` | `HIVEMEM_CONSUMPTION_BLANK_WHITE_FRACTION` | `0.995` | Post-check: fraction of near-white pixels above which a page is dropped outright, whatever the model said. Higher = more conservative (fewer pages dropped). |
| `blank-skip-white-fraction` | `HIVEMEM_CONSUMPTION_BLANK_SKIP_WHITE_FRACTION` | `0.97` | Pre-check: fraction of near-white pixels above which a page's orientation call is skipped (the metadata call still runs and still decides deletion). Deliberately looser than `blank-white-fraction` — it only ever saves a vision call, never causes a deletion, so it can afford to fire on more pages. |
| `min-degraded-pages` | `HIVEMEM_CONSUMPTION_MIN_DEGRADED_PAGES` | `1` | Floor for the review queue's degraded-page branch (see *Page statistics* above). A batch needs at least this many degraded pages, and more than 2 % of its pages degraded, to be flagged on that branch. |
| `blank-ratio-alert` | `HIVEMEM_CONSUMPTION_BLANK_RATIO_ALERT` | `0.60` | Threshold for the review queue's blank-ratio branch: a batch is flagged when `blank_pages / total_pages` exceeds this value, independently of `degraded_pages`. |

### New `hivemem.queen.*` keys added by this feature

| Property | Env var | Default | Description |
|---|---|---|---|
| `separation-webhook-token` | `HIVEMEM_QUEEN_SEPARATION_WEBHOOK_TOKEN` | `""` | Bearer token HiveMem expects Vistierie to present on `POST /vistierie/separation/done`. Must be set when consumption + queen are both enabled. |
| `document-separator-agent` | `HIVEMEM_QUEEN_SEPARATOR_AGENT` | `document-separator` | Vistierie agent name to dispatch separation jobs to. |

## Known limitations and assumptions

1. **Vistierie run-creation contract (reconciled).** `VistierieSeparationClient`
   calls `POST /agents/{name}/run` (singular) with
   `{payload, completion_webhook, completion_webhook_token}`, matching
   Vistierie's `RunController#trigger`. The correlation id and page digests ride
   inside `payload`; the callback is correlated by the returned `run_id`
   (stored as `consumption_jobs.vistierie_run_id`), since Vistierie's completion
   webhook carries no correlation id of its own.

2. **`model_purpose = "separator"` requires a Vistierie routing rule.** The
   `document-separator` agent definition sets `model_purpose = "separator"`
   rather than a hard-coded model ID. Vistierie's `RoutingResolver` needs a
   routing rule mapping `purpose=separator` → a provider+model (intended:
   Bedrock Claude Sonnet), or a wildcard rule; otherwise runs fail with
   "no routing rule". This is Vistierie-side configuration.

3. **No barcode / separator-sheet support.** Boundary detection is purely
   content-based. If your scanner produces separator sheets, they will be
   treated as (likely blank) pages and may or may not trigger a boundary.

4. **No split/merge correction UI.** Low-confidence splits land in the approval
   queue as `pending` cells. Review and approval use the standard
   `approve_pending` workflow. A dedicated correction UI (merge/re-split) is not
   yet implemented.

5. **Page-digest truncation.** Batches larger than `max-pages` are rasterized
   only up to that limit. Boundaries beyond the limit are never detected; those
   pages are folded into the last split part.
