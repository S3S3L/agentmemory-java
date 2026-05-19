package com.agentmemory.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.agentmemory.config.DashScopeConfig;
import com.agentmemory.config.MemoryProperties;
import com.agentmemory.mcp.McpToolRegistrar;
import com.agentmemory.model.TokenBudget;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmark via MCP Tool handler calls.
 * Directly invokes the tool handlers registered by McpToolRegistrar,
 * verifying the full pipeline: search → rerank → token budget formatting.
 *
 * Covered MCP tools:
 *   memory_recall, memory_save, memory_timeline, memory_profile,
 *   memory_file_history, memory_forget, memory_patterns
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpBenchmarkTest {

    private static final String OBS_INDEX = "mcp-benchmark-observations";

    private ElasticsearchClient esClient;
    private ElasticsearchService esService;
    private MemoryPipelineService pipeline;
    private McpToolRegistrar toolRegistrar;

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
        var restClient = RestClient.builder(new HttpHost("localhost", 9200, "http")).build();
        var localMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        localMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        localMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        esClient = new ElasticsearchClient(
            new RestClientTransport(restClient, new JacksonJsonpMapper(localMapper)));

        var dsConfig = new DashScopeConfig();
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        dsConfig.setApiKey(apiKey != null && !apiKey.isBlank() ? apiKey : "");
        dsConfig.setEmbeddingModel("text-embedding-v4");
        dsConfig.setEmbeddingDimensions(1024);
        dsConfig.setRerankModel("gte-rerank");
        var dsService = new DashScopeService(dsConfig);
        var props = new MemoryProperties();

        esService = new ElasticsearchService(esClient, props, dsService, OBS_INDEX);
        pipeline = new MemoryPipelineService(esService, dsService, props);
        toolRegistrar = new McpToolRegistrar(pipeline, esService);
    }

    private Map<String, McpServerFeatures.SyncToolSpecification> getTools() {
        return toolRegistrar.registerAll().stream()
            .collect(java.util.stream.Collectors.toMap(
                spec -> spec.tool().name(),
                spec -> spec
            ));
    }

    @SuppressWarnings("unchecked")
    private McpSchema.CallToolResult invokeTool(String toolName, Map<String, Object> args) {
        var tools = getTools();
        var spec = tools.get(toolName);
        assertNotNull(spec, "Tool '" + toolName + "' should be registered");
        return spec.call().apply(null, args);
    }

    private String getTextContent(McpSchema.CallToolResult result) {
        assertNotNull(result);
        assertFalse(result.isError(), "Tool should not return error: " +
            result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .reduce((a, b) -> a + b).orElse(""));
        return result.content().stream()
            .filter(c -> c instanceof McpSchema.TextContent)
            .map(c -> ((McpSchema.TextContent) c).text())
            .reduce((a, b) -> a + b).orElse("");
    }

    /**
     * Seed test data for MCP tool testing.
     */
    @Test
    @Order(1)
    void seedTestData() throws Exception {
        String[][] seeds = {
            {"Read", "Database connection pooling with HikariCP", "Loaded HikariCP config", "src/db/pool.java"},
            {"Edit", "Added Redis caching for user sessions", "session-cache.ts created", "src/cache/session.ts"},
            {"Write", "Optimize PostgreSQL query execution plan", "Added composite index", "db/migration_042.sql"},
            {"Read", "REST API endpoint rate limiting", "Sliding window implementation", "src/api/ratelimiter.ts"},
            {"Edit", "JWT token refresh with rotation", "refresh token rotation added", "src/auth/refresh.ts"},
            {"Bash", "Run integration test suite", "All 42 tests passed", "src/test/integration"},
            {"Write", "GraphQL resolver with DataLoader batching", "Added DataLoader for N+1", "src/graphql/user.resolver.ts"},
            {"Edit", "WebSocket heartbeat with 30s ping", "heartbeat interval set", "src/ws/heartbeat.ts"},
        };

        for (int i = 0; i < seeds.length; i++) {
            pipeline.observe(seeds[i][0], seeds[i][1], seeds[i][2], seeds[i][3], "mcp-bench-" + i, null);
        }
        esClient.indices().refresh(r -> r.index(OBS_INDEX));
    }

    /**
     * Test memory_recall MCP tool — search and verify results.
     */
    @Test
    @Order(2)
    void memoryRecall_tool() {
        var result = invokeTool("memory_recall", Map.of(
            "query", "database",
            "tokenBudget", 2000
        ));

        String text = getTextContent(result);
        assertTrue(text.contains("Found") || text.contains("relevant"),
            "Recall should return formatted results. Got: " + text.substring(0, Math.min(100, text.length())));
    }

    /**
     * Test memory_recall with token budget truncation.
     */
    @Test
    @Order(3)
    void memoryRecall_tokenBudget() {
        var result = invokeTool("memory_recall", Map.of(
            "query", "database",
            "tokenBudget", 50
        ));

        String text = getTextContent(result);
        // With 50 token budget, output should be truncated
        assertTrue(text.length() < 300,
            "Token budget of 50 should produce short output. Got length: " + text.length());
    }

    /**
     * Test memory_save MCP tool.
     */
    @Test
    @Order(4)
    void memorySave_tool() {
        var result = invokeTool("memory_save", Map.of(
            "content", "Use bcrypt for password hashing with salt rounds >= 12",
            "tier", "SEMANTIC",
            "tags", List.of("security", "password"),
            "sessionId", "mcp-save-test"
        ));

        String text = getTextContent(result);
        assertTrue(text.contains("Saved insight") || text.contains("ID:"),
            "Save should return confirmation. Got: " + text);
    }

    /**
     * Test memory_timeline MCP tool.
     */
    @Test
    @Order(5)
    void memoryTimeline_tool() {
        var result = invokeTool("memory_timeline", Map.of("limit", 10));

        String text = getTextContent(result);
        assertFalse(text.isEmpty(), "Timeline should not be empty after seeding data");
    }

    /**
     * Test memory_profile MCP tool.
     */
    @Test
    @Order(6)
    void memoryProfile_tool() {
        var result = invokeTool("memory_profile", Map.of());

        String text = getTextContent(result);
        assertTrue(text.contains("Total observations") || text.contains("Profile"),
            "Profile should return observation count. Got: " + text);
    }

    /**
     * Test memory_file_history MCP tool.
     */
    @Test
    @Order(7)
    void memoryFileHistory_tool() {
        var result = invokeTool("memory_file_history", Map.of(
            "path", "src/db/pool.java",
            "limit", 5
        ));

        String text = getTextContent(result);
        assertTrue(text.contains("Observations for") || text.contains("No observations"),
            "File history should return results or 'no observations'. Got: " + text);
    }

    /**
     * Test memory_forget MCP tool.
     */
    @Test
    @Order(8)
    void memoryForget_tool() throws Exception {
        // Create an observation to delete
        var obs = pipeline.observe("Read", "forget test content", "output", "src/forget.txt", "mcp-forget-test", null);
        String id = (String) obs.get("id");

        var result = invokeTool("memory_forget", Map.of("id", id));

        String text = getTextContent(result);
        assertTrue(text.contains("Deleted") || text.contains(id),
            "Forget should confirm deletion. Got: " + text);
    }

    /**
     * Test memory_patterns MCP tool.
     */
    @Test
    @Order(9)
    void memoryPatterns_tool() {
        var result = invokeTool("memory_patterns", Map.of());

        String text = getTextContent(result);
        assertTrue(text.contains("Patterns") || text.contains("aggregations"),
            "Patterns should return aggregation data. Got: " + text);
    }

    /**
     * Benchmark: run multiple recall queries through MCP tool and measure quality.
     * This mirrors AgentMemoryBenchmark but through the MCP tool path with token budget.
     */
    @Test
    @Order(10)
    void mcpRecallQualityBenchmark() {
        String[] queries = {
            "database connection pooling",
            "JWT authentication token",
            "Redis caching layer",
            "rate limiting API endpoints",
            "PostgreSQL query optimization"
        };

        System.out.println("\n=== MCP Tool Recall Quality ===");
        int totalFound = 0;
        for (String query : queries) {
            var result = invokeTool("memory_recall", Map.of(
                "query", query,
                "tokenBudget", 2000
            ));

            String text = getTextContent(result);
            // Count number of "## Memory" sections (each is a result)
            long count = text.lines().filter(l -> l.startsWith("## Memory ")).count();
            totalFound += count;
            System.out.printf("  Query: '%s' -> %d results (token budget: 2000)%n", query, count);
        }
        System.out.println("  Total results: " + totalFound);
        assertTrue(totalFound > 0, "At least some queries should return results via MCP tool");
    }

    /**
     * Verify all MCP tools are registered with proper schemas.
     */
    @Test
    @Order(11)
    void allToolsRegistered() {
        var tools = getTools();
        String[] expectedTools = {
            "memory_recall", "memory_save", "memory_sessions",
            "memory_timeline", "memory_profile", "memory_file_history",
            "memory_forget", "memory_patterns"
        };

        for (String name : expectedTools) {
            assertTrue(tools.containsKey(name), "Tool '" + name + "' should be registered");
            var tool = tools.get(name).tool();
            assertNotNull(tool.description(), "Tool '" + name + "' should have a description");
        }
    }
}
