package com.hivemem.hooks;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.CellSearchRepository.RankedRow;
import com.hivemem.search.SearchWeights;
import com.hivemem.search.SearchWeightsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class HookContextService {

    private static final Logger log = LoggerFactory.getLogger(HookContextService.class);
    private static final int SEARCH_LIMIT = 10;

    private final CellSearchRepository searchRepository;
    private final EmbeddingClient embeddingClient;
    private final SkipHeuristics skipHeuristics;
    private final SessionInjectionCache cache;
    private final ContextFormatter formatter;
    private final HookProperties props;
    private final SearchWeightsProperties weightsProperties;

    private final ConcurrentHashMap<String, AtomicInteger> turnCounters = new ConcurrentHashMap<>();

    public HookContextService(
            CellSearchRepository searchRepository,
            EmbeddingClient embeddingClient,
            SkipHeuristics skipHeuristics,
            SessionInjectionCache cache,
            ContextFormatter formatter,
            HookProperties props,
            SearchWeightsProperties weightsProperties
    ) {
        this.searchRepository = searchRepository;
        this.embeddingClient = embeddingClient;
        this.skipHeuristics = skipHeuristics;
        this.cache = cache;
        this.formatter = formatter;
        this.props = props;
        this.weightsProperties = weightsProperties;
    }

    public String contextFor(HookContextRequest req) {
        return contextFor(req, null, null);
    }

    public String contextFor(HookContextRequest req, Double thresholdOverride, Integer maxCellsOverride) {
        if (!props.isEnabled()) return "";
        if (req == null || req.prompt() == null) return "";
        if (skipHeuristics.evaluate(req.prompt()).skip()) return "";

        String sessionKey = req.session_id() == null ? "_" : req.session_id();
        int turn = turnCounters
                .computeIfAbsent(sessionKey, k -> new AtomicInteger())
                .incrementAndGet();

        List<RankedRow> rows;
        try {
            List<Float> queryVector = embeddingClient.encodeQuery(req.prompt());
            SearchWeights w = weightsProperties.toSearchWeights();
            rows = searchRepository.rankedSearch(
                    queryVector, req.prompt(), null, null, null, SEARCH_LIMIT,
                    w.semantic(), w.keyword(), w.recency(),
                    w.importance(), w.popularity(), w.graphProximity());
        } catch (RuntimeException e) {
            log.warn("Hook search failed; returning empty context", e);
            return "";
        }

        double threshold = thresholdOverride != null ? thresholdOverride : props.getRelevanceThreshold();
        int maxCells = maxCellsOverride != null ? maxCellsOverride : props.getMaxCells();

        List<RankedRow> filtered = rows.stream()
                .filter(r -> r.scoreTotal() >= threshold)
                .filter(r -> !cache.recentlyInjected(sessionKey, r.id(), turn))
                .limit(maxCells)
                .toList();

        if (filtered.isEmpty()) return "";
        for (RankedRow r : filtered) {
            cache.recordInjection(sessionKey, r.id(), turn);
        }
        return formatter.format(filtered, turn);
    }
}
