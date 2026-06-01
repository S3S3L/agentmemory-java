package com.agentmemory.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.agentmemory.model.LifecycleJobState;
import com.agentmemory.mcp.McpToolRegistrar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Unit tests for lifecycle observability: memory_lifecycle_status tool and dryRunStats().
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class LifecycleObservabilityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LifecycleCoordinator coordinator;
    private MemoryConsolidationService consolidationService;
    private ElasticsearchClient esClient;

    @BeforeEach
    void setUp() {
        esClient = mock(ElasticsearchClient.class);
        coordinator = mock(LifecycleCoordinator.class);
        consolidationService = mock(MemoryConsolidationService.class);
    }

    @Test
    void statusTool_returnsCompleteJson() throws Exception {
        // Arrange
        when(coordinator.getInstanceId()).thenReturn("test-instance-id");

        LifecycleJobState consolidationState = new LifecycleJobState();
        consolidationState.setJobName("memory-consolidation");
        consolidationState.setLeaseOwner("test-instance-id");
        consolidationState.setLastCompletedAt(Instant.parse("2024-01-01T06:00:00Z"));
        when(coordinator.getJobState("memory-consolidation")).thenReturn(Optional.of(consolidationState));
        when(coordinator.getJobState("memory-decay")).thenReturn(Optional.empty());

        when(consolidationService.isJobDue(anyString(), anyLong()))
                .thenReturn(true)   // consolidation isDue
                .thenReturn(false);  // decay isDue

        var pipeline = mock(com.agentmemory.service.MemoryPipelineService.class);
        var esService = mock(com.agentmemory.service.ElasticsearchService.class);
        var migrationService = mock(com.agentmemory.service.ReindexMigrationService.class);

        McpToolRegistrar registrar = new McpToolRegistrar(
                pipeline, esService, migrationService, consolidationService, coordinator, null);

        // Find and invoke the memory_lifecycle_status handler
        var spec = registrar.registerAll().stream()
                .filter(s -> "memory_lifecycle_status".equals(s.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("memory_lifecycle_status tool not registered"));

        McpSchema.CallToolResult result = spec.call().apply(null, Map.of());
        assertNotNull(result);
        assertFalse(result.isError(), "Result should not be an error");

        String json = ((McpSchema.TextContent) result.content().get(0)).text();
        assertNotNull(json, "Result text must not be null");

        JsonNode root = JSON.readTree(json);

        // Verify top-level instanceId
        assertTrue(root.has("instanceId"), "JSON must contain instanceId");
        assertTrue("test-instance-id".equals(root.get("instanceId").asText()),
                "instanceId must match coordinator value");

        // Verify consolidation section
        JsonNode cons = root.get("consolidation");
        assertNotNull(cons, "JSON must contain 'consolidation' section");
        assertTrue(cons.has("isDue"), "consolidation must have isDue");
        assertTrue(cons.get("isDue").asBoolean(), "consolidation.isDue should be true");
        assertTrue(cons.has("enabled"), "consolidation must have enabled");
        assertTrue(cons.has("intervalMinutes"), "consolidation must have intervalMinutes");
        assertTrue(cons.has("jobName"), "consolidation must have jobName");

        // Verify decay section
        JsonNode decay = root.get("decay");
        assertNotNull(decay, "JSON must contain 'decay' section");
        assertTrue(decay.has("enabled"), "decay must have enabled");
        assertTrue(decay.has("isDue"), "decay must have isDue");
        assertFalse(decay.get("isDue").asBoolean(), "decay.isDue should be false");
    }

    @Test
    void dryRunStats_returnsNonNullResult() {
        // Arrange: use real service with mock ES (will throw, caught internally → error key)
        MemoryConsolidationService service = new MemoryConsolidationService(esClient, coordinator);

        // Act
        Map<String, Object> stats = service.dryRunStats();

        // Assert: result must be non-null and non-empty (either counts or error entry)
        assertNotNull(stats, "dryRunStats() must return non-null");
        assertFalse(stats.isEmpty(), "dryRunStats() result must not be empty");
    }
}
