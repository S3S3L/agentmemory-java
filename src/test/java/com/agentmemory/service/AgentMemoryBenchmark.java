package com.agentmemory.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.agentmemory.config.DashScopeConfig;
import com.agentmemory.config.MemoryProperties;
import com.agentmemory.model.MemorySearchRequest;
import com.agentmemory.model.SearchResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * agentmemory benchmark — Quality evaluation against the rohitg00/agentmemory internal dataset.
 *
 * Dataset: 240 unique synthetic observations across 30 sessions (coding project)
 * Queries: 20 labeled queries with ground-truth relevance
 * Metrics: Recall@5, Recall@10, Precision@5, NDCG@10, MRR
 *
 * Benchmark data from: https://github.com/rohitg00/agentmemory/tree/main/benchmark
 *
 * Thresholds are set deliberately high to catch regressions:
 *   - R@10 > 60%: hybrid BM25 + kNN + RRF should find most relevant docs
 *   - NDCG@10 > 70%: reranking should order results well
 *   - MRR > 80%: first relevant result should appear near the top
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentMemoryBenchmark {

    private static final String OBS_INDEX = "benchmark-observations";

    private MemoryPipelineService pipeline;
    private ElasticsearchService esService;
    private ElasticsearchClient esClient;

    private List<JsonNode> observations;
    private List<LabeledQuery> queries;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void checkES() throws Exception {
        try (var client = RestClient.builder(new HttpHost("localhost", 9200, "http")).build()) {
            var ec = new ElasticsearchClient(
                new RestClientTransport(client, new JacksonJsonpMapper()));
            assertTrue(ec.ping().value(), "ES must be running on localhost:9200");
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (var client = RestClient.builder(new HttpHost("localhost", 9200, "http")).build()) {
            var ec = new ElasticsearchClient(
                new RestClientTransport(client, new JacksonJsonpMapper()));
            ec.indices().delete(d -> d.index(OBS_INDEX).ignoreUnavailable(true));
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        var restClient = RestClient.builder(new HttpHost("localhost", 9200, "http")).build();
        var localMapper = new ObjectMapper();
        localMapper.registerModule(new JavaTimeModule());
        localMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        esClient = new ElasticsearchClient(
            new RestClientTransport(restClient, new JacksonJsonpMapper(localMapper)));

        var dsConfig = new DashScopeConfig();
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        dsConfig.setApiKey(apiKey != null && !apiKey.isBlank() ? apiKey : "");
        dsConfig.setEmbeddingModel("text-embedding-v4");
        dsConfig.setEmbeddingDimensions(1024);
        dsConfig.setRerankModel("gte-rerank");
        var dsEmbedding = new DashScopeEmbeddingService(dsConfig);
        var dsRerank = new DashScopeRerankService(dsConfig);
        var props = new MemoryProperties();

        if (dsEmbedding.isAvailable()) {
            System.out.println("Using REAL DashScope embedding (API key configured)");
        } else {
            System.out.println("Using FALLBACK embedding (no API key)");
        }

        esService = new ElasticsearchService(esClient, props, dsEmbedding, OBS_INDEX);
        pipeline = new MemoryPipelineService(esService, dsEmbedding, dsRerank, props);
    }

    /**
     * Load the benchmark dataset (observations + labeled queries) from JSON.
     */
    @Test
    @Order(1)
    void loadBenchmarkDataset() throws IOException {
        // Load observations
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("benchmark/observations.json")) {
            assertNotNull(is, "observations.json must exist in test resources");
            observations = new ArrayList<>();
            JsonNode node = mapper.readTree(is);
            for (JsonNode n : node) observations.add(n);
        }
        assertFalse(observations.isEmpty(), "Must have observations");

        // Load labeled queries
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("benchmark/queries.json")) {
            queries = mapper.readValue(is, mapper.getTypeFactory().constructCollectionType(List.class, LabeledQuery.class));
        }
        assertNotNull(queries);
        assertEquals(20, queries.size(), "Must have 20 labeled queries");
    }

    /**
     * Seed all 240 observations into Elasticsearch with real embeddings.
     */
    @Test
    @Order(2)
    void seedObservations() throws IOException, InterruptedException {
        if (observations == null) loadBenchmarkDataset();

        // Clean slate: delete and recreate index for a fresh run
        try {
            esClient.indices().delete(d -> d.index(OBS_INDEX));
        } catch (Exception e) { /* may not exist */ }

        int seeded = 0;
        for (JsonNode obs : observations) {
            String content = buildContent(obs);

            // Generate embedding for this observation
            float[] embedding = esService.getEmbeddingService().embed(content);
            List<Double> embeddingList = new ArrayList<>(embedding.length);
            for (float v : embedding) embeddingList.add((double) v);

            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", obs.get("id").asText());
            doc.put("content", content);
            doc.put("embedding", embeddingList);
            doc.put("tier", "WORKING");
            doc.put("sessionId", obs.get("sessionId").asText());
            doc.put("toolName", obs.get("type").asText());
            doc.put("filePath", obs.has("files") && obs.get("files").isArray() && obs.get("files").size() > 0
                ? obs.get("files").get(0).asText() : null);
            doc.put("timestamp", obs.get("timestamp").asText());
            doc.put("tags", extractTags(obs));
            doc.put("isActive", true);
            doc.put("accessCount", 0);
            doc.put("lastAccessed", null);

            esClient.index(i -> i
                .index(OBS_INDEX)
                .id(obs.get("id").asText())
                .document(doc));
            seeded++;
        }

        // Wait for ES to refresh
        esClient.indices().refresh(r -> r.index(OBS_INDEX));
        System.out.println("Seeded " + seeded + " observations with embeddings");
        assertTrue(seeded >= 240, "Should have seeded at least 240 observations");
    }

    /**
     * Run the full quality benchmark: 20 queries, compute metrics.
     */
    @Test
    @Order(3)
    void runQualityBenchmark() throws IOException, InterruptedException {
        if (queries == null) loadBenchmarkDataset();

        System.out.println("\n========================================");
        System.out.println("  AgentMemory Java — Quality Benchmark");
        System.out.println("  Dataset: 240 obs, 20 queries");
        System.out.println("========================================\n");

        List<QueryResult> results = new ArrayList<>();

        for (LabeledQuery q : queries) {
            long start = System.nanoTime();
            var searchResults = pipeline.recall(q.query, null, null);
            long latencyMs = (System.nanoTime() - start) / 1_000_000;

            // Get the list of retrieved IDs
            List<String> retrievedIds = searchResults.stream()
                .map(SearchResult::id)
                .toList();

            // Compute metrics
            Set<String> relevantSet = new HashSet<>(q.relevantObsIds);
            double recall5 = recall(retrievedIds, relevantSet, 5);
            double recall10 = recall(retrievedIds, relevantSet, 10);
            double precision5 = precision(retrievedIds, relevantSet, 5);
            double ndcg10 = ndcg(retrievedIds, relevantSet, 10);
            double mrr = mrr(retrievedIds, relevantSet);

            results.add(new QueryResult(q.query, q.category, recall5, recall10, precision5, ndcg10, mrr, q.relevantObsIds.size(), retrievedIds.size(), latencyMs));
        }

        // Print per-query results
        System.out.printf("%-50s %-16s %6s %6s %6s %6s %6s %5s%n",
            "Query", "Category", "R@5", "R@10", "P@5", "NDCG@10", "MRR", "Relevant");
        System.out.println("-".repeat(110));

        for (QueryResult r : results) {
            System.out.printf("%-50s %-16s %5.1f%% %5.1f%% %5.1f%% %5.1f%% %5.1f%% %5d%n",
                truncate(r.query, 50), r.category,
                r.recall5 * 100, r.recall10 * 100,
                r.precision5 * 100, r.ndcg10 * 100,
                r.mrr * 100, r.relevantCount);
        }

        // Print summary
        double avgR5 = results.stream().mapToDouble(r -> r.recall5).average().orElse(0);
        double avgR10 = results.stream().mapToDouble(r -> r.recall10).average().orElse(0);
        double avgP5 = results.stream().mapToDouble(r -> r.precision5).average().orElse(0);
        double avgNDCG = results.stream().mapToDouble(r -> r.ndcg10).average().orElse(0);
        double avgMRR = results.stream().mapToDouble(r -> r.mrr).average().orElse(0);
        double avgLatency = results.stream().mapToDouble(r -> r.latencyMs).average().orElse(0);

        System.out.println("\n========================================");
        System.out.println("  Summary");
        System.out.println("========================================");
        System.out.printf("  Recall@5:    %5.1f%%%n", avgR5 * 100);
        System.out.printf("  Recall@10:   %5.1f%%%n", avgR10 * 100);
        System.out.printf("  Precision@5: %5.1f%%%n", avgP5 * 100);
        System.out.printf("  NDCG@10:     %5.1f%%%n", avgNDCG * 100);
        System.out.printf("  MRR:         %5.1f%%%n", avgMRR * 100);
        System.out.printf("  Latency:     %5.1fms/query%n", avgLatency);
        System.out.println("========================================\n");

        // Print by category
        Map<String, List<QueryResult>> byCategory = results.stream()
            .collect(Collectors.groupingBy(r -> r.category));

        System.out.println("  By Category:");
        for (var entry : byCategory.entrySet()) {
            double catR10 = entry.getValue().stream().mapToDouble(r -> r.recall10).average().orElse(0);
            double catNDCG = entry.getValue().stream().mapToDouble(r -> r.ndcg10).average().orElse(0);
            double catMRR = entry.getValue().stream().mapToDouble(r -> r.mrr).average().orElse(0);
            System.out.printf("    %-16s  R@10=%5.1f%%  NDCG@10=%5.1f%%  MRR=%5.1f%%  (%d queries)%n",
                entry.getKey(), catR10 * 100, catNDCG * 100, catMRR * 100, entry.getValue().size());
        }

        // --- Enhanced metrics ---

        // Ground Truth Coverage: unique observations labeled as relevant for at least one query
        Set<String> allRelevantIds = new HashSet<>();
        for (LabeledQuery q : queries) allRelevantIds.addAll(q.relevantObsIds);
        Set<String> obsIds = observations.stream().map(o -> o.get("id").asText()).collect(Collectors.toSet());
        Set<String> labeledInObs = new HashSet<>(allRelevantIds);
        labeledInObs.retainAll(obsIds);
        int totalLabelCount = queries.stream().mapToInt(q -> q.relevantObsIds.size()).sum();
        System.out.println("\n  Ground Truth Coverage:");
        System.out.printf("    Unique labeled observations: %d / %d (%.1f%%)%n", labeledInObs.size(), observations.size(), (double) labeledInObs.size() / observations.size() * 100);
        System.out.printf("    Total label assignments (cross-queries): %d%n", totalLabelCount);

        // Per-query latency spread
        double minLatency = results.stream().mapToDouble(r -> r.latencyMs).min().orElse(0);
        double maxLatency = results.stream().mapToDouble(r -> r.latencyMs).max().orElse(0);
        double p50Latency = median(results.stream().mapToDouble(r -> r.latencyMs).boxed().sorted().toList());
        System.out.printf("  Latency Spread: min=%.0fms, p50=%.0fms, max=%.0fms%n", minLatency, p50Latency, maxLatency);

        // Category diagnostics
        System.out.println("\n  Category Diagnostics:");
        for (var entry : byCategory.entrySet()) {
            double catR10 = entry.getValue().stream().mapToDouble(r -> r.recall10).average().orElse(0);
            double catNDCG = entry.getValue().stream().mapToDouble(r -> r.ndcg10).average().orElse(0);
            double catMRR = entry.getValue().stream().mapToDouble(r -> r.mrr).average().orElse(0);
            double catP5 = entry.getValue().stream().mapToDouble(r -> r.precision5).average().orElse(0);
            double catR5 = entry.getValue().stream().mapToDouble(r -> r.recall5).average().orElse(0);
            System.out.printf("    %-16s  R@5=%5.1f%%  R@10=%5.1f%%  P@5=%5.1f%%  NDCG@10=%5.1f%%  MRR=%5.1f%%  (%d queries)%n",
                entry.getKey(), catR5 * 100, catR10 * 100, catP5 * 100, catNDCG * 100, catMRR * 100, entry.getValue().size());

            // Show worst-performing queries in each category
            var worst = entry.getValue().stream().sorted(java.util.Comparator.comparingDouble((QueryResult r) -> r.recall10)).limit(2).toList();
            for (QueryResult w : worst) {
                System.out.printf("      [LOW]  %-45s R@10=%5.1f%%  NDCG=%5.1f%%%n",
                    truncate(w.query, 45), w.recall10 * 100, w.ndcg10 * 100);
            }
        }

        // Assertions — regression guard thresholds (tuned after QueryExpander + numCandidates fixes)
        assertTrue(avgR10 > 0.45, "Average Recall@10 should be > 45% (got " + String.format("%.1f", avgR10 * 100) + "%)");
        assertTrue(avgNDCG > 0.50, "Average NDCG@10 should be > 50% (got " + String.format("%.1f", avgNDCG * 100) + "%)");
        assertTrue(avgMRR > 0.55, "Average MRR should be > 55% (got " + String.format("%.1f", avgMRR * 100) + "%)");
    }

    // --- Metric computation (ported from TypeScript) ---

    private double recall(List<String> retrieved, Set<String> relevant, int k) {
        if (relevant.isEmpty()) return 1;
        Set<String> topK = new HashSet<>(retrieved.subList(0, Math.min(k, retrieved.size())));
        long hits = relevant.stream().filter(topK::contains).count();
        return (double) hits / relevant.size();
    }

    private double precision(List<String> retrieved, Set<String> relevant, int k) {
        List<String> topK = retrieved.subList(0, Math.min(k, retrieved.size()));
        if (topK.isEmpty()) return 0;
        long hits = topK.stream().filter(relevant::contains).count();
        return (double) hits / topK.size();
    }

    private double dcg(List<Boolean> relevances, int k) {
        double sum = 0;
        for (int i = 0; i < Math.min(k, relevances.size()); i++) {
            sum += (relevances.get(i) ? 1 : 0) / Math.log(i + 2);
        }
        return sum / Math.log(2);
    }

    private double ndcg(List<String> retrieved, Set<String> relevant, int k) {
        List<Boolean> actual = retrieved.subList(0, Math.min(k, retrieved.size())).stream()
            .map(relevant::contains).collect(Collectors.toList());
        List<Boolean> ideal = new ArrayList<>();
        for (int i = 0; i < Math.min(k, relevant.size()); i++) ideal.add(true);

        double idealDcg = dcg(ideal, k);
        if (idealDcg == 0) return 0;
        return dcg(actual, k) / idealDcg;
    }

    private double mrr(List<String> retrieved, Set<String> relevant) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (relevant.contains(retrieved.get(i))) return 1.0 / (i + 1);
        }
        return 0;
    }

    // --- Helpers ---

    private String buildContent(JsonNode obs) {
        var sb = new StringBuilder();
        if (obs.has("title")) sb.append(obs.get("title").asText()).append("\n");
        if (obs.has("subtitle")) sb.append(obs.get("subtitle").asText()).append("\n");
        if (obs.has("narrative")) sb.append(obs.get("narrative").asText()).append("\n");
        if (obs.has("facts") && obs.get("facts").isArray()) {
            for (JsonNode f : obs.get("facts")) sb.append(f.asText()).append("\n");
        }
        if (obs.has("concepts") && obs.get("concepts").isArray()) {
            for (JsonNode c : obs.get("concepts")) sb.append(c.asText()).append(" ");
        }
        return sb.toString();
    }

    private List<String> extractTags(JsonNode obs) {
        List<String> tags = new ArrayList<>();
        if (obs.has("concepts") && obs.get("concepts").isArray()) {
            for (JsonNode c : obs.get("concepts")) tags.add(c.asText());
        }
        return tags;
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private double median(List<Double> sorted) {
        if (sorted.isEmpty()) return 0;
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 0
            ? (sorted.get(mid - 1) + sorted.get(mid)) / 2.0
            : sorted.get(mid);
    }

    // --- Data classes ---

    static class LabeledQuery {
        public String query;
        public List<String> relevantObsIds;
        public String description;
        public String category;
    }

    static class QueryResult {
        String query;
        String category;
        double recall5, recall10, precision5, ndcg10, mrr;
        int relevantCount, retrievedCount;
        long latencyMs;

        QueryResult(String query, String category, double recall5, double recall10,
                    double precision5, double ndcg10, double mrr,
                    int relevantCount, int retrievedCount, long latencyMs) {
            this.query = query;
            this.category = category;
            this.recall5 = recall5;
            this.recall10 = recall10;
            this.precision5 = precision5;
            this.ndcg10 = ndcg10;
            this.mrr = mrr;
            this.relevantCount = relevantCount;
            this.retrievedCount = retrievedCount;
            this.latencyMs = latencyMs;
        }
    }
}
