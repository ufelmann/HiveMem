package com.hivemem.consumption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConsumptionServiceIT extends ConsumptionITSupport {

    @Test
    void consumesPlainTextFileAsCommittedCell(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("note.txt"), "Rechnung Acme GmbH Betrag 42 EUR");

        ConsumptionProperties cp = new ConsumptionProperties();
        cp.setEnabled(true);
        cp.setDir(root.toString());
        cp.setRealm("documents");
        ConsumptionService svc = buildService(cp);

        svc.processStaged(file);

        assertFalse(Files.exists(file), "source file should be moved away");
        assertTrue(Files.exists(root.resolve("processed").resolve("note.txt")),
                "file should land in processed/");

        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT realm, status, source FROM cells ORDER BY created_at DESC LIMIT 1")) {
            assertTrue(rs.next(), "Expected at least one cell row");
            assertEquals("documents", rs.getString("realm"));
            assertEquals("committed", rs.getString("status"));
            assertTrue(rs.getString("source").startsWith("consumption:"),
                    "source should start with 'consumption:' but was: " + rs.getString("source"));
        }
    }

    @Test
    void singleFileRecordsLedgerDone(@TempDir Path root) throws Exception {
        // Clean the ledger table so this test is isolated
        dsl.execute("DELETE FROM consumption_file");

        byte[] content = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file = root.resolve("note.txt");
        Files.write(file, content);

        ConsumptionProperties cp = new ConsumptionProperties();
        cp.setEnabled(true);
        cp.setDir(root.toString());
        cp.setRealm("documents");

        ConsumptionFileRepository repo = new ConsumptionFileRepository(dsl);
        ConsumptionService svc = buildService(cp, repo);

        svc.processStaged(file);

        String expectedHash = ConsumptionService.sha256(content);
        Optional<ConsumptionFileRepository.Row> row = repo.findByHash(expectedHash);
        assertTrue(row.isPresent(), "consumption_file row should exist for the processed file");
        assertEquals("done", row.get().state(),
                "consumption_file state should be 'done' after successful ingest");
    }

    /** I2: a corrupt/truncated PDF makes the page-count probe throw before any ingest happens. The
     *  file goes to failed/, so the ledger row must say 'failed' too — leaving it 'staged' (or
     *  'processing') hides it from findRetriableFailed and the retry budget is never used. */
    @Test
    void unreadablePdfMarksTheLedgerRowFailedInsteadOfLeavingItStaged(@TempDir Path root) throws Exception {
        dsl.execute("DELETE FROM consumption_file");

        byte[] notAPdf = "this is not a pdf at all".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file = root.resolve("broken.pdf");
        Files.write(file, notAPdf);
        String hash = ConsumptionService.sha256(notAPdf);

        ConsumptionProperties cp = new ConsumptionProperties();
        cp.setEnabled(true);
        cp.setDir(root.toString());
        cp.setRealm("documents");

        ConsumptionFileRepository repo = new ConsumptionFileRepository(dsl);
        repo.stage(hash, "broken.pdf");           // exactly what ConsumptionWatcher does first
        ConsumptionService svc = buildService(cp, repo);

        svc.processStaged(file, hash);

        assertTrue(Files.exists(root.resolve("failed").resolve("broken.pdf")),
                "an unreadable PDF must land in failed/");
        Optional<ConsumptionFileRepository.Row> row = repo.findByHash(hash);
        assertTrue(row.isPresent());
        assertEquals("failed", row.get().state(),
                "the row must not stay 'staged' while the file sits in failed/");
        assertNotNull(row.get().lastError(), "the parse error must be recorded");
        assertTrue(repo.findRetriableFailed(3, 100).stream().anyMatch(r -> r.sha256().equals(hash)),
                "the row must now be visible to the retry sweep");
    }

    /** I5: moveNoReplace appends -1/-2 when processed/ already holds a file of that name. The ledger
     *  must record the name that actually landed, or consumption_retry resolves
     *  processed/<row.filename> to nothing and answers restaged:false. */
    @Test
    void processedMoveCollisionPersistsTheLandedFilename(@TempDir Path root) throws Exception {
        dsl.execute("DELETE FROM consumption_file");

        Files.createDirectories(root.resolve("processed"));
        Files.writeString(root.resolve("processed").resolve("note.txt"), "an older, unrelated file");

        byte[] content = "collision test content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file = root.resolve("note.txt");
        Files.write(file, content);
        String hash = ConsumptionService.sha256(content);

        ConsumptionProperties cp = new ConsumptionProperties();
        cp.setEnabled(true);
        cp.setDir(root.toString());
        cp.setRealm("documents");

        ConsumptionFileRepository repo = new ConsumptionFileRepository(dsl);
        repo.stage(hash, "note.txt");
        ConsumptionService svc = buildService(cp, repo);

        svc.processStaged(file, hash);

        Optional<ConsumptionFileRepository.Row> row = repo.findByHash(hash);
        assertTrue(row.isPresent());
        assertEquals("done", row.get().state());
        assertNotEquals("note.txt", row.get().filename(),
                "the original name was taken, so the mover must have suffixed it");
        assertTrue(Files.isRegularFile(root.resolve("processed").resolve(row.get().filename())),
                "processed/<row.filename> must resolve to the file that actually landed");
    }
}
