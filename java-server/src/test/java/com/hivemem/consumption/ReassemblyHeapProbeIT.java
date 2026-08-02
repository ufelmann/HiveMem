package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hivemem.attachment.AttachmentService;
import com.hivemem.ocr.PdfPageRasterizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

/**
 * Measures RETAINED heap (not raw allocation) across a FULL {@link ReassemblyOrchestrator#reassemble}
 * call, at three page counts and two grouping shapes.
 *
 * <p><b>Why retained, not allocated:</b> a first version of this probe sampled
 * {@code totalMemory() - freeMemory()} continuously without an intervening {@code System.gc()}, which
 * counts garbage that has been allocated but not yet collected. Dividing that running total by page
 * count recovered ~16.9 MB/page — almost exactly the size of ONE rendered page image
 * (a blank A4 at 150 DPI is 1240x1754 px; as an {@code int} raster that alone is ~8.7 MB, plus the PNG
 * encode buffer). That is the cost of one image in flight, discarded 200 times over — not a per-page
 * retention cost, and not a valid basis for a per-page cap in either direction. Task 3 made the
 * rasterizer stream one page at a time, so exactly one page image is ever alive; retained memory does
 * not grow with page count from rendering alone. This version forces {@code System.gc()} immediately
 * before every reading, so only what is still reachable at that instant counts.
 *
 * <p><b>What is measured:</b> three checkpoints per run — after the streaming analysis pass
 * (render+orient+extract) completes, after {@link BatchSplitter#assemble} has produced the {@code
 * parts} list, and after {@code reassemble(...)} returns — at page counts 50/100/200, and (since the
 * {@code parts} list' overhead depends on how many documents a batch splits into) at 200 pages under
 * two grouping shapes: every page its own document (maximises per-PDF overhead) and a realistic
 * ~10-pages-per-document grouping (what an actual duplex mailing batch looks like).
 *
 * <p>This is a measurement, not a correctness gate: it asserts only that some allocation was observed
 * and prints the numbers for a human (or {@code documentation/consumption.md}) to read. It
 * intentionally runs as an *IT test (see maven-surefire's {@code **}/*IT.java exclude and
 * maven-failsafe's IT-only executions in {@code pom.xml}) so a normal {@code mvn test} never pays for
 * it and CI never treats its noisy numbers as a pass/fail gate.
 */
class ReassemblyHeapProbeIT {

    private static final int[] PAGE_COUNTS = {50, 100, 200};
    private static final int RUNS = 3;
    /** Roughly what a real duplex-scanned mailing (letter + enclosures, front/back) runs to. */
    private static final int REALISTIC_GROUP_SIZE = 10;

    /** Same three-line helper as the Task 3 fixtures (kept local on purpose — it lives in a
     *  different package there only by accident of file layout, not by shared ownership). */
    private static byte[] blankPdf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static PageOrienter mockOrienter() {
        PageOrienter orienter = mock(PageOrienter.class);
        when(orienter.orient(anyString(), anyInt(), any()))
                .thenAnswer(inv -> new PageOrienter.PageOrientation(inv.getArgument(1), 0, false, 0.9));
        return orienter;
    }

    private static PageMetadataExtractor mockExtractor() {
        PageMetadataExtractor extractor = mock(PageMetadataExtractor.class);
        when(extractor.extract(anyString(), anyInt(), any()))
                .thenAnswer(inv -> new PageMetadataExtractor.PageMetadata(inv.getArgument(1),
                        "S", null, null, "letter", null, "p", false, false));
        return extractor;
    }

    /** Empty groups, exactly as {@code ReassemblyPartialFailureTest.mockAssembler()}: every page
     *  falls through {@link PageReassembler} as its own 1-page orphan document. That is the
     *  memory-worst case for the {@code parts} list — {@link BatchSplitter} re-serializes each page
     *  into its own small PDF, so per-document overhead is paid once per PAGE instead of amortised
     *  over a handful of mailings. */
    private static MailingAssembler mockOrphanAssembler() {
        MailingAssembler assembler = mock(MailingAssembler.class);
        when(assembler.assemble(anyString(), anyList())).thenReturn(List.of());
        return assembler;
    }

    /** Groups pages into {@link #REALISTIC_GROUP_SIZE}-page documents — the shape a real duplex
     *  batch actually takes (a letter plus its enclosures, scanned front/back), rather than the
     *  worst-case one-page-per-document shape above. Confidence 1.0 so every group commits, matching
     *  the orphan assembler's downstream path (no pending/committed split noise in the comparison). */
    private static MailingAssembler mockGroupedAssembler(int totalPages) {
        MailingAssembler assembler = mock(MailingAssembler.class);
        when(assembler.assemble(anyString(), anyList())).thenAnswer(inv -> {
            List<DocGroup> groups = new ArrayList<>();
            for (int start = 1; start <= totalPages; start += REALISTIC_GROUP_SIZE) {
                DocGroup g = new DocGroup("doc-" + start, "synthetic");
                g.minConfidence = 1.0;
                for (int p = start; p < start + REALISTIC_GROUP_SIZE && p <= totalPages; p++) {
                    g.pages.add(p);
                }
                groups.add(g);
            }
            return groups;
        });
        return assembler;
    }

    /** Wraps the real {@link PdfPageRasterizer}: after the WHOLE streaming pass finishes (not per
     *  page — a per-page reading with a forced GC in between would itself perturb the very allocation
     *  pattern under test), forces a GC and records what is still reachable. This is the
     *  "after-analysis-pass" checkpoint: {@code pdfBytes} plus the accumulated per-page metadata are
     *  alive; the just-rendered page image is not (it went out of scope with the last loop
     *  iteration). */
    private static final class CheckpointRasterizer extends PdfPageRasterizer {
        private final AtomicLong afterAnalysis;

        CheckpointRasterizer(AtomicLong afterAnalysis) {
            this.afterAnalysis = afterAnalysis;
        }

        @Override
        public void rasterize(byte[] pdfBytes, int dpi, int maxPages, PageConsumer consumer) throws IOException {
            super.rasterize(pdfBytes, dpi, maxPages, consumer);
            Runtime rt = Runtime.getRuntime();
            System.gc();
            afterAnalysis.set(rt.totalMemory() - rt.freeMemory());
        }
    }

    /** Wraps the real {@link BatchSplitter}: right after {@code assemble(...)} returns the {@code
     *  parts} list, forces a GC and records what is reachable. At this point {@code pdfBytes}, the
     *  per-page metadata, AND the freshly assembled {@code parts} are all alive together — the
     *  candidate peak. */
    private static final class CheckpointSplitter extends BatchSplitter {
        private final AtomicLong afterParts;

        CheckpointSplitter(AtomicLong afterParts) {
            this.afterParts = afterParts;
        }

        @Override
        public List<byte[]> assemble(byte[] pdfBytes, List<List<Integer>> groups, Map<Integer, Integer> rotations)
                throws IOException {
            List<byte[]> parts = super.assemble(pdfBytes, groups, rotations);
            Runtime rt = Runtime.getRuntime();
            System.gc();
            afterParts.set(rt.totalMemory() - rt.freeMemory());
            return parts;
        }
    }

    /** Retained heap (bytes, post-GC) at each checkpoint of one run, all relative to the same
     *  pre-call baseline. */
    private record Retained(long baseline, long afterAnalysis, long afterParts, long afterEnd) {
        long deltaAnalysis() {
            return afterAnalysis - baseline;
        }

        long deltaParts() {
            return afterParts - baseline;
        }

        long deltaEnd() {
            return afterEnd - baseline;
        }
    }

    private static Retained measureOneRun(int pageCount, byte[] pdf, boolean orphanGrouping) throws Exception {
        ConsumptionProperties props = new ConsumptionProperties(); // deployed default dpi 150
        // The synthetic pages are blank A4 sheets with no ink, which the real blank-page filter
        // would (correctly) drop — but that would skip BatchSplitter.assemble() and the ingest loop
        // entirely, hiding exactly the allocations this probe exists to measure. Disabling it here
        // mirrors ReassemblyIT's synthetic-fixture setup and keeps the parts/ingest path live.
        props.setBlankFilterEnabled(false);
        PageOrienter orienter = mockOrienter();
        PageMetadataExtractor extractor = mockExtractor();
        MailingAssembler assembler = orphanGrouping ? mockOrphanAssembler() : mockGroupedAssembler(pageCount);
        PageReassembler reassembler = new PageReassembler(props);
        AttachmentService attachments = mock(AttachmentService.class);
        ConsumptionFileMover mover = mock(ConsumptionFileMover.class);

        AtomicLong afterAnalysis = new AtomicLong();
        AtomicLong afterParts = new AtomicLong();
        PdfPageRasterizer rasterizer = new CheckpointRasterizer(afterAnalysis);
        BatchSplitter splitter = new CheckpointSplitter(afterParts);

        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(props, rasterizer, orienter, extractor,
                assembler, reassembler, splitter, attachments, mover);

        Runtime rt = Runtime.getRuntime();
        System.gc();
        long baseline = rt.totalMemory() - rt.freeMemory();

        orch.reassemble(Path.of("Scan_heap_probe.pdf"), pdf, pageCount);

        System.gc();
        long afterEnd = rt.totalMemory() - rt.freeMemory();

        Retained r = new Retained(baseline, afterAnalysis.get(), afterParts.get(), afterEnd);
        System.out.printf(
                "RUN pages=%d grouping=%s baseline=%d after_analysis_delta=%d after_parts_delta=%d after_end_delta=%d%n",
                pageCount, orphanGrouping ? "orphan" : "grouped", r.baseline(), r.deltaAnalysis(), r.deltaParts(),
                r.deltaEnd());
        return r;
    }

    /** Runs {@value RUNS} measured runs (after one unmeasured warm-up) for one (pageCount,
     *  grouping) configuration and prints each run's three checkpoint deltas. */
    private static Retained[] measureConfiguration(int pageCount, boolean orphanGrouping) throws Exception {
        byte[] pdf = blankPdf(pageCount);
        // Unmeasured warm-up: lets class loading and JIT compilation happen off the books, so the
        // first MEASURED run isn't skewed by one-time startup cost.
        measureOneRun(pageCount, pdf, orphanGrouping);
        Retained[] runs = new Retained[RUNS];
        for (int r = 0; r < RUNS; r++) {
            runs[r] = measureOneRun(pageCount, pdf, orphanGrouping);
        }
        return runs;
    }

    /** Prints retained-heap deltas for 50/100/200-page batches under worst-case (one page per
     *  document) grouping, plus a 200-page run under a realistic (~{@value REALISTIC_GROUP_SIZE}
     *  pages per document) grouping for comparison. Heap readings via {@code Runtime} are noisy even
     *  after a forced GC, so this does not assert a tight bound on the numbers themselves — only
     *  that allocation was observed at all. Interpretation and the derived limit are written up in
     *  {@code documentation/consumption.md}. */
    @Test
    void reportRetainedHeapAcrossPageCountsAndGroupings() throws Exception {
        for (int pageCount : PAGE_COUNTS) {
            Retained[] runs = measureConfiguration(pageCount, true);
            for (int r = 0; r < RUNS; r++) {
                System.out.printf("SUMMARY pages=%d grouping=orphan run=%d after_parts_delta=%d%n",
                        pageCount, r + 1, runs[r].deltaParts());
                assertTrue(runs[r].afterParts() > 0, "orphan pages=" + pageCount + " run=" + (r + 1)
                        + " must observe some retained heap");
            }
        }

        Retained[] groupedRuns = measureConfiguration(200, false);
        for (int r = 0; r < RUNS; r++) {
            System.out.printf("SUMMARY pages=200 grouping=grouped run=%d after_parts_delta=%d%n",
                    r + 1, groupedRuns[r].deltaParts());
            assertTrue(groupedRuns[r].afterParts() > 0,
                    "grouped pages=200 run=" + (r + 1) + " must observe some retained heap");
        }
    }
}
