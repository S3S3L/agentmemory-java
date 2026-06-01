package com.agentmemory.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.agentmemory.config.MemoryProperties;
import com.agentmemory.model.LifecycleJobState;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

@SuppressWarnings({"unchecked", "rawtypes"})
class LifecycleSchedulingTest {

    private ElasticsearchClient esClient;
    private LifecycleCoordinator coordinator;

    @BeforeEach
    void setUp() {
        esClient = mock(ElasticsearchClient.class);
        coordinator = mock(LifecycleCoordinator.class);
    }

    @Test
    void isJobDue_returnsTrue_when_noPriorRun() {
        when(coordinator.getJobState("memory-consolidation")).thenReturn(Optional.empty());

        MemoryConsolidationService service = new MemoryConsolidationService(esClient, coordinator);
        assertTrue(service.isJobDue("memory-consolidation", 60),
                "Expected isJobDue=true when no prior run exists");
    }

    @Test
    void isJobDue_returnsFalse_when_recentRun() {
        LifecycleJobState state = new LifecycleJobState();
        state.setLastCompletedAt(Instant.now().minusSeconds(10 * 60)); // 10 minutes ago
        when(coordinator.getJobState("memory-consolidation")).thenReturn(Optional.of(state));

        MemoryConsolidationService service = new MemoryConsolidationService(esClient, coordinator);
        assertFalse(service.isJobDue("memory-consolidation", 60),
                "Expected isJobDue=false when last run was only 10 minutes ago (interval=60min)");
    }

    @Test
    void isJobDue_returnsTrue_when_overdue() {
        LifecycleJobState state = new LifecycleJobState();
        state.setLastCompletedAt(Instant.now().minusSeconds(120 * 60)); // 120 minutes ago
        when(coordinator.getJobState("memory-consolidation")).thenReturn(Optional.of(state));

        MemoryConsolidationService service = new MemoryConsolidationService(esClient, coordinator);
        assertTrue(service.isJobDue("memory-consolidation", 60),
                "Expected isJobDue=true when last run was 120 minutes ago (interval=60min)");
    }

    @Test
    void consolidation_disabled_config_prevents_run() {
        MemoryProperties memProps = new MemoryProperties();
        memProps.getConsolidation().setEnabled(false);

        MemoryConsolidationService service = new MemoryConsolidationService(esClient, coordinator, memProps);
        service.consolidate();

        // acquireLease must never be called when consolidation is disabled
        verify(coordinator, never()).acquireLease(any(), any());
    }
}
