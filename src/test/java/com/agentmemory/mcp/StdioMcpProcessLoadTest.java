package com.agentmemory.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;

class StdioMcpProcessLoadTest {

    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PROCESS_EXIT_TIMEOUT = Duration.ofSeconds(10);
    private static final String SHUTDOWN_ID = "fixture-shutdown";

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void concurrentForgetLoadPreservesEveryResponse() throws Exception {
        List<ToolRequest> requests = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            requests.add(new ToolRequest(
                "forget-" + i,
                "memory_forget",
                Map.of("id", "memory-" + i, "delayMs", 70 + (i % 4) * 10)));
        }

        try (ProcessHarness harness = ProcessHarness.start()) {
            harness.initialize();
            LoadResult result = harness.runLoad("forget-only", requests, LOAD_TIMEOUT);

            result.assertComplete(requests);
            assertTrue(harness.fixtureStats().maxActive() > 1,
                "Child MCP handlers did not execute concurrently");
            harness.shutdownAndAssertClean();
        }
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void mixedLoadAllowsFastResponsesPastSlowRecall() throws Exception {
        List<ToolRequest> requests = mixedRequests("mixed", 180, true);
        String slowId = requests.getFirst().id();

        try (ProcessHarness harness = ProcessHarness.start()) {
            harness.initialize();
            LoadResult result = harness.runLoad("mixed", requests, LOAD_TIMEOUT);

            result.assertComplete(requests);
            long fastBeforeSlow = result.responses().stream()
                .takeWhile(response -> !response.id().equals(slowId))
                .count();
            assertTrue(fastBeforeSlow >= 10,
                "Slow memory_recall caused head-of-line blocking; fastBeforeSlow=" + fastBeforeSlow);
            assertTrue(harness.fixtureStats().maxActive() > 1,
                "Child MCP handlers did not execute concurrently");
            harness.shutdownAndAssertClean();
        }
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void backgroundMaintenanceContentionPreservesMixedLoadResponses() throws Exception {
        List<ToolRequest> requests = mixedRequests("maintenance", 180, false);

        try (ProcessHarness harness = ProcessHarness.start()) {
            harness.initialize();
            JsonNode maintenance = harness.callTool(
                "maintenance-control",
                "fixture_start_background_maintenance",
                Map.of("durationMs", 1200),
                Duration.ofSeconds(5));
            assertSuccessfulToolResponse(maintenance);

            LoadResult result = harness.runLoad(
                "background-maintenance-contention", requests, LOAD_TIMEOUT);

            result.assertComplete(requests);
            FixtureStats stats = harness.fixtureStats();
            assertTrue(stats.maxActive() > 1, "Child MCP handlers did not execute concurrently");
            assertEquals(StdioMcpProcessFixture.MAINTENANCE_TASKS, stats.maintenanceStarted(),
                "Background maintenance did not occupy the shared boundedElastic scheduler");
            harness.shutdownAndAssertClean();
        }
    }

    private static List<ToolRequest> mixedRequests(String prefix, int count, boolean includeSlowRecall) {
        List<ToolRequest> requests = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = prefix + "-" + i;
            if (i == 0 && includeSlowRecall) {
                requests.add(new ToolRequest(id, "memory_recall",
                    Map.of("query", id, "delayMs", 900)));
            }
            else if (i % 3 == 0) {
                requests.add(new ToolRequest(id, "memory_recall",
                    Map.of("query", id, "delayMs", 20 + (i % 5) * 15)));
            }
            else if (i % 3 == 1) {
                requests.add(new ToolRequest(id, "memory_save",
                    Map.of("content", id, "delayMs", 5 + (i % 5) * 10)));
            }
            else {
                requests.add(new ToolRequest(id, "memory_forget",
                    Map.of("id", id, "delayMs", 10 + (i % 5) * 10)));
            }
        }
        return requests;
    }

    private static void assertSuccessfulToolResponse(JsonNode response) {
        assertFalse(response.hasNonNull("error"), "JSON-RPC error response: " + response);
        assertTrue(response.hasNonNull("result"), "Missing JSON-RPC result: " + response);
        assertFalse(response.path("result").path("isError").asBoolean(),
            "Tool returned an error: " + response);
        assertFalse(response.path("result").path("content").path(0).path("text").asText().isBlank(),
            "Tool returned empty content: " + response);
    }

    private record ToolRequest(String id, String toolName, Map<String, Object> arguments) {
    }

    private record ReceivedResponse(String id, JsonNode message, long receivedAtNanos) {
    }

    private record FixtureStats(int maxActive, int maintenanceStarted) {

        private static FixtureStats parse(String text) {
            Map<String, Integer> values = new HashMap<>();
            for (String field : text.split(";")) {
                String[] parts = field.split("=", 2);
                values.put(parts[0], Integer.parseInt(parts[1]));
            }
            return new FixtureStats(values.get("maxActive"), values.get("maintenanceStarted"));
        }
    }

    private record LatencyStats(int count, double p50Ms, double p95Ms, double maxMs) {

        private static LatencyStats from(List<Double> latencyMs) {
            List<Double> sorted = new ArrayList<>(latencyMs);
            Collections.sort(sorted);
            return new LatencyStats(
                sorted.size(),
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                sorted.getLast());
        }

        private static double percentile(List<Double> sorted, double percentile) {
            int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
            return sorted.get(index);
        }
    }

    private record LoadResult(
            List<ReceivedResponse> responses,
            LatencyStats latency,
            Duration elapsed) {

        private void assertComplete(List<ToolRequest> requests) {
            Set<String> expected = new HashSet<>();
            requests.forEach(request -> expected.add(request.id()));
            Set<String> actual = new HashSet<>();
            responses.forEach(response -> {
                actual.add(response.id());
                assertSuccessfulToolResponse(response.message());
            });

            assertEquals(expected, actual, "Lost, duplicated, or mismatched JSON-RPC response IDs");
            assertEquals(requests.size(), responses.size(), "Unexpected response count");
            assertEquals(requests.size(), latency.count(), "Latency sample count mismatch");
            assertTrue(elapsed.compareTo(LOAD_TIMEOUT) < 0,
                "Load exceeded the reasonable global timeout: " + elapsed);
        }
    }

    private static final class ProcessHarness implements AutoCloseable {

        private final ObjectMapper mapper = new ObjectMapper();
        private final Process process;
        private final BufferedWriter stdin;
        private final BlockingQueue<ReceivedResponse> stdoutResponses = new LinkedBlockingQueue<>();
        private final Set<String> allResponseIds = ConcurrentHashMap.newKeySet();
        private final AtomicReference<Throwable> stdoutFailure = new AtomicReference<>();
        private final StringBuffer stderr = new StringBuffer();
        private final AtomicBoolean initialized = new AtomicBoolean();
        private final AtomicBoolean shutdown = new AtomicBoolean();
        private final Thread stdoutThread;
        private final Thread stderrThread;

        private ProcessHarness(Process process) {
            this.process = process;
            this.stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.stdoutThread = Thread.ofPlatform()
                .daemon()
                .name("stdio-process-stdout-" + process.pid())
                .start(this::readStdout);
            this.stderrThread = Thread.ofPlatform()
                .daemon()
                .name("stdio-process-stderr-" + process.pid())
                .start(this::readStderr);
        }

        private static ProcessHarness start() throws IOException {
            String classpath = System.getProperty("surefire.test.class.path");
            if (classpath == null || classpath.isBlank()) {
                classpath = System.getProperty("java.class.path");
            }
            assertNotNull(classpath, "Surefire test classpath is unavailable");
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder builder = new ProcessBuilder(
                java,
                "-Dreactor.schedulers.defaultBoundedElasticSize=16",
                "-Dlogback.configurationFile=logback-stdio.xml",
                "-cp",
                classpath,
                StdioMcpProcessFixture.class.getName());
            builder.redirectErrorStream(false);
            return new ProcessHarness(builder.start());
        }

        private void initialize() throws Exception {
            writeMessages(List.of(Map.of(
                "jsonrpc", McpSchema.JSONRPC_VERSION,
                "id", "initialize",
                "method", McpSchema.METHOD_INITIALIZE,
                "params", Map.of(
                    "protocolVersion", McpSchema.LATEST_PROTOCOL_VERSION,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "stdio-process-test", "version", "1.0.0")))));
            JsonNode response = awaitResponses(Set.of("initialize"), Duration.ofSeconds(5)).getFirst().message();
            assertSuccessfulJsonRpcResponse(response);

            writeMessages(List.of(Map.of(
                "jsonrpc", McpSchema.JSONRPC_VERSION,
                "method", McpSchema.METHOD_NOTIFICATION_INITIALIZED,
                "params", Map.of())));
            initialized.set(true);
        }

        private LoadResult runLoad(
                String scenario, List<ToolRequest> requests, Duration timeout) throws Exception {
            Map<String, Long> sentAt = new HashMap<>();
            List<Map<String, Object>> messages = new ArrayList<>(requests.size());
            long started = System.nanoTime();
            for (ToolRequest request : requests) {
                sentAt.put(request.id(), System.nanoTime());
                messages.add(toolCall(request.id(), request.toolName(), request.arguments()));
            }
            writeMessages(messages);

            Set<String> expectedIds = new HashSet<>(sentAt.keySet());
            List<ReceivedResponse> responses = awaitResponses(expectedIds, timeout);
            long finished = System.nanoTime();
            List<Double> latencyMs = responses.stream()
                .map(response -> (response.receivedAtNanos() - sentAt.get(response.id())) / 1_000_000.0)
                .toList();
            LatencyStats stats = LatencyStats.from(latencyMs);
            Duration elapsed = Duration.ofNanos(finished - started);

            System.out.printf(
                "process stdio %s: count=%d p50=%.1fms p95=%.1fms max=%.1fms elapsed=%.1fms pid=%d%n",
                scenario, stats.count(), stats.p50Ms(), stats.p95Ms(), stats.maxMs(),
                elapsed.toNanos() / 1_000_000.0, process.pid());
            return new LoadResult(responses, stats, elapsed);
        }

        private JsonNode callTool(
                String id, String toolName, Map<String, Object> arguments, Duration timeout)
                throws Exception {
            writeMessages(List.of(toolCall(id, toolName, arguments)));
            JsonNode response = awaitResponses(Set.of(id), timeout).getFirst().message();
            assertSuccessfulJsonRpcResponse(response);
            return response;
        }

        private FixtureStats fixtureStats() throws Exception {
            JsonNode response = callTool(
                "fixture-stats", "fixture_stats", Map.of(), Duration.ofSeconds(5));
            return FixtureStats.parse(
                response.path("result").path("content").path(0).path("text").asText());
        }

        private void shutdownAndAssertClean() throws Exception {
            if (shutdown.compareAndSet(false, true)) {
                callTool(SHUTDOWN_ID, "fixture_shutdown", Map.of(), Duration.ofSeconds(5));
                stdin.close();
                if (!process.waitFor(PROCESS_EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    destroyProcess("Child JVM did not exit after fixture_shutdown");
                }
                stdoutThread.join(1000);
                stderrThread.join(1000);
                assertEquals(0, process.exitValue(),
                    "Child JVM exited abnormally; pid=" + process.pid() + ", stderr=" + stderr);
            }
            assertNoTransportErrors();
            assertNoStdoutFailure();
        }

        private Map<String, Object> toolCall(
                String id, String toolName, Map<String, Object> arguments) {
            return Map.of(
                "jsonrpc", McpSchema.JSONRPC_VERSION,
                "id", id,
                "method", McpSchema.METHOD_TOOLS_CALL,
                "params", Map.of("name", toolName, "arguments", arguments));
        }

        private synchronized void writeMessages(List<Map<String, Object>> messages) throws IOException {
            for (Map<String, Object> message : messages) {
                stdin.write(mapper.writeValueAsString(message));
                stdin.newLine();
            }
            stdin.flush();
        }

        private List<ReceivedResponse> awaitResponses(Set<String> expectedIds, Duration timeout)
                throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            List<ReceivedResponse> found = new ArrayList<>(expectedIds.size());
            while (found.size() < expectedIds.size()) {
                assertNoStdoutFailure();
                if (!process.isAlive() && stdoutResponses.isEmpty()) {
                    fail("Child JVM exited before all responses arrived; pid=" + process.pid()
                        + ", exit=" + process.exitValue() + ", received=" + found.size()
                        + "/" + expectedIds.size() + ", stderr=" + stderr);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    fail("Timed out waiting for child JVM responses; pid=" + process.pid()
                        + ", received=" + found.size() + "/" + expectedIds.size()
                        + ", missing=" + missingIds(expectedIds, found) + ", stderr=" + stderr);
                }
                ReceivedResponse response = stdoutResponses.poll(
                    Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                    TimeUnit.NANOSECONDS);
                if (response == null) {
                    continue;
                }
                assertTrue(expectedIds.contains(response.id()),
                    "Unexpected JSON-RPC response ID " + response.id() + ": " + response.message());
                found.add(response);
            }
            return found;
        }

        private Set<String> missingIds(Set<String> expected, List<ReceivedResponse> found) {
            Set<String> missing = new HashSet<>(expected);
            found.forEach(response -> missing.remove(response.id()));
            return missing;
        }

        private void readStdout() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JsonNode message;
                    try {
                        message = mapper.readTree(line);
                    }
                    catch (IOException error) {
                        throw new IOException("Non-JSON child stdout line: " + line, error);
                    }
                    String id = message.path("id").asText();
                    if (id.isBlank()) {
                        throw new IOException("Child stdout message has no JSON-RPC ID: " + line);
                    }
                    if (!allResponseIds.add(id)) {
                        throw new IOException("Duplicate JSON-RPC response ID from child: " + id);
                    }
                    stdoutResponses.add(new ReceivedResponse(id, message, System.nanoTime()));
                }
            }
            catch (Throwable error) {
                stdoutFailure.compareAndSet(null, error);
            }
        }

        private void readStderr() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (stderr.length() < 1_000_000) {
                        stderr.append(line).append('\n');
                    }
                }
            }
            catch (IOException error) {
                if (process.isAlive()) {
                    stderr.append("stderr reader failed: ").append(error).append('\n');
                }
            }
        }

        private void assertNoTransportErrors() {
            String output = stderr.toString();
            assertFalse(output.contains("FAIL_NON_SERIALIZED"),
                "Child stderr contained FAIL_NON_SERIALIZED; pid=" + process.pid() + "\n" + output);
            assertFalse(output.contains("Failed to enqueue message"),
                "Child stderr contained Failed to enqueue message; pid=" + process.pid() + "\n" + output);
        }

        private void assertNoStdoutFailure() {
            Throwable failure = stdoutFailure.get();
            if (failure != null) {
                throw new AssertionError(
                    "Child stdout reader failed; pid=" + process.pid() + ", stderr=" + stderr,
                    failure);
            }
        }

        private void assertSuccessfulJsonRpcResponse(JsonNode response) {
            assertFalse(response.hasNonNull("error"), "JSON-RPC request failed: " + response);
            assertTrue(response.hasNonNull("result"), "JSON-RPC response has no result: " + response);
        }

        private void destroyProcess(String reason) throws InterruptedException {
            long pid = process.pid();
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            fail(reason + "; destroyed child pid=" + pid + ", stderr=" + stderr);
        }

        @Override
        public void close() throws Exception {
            if (process.isAlive()) {
                try {
                    if (initialized.get() && !shutdown.get()) {
                        shutdownAndAssertClean();
                    }
                }
                finally {
                    if (process.isAlive()) {
                        long pid = process.pid();
                        process.destroy();
                        if (!process.waitFor(2, TimeUnit.SECONDS)) {
                            process.destroyForcibly();
                            process.waitFor(2, TimeUnit.SECONDS);
                        }
                        System.err.println("Destroyed leaked stdio fixture child pid=" + pid);
                    }
                }
            }
        }
    }
}
