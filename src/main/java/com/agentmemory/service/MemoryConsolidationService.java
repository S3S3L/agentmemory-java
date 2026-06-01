package com.agentmemory.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 4-tier memory consolidation + Ebbinghaus decay.
 *
 * Tiers: WORKING (raw) → EPISODIC (session summary) → SEMANTIC (facts) → PROCEDURAL (patterns)
 * Decay: half-life per tier (30d/60d/90d), importance boosts via accessCount.
 * Contradiction: same file+tool combo with many updates → mark older ones as possibly-stale.
 */
@Service
public class MemoryConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationService.class);
    private static final String OBS_INDEX = "memory-observations";

    private final ElasticsearchClient esClient;
    private final LifecycleCoordinator coordinator;
    private ScheduledExecutorService scheduler;

    @Autowired
    public MemoryConsolidationService(ElasticsearchClient esClient, LifecycleCoordinator coordinator) {
        this.esClient = esClient;
        this.coordinator = coordinator;
    }

    public MemoryConsolidationService(ElasticsearchClient esClient) {
        this(esClient, null);
    }

    /**
     * Start background consolidation/decay scheduler for non-Spring usage.
     * Call this from StdioMcpServer or other standalone entry points.
     */
    public void startScheduler() {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "memory-consolidation");
            t.setDaemon(true);
            return t;
        });
        // Consolidate every 6 hours
        scheduler.scheduleAtFixedRate(this::consolidate, 0, 6, TimeUnit.HOURS);
        // Decay sweep daily at ~2am (approximate: 2 hours after start, then every 24h)
        scheduler.scheduleAtFixedRate(this::applyDecay, 2, 24, TimeUnit.HOURS);
        log.info("Memory consolidation scheduler started");
    }

    public void stopScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            log.info("Memory consolidation scheduler stopped");
        }
    }

    @Scheduled(cron = "${memory.consolidation.cron:0 0 */6 * * *}")
    public void consolidate() {
        if (coordinator != null && !coordinator.acquireLease("memory-consolidation", Duration.ofMinutes(10))) {
            log.debug("Skipping consolidation: lease held by another instance");
            return;
        }
        log.info("Running memory consolidation...");
        String error = null;
        try {
            promoteToEpisodic();
            promoteToSemantic();
            promoteToProcedural();
            log.info("Memory consolidation complete");
        } catch (Exception e) {
            log.error("Memory consolidation failed", e);
            error = e.getMessage();
        } finally {
            if (coordinator != null) coordinator.recordJobComplete("memory-consolidation", error);
        }
    }

    @Scheduled(cron = "${memory.decay.cron:0 0 2 * * *}")
    public void applyDecay() {
        if (coordinator != null && !coordinator.acquireLease("memory-decay", Duration.ofMinutes(10))) {
            log.debug("Skipping decay sweep: lease held by another instance");
            return;
        }
        log.info("Running memory decay sweep...");
        String error = null;
        try {
            evictStaleMemories();
            detectContradictions();
            log.info("Memory decay sweep complete");
        } catch (Exception e) {
            log.error("Memory decay failed", e);
            error = e.getMessage();
        } finally {
            if (coordinator != null) coordinator.recordJobComplete("memory-decay", error);
        }
    }

    // --- Consolidation ---

    private void promoteToEpisodic() throws java.io.IOException {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        promoteTier("WORKING", "EPISODIC", cutoff);
    }

    private void promoteToSemantic() throws java.io.IOException {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        promoteWithAccessThreshold("EPISODIC", "SEMANTIC", cutoff, 3);
    }

    private void promoteToProcedural() throws java.io.IOException {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        promoteWithAccessThreshold("SEMANTIC", "PROCEDURAL", cutoff, 10);
    }

    private void promoteTier(String from, String to, Instant cutoff) throws java.io.IOException {
        var docs = getMemoriesByTier(from, 100);
        int promoted = 0;
        for (var doc : docs) {
            String ts = (String) doc.get("timestamp");
            if (ts != null && Instant.parse(ts).isBefore(cutoff)) {
                String id = (String) doc.get("id");
                esClient.update(u -> u
                    .index(OBS_INDEX)
                    .id(id)
                    .doc(Map.of("tier", to)), Map.class);
                promoted++;
            }
        }
        log.info("Promoted {} memories from {} → {}", promoted, from, to);
    }

    private void promoteWithAccessThreshold(String from, String to, Instant cutoff, int minAccess) throws java.io.IOException {
        var docs = getMemoriesByTier(from, 100);
        int promoted = 0;
        for (var doc : docs) {
            String ts = (String) doc.get("timestamp");
            Number accessCount = (Number) doc.get("accessCount");
            if (ts != null && Instant.parse(ts).isBefore(cutoff)
                && accessCount != null && accessCount.intValue() >= minAccess) {
                String id = (String) doc.get("id");
                esClient.update(u -> u
                    .index(OBS_INDEX)
                    .id(id)
                    .doc(Map.of("tier", to)), Map.class);
                promoted++;
            }
        }
        log.info("Promoted {} memories from {} → {} (minAccess={})", promoted, from, to, minAccess);
    }

    // --- Decay ---

    private void evictStaleMemories() throws java.io.IOException {
        Map<String, Double> halfLives = Map.of(
            "WORKING", 1.0,
            "EPISODIC", 30.0,
            "SEMANTIC", 60.0,
            "PROCEDURAL", 90.0
        );

        int evicted = 0;
        for (var entry : halfLives.entrySet()) {
            String tier = entry.getKey();
            double halfLifeDays = entry.getValue();
            var docs = getMemoriesByTier(tier, 200);

            for (var doc : docs) {
                double importance = importanceScore(doc);
                double effectiveHalfLife = halfLifeDays / Math.max(0.1, importance);
                String ts = (String) doc.get("timestamp");
                if (ts != null) {
                    double ageDays = Duration.between(Instant.parse(ts), Instant.now()).toDays();
                    if (ageDays > effectiveHalfLife * 3) {
                        String id = (String) doc.get("id");
                        esClient.delete(d -> d.index(OBS_INDEX).id(id));
                        evicted++;
                    }
                }
            }
        }
        log.info("Evicted {} stale memories", evicted);
    }

    private void detectContradictions() throws java.io.IOException {
        var allDocs = getAllMemories(500);
        Map<String, List<Map<String, Object>>> grouped = allDocs.stream()
            .filter(d -> d.get("toolName") != null && d.get("filePath") != null)
            .collect(Collectors.groupingBy(
                d -> d.get("toolName") + "|" + d.get("filePath")
            ));

        int contradictions = 0;
        for (var entry : grouped.entrySet()) {
            var group = entry.getValue();
            if (group.size() > 5) {
                var sorted = group.stream()
                    .sorted(Comparator.comparing(d -> (String) d.get("timestamp"), Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
                for (int i = 1; i < sorted.size(); i++) {
                    String id = (String) sorted.get(i).get("id");
                    esClient.update(u -> u
                        .index(OBS_INDEX)
                        .id(id)
                        .doc(Map.of("tags", List.of("possibly-stale"))), Map.class);
                    contradictions++;
                }
            }
        }
        log.info("Detected {} potential contradictions", contradictions);
    }

    // --- Helpers ---

    private double importanceScore(Map<String, Object> doc) {
        int accessCount = doc.get("accessCount") != null
            ? ((Number) doc.get("accessCount")).intValue() : 0;
        return 1.0 + Math.log(1.0 + accessCount) / Math.log(2.0);
    }

    private List<Map<String, Object>> getMemoriesByTier(String tier, int size) throws java.io.IOException {
        SearchResponse<Map> response = esClient.search(s -> s
            .index(OBS_INDEX)
            .size(size)
            .query(q -> q.term(t -> t.field("tier").value(tier))),
            Map.class
        );
        return extractHits(response);
    }

    private List<Map<String, Object>> getAllMemories(int size) throws java.io.IOException {
        SearchResponse<Map> response = esClient.search(s -> s
            .index(OBS_INDEX)
            .size(size)
            .sort(sort -> sort.field(f -> f.field("timestamp").order(SortOrder.Desc))),
            Map.class
        );
        return extractHits(response);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractHits(SearchResponse<Map> response) {
        return response.hits().hits().stream()
            .map(h -> (Map<String, Object>) h.source())
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
}
