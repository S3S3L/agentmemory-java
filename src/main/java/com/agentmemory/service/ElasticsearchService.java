package com.agentmemory.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.agentmemory.config.MemoryProperties;
import com.agentmemory.model.MemorySearchRequest;
import com.agentmemory.model.MemoryTier;
import com.agentmemory.model.QueryExpander;
import com.agentmemory.model.SearchResult;
import com.agentmemory.model.SessionRecord;
import com.agentmemory.service.embed.EmbeddingService;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;

@SuppressWarnings("unchecked")
@Service
public class ElasticsearchService {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchService.class);
    private static final String DEFAULT_OBS_INDEX = "memory-observations";
    private static final String SESSION_INDEX = "memory-sessions";
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ElasticsearchClient client;
    private final MemoryProperties props;
    private final EmbeddingService embeddingService;
    private final String observationIndex;

    @Autowired
    public ElasticsearchService(ElasticsearchClient client, MemoryProperties props, EmbeddingService embeddingService) {
        this(client, props, embeddingService, DEFAULT_OBS_INDEX);
    }

    public ElasticsearchService(ElasticsearchClient client, MemoryProperties props, EmbeddingService embeddingService, String observationIndex) {
        this.client = client;
        this.props = props;
        this.embeddingService = embeddingService;
        this.observationIndex = observationIndex;
    }

    public EmbeddingService getEmbeddingService() { return embeddingService; }

    public void saveObservation(Map<String, Object> doc, String id) throws IOException {
        client.index(i -> i
            .index(observationIndex)
            .id(id)
            .document(doc)
        );
        log.debug("Saved observation {} to {}", id, observationIndex);
    }

    public void saveSession(SessionRecord session) throws IOException {
        client.index(i -> i
            .index(SESSION_INDEX)
            .id(session.id())
            .document(session)
        );
    }

    public List<SearchResult> search(MemorySearchRequest req, float[] queryVector) throws IOException {
        if (req.query() == null || req.query().isBlank()) {
            return List.of();
        }

        boolean canUseVector = embeddingService != null
            && embeddingService.isAvailable()
            && queryVector != null
            && queryVector.length > 0;

        // Launch BM25 and vector searches in parallel
        CompletableFuture<List<Hit<Map<String, Object>>>> bm25Future =
            CompletableFuture.supplyAsync(() -> {
                try {
                    return bm25Search(req);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }, SEARCH_EXECUTOR);

        CompletableFuture<List<Hit<Map<String, Object>>>> vectorFuture = canUseVector
            ? CompletableFuture.supplyAsync(() -> {
                try {
                    return vectorSearch(req, queryVector);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }, SEARCH_EXECUTOR)
            : CompletableFuture.completedFuture(List.of());

        List<Hit<Map<String, Object>>> bm25Hits;
        List<Hit<Map<String, Object>>> vectorHits = List.of();
        boolean useVector = canUseVector;

        try {
            bm25Hits = bm25Future.join();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IOException("BM25 search failed: " + cause.getMessage(), cause);
        }

        if (canUseVector) {
            try {
                vectorHits = vectorFuture.join();
            } catch (Exception e) {
                useVector = false;
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn(
                    "Vector search unavailable for index {}. Falling back to BM25 only: {}",
                    observationIndex,
                    cause.getMessage()
                );
                log.debug("Vector search failure details", cause);
            }
        }

        // RRF fusion
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, Hit<Map<String, Object>>> allHits = new LinkedHashMap<>();

        int bm25K = useVector ? props.getRrfK() : 10; // Higher BM25 weight when no vector
        int vectorK = props.getRrfK();

        for (int i = 0; i < bm25Hits.size(); i++) {
            String id = bm25Hits.get(i).id();
            double score = 1.0 / (bm25K + i + 1);
            rrfScores.merge(id, score, Double::sum);
            allHits.putIfAbsent(id, bm25Hits.get(i));
        }
        for (int i = 0; i < vectorHits.size(); i++) {
            String id = vectorHits.get(i).id();
            double score = 1.0 / (vectorK + i + 1);
            rrfScores.merge(id, score, Double::sum);
            allHits.putIfAbsent(id, vectorHits.get(i));
        }

        // Sort by RRF score, session diversification
        var sorted = rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .toList();

        Map<String, Integer> sessionCounts = new HashMap<>();
        var results = new ArrayList<SearchResult>();
        for (var entry : sorted) {
            String id = entry.getKey();
            var hit = allHits.get(id);
            if (hit == null) continue;

            Map<String, Object> src = hit.source();
            String sessionId = src != null ? (String) src.get("sessionId") : null;
            int count = sessionCounts.getOrDefault(sessionId, 0);
            if (count >= props.getMaxResultsPerSession()) continue;
            sessionCounts.put(sessionId, count + 1);

            results.add(toSearchResult(hit, entry.getValue()));
            if (results.size() >= props.getTopKFinal()) break;
        }

        return results;
    }

    private List<Hit<Map<String, Object>>> bm25Search(MemorySearchRequest req) throws IOException {
        // Use QueryExpander to remove stop words for better BM25 matching
        QueryExpander.ExpandedQuery expanded = QueryExpander.expand(req.query());
        String searchQuery = expanded.normalizedQuery().isEmpty() ? req.query() : expanded.normalizedQuery();

        if (!searchQuery.equals(req.query())) {
            log.debug("BM25 query expanded: '{}' -> '{}'", req.query(), searchQuery);
        }
        if (!expanded.synonymsAdded().isEmpty()) {
            log.debug("BM25 synonyms added: {}", expanded.synonymsAdded());
        }

        SearchResponse<Map<String, Object>> response = client.search(s -> {
            var q = s.index(observationIndex)
                .size(props.getTopKBm25())
                .source(src -> src.filter(f -> f.excludes("embedding")));

            // Multi-field multi-match with per-field boosts; tieBreaker combines cross-field scores
            q = q.query(qb -> qb.multiMatch(mm -> mm
                .query(searchQuery)
                .fields(List.of(
                    "title^3.0",
                    "concepts^2.5",
                    "tags^2.0",
                    "facts^2.0",
                    "content^1.0",
                    "input^0.8",
                    "output^0.6",
                    "filePath^1.5"
                ))
                .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                .tieBreaker(0.3)
            ));

            return q;
        }, mapDocumentClass());

        return (List<Hit<Map<String, Object>>>)(List<?>) response.hits().hits();
    }

    private List<Hit<Map<String, Object>>> vectorSearch(MemorySearchRequest req, float[] queryVector) throws IOException {
        List<Float> floatList = new ArrayList<>(queryVector.length);
        for (float v : queryVector) floatList.add(v);

        // numCandidates must be significantly larger than k to ensure semantic recall
        // Scanning more candidates improves recall at the cost of slightly higher latency
        int numCandidates = Math.max(200, props.getTopKVector() * 10);

        SearchResponse<Map<String, Object>> response = client.search(s -> {
            var builder = s.index(observationIndex)
                .size(props.getTopKVector())
                .source(src -> src.filter(f -> f.excludes("embedding")))
                .knn(k -> k
                    .field("embedding")
                    .queryVector(floatList)
                    .numCandidates(numCandidates)
                    .k(props.getTopKVector())
                );
            return builder;
        }, mapDocumentClass());

        return (List<Hit<Map<String, Object>>>)(List<?>) response.hits().hits();
    }

    private SearchResult toSearchResult(Hit<Map<String, Object>> hit, double score) {
        Map<String, Object> src = hit.source();
        if (src == null) return null;
        String tierStr = (String) src.getOrDefault("tier", "WORKING");
        return new SearchResult(
            hit.id(),
            (String) src.get("content"),
            MemoryTier.valueOf(tierStr),
            (String) src.get("sessionId"),
            (String) src.get("toolName"),
            (String) src.get("filePath"),
            score,
            hit.score() != null ? hit.score() : 0.0,
            0.0,
            0.0
        );
    }

    public Optional<SessionRecord> getSession(String id) throws IOException {
        var response = client.get(g -> g.index(SESSION_INDEX).id(id), SessionRecord.class);
        return response.found() ? Optional.of(response.source()) : Optional.empty();
    }

    public List<SessionRecord> getRecentSessions(int limit) throws IOException {
        SearchResponse<SessionRecord> response = client.search(s -> s
            .index(SESSION_INDEX)
            .size(limit)
            .sort(sort -> sort.field(f -> f.field("startTime").order(SortOrder.Desc))),
            SessionRecord.class
        );
        return response.hits().hits().stream()
            .map(Hit::source)
            .filter(Objects::nonNull)
            .toList();
    }

    public void deleteMemory(String id) throws IOException {
        client.delete(d -> d.index(observationIndex).id(id));
    }

    public void batchSave(List<Map<String, Object>> documents, List<String> ids) throws IOException {
        if (documents.isEmpty()) return;
        var ops = new ArrayList<co.elastic.clients.elasticsearch.core.bulk.BulkOperation>();
        for (int i = 0; i < documents.size(); i++) {
            int idx = i;
            ops.add(co.elastic.clients.elasticsearch.core.bulk.BulkOperation.of(o -> o
                .index(bi -> bi.index(observationIndex).id(ids.get(idx)).document(documents.get(idx)))));
        }
        client.bulk(b -> b.operations(ops));
    }

    public List<Map<String, Object>> getObservationsByFile(String filePath, int limit) throws IOException {
        SearchResponse<Map<String, Object>> response = client.search(s -> s
            .index(observationIndex)
            .size(limit)
            .query(q -> q.term(t -> t.field("filePath.keyword").value(filePath)))
            .sort(sort -> sort.field(f -> f.field("timestamp").order(SortOrder.Desc))),
            mapDocumentClass()
        );
        return (List<Map<String, Object>>)(List<?>) response.hits().hits().stream()
            .map(Hit::source)
            .filter(Objects::nonNull)
            .toList();
    }

    public List<Map<String, Object>> getTimeline(int limit) throws IOException {
        SearchResponse<Map<String, Object>> response = client.search(s -> s
            .index(observationIndex)
            .size(limit)
            .sort(sort -> sort.field(f -> f.field("timestamp").order(SortOrder.Desc)))
            .source(src -> src.filter(f -> f.excludes("embedding"))),
            mapDocumentClass()
        );
        return (List<Map<String, Object>>)(List<?>) response.hits().hits().stream()
            .map(Hit::source)
            .filter(Objects::nonNull)
            .toList();
    }

    public Map<String, Object> getProfile() throws IOException {
        SearchResponse<Void> response = client.search(s -> s
            .index(observationIndex)
            .size(0)
            .aggregations("top_files", a -> a.terms(t -> t.field("filePath.keyword").size(20))),
            Void.class
        );
        return Map.of(
            "totalObservations", getTotalCount(),
            "topFiles", response.aggregations()
        );
    }

    public Map<String, Object> getPatternAggregations() throws IOException {
        SearchResponse<Void> response = client.search(s -> s
            .index(observationIndex)
            .size(0)
            .aggregations("tool_usage", a -> a.terms(t -> t.field("toolName.keyword").size(20)))
            .aggregations("top_tags", a -> a.terms(t -> t.field("tags.keyword").size(20))),
            Void.class
        );
        return Map.of("aggregations", response.aggregations());
    }

    public Map<String, Object> getProjectMetrics(String projectId) throws IOException {
        SearchResponse<Map<String, Object>> response = client.search(s -> {
            var base = s.index(observationIndex).size(0);
            if (projectId != null && !projectId.isBlank()) {
                base = base.query(q -> q.term(t -> t.field("projectId").value(projectId)));
            }
            return base
                .aggregations("total", a -> a.valueCount(vc -> vc.field("_index")))
                .aggregations("tier_dist", a -> a.terms(t -> t.field("tier.keyword").size(10)))
                .aggregations("top_files", a -> a.terms(t -> t.field("filePath.keyword").size(20)))
                .aggregations("tool_usage", a -> a.terms(t -> t.field("toolName.keyword").size(20)))
                .aggregations("tag_trends", a -> a.terms(t -> t.field("tags.keyword").size(20)))
                .aggregations("daily_trend", a -> a.dateHistogram(dh -> dh
                    .field("timestamp")
                    .calendarInterval(co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval.Day)
                    .format("yyyy-MM-dd")
                ));
            }, mapDocumentClass());

        var aggs = response.aggregations();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId != null ? projectId : "all");

        if (aggs != null && aggs.containsKey("total")) {
            result.put("totalObservations", aggs.get("total").valueCount().value());
        }
        if (aggs != null && aggs.containsKey("tier_dist")) {
            result.put("tierDistribution", extractBuckets(aggs.get("tier_dist")));
        }
        if (aggs != null && aggs.containsKey("top_files")) {
            result.put("topFiles", extractBuckets(aggs.get("top_files")));
        }
        if (aggs != null && aggs.containsKey("tool_usage")) {
            result.put("toolUsage", extractBuckets(aggs.get("tool_usage")));
        }
        if (aggs != null && aggs.containsKey("tag_trends")) {
            result.put("tagTrends", extractBuckets(aggs.get("tag_trends")));
        }
        if (aggs != null && aggs.containsKey("daily_trend")) {
            result.put("dailyTrend", extractDateHistogram(aggs.get("daily_trend")));
        }

        return result;
    }

    private List<Map<String, Object>> extractBuckets(co.elastic.clients.elasticsearch._types.aggregations.Aggregate agg) {
        var buckets = agg.sterms().buckets().array();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var bucket : buckets) {
            String key = bucket.key().stringValue();
            result.add(Map.of(
                "key", key,
                "count", bucket.docCount()
            ));
        }
        return result;
    }

    private List<Map<String, Object>> extractDateHistogram(co.elastic.clients.elasticsearch._types.aggregations.Aggregate agg) {
        var buckets = agg.dateHistogram().buckets().array();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var bucket : buckets) {
            result.add(Map.of(
                "date", bucket.keyAsString(),
                "count", bucket.docCount()
            ));
        }
        return result;
    }

    private long getTotalCount() throws IOException {
        return client.count(c -> c.index(observationIndex)).count();
    }

    private Class<Map<String, Object>> mapDocumentClass() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }
}
