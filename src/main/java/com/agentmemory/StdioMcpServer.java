package com.agentmemory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.agentmemory.config.DashScopeConfig;
import com.agentmemory.config.MemoryProperties;
import com.agentmemory.mcp.McpToolRegistrar;
import com.agentmemory.service.DashScopeEmbeddingService;
import com.agentmemory.service.DashScopeRerankService;
import com.agentmemory.service.ElasticsearchService;
import com.agentmemory.service.EmbeddingService;
import com.agentmemory.service.MemoryConsolidationService;
import com.agentmemory.service.MemoryPipelineService;
import com.agentmemory.service.RerankService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * Standalone MCP server via stdio transport.
 * Does not start Spring Boot or any web container.
 * Connects directly to Elasticsearch and DashScope.
 *
 * Usage: java -cp agentmemory.jar com.agentmemory.StdioMcpServer
 */
public class StdioMcpServer {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpServer.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting AgentMemory MCP stdio server...");

        // Config from environment
        String esHost = System.getenv("ES_HOST");
        if (esHost == null || esHost.isBlank()) esHost = "localhost";
        int esPort = parseIntOr(System.getenv("ES_PORT"), 9200);
        String esScheme = System.getenv("ES_SCHEME");
        if (esScheme == null || esScheme.isBlank()) esScheme = "http";

        String dashScopeApiKey = System.getenv("DASHSCOPE_API_KEY");

        // Create config objects manually
        DashScopeConfig dsConfig = new DashScopeConfig();
        dsConfig.setApiKey(dashScopeApiKey != null ? dashScopeApiKey : "");
        dsConfig.setEmbeddingModel(getEnvOr("DASHSCOPE_EMBEDDING_MODEL", "text-embedding-v4"));
        dsConfig.setEmbeddingDimensions(parseIntOr(System.getenv("DASHSCOPE_EMBEDDING_DIMENSIONS"), 1024));
        dsConfig.setRerankModel(getEnvOr("DASHSCOPE_RERANK_MODEL", "gte-rerank"));

        MemoryProperties memProps = new MemoryProperties();

        // Elasticsearch client
        RestClient restClient = RestClient.builder(
            new HttpHost(esHost, esPort, esScheme)
        ).build();
        ElasticsearchClient esClient = new ElasticsearchClient(
            new RestClientTransport(restClient, new JacksonJsonpMapper())
        );

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
        EmbeddingService embeddingService = new DashScopeEmbeddingService(dsConfig);
        RerankService rerankService = new DashScopeRerankService(dsConfig);
        ElasticsearchService esService = new ElasticsearchService(esClient, memProps, embeddingService);
        MemoryPipelineService pipeline = new MemoryPipelineService(esService, embeddingService, rerankService, memProps);

        // Consolidation (scheduled tasks) - start in background
        MemoryConsolidationService consolidation = new MemoryConsolidationService(esClient);
        consolidation.startScheduler();

        // MCP tools
        McpToolRegistrar registrar = new McpToolRegistrar(pipeline, esService);

        // Stdio transport
        StdioServerTransportProvider stdioTransport = new StdioServerTransportProvider(mapper);

        // Build MCP server
        McpSyncServer server = McpServer.sync(stdioTransport)
            .serverInfo("agentmemory", "0.1.0")
            .instructions("Persistent memory for coding agents. Search, save, and manage coding knowledge across sessions.")
            .tools(registrar.registerAll())
            .build();

        log.info("AgentMemory MCP stdio server started. Waiting for input on stdin...");

        // Block until process is terminated
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down MCP server...");
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
        if (val == null || val.isBlank()) return defaultValue;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return defaultValue; }
    }
}
