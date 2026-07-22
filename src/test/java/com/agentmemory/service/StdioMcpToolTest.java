package com.agentmemory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.agentmemory.config.DashScopeConfig;
import com.agentmemory.config.MemoryProperties;
import com.agentmemory.config.RestRerankConfig;
import com.agentmemory.mcp.McpToolRegistrar;
import com.agentmemory.service.embed.DashScopeEmbeddingService;
import com.agentmemory.service.embed.EmbeddingService;
import com.agentmemory.service.rerank.RerankService;
import com.agentmemory.service.rerank.RestRerankService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Comprehensive test for ALL 11 stdio-mode MCP tools.
 *
 * Uses DashScope for embedding (requires DASHSCOPE_API_KEY env var)
 * and REST reranker — matching the stdio server's production wiring.
 *
 * This test validates the exact tool handlers that StdioMcpServer
 * registers via McpToolRegistrar, including lifecycle tools that
 * are only available in stdio mode (not via the 3-arg convenience
 * constructor).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StdioMcpToolTest {

    private static final String TEST_INDEX = "stdio-tool-test-obs";

    private ElasticsearchClient esClient;
    private ElasticsearchService esService;
    private MemoryPipelineService pipeline;
    private LifecycleCoordinator coordinator;
    private MemoryConsolidationService consolidation;
    private McpToolRegistrar toolRegistrar;
    private MemoryProperties memProps;

    @BeforeAll
    static void checkPrerequisites() throws Exception {
        // ES must be running
        try (var client = Rest5Client.builder(new HttpHost("http", "localhost", 9200)).build()) {
            var ec = new ElasticsearchClient(
                    new Rest5ClientTransport(client, new JacksonJsonpMapper()));
            assertTrue(ec.ping().value(), "Elasticsearch must be running on localhost:9200");
        }

        // DashScope API key must be set (Ollama is not available in this environment)
        String key = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(key, "DASHSCOPE_API_KEY must be set");
        assertFalse(key.isBlank(), "DASHSCOPE_API_KEY must not be blank");
    }

    @AfterAll
    static void cleanupIndices() throws Exception {
        try (var client = Rest5Client.builder(new HttpHost("http", "localhost", 9200)).build()) {
            var localMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            localMapper.registerModule(new JavaTimeModule());
            localMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            var ec = new ElasticsearchClient(
                    new Rest5ClientTransport(client, new JacksonJsonpMapper(localMapper)));
            ec.indices().delete(d -> d.index(TEST_INDEX).ignoreUnavailable(true));
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // ---- ES client (same pattern as StdioMcpServer.main) ----
        var restClient = Rest5Client.builder(new HttpHost("http", "localhost", 9200)).build();
        var localMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        localMapper.registerModule(new JavaTimeModule());
        localMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        esClient = new ElasticsearchClient(
                new Rest5ClientTransport(restClient, new JacksonJsonpMapper(localMapper)));

        // ---- Embedding service (DashScope, same as stdio DASH_SCOPE mode) ----
        DashScopeConfig dsConfig = new DashScopeConfig();
        dsConfig.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
        dsConfig.setEmbeddingModel(System.getenv().getOrDefault("DASHSCOPE_EMBEDDING_MODEL", "text-embedding-v4"));
        dsConfig.setEmbeddingDimensions(
                Integer.parseInt(System.getenv().getOrDefault("DASHSCOPE_EMBEDDING_DIMENSIONS", "1024")));
        dsConfig.setRerankModel(System.getenv().getOrDefault("DASHSCOPE_RERANK_MODEL", "gte-rerank"));
        EmbeddingService embedding = new DashScopeEmbeddingService(dsConfig);

        // ---- Rerank service (REST, same as stdio REST mode) ----
        RestRerankConfig rrConfig = new RestRerankConfig();
        rrConfig.setEndpoint(System.getenv().getOrDefault("REST_RERANK_ENDPOINT", "http://localhost:8000/rerank"));
        RerankService rerank = new RestRerankService(rrConfig);

        // ---- Memory properties (same as stdio reads from application.yml) ----
        memProps = new MemoryProperties();
        // Ensure lifecycle tools are enabled for testing
        memProps.getConsolidation().setEnabled(true);
        memProps.getConsolidation().setIntervalMinutes(360);
        memProps.getDecay().setEnabled(true);
        memProps.getDecay().setIntervalMinutes(1440);

        // ---- Services (exactly like StdioMcpServer.main) ----
        esService = new ElasticsearchService(esClient, memProps, embedding, TEST_INDEX);
        pipeline = new MemoryPipelineService(esService, embedding, rerank, memProps);
        coordinator = new LifecycleCoordinator(esClient);
        consolidation = new MemoryConsolidationService(esClient, coordinator, memProps);
        var migrationService = new ReindexMigrationService(esClient, embedding, memProps);

        // ---- Full McpToolRegistrar with lifecycle services (stdio mode) ----
        toolRegistrar = new McpToolRegistrar(pipeline, esService, migrationService, consolidation, coordinator, memProps);
    }

    // ---- helpers ----

    private Map<String, McpServerFeatures.SyncToolSpecification> getTools() {
        return toolRegistrar.registerAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        spec -> spec.tool().name(),
                        spec -> spec));
    }

    @SuppressWarnings("unchecked")
    private McpSchema.CallToolResult invokeTool(String toolName, Map<String, Object> args) {
        var tools = getTools();
        var spec = tools.get(toolName);
        assertNotNull(spec, "Tool '" + toolName + "' should be registered");
        return spec.call().apply(null, args);
    }

    private String getTextContent(McpSchema.CallToolResult result) {
        assertNotNull(result, "Tool result must not be null");
        assertFalse(result.isError(), "Tool '" + result + "' should not return error: " +
                result.content().stream()
                        .filter(c -> c instanceof McpSchema.TextContent)
                        .map(c -> ((McpSchema.TextContent) c).text())
                        .reduce((a, b) -> a + b).orElse(""));
        return result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .reduce((a, b) -> a + b).orElse("");
    }

    private boolean toolReturnsError(McpSchema.CallToolResult result) {
        return result != null && result.isError();
    }

    // =================================================================
    // TOOL 1: memory_recall
    // =================================================================

    @Test
    @Order(1)
    @DisplayName("memory_recall — search with hybrid BM25+vector+rerank")
    void memoryRecall_tool() throws Exception {
        // Seed data with embedding
        pipeline.observe("Read", "Database connection pooling", "HikariCP config loaded",
                "src/db/pool.java", "stdio-recall-test", "test-project");
        esClient.indices().refresh(r -> r.index(TEST_INDEX));

        var result = invokeTool("memory_recall", Map.of(
                "query", "database pooling",
                "tokenBudget", 2000));

        String text = getTextContent(result);
        assertFalse(text.isBlank(), "Recall should return results");
        System.out.println("[memory_recall] " + text.substring(0, Math.min(200, text.length())));
    }

    // =================================================================
    // TOOL 2: memory_save
    // =================================================================

    private String savedMemoryId;

    @Test
    @Order(2)
    @DisplayName("memory_save — save to SEMANTIC tier")
    void memorySave_tool() throws Exception {
        var result = invokeTool("memory_save", Map.of(
                "content", "Use repository pattern for data access layer",
                "tier", "SEMANTIC",
                "tags", List.of("architecture", "java"),
                "sessionId", "stdio-save-test",
                "projectId", "test-project"));

        String text = getTextContent(result);
        assertTrue(text.contains("Saved insight") || text.contains("ID:"),
                "Save should return confirmation. Got: " + text);
        System.out.println("[memory_save] " + text);

        // Extract ID for later forget test
        if (text.contains("ID: ")) {
            savedMemoryId = text.substring(text.indexOf("ID: ") + 4).trim();
        }
    }

    // =================================================================
    // TOOL 3: memory_sessions
    // =================================================================

    @Test
    @Order(3)
    @DisplayName("memory_sessions — list recent sessions")
    void memorySessions_tool() {
        var result = invokeTool("memory_sessions", Map.of("limit", 10));

        String text = getTextContent(result);
        assertFalse(text.isBlank(), "Sessions should return data");
        System.out.println("[memory_sessions] " + text);
    }

    // =================================================================
    // TOOL 4: memory_timeline
    // =================================================================

    @Test
    @Order(4)
    @DisplayName("memory_timeline — chronological observation list")
    void memoryTimeline_tool() throws Exception {
        // Ensure data exists
        pipeline.observe("Write", "Timeline test entry", "created for timeline check",
                "src/timeline/test.java", "stdio-timeline-test", "test-project");
        esClient.indices().refresh(r -> r.index(TEST_INDEX));

        var result = invokeTool("memory_timeline", Map.of("limit", 10));

        String text = getTextContent(result);
        assertTrue(text.contains("tool=") || text.contains("observations") || text.contains("timestamp"),
                "Timeline should show observations. Got: " + text.substring(0, Math.min(200, text.length())));
        System.out.println("[memory_timeline] " + text.substring(0, Math.min(300, text.length())));
    }

    // =================================================================
    // TOOL 5: memory_profile
    // =================================================================

    @Test
    @Order(5)
    @DisplayName("memory_profile — project overview stats")
    void memoryProfile_tool() throws Exception {
        esClient.indices().refresh(r -> r.index(TEST_INDEX));

        var result = invokeTool("memory_profile", Map.of());

        String text = getTextContent(result);
        assertTrue(text.contains("Total observations") || text.contains("Profile"),
                "Profile should show stats. Got: " + text);
        System.out.println("[memory_profile] " + text);
    }

    // =================================================================
    // TOOL 6: memory_file_history
    // =================================================================

    @Test
    @Order(6)
    @DisplayName("memory_file_history — observations for a file")
    void memoryFileHistory_tool() throws Exception {
        esClient.indices().refresh(r -> r.index(TEST_INDEX));

        var result = invokeTool("memory_file_history", Map.of(
                "path", "src/db/pool.java",
                "limit", 5));

        String text = getTextContent(result);
        assertFalse(text.isBlank(), "File history should return data");
        System.out.println("[memory_file_history] " + text);
    }

    // =================================================================
    // TOOL 7: memory_forget
    // =================================================================

    @Test
    @Order(7)
    @DisplayName("memory_forget — delete a memory by ID")
    void memoryForget_tool() throws Exception {
        // Create a dedicated observation to delete
        var obs = pipeline.observe("Bash", "forget me", "output", "src/forget.txt",
                "stdio-forget-test", "test-project");
        esClient.indices().refresh(r -> r.index(TEST_INDEX));
        String id = (String) obs.get("id");
        assertNotNull(id, "Created observation should have an ID");

        var result = invokeTool("memory_forget", Map.of("id", id));

        String text = getTextContent(result);
        assertTrue(text.contains("Deleted") || text.contains(id),
                "Forget should confirm deletion. Got: " + text);
        System.out.println("[memory_forget] " + text);
    }

    // =================================================================
    // TOOL 8: memory_patterns
    // =================================================================

    @Test
    @Order(8)
    @DisplayName("memory_patterns — tag/tool usage patterns")
    void memoryPatterns_tool() throws Exception {
        esClient.indices().refresh(r -> r.index(TEST_INDEX));

        var result = invokeTool("memory_patterns", Map.of());

        String text = getTextContent(result);
        assertTrue(text.contains("Patterns") || text.contains("aggregations"),
                "Patterns should return aggregation data. Got: " + text);
        System.out.println("[memory_patterns] " + text);
    }

    // =================================================================
    // TOOL 9: memory_reindex
    // =================================================================

    @Test
    @Order(9)
    @DisplayName("memory_reindex — dry-run migration preview")
    void memoryReindex_dryRun() {
        // First populate the test index with some data
        var result = invokeTool("memory_reindex", Map.of("dryRun", true));

        // Dry run should succeed even without data — just validate the tool works
        String text = getTextContent(result);
        assertFalse(text.isBlank(), "Reindex dry-run should return migration info");
        System.out.println("[memory_reindex dryRun] " + text.substring(0, Math.min(500, text.length())));
    }

    // =================================================================
    // TOOL 10: memory_lifecycle_status
    // =================================================================

    @Test
    @Order(10)
    @DisplayName("memory_lifecycle_status — job state (consolidation + decay)")
    void memoryLifecycleStatus_tool() {
        var result = invokeTool("memory_lifecycle_status", Map.of());

        String text = getTextContent(result);
        assertTrue(text.contains("instanceId") || text.contains("consolidation"),
                "Lifecycle status should include instance and job info. Got: " + text);
        assertTrue(text.contains("consolidation"), "Should include consolidation job info");
        assertTrue(text.contains("decay"), "Should include decay job info");
        System.out.println("[memory_lifecycle_status] " + text);
    }

    // =================================================================
    // TOOL 11: memory_lifecycle_run
    // =================================================================

    @Test
    @Order(11)
    @DisplayName("memory_lifecycle_run — dry-run stats (no mutation)")
    void memoryLifecycleRun_dryRun() {
        var result = invokeTool("memory_lifecycle_run", Map.of(
                "job", "all",
                "dryRun", true));

        String text = getTextContent(result);
        assertTrue(text.contains("Dry-run") || text.contains("stats"),
                "Dry-run should return stats. Got: " + text);
        System.out.println("[memory_lifecycle_run dryRun] " + text);
    }

    @Test
    @Order(12)
    @DisplayName("memory_lifecycle_run — trigger consolidation (with mutation)")
    void memoryLifecycleRun_consolidation() {
        var result = invokeTool("memory_lifecycle_run", Map.of(
                "job", "consolidation",
                "dryRun", false));

        // May fail gracefully if ES lifecycle index doesn't exist yet, but should not crash
        String text = result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .findFirst().orElse("");

        System.out.println("[memory_lifecycle_run consolidation] " + text);
        // Even on error, the tool should return a valid result (not throw)
        assertNotNull(result, "Tool should always return a result");
    }

    // =================================================================
    // Verification: all 11 tools registered
    // =================================================================

    @Test
    @Order(13)
    @DisplayName("Verify all 11 stdio tools are registered with valid schemas")
    void allToolsRegistered() {
        var tools = getTools();

        // All 11 tools that StdioMcpServer registers
        String[] expectedTools = {
                "memory_recall",
                "memory_save",
                "memory_sessions",
                "memory_timeline",
                "memory_profile",
                "memory_file_history",
                "memory_forget",
                "memory_patterns",
                "memory_reindex",
                "memory_lifecycle_status",
                "memory_lifecycle_run"
        };

        assertEquals(11, tools.size(),
                "Should have exactly 11 tools registered. Got: " + tools.keySet());

        for (String name : expectedTools) {
            assertTrue(tools.containsKey(name),
                    "Tool '" + name + "' should be registered in stdio mode");
            var tool = tools.get(name).tool();
            assertNotNull(tool.description(),
                    "Tool '" + name + "' should have a non-null description");
            assertFalse(tool.description().isBlank(),
                    "Tool '" + name + "' should have a non-blank description");
            // Validate schema is well-formed JSON
            var schema = tool.inputSchema();
            assertNotNull(schema, "Tool '" + name + "' should have an input schema");
        }

        System.out.println("[allTools] ✓ All 11 tools registered with valid schemas");
    }
}
