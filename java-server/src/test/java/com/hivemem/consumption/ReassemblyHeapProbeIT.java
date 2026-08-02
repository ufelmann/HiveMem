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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

/**
 * Measures peak JVM heap usage across a FULL {@link ReassemblyOrchestrator#reassemble} call (not
 * the rasterizer alone). Task 3 made the rasterizer stream one page at a time, so its own peak no
 * longer scales with batch size — but the orchestrator still holds the whole {@code pdfBytes}
 * array and the whole assembled {@code parts} list in memory for the duration of the call. Those
 * are what this probe measures, because those are what a page cap must actually bound.
 *
 * <p>This is a measurement, not a correctness gate: it asserts only that some allocation was
 * observed and prints the numbers for a human (or {@code documentation/consumption.md}) to read.
 * It intentionally runs as an *IT test (see maven-surefire's {@code **}/*IT.java exclude and
 * maven-failsafe's IT-only executions in {@code pom.xml}) so a normal {@code mvn test} never pays
 * for it and CI never treats its noisy numbers as a pass/fail gate.
 */
class ReassemblyHeapProbeIT {

    private static final int PAGE_COUNT = 200;
    private static final int RUNS = 3;

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
     *  memory-worst realistic case for the {@code parts} list — the real BatchSplitter re-serializes
     *  each page into its own small PDF, so per-document overhead is paid 200 times instead of
     *  amortised over a handful of mailings. Measuring this rather than a hand-tuned grouping keeps
     *  the probe honest: no invented grouping logic, and it does not undercount. */
    private static MailingAssembler mockAssembler() {
        MailingAssembler assembler = mock(MailingAssembler.class);
        when(assembler.assemble(anyString(), anyList())).thenReturn(List.of());
        return assembler;
    }

    /** Wraps the real {@link PdfPageRasterizer} to sample used heap right after each page has been
     *  fully processed by the orchestrator's per-page callback (render, orient, extract, accumulate
     *  metadata) — the point where that page's transient allocations plus the running accumulation
     *  are both live. */
    private static final class SamplingRasterizer extends PdfPageRasterizer {
        private final AtomicLong peak;
        private final Runtime rt = Runtime.getRuntime();

        SamplingRasterizer(AtomicLong peak) {
            this.peak = peak;
        }

        @Override
        public void rasterize(byte[] pdfBytes, int dpi, int maxPages, PageConsumer consumer) throws IOException {
            super.rasterize(pdfBytes, dpi, maxPages, (index, png) -> {
                consumer.accept(index, png);
                sample();
            });
        }

        private void sample() {
            long used = rt.totalMemory() - rt.freeMemory();
            peak.accumulateAndGet(used, Math::max);
        }
    }

    /** Runs one full {@code reassemble(...)} call and returns bytes-per-page for that run.
     *  Peak heap is tracked two ways and combined: (a) the {@link SamplingRasterizer} above samples
     *  after every page, covering the streaming render/orient/extract pass; (b) a background poller
     *  samples every millisecond for the whole call, covering the parts-assembly and ingest phases
     *  that happen AFTER the streaming pass returns and are otherwise invisible to this test (the
     *  orchestrator does not expose a hook there). */
    private static long measureOneRun(byte[] pdf) throws Exception {
        ConsumptionProperties props = new ConsumptionProperties(); // deployed defaults: max-pages 200, dpi 150
        // The synthetic pages are blank A4 sheets with no ink, which the real blank-page filter
        // would (correctly) drop — but that would skip BatchSplitter.assemble() and the ingest
        // loop entirely, hiding exactly the allocations this probe exists to measure. Disabling it
        // here mirrors ReassemblyIT's synthetic-fixture setup and keeps the parts/ingest path live.
        props.setBlankFilterEnabled(false);
        PageOrienter orienter = mockOrienter();
        PageMetadataExtractor extractor = mockExtractor();
        MailingAssembler assembler = mockAssembler();
        PageReassembler reassembler = new PageReassembler(props);
        BatchSplitter splitter = new BatchSplitter();
        AttachmentService attachments = mock(AttachmentService.class);
        ConsumptionFileMover mover = mock(ConsumptionFileMover.class);

        AtomicLong peak = new AtomicLong(0);
        PdfPageRasterizer rasterizer = new SamplingRasterizer(peak);

        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(props, rasterizer, orienter, extractor,
                assembler, reassembler, splitter, attachments, mover);

        Runtime rt = Runtime.getRuntime();
        System.gc();
        Thread.sleep(50); // let the collector actually settle before taking the baseline
        long before = rt.totalMemory() - rt.freeMemory();
        peak.set(before);

        AtomicBoolean running = new AtomicBoolean(true);
        Thread poller = new Thread(() -> {
            while (running.get()) {
                long used = rt.totalMemory() - rt.freeMemory();
                peak.accumulateAndGet(used, Math::max);
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        poller.setDaemon(true);
        poller.start();

        orch.reassemble(Path.of("Scan_heap_probe.pdf"), pdf, PAGE_COUNT);

        running.set(false);
        poller.join();

        long delta = peak.get() - before;
        long bytesPerPage = delta / PAGE_COUNT;
        System.out.printf("RUN peak_heap_delta_bytes=%d bytes_per_page=%d%n", delta, bytesPerPage);
        return bytesPerPage;
    }

    /** Prints bytes-per-page for {@value RUNS} independent runs of a full reassembly over a
     *  synthetic {@value PAGE_COUNT}-page PDF. Heap readings via {@code Runtime} are noisy, so this
     *  does not assert a tight bound on the number itself — only that allocation was observed at
     *  all in every run. The cap is derived by a human from the printed numbers, per
     *  {@code documentation/consumption.md}. */
    @Test
    void reportBytesPerPageForAFullReassembly() throws Exception {
        byte[] pdf = blankPdf(PAGE_COUNT);
        // Unmeasured warm-up: lets class loading and JIT compilation happen off the books, so the
        // first MEASURED run isn't skewed low/high by one-time startup cost. Not one of the 3
        // reported runs.
        measureOneRun(pdf);
        long[] bytesPerPage = new long[RUNS];
        for (int r = 0; r < RUNS; r++) {
            bytesPerPage[r] = measureOneRun(pdf);
        }
        for (int r = 0; r < RUNS; r++) {
            System.out.printf("BYTES_PER_PAGE_RUN_%d=%d%n", r + 1, bytesPerPage[r]);
            assertTrue(bytesPerPage[r] > 0, "run " + (r + 1) + " must observe some allocation");
        }
    }
}
