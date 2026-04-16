package com.hivemem.write;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.EmbeddingClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WriteToolService {

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_COMMITTED = "committed";
    private static final String STATUS_REJECTED = "rejected";

    private final WriteToolRepository writeToolRepository;
    private final EmbeddingClient embeddingClient;

    public WriteToolService(
            WriteToolRepository writeToolRepository,
            EmbeddingClient embeddingClient
    ) {
        this.writeToolRepository = writeToolRepository;
        this.embeddingClient = embeddingClient;
    }

    public Map<String, Object> addDrawer(
            AuthPrincipal principal,
            String content,
            String wing,
            String hall,
            String room,
            String source,
            List<String> tags,
            Integer importance,
            String summary,
            List<String> keyPoints,
            String insight,
            String actionability,
            String requestedStatus,
            OffsetDateTime validFrom
    ) {
        String status = effectiveStatus(principal.role(), requestedStatus);
        List<Float> embedding = embeddingClient.encodeDocument(content);
        return writeToolRepository.addDrawer(
                content,
                embedding,
                wing,
                hall,
                room,
                source,
                tags,
                importance,
                summary,
                keyPoints,
                insight,
                actionability,
                status,
                principal.name(),
                validFrom
        );
    }

    public Map<String, Object> kgAdd(
            AuthPrincipal principal,
            String subject,
            String predicate,
            String object,
            double confidence,
            UUID sourceId,
            String requestedStatus,
            OffsetDateTime validFrom
    ) {
        String status = effectiveStatus(principal.role(), requestedStatus);
        return writeToolRepository.addFact(subject, predicate, object, confidence, sourceId, status, principal.name(), validFrom);
    }

    public Map<String, Object> kgInvalidate(UUID factId) {
        writeToolRepository.invalidateFact(factId);
        return Map.of("invalidated", true);
    }

    public Map<String, Object> reviseFact(AuthPrincipal principal, UUID oldId, String newObject) {
        String status = principal.role() == AuthRole.AGENT ? STATUS_PENDING : STATUS_COMMITTED;
        return writeToolRepository.reviseFact(
                oldId,
                newObject,
                principal.name(),
                status
        );
    }

    public Map<String, Object> reviseDrawer(AuthPrincipal principal, UUID oldId, String newContent, String newSummary) {
        String status = principal.role() == AuthRole.AGENT ? STATUS_PENDING : STATUS_COMMITTED;
        List<Float> embedding = embeddingClient.encodeDocument(newContent);
        return writeToolRepository.reviseDrawer(oldId, newContent, newSummary, embedding, principal.name(), status);
    }

    public Map<String, Object> updateIdentity(String key, String content) {
        int tokenCount = content.length() / 4;
        writeToolRepository.upsertIdentity(key, content, tokenCount);
        return Map.of("key", key, "token_count", tokenCount);
    }

    public Map<String, Object> addReference(
            String title,
            String url,
            String author,
            String refType,
            String status,
            String notes,
            List<String> tags,
            Integer importance
    ) {
        String effectiveStatus = status == null ? "read" : status;
        return writeToolRepository.addReference(title, url, author, refType, effectiveStatus, notes, tags, importance);
    }

    public Map<String, Object> linkReference(UUID drawerId, UUID referenceId, String relation) {
        return writeToolRepository.linkReference(drawerId, referenceId, relation);
    }

    public Map<String, Object> registerAgent(
            String name,
            String focus,
            String autonomyJson,
            String schedule,
            String modelRoutingJson,
            List<String> tools
    ) {
        return writeToolRepository.registerAgent(name, focus, autonomyJson, schedule, modelRoutingJson, tools);
    }

    public Map<String, Object> diaryWrite(String agent, String entry) {
        return writeToolRepository.diaryWrite(agent, entry);
    }

    public Map<String, Object> updateBlueprint(
            AuthPrincipal principal,
            String wing,
            String title,
            String narrative,
            List<String> hallOrder,
            List<UUID> keyDrawers
    ) {
        return writeToolRepository.updateBlueprint(principal.name(), wing, title, narrative, hallOrder, keyDrawers);
    }

    public Map<String, Object> addTunnel(
            AuthPrincipal principal,
            UUID fromDrawer,
            UUID toDrawer,
            String relation,
            String note,
            String requestedStatus
    ) {
        String status = principal.role() == AuthRole.AGENT ? STATUS_PENDING : effectiveStatus(principal.role(), requestedStatus);
        return writeToolRepository.addTunnel(fromDrawer, toDrawer, relation, note, status, principal.name());
    }

    public Map<String, Object> removeTunnel(UUID tunnelId) {
        writeToolRepository.removeTunnel(tunnelId);
        return Map.of("id", tunnelId.toString(), "removed", true);
    }

    public List<Map<String, Object>> checkDuplicate(String content, double threshold) {
        List<Float> queryVector = embeddingClient.encodeDocument(content);
        String vectorLiteral = queryVector.toString();
        return writeToolRepository.checkDuplicateDrawer(vectorLiteral, threshold);
    }

    public List<Map<String, Object>> checkContradiction(String subject, String predicate, String newObject) {
        return writeToolRepository.checkContradiction(subject, predicate, newObject);
    }

    public Map<String, Object> approvePending(List<UUID> ids, String decision) {
        int count = writeToolRepository.approvePending(ids, decision);
        return Map.of("decision", decision, "count", count);
    }

    private static String effectiveStatus(AuthRole role, String requestedStatus) {
        if (role == AuthRole.AGENT) {
            return STATUS_PENDING;
        }
        if (requestedStatus == null) {
            return STATUS_COMMITTED;
        }
        return switch (requestedStatus) {
            case STATUS_PENDING, STATUS_COMMITTED, STATUS_REJECTED -> requestedStatus;
            default -> throw new IllegalArgumentException("Invalid status");
        };
    }
}
