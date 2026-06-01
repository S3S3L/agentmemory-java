package com.agentmemory.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.agentmemory.config.MemoryProperties;
import com.agentmemory.model.LifecycleJobState;

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
 * Decay: configurable stale-day thresholds per tier; soft-delete preferred over hard-delete.
 * Contradiction: same file+tool combo with many updates → append "possibly-stale" tag via Painless script.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@Service
public class MemoryConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationService.class);
    private static final String OBS_INDEX = "memory-observations";
    private static final int PAGE_SIZE = 100;

    private final ElasticsearchClient esClient;
    private final LifecycleCoordinator coordinator;
    final MemoryProperties memProps;
    private ScheduledExecutorService scheduler;

    @Autowired
    public MemoryConsolidationService(ElasticsearchClient esClient, LifecycleCoordinator coordinator,
                                      MemoryProperties memProps) {
        this.esClient = esClient;
        this.coordinator = coordinator;
        this.memProps = memProps;
    }

    public MemoryConsolidationService(ElasticsearchClient esClient, LifecycleCoordinator coordinator) {
        this(esClient, coordinator, null);
    }

    public MemoryConsolidationService(ElasticsearchClient esClient) {
        this(esClient, null, null);
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
        if (memProps != null && !memProps.getConsolidation().isEnabled()) {
            log.debug("Consolidation is disabled, skipping.");
            return;
        }
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
        if (memProps != null && !memProps.getDecay().isEnabled()) {
            log.debug("Decay is disabled, skipping.");
            return;
        }
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

    void promoteToEpisodic() throws java.io.IOException {
        var p = promotion();
        Instant ageCutoff = Instant.now().minus(p.getWorkingToEpisodicDays(), ChronoUnit.DAYS);
        int minAccess = p.getWorkingToEpisodicAccessCount();
        // WORKING → EPISODIC: age >= threshold OR accessCount >= minAccess
        promoteByTierPaginated("WORKING", "EPISODIC", ageCutoff, minAccess, true);
    }

    void promoteToSemantic() throws java.io.IOException {
        var p = promotion();
        Instant ageCutoff = Instant.now().minus(p.getEpisodicToSemanticDays(), ChronoUnit.DAYS);
        int minAccess = p.getEpisodicToSemanticAccessCount();
        // EPISODIC → SEMANTIC: age >= threshold AND accessCount >= minAccess
        promoteByTierPaginated("EPISODIC", "SEMANTIC", ageCutoff, minAccess, false);
    }

    void promoteToProcedural() throws java.io.IOException {
        int minAccess = promotion().getSemanticToProceduralAccessCount();
        // SEMANTIC → PROCEDURAL: accessCount >= minAccess only (no age requirement)
        promoteByAccessCountPaginated("SEMANTIC", "PROCEDURAL", minAccess);
    }

    /**
     * Paginated promotion based on age and/or access count.
     *
     * @param ageOrAccess if true, qualify when age OR access meets threshold (OR logic);
     *                    if false, both age AND access must meet threshold (AND logic)
     */
    private void promoteByTierPaginated(String from, String to, Instant ageCutoff,
                                        int minAccess, boolean ageOrAccess) throws java.io.IOException {
        List<FieldValue> searchAfter = null;
        int promoted = 0;
        while (true) {
            var page = searchByTierPage(from, PAGE_SIZE, searchAfter);
            if (page.isEmpty()) break;
            for (var hit : page) {
                Map<String, Object> doc = hit.source();
                if (doc == null) continue;
                String ts = (String) doc.get("timestamp");
                Number accessCountNum = (Number) doc.get("accessCount");
                int access = accessCountNum != null ? accessCountNum.intValue() : 0;
                boolean ageQualifies = ts != null && Instant.parse(ts).isBefore(ageCutoff);
                boolean accessQualifies = access >= minAccess;
                boolean shouldPromote = ageOrAccess ? (ageQualifies || accessQualifies)
                                                    : (ageQualifies && accessQualifies);
                if (shouldPromote) {
                    String id = hit.id();
                    esClient.update(u -> u
                        .index(OBS_INDEX)
                        .id(id)
                        .doc(Map.of("tier", to)), Map.class);
                    promoted++;
                }
            }
            if (page.size() < PAGE_SIZE) break;
            searchAfter = page.get(page.size() - 1).sort();
        }
        log.info("Promoted {} memories from {} → {} (ageOrAccess={})", promoted, from, to, ageOrAccess);
    }

    /** Paginated promotion based solely on access count (no age gate). */
    private void promoteByAccessCountPaginated(String from, String to, int minAccess) throws java.io.IOException {
        List<FieldValue> searchAfter = null;
        int promoted = 0;
        while (true) {
            var page = searchByTierPage(from, PAGE_SIZE, searchAfter);
            if (page.isEmpty()) break;
            for (var hit : page) {
                Map<String, Object> doc = hit.source();
                if (doc == null) continue;
                Number accessCountNum = (Number) doc.get("accessCount");
                int access = accessCountNum != null ? accessCountNum.intValue() : 0;
                if (access >= minAccess) {
                    String id = hit.id();
                    esClient.update(u -> u
                        .index(OBS_INDEX)
                        .id(id)
                        .doc(Map.of("tier", to)), Map.class);
                    promoted++;
                }
            }
            if (page.size() < PAGE_SIZE) break;
            searchAfter = page.get(page.size() - 1).sort();
        }
        log.info("Promoted {} memories from {} → {} (minAccess={})", promoted, from, to, minAccess);
    }

    // --- Decay ---

    void evictStaleMemories() throws java.io.IOException {
        int staleEpisodicDays = memProps != null ? memProps.getDecay().getStaleEpisodicDays() : 30;
        int staleSemanticDays = memProps != null ? memProps.getDecay().getStaleSemanticDays() : 90;
        boolean softDelete = memProps == null || memProps.getDecay().isSoftDelete();

        evictStaleTierPaginated("WORKING", 3, softDelete);
        evictStaleTierPaginated("EPISODIC", staleEpisodicDays, softDelete);
        evictStaleTierPaginated("SEMANTIC", staleSemanticDays, softDelete);
        evictStaleTierPaginated("PROCEDURAL", staleSemanticDays * 2, softDelete);
    }

    private void evictStaleTierPaginated(String tier, int staleDays, boolean softDelete) throws java.io.IOException {
        Instant cutoff = Instant.now().minus(staleDays, ChronoUnit.DAYS);
        List<FieldValue> searchAfter = null;
        int evicted = 0;
        while (true) {
            var page = searchByTierPage(tier, PAGE_SIZE, searchAfter);
            if (page.isEmpty()) break;
            for (var hit : page) {
                Map<String, Object> doc = hit.source();
                if (doc == null) continue;
                String ts = (String) doc.get("timestamp");
                if (ts != null && Instant.parse(ts).isBefore(cutoff)) {
                    String id = hit.id();
                    if (softDelete) {
                        esClient.update(u -> u
                            .index(OBS_INDEX)
                            .id(id)
                            .doc(Map.of("isActive", false)), Map.class);
                    } else {
                        esClient.delete(d -> d.index(OBS_INDEX).id(id));
                    }
                    evicted++;
                }
            }
            if (page.size() < PAGE_SIZE) break;
            searchAfter = page.get(page.size() - 1).sort();
        }
        log.info("Evicted {} stale {} memories (softDelete={})", evicted, tier, softDelete);
    }

    void detectContradictions() throws java.io.IOException {
        // Paginate over all docs, accumulate for grouping
        List<Map<String, Object>> allDocs = new ArrayList<>();
        List<FieldValue> searchAfter = null;
        while (true) {
            var page = getAllMemoriesPage(PAGE_SIZE, searchAfter);
            if (page.isEmpty()) break;
            for (var hit : page) {
                Map<String, Object> src = hit.source();
                if (src != null) {
                    Map<String, Object> doc = new HashMap<>(src);
                    doc.put("id", hit.id());
                    allDocs.add(doc);
                }
            }
            if (page.size() < PAGE_SIZE) break;
            searchAfter = page.get(page.size() - 1).sort();
        }

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
                    .sorted(Comparator.comparing(d -> (String) d.get("timestamp"),
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
                for (int i = 1; i < sorted.size(); i++) {
                    String id = (String) sorted.get(i).get("id");
                    // Append tag using Painless script to preserve existing tags
                    esClient.<Map, Map>update(u -> u
                        .index(OBS_INDEX)
                        .id(id)
                        .script(s -> s
                            .source(src2 -> src2.scriptString(
                                "if (ctx._source.tags == null) { ctx._source.tags = [params.tag]; } " +
                                "else if (!ctx._source.tags.contains(params.tag)) { ctx._source.tags.add(params.tag); }"))
                            .params(Map.of("tag", JsonData.of("possibly-stale")))
                        ), Map.class);
                    contradictions++;
                }
            }
        }
        log.info("Detected {} potential contradictions", contradictions);
    }

    // --- Scheduling helpers ---

    /**
     * Returns true if the job has never completed, or completed more than {@code intervalMinutes} ago.
     */
    public boolean isJobDue(String jobName, long intervalMinutes) {
        if (coordinator == null) return true;
        Optional<LifecycleJobState> state = coordinator.getJobState(jobName);
        if (state.isEmpty() || state.get().getLastCompletedAt() == null) return true;
        return state.get().getLastCompletedAt().isBefore(
                Instant.now().minus(intervalMinutes, ChronoUnit.MINUTES));
    }

    /**
     * Returns estimated counts of memories that would be affected by consolidation/decay.
     * Uses ES count API (no data mutation).
     */
    public Map<String, Object> dryRunStats() {
        var p = promotion();
        var d = decayCfg();
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        try {
            stats.put("workingToEpisodicCandidates",
                    countTierOlderThan("WORKING", Instant.now().minus(p.getWorkingToEpisodicDays(), ChronoUnit.DAYS)));
            stats.put("episodicToSemanticCandidates",
                    countTierOlderThan("EPISODIC", Instant.now().minus(p.getEpisodicToSemanticDays(), ChronoUnit.DAYS)));
            stats.put("staleEpisodicCandidates",
                    countTierOlderThan("EPISODIC", Instant.now().minus(d.getStaleEpisodicDays(), ChronoUnit.DAYS)));
            stats.put("staleSemanticCandidates",
                    countTierOlderThan("SEMANTIC", Instant.now().minus(d.getStaleSemanticDays(), ChronoUnit.DAYS)));
        } catch (Exception e) {
            log.warn("dryRunStats query failed: {}", e.getMessage());
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    private long countTierOlderThan(String tier, Instant cutoff) throws java.io.IOException {
        var resp = esClient.count(c -> c
                .index(OBS_INDEX)
                .query(q -> q.bool(b -> b
                        .must(m -> m.term(t -> t.field("tier").value(tier)))
                        .must(m -> m.range(r -> r.date(d -> d
                                .field("timestamp")
                                .lt(cutoff.toString()))))
                )));
        return resp.count();
    }

    // --- ES query helpers ---

    /** One page of hits for a tier, sorted by _id for stable search_after cursor. */
    private List<Hit<Map>> searchByTierPage(String tier, int size, List<FieldValue> searchAfter)
            throws java.io.IOException {
        SearchResponse<Map> response = esClient.search(s -> {
            var q = s.index(OBS_INDEX)
                .size(size)
                .sort(sort -> sort.field(f -> f.field("_id").order(SortOrder.Asc)))
                .query(query -> query.term(t -> t.field("tier").value(tier)));
            if (searchAfter != null && !searchAfter.isEmpty()) {
                q = q.searchAfter(searchAfter);
            }
            return q;
        }, Map.class);
        return (List<Hit<Map>>) (List<?>) response.hits().hits();
    }

    /** One page of all observations, sorted by _id for stable search_after cursor. */
    private List<Hit<Map>> getAllMemoriesPage(int size, List<FieldValue> searchAfter)
            throws java.io.IOException {
        SearchResponse<Map> response = esClient.search(s -> {
            var q = s.index(OBS_INDEX)
                .size(size)
                .sort(sort -> sort.field(f -> f.field("_id").order(SortOrder.Asc)));
            if (searchAfter != null && !searchAfter.isEmpty()) {
                q = q.searchAfter(searchAfter);
            }
            return q;
        }, Map.class);
        return (List<Hit<Map>>) (List<?>) response.hits().hits();
    }

    private MemoryProperties.Consolidation.Promotion promotion() {
        if (memProps != null) return memProps.getConsolidation().getPromotion();
        return new MemoryProperties.Consolidation.Promotion();
    }

    private MemoryProperties.Decay decayCfg() {
        if (memProps != null) return memProps.getDecay();
        return new MemoryProperties.Decay();
    }
}
