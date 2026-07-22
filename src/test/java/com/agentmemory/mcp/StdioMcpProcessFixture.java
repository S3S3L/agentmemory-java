package com.agentmemory.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.scheduler.Schedulers;

/**
 * Child-JVM MCP server used by {@link StdioMcpProcessLoadTest}.
 */
public final class StdioMcpProcessFixture {

    static final int MAINTENANCE_TASKS = 8;

    private static final CountDownLatch SHUTDOWN = new CountDownLatch(1);
    private static final FixtureMemoryService MEMORY_SERVICE = new FixtureMemoryService();

    private StdioMcpProcessFixture() {
    }

    public static void main(String[] args) throws Exception {
        SingleWriterStdioTransportProvider transport =
            new SingleWriterStdioTransportProvider(new ObjectMapper());
        McpSyncServer server = McpServer.sync(transport)
            .serverInfo("stdio-process-fixture", "1.0.0")
            .requestTimeout(Duration.ofSeconds(20))
            .tools(List.of(
                memoryRecall(),
                memorySave(),
                memoryForget(),
                startBackgroundMaintenance(),
                fixtureStats(),
                fixtureShutdown()))
            .build();

        try {
            SHUTDOWN.await();
            server.closeGracefully();
        }
        finally {
            server.close();
            Schedulers.shutdownNow();
        }
    }

    private static McpServerFeatures.SyncToolSpecification memoryRecall() {
        return tool(
            "memory_recall",
            """
                {"type":"object","properties":{"query":{"type":"string"},"delayMs":{"type":"integer"}},
                "required":["query"]}""",
            args -> MEMORY_SERVICE.execute("recall", (String) args.get("query"), delay(args)));
    }

    private static McpServerFeatures.SyncToolSpecification memorySave() {
        return tool(
            "memory_save",
            """
                {"type":"object","properties":{"content":{"type":"string"},"delayMs":{"type":"integer"}},
                "required":["content"]}""",
            args -> MEMORY_SERVICE.execute("save", (String) args.get("content"), delay(args)));
    }

    private static McpServerFeatures.SyncToolSpecification memoryForget() {
        return tool(
            "memory_forget",
            """
                {"type":"object","properties":{"id":{"type":"string"},"delayMs":{"type":"integer"}},
                "required":["id"]}""",
            args -> MEMORY_SERVICE.execute("forget", (String) args.get("id"), delay(args)));
    }

    private static McpServerFeatures.SyncToolSpecification startBackgroundMaintenance() {
        return tool(
            "fixture_start_background_maintenance",
            "{\"type\":\"object\",\"properties\":{\"durationMs\":{\"type\":\"integer\"}}}",
            args -> {
                int durationMs = ((Number) args.getOrDefault("durationMs", 1000)).intValue();
                MEMORY_SERVICE.startBackgroundMaintenance(durationMs);
                return "background maintenance started=" + MAINTENANCE_TASKS;
            });
    }

    private static McpServerFeatures.SyncToolSpecification fixtureStats() {
        return tool(
            "fixture_stats",
            "{\"type\":\"object\",\"properties\":{}}",
            args -> "maxActive=" + MEMORY_SERVICE.maxActive()
                + ";maintenanceStarted=" + MEMORY_SERVICE.maintenanceStarted());
    }

    private static McpServerFeatures.SyncToolSpecification fixtureShutdown() {
        return tool(
            "fixture_shutdown",
            "{\"type\":\"object\",\"properties\":{}}",
            args -> {
                Thread.ofVirtual().start(() -> {
                    try {
                        Thread.sleep(200);
                    }
                    catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                    SHUTDOWN.countDown();
                });
                return "shutting down";
            });
    }

    private static McpServerFeatures.SyncToolSpecification tool(
            String name,
            String schema,
            java.util.function.Function<Map<String, Object>, String> handler) {
        McpSchema.Tool tool = new McpSchema.Tool(name, "Process-level stdio test fixture", schema);
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) ->
            new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(handler.apply(args))), false));
    }

    private static int delay(Map<String, Object> args) {
        return ((Number) args.getOrDefault("delayMs", 0)).intValue();
    }

    private static final class FixtureMemoryService {

        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final AtomicInteger maintenanceStarted = new AtomicInteger();

        private String execute(String operation, String value, int delayMs) {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            try {
                TimeUnit.MILLISECONDS.sleep(delayMs);
                return operation + ":" + value;
            }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Fixture operation interrupted", error);
            }
            finally {
                active.decrementAndGet();
            }
        }

        private void startBackgroundMaintenance(int durationMs) {
            CountDownLatch started = new CountDownLatch(MAINTENANCE_TASKS);
            for (int i = 0; i < MAINTENANCE_TASKS; i++) {
                Schedulers.boundedElastic().schedule(() -> {
                    maintenanceStarted.incrementAndGet();
                    started.countDown();
                    try {
                        TimeUnit.MILLISECONDS.sleep(durationMs);
                    }
                    catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            try {
                if (!started.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Background maintenance did not start");
                }
            }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted starting background maintenance", error);
            }
        }

        private int maxActive() {
            return maxActive.get();
        }

        private int maintenanceStarted() {
            return maintenanceStarted.get();
        }
    }
}
