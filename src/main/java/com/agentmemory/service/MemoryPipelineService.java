package com.agentmemory.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.agentmemory.config.MemoryProperties;
import com.agentmemory.model.MemorySearchRequest;
import com.agentmemory.model.MemoryTier;
import com.agentmemory.model.SearchResult;
import com.agentmemory.service.embed.EmbeddingService;
import com.agentmemory.service.rerank.RerankService;

@Service
public class MemoryPipelineService {

    private static final Logger log = LoggerFactory.getLogger(MemoryPipelineService.class);

    private static final Set<String> CONCEPT_TERMS = Set.of(
        "authentication", "jwt", "oauth", "token", "database", "sql", "postgres",
        "prisma", "migration", "deployment", "kubernetes", "docker", "cache", "redis",
        "test", "ci", "pipeline", "security", "authorization", "performance",
        "monitoring", "error", "exception", "middleware", "api", "route",
        "controller", "schema", "config", "typescript"
    );

    private final ElasticsearchService esService;
    private final EmbeddingService embeddingService;
    private final RerankService rerankService;
    private final MemoryProperties props;
    private final Map<String, Instant> dedupCache = new ConcurrentHashMap<>();
    private final Cache<String, float[]> embeddingCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(6, TimeUnit.HOURS)
            .recordStats()
            .build();

    public MemoryPipelineService(ElasticsearchService esService, EmbeddingService embeddingService, RerankService rerankService, MemoryProperties props) {
        this.esService = esService;
        this.embeddingService = embeddingService;
        this.rerankService = rerankService;
        this.props = props;
    }

    public Map<String, Object> observe(String toolName, String input, String output, String filePath, String sessionId, String projectId) throws IOException {
        String content = buildContent(toolName, input, output);
        String contentHash = sha256(content);

        // Dedup
        Instant now = Instant.now();
        Instant window = now.minusSeconds(props.getDedupWindowMinutes() * 60L);
        dedupCache.entrySet().removeIf(e -> e.getValue().isBefore(window));
        if (dedupCache.containsKey(contentHash)) {
            log.debug("Dedup: skipping duplicate observation");
            return Map.of("status", "duplicate");
        }
        dedupCache.put(contentHash, now);

        // Privacy filter
        content = privacyFilter(content);
        input = privacyFilter(input);
        output = privacyFilter(output);

        // Embedding
        String filteredContent = content; // effectively-final alias for lambda
        String embedCacheKey = "model:" + content.strip().toLowerCase();
        float[] embedding = embeddingCache.get(embedCacheKey, k -> embeddingService.embed(filteredContent));

        String id = UUID.randomUUID().toString();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", id);
        doc.put("content", content);
        doc.put("embedding", toDoubleList(embedding));
        doc.put("tier", MemoryTier.WORKING.name());
        doc.put("sessionId", sessionId != null ? sessionId : "default");
        doc.put("projectId", projectId);
        doc.put("toolName", toolName);
        doc.put("input", truncate(input, 2000));
        doc.put("output", truncate(output, 4000));
        doc.put("filePath", filePath);
        doc.put("timestamp", now.toString());
        doc.put("metadata", Map.of());
        doc.put("tags", extractTags(content));

        // Structured fields for multi-field BM25 boosting
        String title = buildTitle(toolName, input);
        List<String> concepts = extractConcepts(content, extractTags(content));
        doc.put("title", title);
        doc.put("concepts", concepts);
        doc.put("narrative", truncate(output, 1000));
        doc.put("facts", List.of());
        doc.put("files", filePath != null ? List.of(filePath) : List.of());

        doc.put("isActive", true);
        doc.put("accessCount", 0);
        doc.put("lastAccessed", null);

        esService.saveObservation(doc, id);
        log.info("Observation saved: {} tool={} file={}", id, toolName, filePath);

        Map<String, Object> result = new LinkedHashMap<>(doc);
        result.put("status", "saved");
        return result;
    }

    public List<SearchResult> recall(String query, String projectId, String sessionId) throws IOException {
        String cacheKey = "model:" + query.strip().toLowerCase();
        float[] queryVector = embeddingCache.get(cacheKey, k -> embeddingService.embed(query));
        log.debug("Embedding cache stats: {}", getCacheStats());

        var req = new MemorySearchRequest(query, projectId, sessionId, null, props.getTopKFinal(), null);
        List<SearchResult> results = esService.search(req, queryVector);

        // Skip rerank when result set is small or top score is dominating
        if (!results.isEmpty()) {
            boolean skipRerank = results.size() <= 3
                    || (results.size() >= 2 && results.get(0).score() / Math.max(results.get(1).score(), 0.01) > 2.0);

            if (!skipRerank && rerankService != null) {
                List<String> docs = results.stream().map(SearchResult::content).filter(Objects::nonNull).toList();
                var reranked = rerankService.rerank(query, docs);
                if (!reranked.isEmpty()) {
                    // Build content → rerank score map for accurate lookup
                    Map<String, Double> contentToRerankScore = new HashMap<>();
                    for (var r : reranked) {
                        if (r.index() >= 0 && r.index() < docs.size()) {
                            contentToRerankScore.put(docs.get(r.index()), r.score());
                        }
                    }
                    results = results.stream()
                        .map(r -> new SearchResult(
                            r.id(), r.content(), r.tier(), r.sessionId(), r.toolName(),
                            r.filePath(), contentToRerankScore.getOrDefault(r.content(), 0.0),
                            r.bm25Score(), r.vectorScore(),
                            contentToRerankScore.getOrDefault(r.content(), 0.0)
                        ))
                        .sorted(Comparator.comparingDouble(SearchResult::rerankScore).reversed())
                        .toList();
                }
            }
        }

        return results;
    }

    public Map<String, Object> saveInsight(String content, MemoryTier tier, String sessionId, List<String> tags, String projectId) throws IOException {
        String cacheKey = "model:" + content.strip().toLowerCase();
        float[] embedding = embeddingCache.get(cacheKey, k -> embeddingService.embed(content));
        String id = UUID.randomUUID().toString();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", id);
        doc.put("content", content);
        doc.put("embedding", toDoubleList(embedding));
        doc.put("tier", tier.name());
        doc.put("sessionId", sessionId != null ? sessionId : "default");
        doc.put("projectId", projectId);
        doc.put("toolName", null);
        doc.put("input", null);
        doc.put("output", null);
        doc.put("filePath", null);
        doc.put("timestamp", Instant.now().toString());
        doc.put("tags", tags != null ? tags : List.of());
        doc.put("title", truncate(content, 120));
        doc.put("concepts", tags != null ? tags : List.of());
        doc.put("narrative", content);
        doc.put("facts", List.of());
        doc.put("files", List.of());
        doc.put("isActive", true);
        doc.put("accessCount", 0);
        esService.saveObservation(doc, id);
        return doc;
    }

    public String getCacheStats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats stats = embeddingCache.stats();
        return String.format("EmbeddingCache: size=%d, hits=%d, misses=%d, hitRate=%.1f%%",
            embeddingCache.estimatedSize(), stats.hitCount(), stats.missCount(), stats.hitRate() * 100);
    }

    private String buildTitle(String toolName, String input) {
        String base = toolName != null ? toolName + ": " : "";
        if (input != null && !input.isBlank()) {
            String firstLine = input.strip().lines().findFirst().orElse("").strip();
            base += firstLine.length() > 80 ? firstLine.substring(0, 80) : firstLine;
        }
        return base.length() > 120 ? base.substring(0, 120) : base;
    }

    private List<String> extractConcepts(String content, List<String> existingTags) {
        Set<String> concepts = new LinkedHashSet<>(existingTags);
        String lower = content.toLowerCase();
        for (String term : CONCEPT_TERMS) {
            if (lower.contains(term)) concepts.add(term);
        }
        return new ArrayList<>(concepts);
    }

    private String buildContent(String toolName, String input, String output) {
        var sb = new StringBuilder();
        if (toolName != null) sb.append("Tool: ").append(toolName).append("\n");
        if (input != null && !input.isBlank()) sb.append("Input: ").append(truncate(input, 1000)).append("\n");
        if (output != null && !output.isBlank()) sb.append("Output: ").append(truncate(output, 2000));
        return sb.toString();
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return input.hashCode() + "";
        }
    }

    private String privacyFilter(String text) {
        if (text == null) return null;
        return text
            .replaceAll("(?i)(api[_-]?key|secret|token|password)\\s*[:=]\\s*[\\S]+", "$1: ***REDACTED***")
            .replaceAll("sk-[a-zA-Z0-9]{20,}", "***REDACTED***")
            .replaceAll("Bearer\\s+[a-zA-Z0-9._-]+", "Bearer ***REDACTED***");
    }

    private List<String> extractTags(String content) {
        List<String> tags = new ArrayList<>();
        String lower = content.toLowerCase();
        if (lower.contains("test")) tags.add("testing");
        if (lower.contains("auth") || lower.contains("jwt") || lower.contains("token")) tags.add("auth");
        if (lower.contains("database") || lower.contains("sql") || lower.contains("query")) tags.add("database");
        if (lower.contains("api") || lower.contains("endpoint") || lower.contains("route")) tags.add("api");
        if (lower.contains("error") || lower.contains("exception") || lower.contains("bug")) tags.add("bug");
        if (lower.contains("config") || lower.contains("setting")) tags.add("config");
        return tags;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private List<Double> toDoubleList(float[] arr) {
        List<Double> list = new ArrayList<>(arr.length);
        for (float v : arr) list.add((double) v);
        return list;
    }
}
