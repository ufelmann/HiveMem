package com.hivemem.tools.read;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.drawers.DrawerReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.search.DrawerSearchRepository;
import com.hivemem.search.KgSearchRepository;
import com.hivemem.write.AdminToolService;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Comparator;
import java.util.LinkedHashMap;

@Service
public class ReadToolService {

    private final DrawerReadRepository drawerReadRepository;
    private final KgSearchRepository kgSearchRepository;
    private final DrawerSearchRepository drawerSearchRepository;
    private final EmbeddingClient embeddingClient;
    private final AdminToolService adminToolService;

    public ReadToolService(
            DrawerReadRepository drawerReadRepository,
            KgSearchRepository kgSearchRepository,
            DrawerSearchRepository drawerSearchRepository,
            EmbeddingClient embeddingClient,
            AdminToolService adminToolService
    ) {
        this.drawerReadRepository = drawerReadRepository;
        this.kgSearchRepository = kgSearchRepository;
        this.drawerSearchRepository = drawerSearchRepository;
        this.embeddingClient = embeddingClient;
        this.adminToolService = adminToolService;
    }

    public Map<String, Object> status() {
        return drawerReadRepository.statusSnapshot();
    }

    public List<Map<String, Object>> listWings() {
        return drawerReadRepository.listWings();
    }

    public List<Map<String, Object>> listHalls(String wing) {
        return drawerReadRepository.listHalls(wing);
    }

    public List<Map<String, Object>> search(
            String query,
            int limit,
            String wing,
            String hall,
            String room,
            double weightSemantic,
            double weightKeyword,
            double weightRecency,
            double weightImportance,
            double weightPopularity
    ) {
        List<Float> queryVector = embeddingClient.encodeQuery(query);
        List<DrawerSearchRepository.SearchCandidate> candidates = drawerSearchRepository.searchCandidates(wing, hall, room);
        long maxAccessCount = candidates.stream().mapToLong(DrawerSearchRepository.SearchCandidate::accessCount).max().orElse(0L);
        OffsetDateTime now = OffsetDateTime.now();

        return candidates.stream()
                .map(candidate -> scoredResult(
                        candidate,
                        query,
                        queryVector,
                        candidate.embedding() == null ? embeddingClient.encodeDocument(candidate.content()) : candidate.embedding(),
                        now,
                        maxAccessCount,
                        weightSemantic,
                        weightKeyword,
                        weightRecency,
                        weightImportance,
                        weightPopularity
                ))
                .sorted(Comparator.comparing((Map<String, Object> row) -> ((Number) row.get("score_total")).doubleValue()).reversed()
                        .thenComparing(row -> (String) row.get("id")))
                .limit(limit)
                .toList();
    }

    public List<Map<String, Object>> searchKg(String subject, String predicate, String object_, int limit) {
        return kgSearchRepository.search(subject, predicate, object_, limit);
    }

    public Map<String, Object> getDrawer(AuthPrincipal principal, UUID drawerId) {
        Optional<Map<String, Object>> drawer = drawerReadRepository.findDrawer(drawerId);
        drawer.ifPresent(d -> adminToolService.logAccess(drawerId, null, principal.name()));
        return drawer.orElse(null);
    }

    public List<Map<String, Object>> traverse(UUID drawerId, int maxDepth, String relationFilter) {
        return drawerReadRepository.traverse(drawerId, maxDepth, relationFilter);
    }

    public List<Map<String, Object>> quickFacts(String entity) {
        return drawerReadRepository.quickFacts(entity);
    }

    public List<Map<String, Object>> timeMachine(String subject, OffsetDateTime asOf, OffsetDateTime asOfIngestion, int limit) {
        return drawerReadRepository.timeMachine(subject, asOf, asOfIngestion, limit);
    }

    public List<Map<String, Object>> drawerHistory(UUID drawerId) {
        return drawerReadRepository.drawerHistory(drawerId);
    }

    public List<Map<String, Object>> factHistory(UUID factId) {
        return drawerReadRepository.factHistory(factId);
    }

    public List<Map<String, Object>> pendingApprovals() {
        return drawerReadRepository.pendingApprovals();
    }

    public List<Map<String, Object>> readingList(String refType, int limit) {
        return drawerReadRepository.readingList(refType, limit);
    }

    public List<Map<String, Object>> listAgents() {
        return drawerReadRepository.listAgents();
    }

    public List<Map<String, Object>> diaryRead(String agent, int lastN) {
        return drawerReadRepository.diaryRead(agent, lastN);
    }

    public List<Map<String, Object>> getBlueprint(String wing) {
        return drawerReadRepository.getBlueprint(wing);
    }

    public Map<String, Object> wakeUp() {
        return drawerReadRepository.wakeUp();
    }

    private static Map<String, Object> scoredResult(
            DrawerSearchRepository.SearchCandidate candidate,
            String query,
            List<Float> queryVector,
            List<Float> candidateVector,
            OffsetDateTime newest,
            long maxAccessCount,
            double weightSemantic,
            double weightKeyword,
            double weightRecency,
            double weightImportance,
            double weightPopularity
    ) {
        double semantic = cosineSimilarity(queryVector, candidateVector);
        double keyword = keywordScore(query, candidate.content(), candidate.summary(), candidate.tags());
        double recency = recencyScore(candidate.createdAt(), newest);
        double importance = importanceScore(candidate.importance());
        double popularity = maxAccessCount <= 0L ? 0.0d : (double) candidate.accessCount() / (double) maxAccessCount;
        double total = (semantic * weightSemantic)
                + (keyword * weightKeyword)
                + (recency * weightRecency)
                + (importance * weightImportance)
                + (popularity * weightPopularity);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", candidate.id().toString());
        row.put("content", candidate.content());
        row.put("summary", candidate.summary());
        row.put("wing", candidate.wing());
        row.put("hall", candidate.hall());
        row.put("room", candidate.room());
        row.put("tags", candidate.tags());
        row.put("importance", candidate.importance());
        row.put("created_at", candidate.createdAt() == null ? null : candidate.createdAt().toString());
        row.put("valid_from", candidate.validFrom() == null ? null : candidate.validFrom().toString());
        row.put("score_semantic", rounded(semantic));
        row.put("score_keyword", rounded(keyword));
        row.put("score_recency", rounded(recency));
        row.put("score_importance", rounded(importance));
        row.put("score_popularity", rounded(popularity));
        row.put("score_total", rounded(total));
        return row;
    }

    private static double cosineSimilarity(List<Float> left, List<Float> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0.0d;
        }
        int size = Math.min(left.size(), right.size());
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int i = 0; i < size; i++) {
            double a = left.get(i);
            double b = right.get(i);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static double keywordScore(String query, String content, String summary, List<String> tags) {
        List<String> tokens = java.util.Arrays.stream(query.toLowerCase().split("\\s+"))
                .filter(token -> !token.isBlank())
                .toList();
        if (tokens.isEmpty()) {
            return 0.0d;
        }
        String haystack = String.join(" ",
                content == null ? "" : content.toLowerCase(),
                summary == null ? "" : summary.toLowerCase(),
                String.join(" ", tags == null ? List.of() : tags).toLowerCase());
        long matches = tokens.stream().filter(haystack::contains).count();
        return (double) matches / (double) tokens.size();
    }

    private static double recencyScore(OffsetDateTime createdAt, OffsetDateTime reference) {
        if (createdAt == null || reference == null) {
            return 0.0d;
        }
        long ageSeconds = Math.max(0L, ChronoUnit.SECONDS.between(createdAt, reference));
        return Math.exp(-0.693d * ageSeconds / (90.0d * 86400.0d));
    }

    private static double importanceScore(Integer importance) {
        if (importance == null) {
            return 0.6d;
        }
        return switch (importance) {
            case 1 -> 1.0d;
            case 2 -> 0.8d;
            case 3 -> 0.6d;
            case 4 -> 0.4d;
            case 5 -> 0.2d;
            default -> 0.6d;
        };
    }

    private static double rounded(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }
}
