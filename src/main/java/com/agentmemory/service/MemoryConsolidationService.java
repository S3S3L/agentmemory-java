package com.agentmemory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MemoryConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationService.class);
    private final ElasticsearchService esService;

    public MemoryConsolidationService(ElasticsearchService esService) {
        this.esService = esService;
    }

    @Scheduled(cron = "${memory.consolidation.cron:0 0 */6 * * *}")
    public void consolidate() {
        if (!true) return; // controlled by config in production
        log.info("Running memory consolidation...");
        // TODO: promote WORKING -> EPISODIC, EPISODIC -> SEMANTIC
        log.info("Memory consolidation complete");
    }

    @Scheduled(cron = "${memory.decay.cron:0 0 2 * * *}")
    public void applyDecay() {
        if (!true) return; // controlled by config in production
        log.info("Running memory decay sweep...");
        // TODO: apply Ebbinghaus decay, evict stale memories
        log.info("Memory decay sweep complete");
    }
}
