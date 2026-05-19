package com.agentmemory.service;

import com.agentmemory.config.MemoryProperties;
import com.agentmemory.model.MemorySearchRequest;
import com.agentmemory.model.MemoryTier;
import com.agentmemory.model.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MemoryPipelineService {

    private static final Logger log = LoggerFactory.getLogger(MemoryPipelineService.class);

    private final ElasticsearchService esService;
    private final DashScopeService dashScopeService;
    private final MemoryProperties props;
    private final Map<String, Instant> dedupCache = new ConcurrentHashMap<>();

    public MemoryPipelineService(ElasticsearchService esService, DashScopeService dashScopeService, MemoryProperties props) {
        this.esService = esService;
        this.dashScopeService = dashScopeService;
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
        float[] embedding = dashScopeService.embed(content);

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
        float[] queryVector = dashScopeService.embed(query);

        var req = new MemorySearchRequest(query, projectId, sessionId, null, props.getTopKFinal(), null);
        List<SearchResult> results = esService.search(req, queryVector);

        // Rerank with DashScope
        if (!results.isEmpty()) {
            List<String> docs = results.stream().map(SearchResult::content).filter(Objects::nonNull).toList();
            var reranked = dashScopeService.rerank(query, docs);
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

        return results;
    }

    public Map<String, Object> saveInsight(String content, MemoryTier tier, String sessionId, List<String> tags, String projectId) throws IOException {
        float[] embedding = dashScopeService.embed(content);
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
        doc.put("isActive", true);
        doc.put("accessCount", 0);
        esService.saveObservation(doc, id);
        return doc;
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
