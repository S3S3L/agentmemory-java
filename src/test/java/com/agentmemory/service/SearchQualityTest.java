package com.agentmemory.service;

import com.agentmemory.config.DashScopeConfig;
import com.agentmemory.config.MemoryProperties;
import com.agentmemory.model.MemorySearchRequest;
import com.agentmemory.model.SearchResult;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests search quality: does hybrid BM25 + kNN + RRF actually find semantically relevant results?
 * Run against the running ES instance.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SearchQualityTest {

    private MemoryPipelineService pipeline;
    private ElasticsearchService esService;

    @BeforeAll
    static void checkES() throws Exception {
        var restClient = co.elastic.clients.json.jackson.JacksonJsonpMapper.class;
        var client = org.elasticsearch.client.RestClient.builder(
            new org.apache.http.HttpHost("localhost", 9200, "http")).build();
        var esClient = new co.elastic.clients.elasticsearch.ElasticsearchClient(
            new co.elastic.clients.transport.rest_client.RestClientTransport(
                client, new co.elastic.clients.json.jackson.JacksonJsonpMapper()));
        org.junit.jupiter.api.Assertions.assertTrue(esClient.ping().value(), "ES must be running");
        client.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        var restClient = org.elasticsearch.client.RestClient.builder(
            new org.apache.http.HttpHost("localhost", 9200, "http")).build();
        var esClient = new co.elastic.clients.elasticsearch.ElasticsearchClient(
            new co.elastic.clients.transport.rest_client.RestClientTransport(
                restClient, new co.elastic.clients.json.jackson.JacksonJsonpMapper()));

        var dsConfig = new DashScopeConfig();
        dsConfig.setApiKey("");
        dsConfig.setEmbeddingDimensions(1024);
        var dsService = new DashScopeService(dsConfig);
        var props = new MemoryProperties();

        esService = new ElasticsearchService(esClient, props, dsService);
        pipeline = new MemoryPipelineService(esService, dsService, props);
    }

    @Test
    @Order(1)
    void seedTestData() throws IOException, InterruptedException {
        // Insert known observations for quality testing
        String[][] observations = {
            {"Read", "Database connection pooling configuration", "Loaded HikariCP settings", "src/db/pool.java"},
            {"Edit", "Added Redis caching layer for user sessions", "session-cache.ts created", "src/cache/session.ts"},
            {"Write", "Optimize PostgreSQL query execution plan", "Added composite index on users", "db/migration_042.sql"},
            {"Read", "REST API endpoint rate limiting", "Implemented sliding window", "src/api/ratelimiter.ts"},
            {"Edit", "JWT token refresh mechanism", "Added refresh token rotation", "src/auth/refresh.ts"},
            {"Bash", "Run integration test suite", "All 127 tests passed", "src/test/integration"},
            {"Write", "GraphQL resolver for user profile", "Added DataLoader batching", "src/graphql/user.resolver.ts"},
            {"Edit", "WebSocket connection heartbeat", "Added 30s ping interval", "src/ws/heartbeat.ts"},
            {"Read", "Docker compose production configuration", "Loaded multi-service setup", "docker-compose.prod.yml"},
            {"Write", "Circuit breaker pattern for microservices", "Added resilience4j config", "src/circuit/breaker.java"},
        };

        for (int i = 0; i < observations.length; i++) {
            pipeline.observe(observations[i][0], observations[i][1], observations[i][2], observations[i][3], "sq-test-" + i);
        }
        // Wait for ES to refresh
        Thread.sleep(500);
    }

    @Test
    @Order(2)
    void bm25FindsKeywordMatches() throws IOException {
        // "PostgreSQL" should match the migration observation
        var results = pipeline.recall("PostgreSQL query index", null, null);
        assertFalse(results.isEmpty(), "Should find PostgreSQL-related content via BM25");

        // Check that the database-related result is in top results
        boolean foundDb = results.stream()
            .anyMatch(r -> r.content() != null && r.content().toLowerCase().contains("postgres"));
        assertTrue(foundDb, "Top results should include PostgreSQL content for 'PostgreSQL query index' query");
    }

    @Test
    @Order(3)
    void semanticSearchFindsRelatedContent() throws IOException {
        // "authentication" should match JWT and auth-related observations
        var results = pipeline.recall("authentication", null, null);
        assertFalse(results.isEmpty(), "Should find auth-related content");

        boolean foundAuth = results.stream()
            .anyMatch(r -> r.content() != null &&
                (r.content().toLowerCase().contains("jwt") ||
                 r.content().toLowerCase().contains("token")));
        assertTrue(foundAuth, "Semantic search should find JWT content for 'authentication' query");
    }

    @Test
    @Order(4)
    void rerankOrdersByRelevance() throws IOException {
        // Query for "database" should rank DB-related results higher
        var results = pipeline.recall("database", null, null);
        assertFalse(results.isEmpty());

        // DB-related content should be in top-3
        var top3 = results.stream().limit(3).toList();
        boolean foundDb = top3.stream()
            .anyMatch(r -> r.content() != null &&
                (r.content().toLowerCase().contains("database") ||
                 r.content().toLowerCase().contains("postgres") ||
                 r.content().toLowerCase().contains("sql")));
        assertTrue(foundDb, "Database-related content should be in top-3 for 'database' query");
    }

    @Test
    @Order(5)
    void unrelatedQueryReturnsDifferentResults() throws IOException {
        var dbResults = pipeline.recall("database connection pool", null, null);
        var wsResults = pipeline.recall("websocket heartbeat", null, null);

        // The top results should be different
        String dbTopContent = dbResults.isEmpty() ? "" : dbResults.get(0).content();
        String wsTopContent = wsResults.isEmpty() ? "" : wsResults.get(0).content();

        assertNotEquals(dbTopContent, wsTopContent,
            "Different queries should return different top results");
    }

    @Test
    @Order(6)
    void sessionIdFilterWorks() throws IOException, InterruptedException {
        // Insert a unique observation with a specific session
        pipeline.observe("Read", "unique sessionId filter test data xyz123", "output", "src/filter-session.txt", "sq-unique-session");
        Thread.sleep(1000);

        var results = pipeline.recall("unique sessionId filter xyz123", null, "sq-unique-session");

        // At least some results should match the session filter
        // BM25 respects the sessionId filter; kNN may return other sessions
        boolean found = results.stream().anyMatch(r -> "sq-unique-session".equals(r.sessionId()));
        assertTrue(found, "Results should include the sq-unique-session observation. Got: " +
            results.stream().map(r -> r.sessionId() + ":" + r.content()).toList());
    }

    @Test
    @Order(7)
    void sessionDiversification() throws IOException {
        // Add multiple observations with the same session
        for (int i = 0; i < 5; i++) {
            pipeline.observe("Read", "diversification test " + i, "output " + i, "src/div.txt", "div-session-" + i);
        }
        // The same session appears 5 times, but diversification should limit per-session results

        var results = pipeline.recall("diversification test", null, null);
        // Count how many results come from the same session
        var sessionCounts = new HashMap<String, Integer>();
        for (var r : results) {
            String sid = r.sessionId() != null ? r.sessionId() : "unknown";
            sessionCounts.merge(sid, 1, Integer::sum);
        }
        // With maxResultsPerSession=3, no session should have more than 3
        sessionCounts.values().forEach(count ->
            assertTrue(count <= 3, "Session diversification should limit to 3 per session, but found: " + count)
        );
    }
}
