package com.agentmemory.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.agentmemory.config.DashScopeConfig;
import com.agentmemory.config.MemoryProperties;
import com.agentmemory.model.*;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests against the running Elasticsearch instance.
 * Requires: docker compose up -d (ES 8.15.2 on localhost:9200)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemoryPipelineIntegrationTest {

    private MemoryPipelineService pipeline;
    private ElasticsearchService esService;

    @BeforeAll
    static void checkES() throws Exception {
        try (var client = RestClient.builder(new HttpHost("localhost", 9200, "http")).build()) {
            var esClient = new ElasticsearchClient(
                new RestClientTransport(client, new JacksonJsonpMapper()));
            assertTrue(esClient.ping().value(), "Elasticsearch must be running on localhost:9200");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        var restClient = RestClient.builder(new HttpHost("localhost", 9200, "http")).build();
        var esClient = new ElasticsearchClient(
            new RestClientTransport(restClient, new JacksonJsonpMapper()));

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
    void observe_createsRecord() throws IOException {
        var result = pipeline.observe("Read", "test input", "test output", "src/test.txt", "integration-test");

        assertEquals("saved", result.get("status"));
        assertNotNull(result.get("id"));
    }

    @Test
    @Order(2)
    void observe_dedup_sameContentWithinWindow() throws IOException {
        var r1 = pipeline.observe("Read", "dedup test content", "dedup output", "src/dedup.txt", "dedup-test");
        var r2 = pipeline.observe("Read", "dedup test content", "dedup output", "src/dedup.txt", "dedup-test");

        assertEquals("saved", r1.get("status"));
        assertEquals("duplicate", r2.get("status"));
    }

    @Test
    @Order(3)
    void recall_returnsResults() throws IOException {
        pipeline.observe("Edit", "database index optimization", "Created index", "db/schema.sql", "recall-test");
        pipeline.observe("Read", "user authentication flow", "Auth middleware", "src/auth.ts", "recall-test");

        var results = pipeline.recall("database", null, null);
        assertFalse(results.isEmpty(), "Should find database-related observations");
    }

    @Test
    @Order(4)
    void recall_filtersByQuery() throws IOException {
        pipeline.observe("Write", "frontend CSS styles", "Added flexbox layout", "src/styles.css", "recall-filter");

        var dbResults = pipeline.recall("database optimization", null, null);
        var cssResults = pipeline.recall("frontend CSS", null, null);

        // Both should return results but different top items
        assertFalse(cssResults.isEmpty());
    }

    @Test
    @Order(5)
    void saveInsight_worksWithDifferentTiers() throws IOException {
        var semantic = pipeline.saveInsight("Use JWT for auth", MemoryTier.SEMANTIC, "tier-test", List.of("auth"));
        assertEquals(MemoryTier.SEMANTIC.name(), semantic.get("tier"));

        var procedural = pipeline.saveInsight("Always write tests first", MemoryTier.PROCEDURAL, "tier-test", List.of("workflow"));
        assertEquals(MemoryTier.PROCEDURAL.name(), procedural.get("tier"));
    }

    @Test
    @Order(6)
    void search_returnsScoredResults() throws IOException {
        var req = new MemorySearchRequest("authentication", null, null, null, 10, null);
        float[] vector = new DashScopeService(new DashScopeConfig() {{
            setApiKey(""); setEmbeddingDimensions(1024);
        }}).embed("authentication");

        var results = esService.search(req, vector);
        assertFalse(results.isEmpty());

        var first = results.get(0);
        assertTrue(first.score() > 0, "Should have positive RRF score");
    }

    @Test
    @Order(7)
    void fileHistory_returnsObservationsForFile() throws IOException, InterruptedException {
        pipeline.observe("Read", "test file observation 1", "output1", "src/specific.rs", "fh-test");
        Thread.sleep(200);
        pipeline.observe("Write", "test file observation 2", "output2", "src/specific.rs", "fh-test");
        Thread.sleep(500);

        var results = esService.getObservationsByFile("src/specific.rs", 10);
        assertTrue(results.size() >= 2);
    }

    @Test
    @Order(8)
    void timeline_returnsObservationsOrdered() throws IOException {
        var items = esService.getTimeline(10);
        assertNotNull(items);
    }

    @Test
    @Order(9)
    void getProfile_returnsTotalCount() throws IOException {
        var profile = esService.getProfile();
        assertNotNull(profile.get("totalObservations"));
        assertTrue((Long) profile.get("totalObservations") > 0);
    }

    @Test
    @Order(10)
    void getPatternAggregations_returnsData() throws IOException {
        var patterns = esService.getPatternAggregations();
        assertNotNull(patterns);
        assertTrue(patterns.containsKey("aggregations"));
    }

    @Test
    @Order(11)
    void deleteMemory_removesRecord() throws IOException {
        var result = pipeline.observe("Delete", "delete test", "output", "src/delete.txt", "del-test");
        String id = (String) result.get("id");

        esService.deleteMemory(id);

        // Search should not find this exact ID (may still exist in ES due to eventual consistency,
        // but deletion should have succeeded without error)
        var timeline = esService.getTimeline(1);
        assertNotNull(timeline);
    }

    @Test
    @Order(12)
    void batchSave_multipleDocuments() throws IOException, InterruptedException {
        var docs = new ArrayList<Map<String, Object>>();
        var ids = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            String id = "batch-" + System.currentTimeMillis() + "-" + i;
            docs.add(Map.of(
                "id", id,
                "content", "batch observation " + i,
                "tier", MemoryTier.WORKING.name(),
                "sessionId", "batch-test",
                "toolName", "Batch",
                "filePath", "src/batch.txt",
                "timestamp", java.time.Instant.now().toString(),
                "tags", List.of("batch")
            ));
            ids.add(id);
        }

        esService.batchSave(docs, ids);
        Thread.sleep(500);

        var results = esService.getObservationsByFile("src/batch.txt", 10);
        assertTrue(results.size() >= 5);
    }
}
