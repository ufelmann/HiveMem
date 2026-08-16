package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.attachment.SeaweedFsClient;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentDedupServiceTest {

    private DocumentDedupRepository repo;
    private AttachmentRepository attachments;
    private SeaweedFsClient seaweed;
    private DedupProperties props;
    private DocumentDedupService service;

    private final UUID target = UUID.randomUUID();
    private final UUID original = UUID.randomUUID();
    private final UUID attId = UUID.randomUUID();
    private static final OffsetDateTime CANDIDATE_CREATED_AT = OffsetDateTime.parse("2026-05-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        repo = mock(DocumentDedupRepository.class);
        attachments = mock(AttachmentRepository.class);
        seaweed = mock(SeaweedFsClient.class);
        props = new DedupProperties();
        service = new DocumentDedupService(repo, attachments, seaweed, props);
        when(repo.findTarget(target)).thenReturn(Optional.of(
                new DocumentDedupRepository.TargetCell(target, "Rechnung 4711 Betrag 199",
                        "consumption:a", OffsetDateTime.parse("2026-06-01T10:00:00Z"))));
    }

    @Test
    void doesNotDiscardNonConsumptionTarget() {
        when(repo.findTarget(target)).thenReturn(Optional.of(
                new DocumentDedupRepository.TargetCell(target, "Rechnung 4711 Betrag 199",
                        "manual:user", OffsetDateTime.parse("2026-06-01T10:00:00Z"))));

        assertTrue(service.findAndDiscardDuplicate(target).isEmpty());
        verify(repo, never()).findSimilarOlderCandidates(any(), anyDouble(), anyInt());
        verify(repo, never()).linkAndSoftDelete(any(), any(), any(), any());
    }

    @Test
    void discardsWhenBothStagesPass() {
        when(repo.findSimilarOlderCandidates(eq(target), anyDouble(), anyInt())).thenReturn(List.of(
                new DocumentDedupRepository.Candidate(
                        original, "Rechnung 4711 Betrag 199", 0.99, CANDIDATE_CREATED_AT)));
        when(repo.findAttachmentKeysForCell(target)).thenReturn(Optional.of(
                new DocumentDedupRepository.AttachmentKeys(attId, "orig.pdf", "thumb.jpg")));
        when(repo.countOtherLiveCellsForAttachment(attId, target)).thenReturn(0);

        Optional<UUID> result = service.findAndDiscardDuplicate(target);

        assertEquals(Optional.of(original), result);
        verify(repo).linkAndSoftDelete(eq(target), eq(original), any(), any());
        verify(seaweed).delete("orig.pdf");
        verify(seaweed).delete("thumb.jpg");
        verify(attachments).softDelete(attId);
    }

    @Test
    void keepsAttachmentBinaryWhenStillReferenced() {
        when(repo.findSimilarOlderCandidates(eq(target), anyDouble(), anyInt())).thenReturn(List.of(
                new DocumentDedupRepository.Candidate(
                        original, "Rechnung 4711 Betrag 199", 0.99, CANDIDATE_CREATED_AT)));
        when(repo.findAttachmentKeysForCell(target)).thenReturn(Optional.of(
                new DocumentDedupRepository.AttachmentKeys(attId, "orig.pdf", null)));
        when(repo.countOtherLiveCellsForAttachment(attId, target)).thenReturn(1);

        Optional<UUID> result = service.findAndDiscardDuplicate(target);

        assertEquals(Optional.of(original), result);
        verify(repo).linkAndSoftDelete(eq(target), eq(original), any(), any());
        verify(seaweed, never()).delete(any());
        verify(attachments, never()).softDelete(any());
    }

    @Test
    void s3FailureStillSoftDeletesAttachment() {
        when(repo.findSimilarOlderCandidates(eq(target), anyDouble(), anyInt())).thenReturn(List.of(
                new DocumentDedupRepository.Candidate(
                        original, "Rechnung 4711 Betrag 199", 0.99, CANDIDATE_CREATED_AT)));
        when(repo.findAttachmentKeysForCell(target)).thenReturn(Optional.of(
                new DocumentDedupRepository.AttachmentKeys(attId, "orig.pdf", null)));
        when(repo.countOtherLiveCellsForAttachment(attId, target)).thenReturn(0);
        org.mockito.Mockito.doThrow(new RuntimeException("s3 down")).when(seaweed).delete("orig.pdf");

        Optional<UUID> result = service.findAndDiscardDuplicate(target);

        assertEquals(Optional.of(original), result);
        verify(attachments).softDelete(attId);
    }

    @Test
    void noDiscardWhenTextGateFails() {
        when(repo.findSimilarOlderCandidates(eq(target), anyDouble(), anyInt())).thenReturn(List.of(
                new DocumentDedupRepository.Candidate(
                        original, "Mietvertrag Wohnung Kaution", 0.99, CANDIDATE_CREATED_AT)));

        assertTrue(service.findAndDiscardDuplicate(target).isEmpty());
        verify(repo, never()).linkAndSoftDelete(any(), any(), any(), any());
    }

    @Test
    void discardsWhenCandidateCosineIsSqlNull() {
        // Candidate.cosine is nullable because a channel may not compute one (the lexical channel
        // added in a later task) — the service must discard on the text gate alone. No vector-channel
        // row can currently produce a NULL cosine; this guards the shape, not an observed occurrence.
        when(repo.findSimilarOlderCandidates(eq(target), anyDouble(), anyInt())).thenReturn(List.of(
                new DocumentDedupRepository.Candidate(
                        original, "Rechnung 4711 Betrag 199", null, CANDIDATE_CREATED_AT)));
        when(repo.findAttachmentKeysForCell(target)).thenReturn(Optional.of(
                new DocumentDedupRepository.AttachmentKeys(attId, "orig.pdf", "thumb.jpg")));
        when(repo.countOtherLiveCellsForAttachment(attId, target)).thenReturn(0);

        Optional<UUID> result = service.findAndDiscardDuplicate(target);

        assertEquals(Optional.of(original), result);
        verify(repo).linkAndSoftDelete(eq(target), eq(original), any(), any());
    }

    // A synthetic long-ish document and its re-scan. The ORIGINAL is one token SHORTER than the
    // re-scan — the asymmetric direction that a conjunctive tsquery would miss and that the lexical
    // channel exists to cover. The service must still confirm and discard.
    private static final String RESCAN_TEXT =
            "[page=1] Zusatzvereinbarung zwischen der Beispielfirma Musterbau GmbH und dem "
            + "Auftragnehmer ueber die Erbringung von Beratungsleistungen im Geschaeftsjahr 2026. "
            + "Die Vertragsparteien vereinbaren eine monatliche Verguetung in Hoehe von "
            + "eintausendzweihundert Euro zuzueglich der gesetzlichen Umsatzsteuer. "
            + "Kuendigungsfrist drei Monate zum Quartalsende, Gerichtsstand ist Musterstadt.";
    private static final String ORIGINAL_TEXT = RESCAN_TEXT.replace(" ist Musterstadt", "");

    @Test
    void discardsWhenTheOlderTwinIsOneTokenShorter() {
        when(repo.findTarget(target)).thenReturn(Optional.of(
                new DocumentDedupRepository.TargetCell(target, RESCAN_TEXT, "consumption:b",
                        OffsetDateTime.parse("2026-06-01T10:00:00Z"))));
        // cosine null: found by the lexical channel only, which is the case this fix is about.
        when(repo.findSimilarOlderCandidates(eq(target), anyDouble(), anyInt())).thenReturn(List.of(
                new DocumentDedupRepository.Candidate(original, ORIGINAL_TEXT, null, CANDIDATE_CREATED_AT)));

        assertEquals(Optional.of(original), service.findAndDiscardDuplicate(target));
        verify(repo).linkAndSoftDelete(eq(target), eq(original), any(), any());
    }

    @Test
    void keepsLexicallyOverlappingButDifferentDocument() {
        String different =
                "[page=1] Zusatzvereinbarung zwischen der Beispielfirma Musterbau GmbH und dem "
                + "Auftragnehmer ueber die Rueckgabe des Dienstfahrzeuges nach Beendigung des "
                + "Arbeitsverhaeltnisses. Das Fahrzeug ist gereinigt und vollgetankt am "
                + "Betriebsgelaende abzustellen.";
        when(repo.findTarget(target)).thenReturn(Optional.of(
                new DocumentDedupRepository.TargetCell(target, RESCAN_TEXT, "consumption:b",
                        OffsetDateTime.parse("2026-06-01T10:00:00Z"))));
        when(repo.findSimilarOlderCandidates(eq(target), anyDouble(), anyInt())).thenReturn(List.of(
                new DocumentDedupRepository.Candidate(original, different, null, CANDIDATE_CREATED_AT)));

        assertTrue(service.findAndDiscardDuplicate(target).isEmpty());
        verify(repo, never()).linkAndSoftDelete(any(), any(), any(), any());
    }

    /** The repository hands back a merged, oldest-first list; the service takes the first match. */
    @Test
    void takesTheFirstMatchingCandidateOfTheMergedList() {
        UUID newer = UUID.randomUUID();
        when(repo.findSimilarOlderCandidates(eq(target), anyDouble(), anyInt())).thenReturn(List.of(
                new DocumentDedupRepository.Candidate(
                        original, "Rechnung 4711 Betrag 199", null, CANDIDATE_CREATED_AT),
                new DocumentDedupRepository.Candidate(
                        newer, "Rechnung 4711 Betrag 199", 0.99, CANDIDATE_CREATED_AT.plusDays(1))));

        assertEquals(Optional.of(original), service.findAndDiscardDuplicate(target));
        verify(repo).linkAndSoftDelete(eq(target), eq(original), any(), any());
    }

    @Test
    void disabledShortCircuits() {
        props.setEnabled(false);
        assertTrue(service.findAndDiscardDuplicate(target).isEmpty());
        verify(repo, never()).findTarget(any());
    }

    @Test
    void repoErrorIsSwallowed() {
        when(repo.findSimilarOlderCandidates(eq(target), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("boom"));
        assertFalse(service.findAndDiscardDuplicate(target).isPresent());
        verify(repo, never()).linkAndSoftDelete(any(), any(), any(), any());
    }

    @Test
    void factOrphanBackfillDisabledShortCircuits() {
        props.setEnabled(false);
        OffsetDateTime afterCreatedAt = OffsetDateTime.parse("2026-06-01T10:00:00Z");
        UUID afterId = UUID.randomUUID();

        DocumentDedupService.FactOrphanReport report =
                service.factOrphanBackfill(afterCreatedAt, afterId, 100);

        assertEquals(new DocumentDedupService.FactOrphanReport(
                0, 0, 0, 0, 0, afterCreatedAt, afterId, 0), report);
        verify(repo, never()).findDiscardedCellsWithLiveFacts(any(), any(), anyInt());
    }

    /**
     * The entire point of the error-containment fix: a cell whose settlement throws must not abort
     * the page, and the cursor must still land on the LAST cell the page looked at — not on the
     * failing one — so a retry does not re-read and re-throw on the same row forever.
     */
    @Test
    void factOrphanBackfillContainsAPerCellFailureAndAdvancesTheCursorPastIt() {
        UUID cell1 = UUID.randomUUID();
        UUID cell2 = UUID.randomUUID();
        UUID origin1 = UUID.randomUUID();
        UUID origin2 = UUID.randomUUID();
        OffsetDateTime t1 = OffsetDateTime.parse("2026-06-01T10:00:00Z");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-06-01T10:05:00Z");

        when(repo.findDiscardedCellsWithLiveFacts(any(), any(), anyInt())).thenReturn(List.of(
                new DocumentDedupRepository.LiveCell(cell1, t1),
                new DocumentDedupRepository.LiveCell(cell2, t2)));
        when(repo.findDuplicateOfOriginal(cell1)).thenReturn(Optional.of(origin1));
        when(repo.settleDiscardedCellFacts(cell1, origin1)).thenThrow(new RuntimeException("lock conflict"));
        when(repo.findDuplicateOfOriginal(cell2)).thenReturn(Optional.of(origin2));
        when(repo.settleDiscardedCellFacts(cell2, origin2)).thenReturn(
                new DocumentDedupRepository.FactSettlement(
                        DocumentDedupRepository.FactSettlement.Branch.REPOINTED, 1));

        DocumentDedupService.FactOrphanReport report = service.factOrphanBackfill(null, null, 100);

        assertEquals(2, report.checked());
        assertEquals(1, report.failed());
        assertEquals(1, report.repointed());
        assertEquals(0, report.skipped());
        assertEquals(cell2, report.lastId(), "the cursor must advance past the failing cell");
    }
}
