package com.agentmemory;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agentmemory.config.DashScopeConfig;
import com.agentmemory.config.MemoryProperties;
import com.agentmemory.config.OllamaConfig;
import com.agentmemory.config.RestRerankConfig;
import com.agentmemory.mcp.McpToolRegistrar;
import com.agentmemory.mcp.SingleWriterStdioTransportProvider;
import com.agentmemory.model.EmbeddingImpl;
import com.agentmemory.model.RerankImpl;
import com.agentmemory.service.ElasticsearchService;
import com.agentmemory.service.LifecycleCoordinator;
import com.agentmemory.service.MemoryConsolidationService;
import com.agentmemory.service.MemoryPipelineService;
import com.agentmemory.service.ReindexMigrationService;
import com.agentmemory.service.embed.DashScopeEmbeddingService;
import com.agentmemory.service.embed.EmbeddingService;
import com.agentmemory.service.embed.OllamaEmbeddingService;
import com.agentmemory.service.rerank.DashScopeRerankService;
import com.agentmemory.service.rerank.RerankService;
import com.agentmemory.service.rerank.RestRerankService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;

/**
 * Standalone MCP server via stdio transport.
 * Does not start Spring Boot or any web container.
 * Connects directly to Elasticsearch and DashScope.
 *
 * Usage: java -cp agentmemory.jar com.agentmemory.StdioMcpServer
 */
public class StdioMcpServer {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpServer.class);
    static final int DEFAULT_CONNECTION_REQUEST_TIMEOUT_MILLIS = 5_000;
    static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 3_000;
    static final int DEFAULT_RESPONSE_TIMEOUT_MILLIS = 30_000;
    private static DashScopeConfig dsConfig;

    record ElasticsearchTimeouts(
            int connectionRequestTimeoutMillis,
            int connectTimeoutMillis,
            int responseTimeoutMillis) {
    }

    private static DashScopeConfig getDsConfig() {
        if (dsConfig != null)
            return dsConfig; // Already initialized

        String dashScopeApiKey = System.getenv("DASHSCOPE_API_KEY");

        // Create config objects manually
        dsConfig = new DashScopeConfig();
        dsConfig.setApiKey(dashScopeApiKey != null ? dashScopeApiKey : "");
        dsConfig.setEmbeddingModel(getEnvOr("DASHSCOPE_EMBEDDING_MODEL", "text-embedding-v4"));
        dsConfig.setEmbeddingDimensions(parseIntOr(System.getenv("DASHSCOPE_EMBEDDING_DIMENSIONS"), 1024));
        dsConfig.setRerankModel(getEnvOr("DASHSCOPE_RERANK_MODEL", "gte-rerank"));
        return dsConfig;
    }

    public static void main(String[] args) throws Exception {
        log.info("Starting AgentMemory MCP stdio server...");
        ObjectMapper yml = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Config from environment
        String esHost = System.getenv("ES_HOST");
        if (esHost == null || esHost.isBlank())
            esHost = "localhost";
        int esPort = parseIntOr(System.getenv("ES_PORT"), 9200);
        String esScheme = System.getenv("ES_SCHEME");
        if (esScheme == null || esScheme.isBlank())
            esScheme = "http";

        JsonNode config = yml.readTree(StdioMcpServer.class.getClassLoader().getResourceAsStream("application.yml"));

        EmbeddingImpl eImpl = EmbeddingImpl.fromString(config.get("agentmemory").get("embedding").asText());
        RerankImpl rImpl = RerankImpl.fromString(config.get("agentmemory").get("rerank").asText());

        EmbeddingService embeddingService;
        RerankService rerankService;

        switch (eImpl) {
            case DASH_SCOPE:
                embeddingService = new DashScopeEmbeddingService(getDsConfig());
                break;
            case OLLAMA:
            default:
                embeddingService = new OllamaEmbeddingService(
                        yml.treeToValue(config.get("ollama"), OllamaConfig.class));
                break;
        }

        switch (rImpl) {
            case DASH_SCOPE:
                rerankService = new DashScopeRerankService(getDsConfig());
                break;
            case REST:
            default:
                rerankService = new RestRerankService(yml.treeToValue(config.get("rest").get("rerank"), RestRerankConfig.class));
                break;
        }

        MemoryProperties memProps = yml.treeToValue(config.get("memory"), MemoryProperties.class);

        // Elasticsearch client
        ElasticsearchTimeouts esTimeouts = elasticsearchTimeouts(config);
        Rest5Client restClient = configureElasticsearchTimeouts(
                Rest5Client.builder(new HttpHost(esScheme, esHost, esPort)),
                esTimeouts).build();
        ElasticsearchClient esClient = new ElasticsearchClient(
                new Rest5ClientTransport(restClient, new JacksonJsonpMapper()));

        // Verify ES connection
        try {
            boolean exists = esClient.ping().value();
            if (!exists) {
                log.error("Cannot connect to Elasticsearch at {}://{}:{}", esScheme, esHost, esPort);
                System.exit(1);
            }
            log.info("Connected to Elasticsearch at {}://{}:{}", esScheme, esHost, esPort);
        } catch (Exception e) {
            log.error("Elasticsearch connection failed: {}", e.getMessage());
            System.exit(1);
        }

        // ObjectMapper for MCP
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Services
        ElasticsearchService esService = new ElasticsearchService(esClient, memProps, embeddingService);
        MemoryPipelineService pipeline = new MemoryPipelineService(esService, embeddingService, rerankService,
                memProps);

        // Consolidation (lifecycle jobs) - multi-trigger scheduler
        LifecycleCoordinator coordinator = new LifecycleCoordinator(esClient);
        MemoryConsolidationService consolidation = new MemoryConsolidationService(esClient, coordinator, memProps);

        boolean consolidationEnabled = memProps.getConsolidation().isEnabled();
        boolean decayEnabled = memProps.getDecay().isEnabled();
        long consolidationInterval = memProps.getConsolidation().getIntervalMinutes();
        long decayInterval = memProps.getDecay().getIntervalMinutes();

        ScheduledExecutorService lifecycleScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lifecycle-scheduler");
            t.setDaemon(true);
            return t;
        });

        // Startup catch-up: run immediately if overdue
        if (consolidationEnabled && consolidation.isJobDue("memory-consolidation", consolidationInterval)) {
            lifecycleScheduler.submit(consolidation::consolidate);
        }
        if (decayEnabled && consolidation.isJobDue("memory-decay", decayInterval)) {
            lifecycleScheduler.submit(consolidation::applyDecay);
        }

        // Short poll every 2 minutes: only trigger if interval has elapsed
        final long pollMinutes = 2;
        lifecycleScheduler.scheduleAtFixedRate(() -> {
            try {
                if (consolidationEnabled && consolidation.isJobDue("memory-consolidation", consolidationInterval)) {
                    consolidation.consolidate();
                }
                if (decayEnabled && consolidation.isJobDue("memory-decay", decayInterval)) {
                    consolidation.applyDecay();
                }
            } catch (Exception ex) {
                log.error("Lifecycle poll error", ex);
            }
        }, pollMinutes, pollMinutes, TimeUnit.MINUTES);

        // MCP tools
        ReindexMigrationService migrationService = new ReindexMigrationService(esClient, embeddingService, memProps);
        McpToolRegistrar registrar = new McpToolRegistrar(pipeline, esService, migrationService, consolidation, coordinator, memProps);

        // Stdio transport
        SingleWriterStdioTransportProvider stdioTransport =
                new SingleWriterStdioTransportProvider(mapper);

        // Build MCP server
        McpSyncServer server = McpServer.sync(stdioTransport)
                .serverInfo("agentmemory", "0.1.0")
                .instructions(
                        "Persistent memory for coding agents. Search, save, and manage coding knowledge across sessions.")
                .tools(registrar.registerAll())
                .build();

        log.info("AgentMemory MCP stdio server started. Waiting for input on stdin...");

        // Block until process is terminated
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down MCP server...");
            lifecycleScheduler.shutdownNow();
            try {
                server.close();
                restClient.close();
            } catch (IOException e) {
                log.error("Error closing resources", e);
            }
            latch.countDown();
        }));

        latch.await();
    }

    private static String getEnvOr(String name, String defaultValue) {
        String val = System.getenv(name);
        return val != null && !val.isBlank() ? val : defaultValue;
    }

    private static int parseIntOr(String val, int defaultValue) {
        if (val == null || val.isBlank())
            return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    static ElasticsearchTimeouts elasticsearchTimeouts(JsonNode config) {
        JsonNode elasticsearch = config.path("elasticsearch");
        return new ElasticsearchTimeouts(
            timeoutMillis(elasticsearch, "connection-request-timeout-ms",
                DEFAULT_CONNECTION_REQUEST_TIMEOUT_MILLIS),
            timeoutMillis(elasticsearch, "connect-timeout-ms", DEFAULT_CONNECT_TIMEOUT_MILLIS),
            timeoutMillis(elasticsearch, "response-timeout-ms", DEFAULT_RESPONSE_TIMEOUT_MILLIS)
        );
    }

    static Rest5ClientBuilder configureElasticsearchTimeouts(
            Rest5ClientBuilder builder, ElasticsearchTimeouts timeouts) {
        return builder
            .setRequestConfigCallback(request -> request
                .setConnectionRequestTimeout(Timeout.of(
                    timeouts.connectionRequestTimeoutMillis(), TimeUnit.MILLISECONDS))
                .setResponseTimeout(Timeout.of(
                    timeouts.responseTimeoutMillis(), TimeUnit.MILLISECONDS)))
            .setConnectionConfigCallback(connection -> connection
                .setConnectTimeout(Timeout.of(
                    timeouts.connectTimeoutMillis(), TimeUnit.MILLISECONDS)));
    }

    private static int timeoutMillis(JsonNode elasticsearch, String field, int defaultValue) {
        JsonNode value = elasticsearch.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw new IllegalArgumentException(
                "elasticsearch." + field + " must be a positive integer");
        }
        return value.intValue();
    }
}
