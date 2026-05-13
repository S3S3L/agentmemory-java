package com.agentmemory.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.SortOrder;
import com.agentmemory.config.MemoryProperties;
import com.agentmemory.model.MemorySearchRequest;
import com.agentmemory.model.MemoryTier;
import com.agentmemory.model.QueryExpander;
import com.agentmemory.model.SearchResult;
import com.agentmemory.model.SessionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("unchecked")
@Service
public class ElasticsearchService {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchService.class);
    private static final String OBS_INDEX = "memory-observations";
    private static final String SESSION_INDEX = "memory-sessions";

    private final ElasticsearchClient client;
    private final MemoryProperties props;
    private final DashScopeService dashScopeService;

    public ElasticsearchService(ElasticsearchClient client, MemoryProperties props, DashScopeService dashScopeService) {
        this.client = client;
        this.props = props;
        this.dashScopeService = dashScopeService;
    }

    public DashScopeService getDashScopeService() { return dashScopeService; }

    public void saveObservation(Map<String, Object> doc, String id) throws IOException {
        client.index(i -> i
            .index(OBS_INDEX)
            .id(id)
            .document(doc)
        );
        log.debug("Saved observation {} to {}", id, OBS_INDEX);
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

        // Enhanced BM25 search
        List<Hit<Map<String, Object>>> bm25Hits = bm25Search(req);

        // Vector search — only if real embedding is available
        List<Hit<Map<String, Object>>> vectorHits = List.<Hit<Map<String, Object>>>of();
        boolean useVector = dashScopeService != null && dashScopeService.isRealEmbeddingAvailable();
        if (useVector && queryVector != null && queryVector.length > 0) {
            vectorHits = vectorSearch(req, queryVector);
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
        SearchResponse<Map> response = client.search(s -> {
            var q = s.index(OBS_INDEX)
                .size(props.getTopKBm25())
                .source(src -> src.filter(f -> f.excludes("embedding")))
                .query(qb -> qb.match(m -> m.field("content").query(req.query())));
            return q;
        }, Map.class);

        return (List<Hit<Map<String, Object>>>)(List<?>) response.hits().hits();
    }

    private List<Hit<Map<String, Object>>> vectorSearch(MemorySearchRequest req, float[] queryVector) throws IOException {
        SearchResponse<Map> response = client.search(s -> {
            List<Float> floatList = new ArrayList<>(queryVector.length);
            for (float v : queryVector) floatList.add(v);
            var builder = s.index(OBS_INDEX)
                .size(props.getTopKVector())
                .source(src -> src.filter(f -> f.excludes("embedding")))
                .knn(k -> k
                    .field("embedding")
                    .queryVector(floatList)
                    .numCandidates(props.getTopKVector())
                    .k(props.getTopKVector())
                );
            return builder;
        }, Map.class);

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
        client.delete(d -> d.index(OBS_INDEX).id(id));
    }

    public void batchSave(List<Map<String, Object>> documents, List<String> ids) throws IOException {
        if (documents.isEmpty()) return;
        var ops = new ArrayList<co.elastic.clients.elasticsearch.core.bulk.BulkOperation>();
        for (int i = 0; i < documents.size(); i++) {
            int idx = i;
            ops.add(co.elastic.clients.elasticsearch.core.bulk.BulkOperation.of(o -> o
                .index(bi -> bi.index(OBS_INDEX).id(ids.get(idx)).document(documents.get(idx)))));
        }
        client.bulk(b -> b.operations(ops));
    }

    public List<Map<String, Object>> getObservationsByFile(String filePath, int limit) throws IOException {
        SearchResponse<Map> response = client.search(s -> s
            .index(OBS_INDEX)
            .size(limit)
            .query(q -> q.term(t -> t.field("filePath.keyword").value(filePath)))
            .sort(sort -> sort.field(f -> f.field("timestamp").order(SortOrder.Desc))),
            Map.class
        );
        return (List<Map<String, Object>>)(List<?>) response.hits().hits().stream()
            .map(Hit::source)
            .filter(Objects::nonNull)
            .toList();
    }

    public List<Map<String, Object>> getTimeline(int limit) throws IOException {
        SearchResponse<Map> response = client.search(s -> s
            .index(OBS_INDEX)
            .size(limit)
            .sort(sort -> sort.field(f -> f.field("timestamp").order(SortOrder.Desc)))
            .source(src -> src.filter(f -> f.excludes("embedding"))),
            Map.class
        );
        return (List<Map<String, Object>>)(List<?>) response.hits().hits().stream()
            .map(Hit::source)
            .filter(Objects::nonNull)
            .toList();
    }

    public Map<String, Object> getProfile() throws IOException {
        SearchResponse<Void> response = client.search(s -> s
            .index(OBS_INDEX)
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
            .index(OBS_INDEX)
            .size(0)
            .aggregations("tool_usage", a -> a.terms(t -> t.field("toolName.keyword").size(20)))
            .aggregations("top_tags", a -> a.terms(t -> t.field("tags.keyword").size(20))),
            Void.class
        );
        return Map.of("aggregations", response.aggregations());
    }

    private long getTotalCount() throws IOException {
        return client.count(c -> c.index(OBS_INDEX)).count();
    }
}
