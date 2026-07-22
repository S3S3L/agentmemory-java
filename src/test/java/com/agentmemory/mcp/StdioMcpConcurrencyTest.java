package com.agentmemory.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.agentmemory.model.MemoryTier;
import com.agentmemory.model.SearchResult;
import com.agentmemory.service.ElasticsearchService;
import com.agentmemory.service.MemoryPipelineService;
import com.agentmemory.service.ReindexMigrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import reactor.core.publisher.Mono;

class StdioMcpConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 4;
    private static final Duration HANDLER_OVERLAP_WINDOW = Duration.ofMillis(750);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    @ParameterizedTest(name = "{0} concurrent responses")
    @ValueSource(ints = { 10, 50, 100 })
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void concurrentForgetRequestsOverlapAndPreserveJsonRpcIds(int requestCount) throws Exception {
        MemoryPipelineService pipeline = mock(MemoryPipelineService.class);
        ElasticsearchService esService = mock(ElasticsearchService.class);
        CompletionBurstProbe probe = new CompletionBurstProbe(Math.min(requestCount, 10));
        doAnswer(invocation -> {
            probe.enterAndAwaitRelease();
            return ElasticsearchService.DeleteResult.DELETED;
        }).when(esService).deleteMemory(anyString());

        try (StdioHarness harness = harness(pipeline, esService)) {
            Set<String> requestIds = new HashSet<>();
            for (int i = 0; i < requestCount; i++) {
                String requestId = "forget-" + i;
                requestIds.add(requestId);
                harness.sendToolCall(requestId, "memory_forget", Map.of("id", "memory-" + i));
            }

            try {
                assertTrue(probe.awaitReady(Duration.ofSeconds(2)),
                    "Concurrent memory_forget handlers did not become ready for the response burst");
            }
            finally {
                probe.release();
            }

            List<JsonNode> responses = harness.readResponses(requestCount, RESPONSE_TIMEOUT);
            assertEquals(requestIds, responseIds(responses),
                "Lost, duplicated, or mismatched JSON-RPC response IDs");
            assertEquals(requestCount, responses.stream()
                .map(response -> response.path("id").asText())
                .distinct()
                .count(), "Duplicate JSON-RPC response IDs were returned");
            responses.forEach(response -> assertSuccessfulToolResponse(response, "memory_forget"));
            probe.assertConcurrent("memory_forget handlers were serialized by the stdio MCP path");
            harness.assertNoResponses(Duration.ofMillis(200));
            harness.assertSingleWriter();

            System.out.printf("stdio concurrent forget: requests=%d responses=%d maxActive=%d%n",
                requestCount, responses.size(), probe.maxActive());
        }

        verify(esService, times(requestCount)).deleteMemory(anyString());
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.SECONDS)
    void concurrentForgetForSameMemoryReportsOneDeletedAndRemainingNotFound() throws Exception {
        MemoryPipelineService pipeline = mock(MemoryPipelineService.class);
        ElasticsearchService esService = mock(ElasticsearchService.class);
        OverlapProbe probe = new OverlapProbe(CONCURRENT_REQUESTS);
        AtomicInteger completionOrder = new AtomicInteger();
        doAnswer(invocation -> {
            probe.enterAndAwait();
            return completionOrder.getAndIncrement() == 0
                ? ElasticsearchService.DeleteResult.DELETED
                : ElasticsearchService.DeleteResult.NOT_FOUND;
        }).when(esService).deleteMemory("shared-memory");

        try (StdioHarness harness = harness(pipeline, esService)) {
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                harness.sendToolCall("same-id-" + i, "memory_forget", Map.of("id", "shared-memory"));
            }

            List<JsonNode> responses = harness.readResponses(CONCURRENT_REQUESTS, RESPONSE_TIMEOUT);
            assertEquals(CONCURRENT_REQUESTS, responseIds(responses).size(),
                "Concurrent same-ID deletes lost or duplicated responses");
            long deleted = 0;
            long notFound = 0;
            for (JsonNode response : responses) {
                assertSuccessfulToolResponse(response, "memory_forget");
                if (toolText(response).contains("Deleted memory: shared-memory")) {
                    deleted++;
                }
                else if (toolText(response).contains("Memory not found: shared-memory")) {
                    notFound++;
                }
                else {
                    fail("Unexpected same-ID delete result: " + response);
                }
            }
            assertEquals(1, deleted, "Exactly one same-ID delete should report deleted");
            assertEquals(CONCURRENT_REQUESTS - 1, notFound,
                "Every repeated same-ID delete should report not_found");
            probe.assertConcurrent("same-ID memory_forget handlers did not overlap");

            System.out.printf("stdio same-ID forget: calls=%d deleted=%d notFound=%d maxActive=%d%n",
                CONCURRENT_REQUESTS, deleted, notFound, probe.maxActive());
        }

        verify(esService, times(CONCURRENT_REQUESTS)).deleteMemory("shared-memory");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void slowRecallDoesNotBlockSaveOrForgetResponses() throws Exception {
        MemoryPipelineService pipeline = mock(MemoryPipelineService.class);
        ElasticsearchService esService = mock(ElasticsearchService.class);
        CountDownLatch recallEntered = new CountDownLatch(1);
        CountDownLatch releaseRecall = new CountDownLatch(1);

        when(pipeline.recall(anyString(), nullable(String.class), nullable(String.class))).thenAnswer(invocation -> {
            recallEntered.countDown();
            if (!releaseRecall.await(3, TimeUnit.SECONDS)) {
                throw new IOException("test timed out waiting to release slow recall");
            }
            return List.of(new SearchResult(
                "recall-result", "slow recall completed", MemoryTier.SEMANTIC,
                null, null, null, 1.0, 0.0, 0.0, 1.0));
        });
        when(pipeline.saveInsight(
            anyString(), any(MemoryTier.class), nullable(String.class), nullable(List.class), nullable(String.class)))
            .thenAnswer(invocation -> {
                Thread.sleep(100);
                return Map.of("id", "saved-memory");
            });
        when(esService.deleteMemory("fast-memory"))
            .thenReturn(ElasticsearchService.DeleteResult.DELETED);

        try (StdioHarness harness = harness(pipeline, esService)) {
            harness.sendToolCall("slow-recall", "memory_recall", Map.of("query", "slow"));
            assertTrue(recallEntered.await(1, TimeUnit.SECONDS),
                "Slow memory_recall handler never started through stdio");

            harness.sendToolCall("fast-save", "memory_save", Map.of("content", "fast"));
            harness.sendToolCall("fast-forget", "memory_forget", Map.of("id", "fast-memory"));

            List<JsonNode> fastResponses;
            try {
                fastResponses = harness.readResponses(2, Duration.ofMillis(1200));
            }
            catch (AssertionError error) {
                throw new AssertionError(
                    "Slow memory_recall serially blocked memory_save/memory_forget or their responses were lost",
                    error);
            }
            finally {
                releaseRecall.countDown();
            }

            assertEquals(Set.of("fast-save", "fast-forget"), responseIds(fastResponses),
                "Expected both fast mixed-operation responses before releasing slow recall");
            fastResponses.forEach(response -> assertSuccessfulToolResponse(response, "fast mixed operation"));

            JsonNode recallResponse = harness.readResponses(1, RESPONSE_TIMEOUT).getFirst();
            assertEquals("slow-recall", recallResponse.path("id").asText(),
                "Slow recall response ID was lost or replaced");
            assertSuccessfulToolResponse(recallResponse, "memory_recall");

            System.out.printf("stdio mixed operations: earlyResponseIds=%s finalResponseId=%s%n",
                responseIds(fastResponses), recallResponse.path("id").asText());
        }

        verify(pipeline).saveInsight(
            anyString(), any(MemoryTier.class), nullable(String.class), nullable(List.class), nullable(String.class));
        verify(esService).deleteMemory("fast-memory");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void fullOutboundQueueFailsExplicitlyWithoutDroppingAcceptedMessages() throws Exception {
        GateOutputStream output = new GateOutputStream(false);
        try (DirectTransportHarness harness = new DirectTransportHarness(output, 1)) {
            CompletableFuture<Void> first = harness.send("first");
            assertTrue(output.awaitWriteStarted(Duration.ofSeconds(1)),
                "Writer did not start the first outbound message");

            CompletableFuture<Void> second = harness.send("second");
            RejectedExecutionException error = assertThrows(RejectedExecutionException.class,
                () -> harness.transport().sendMessage(notification("overflow")).block());
            assertTrue(error.getMessage().contains("capacity=1"),
                "Queue-full error did not include its configured capacity: " + error.getMessage());

            output.release();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void gracefulCloseDrainsAcceptedMessagesAndRejectsLaterSends() throws Exception {
        GateOutputStream output = new GateOutputStream(false);
        try (DirectTransportHarness harness = new DirectTransportHarness(output, 2)) {
            CompletableFuture<Void> first = harness.send("first");
            assertTrue(output.awaitWriteStarted(Duration.ofSeconds(1)),
                "Writer did not start the first outbound message");
            CompletableFuture<Void> second = harness.send("second");

            CompletableFuture<Void> closed = harness.provider().closeGracefully().toFuture();
            output.release();

            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
            closed.get(1, TimeUnit.SECONDS);

            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> harness.transport().sendMessage(notification("after-close")).block());
            assertTrue(error.getMessage().contains("closed"),
                "Post-close error was not diagnostic: " + error.getMessage());
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void immediateCloseFailsActiveAndQueuedMessages() throws Exception {
        GateOutputStream output = new GateOutputStream(false);
        try (DirectTransportHarness harness = new DirectTransportHarness(output, 2)) {
            CompletableFuture<Void> first = harness.send("first");
            assertTrue(output.awaitWriteStarted(Duration.ofSeconds(1)),
                "Writer did not start the first outbound message");
            CompletableFuture<Void> second = harness.send("second");

            harness.provider().close();

            assertTrue(futureFailure(first).getMessage().contains("closed"),
                "Active send did not report transport closure");
            assertTrue(futureFailure(second).getMessage().contains("closed"),
                "Queued send did not report transport closure");
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void outputStreamFailureFailsActiveQueuedAndFutureMessages() throws Exception {
        GateOutputStream output = new GateOutputStream(true);
        try (DirectTransportHarness harness = new DirectTransportHarness(output, 2)) {
            CompletableFuture<Void> first = harness.send("first");
            assertTrue(output.awaitWriteStarted(Duration.ofSeconds(1)),
                "Writer did not start the first outbound message");
            CompletableFuture<Void> second = harness.send("second");
            output.release();

            assertInstanceOf(IOException.class, futureFailure(first));
            assertInstanceOf(IOException.class, futureFailure(second));

            IllegalStateException futureError = assertThrows(IllegalStateException.class,
                () -> harness.transport().sendMessage(notification("after-failure")).block());
            assertNotNull(futureError.getCause(), "Writer failure was not retained as the cause");
            assertTrue(futureError.getMessage().contains("failed"),
                "Future send did not report the terminal writer failure");
        }
    }

    private static StdioHarness harness(MemoryPipelineService pipeline, ElasticsearchService esService)
            throws Exception {
        ReindexMigrationService migrationService = mock(ReindexMigrationService.class);
        McpToolRegistrar registrar = new McpToolRegistrar(pipeline, esService, migrationService);
        return new StdioHarness(registrar);
    }

    private static Set<String> responseIds(List<JsonNode> responses) {
        Set<String> ids = new HashSet<>();
        responses.forEach(response -> ids.add(response.path("id").asText()));
        return ids;
    }

    private static void assertSuccessfulToolResponse(JsonNode response, String operation) {
        assertFalse(response.hasNonNull("error"),
            operation + " returned a JSON-RPC error: " + response);
        assertTrue(response.hasNonNull("result"),
            operation + " returned an incomplete JSON-RPC response: " + response);
        assertFalse(response.path("result").path("isError").asBoolean(),
            operation + " returned a tool error: " + response);
        assertFalse(toolText(response).isBlank(),
            operation + " returned an empty tool response: " + response);
    }

    private static String toolText(JsonNode response) {
        return response.path("result").path("content").path(0).path("text").asText();
    }

    private static final class OverlapProbe {

        private final CountDownLatch allEntered;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final AtomicBoolean overlapTimedOut = new AtomicBoolean();

        private OverlapProbe(int expectedCalls) {
            this.allEntered = new CountDownLatch(expectedCalls);
        }

        private void enterAndAwait() throws InterruptedException {
            int currentActive = active.incrementAndGet();
            maxActive.accumulateAndGet(currentActive, Math::max);
            allEntered.countDown();
            try {
                if (!allEntered.await(HANDLER_OVERLAP_WINDOW.toMillis(), TimeUnit.MILLISECONDS)) {
                    overlapTimedOut.set(true);
                }
            }
            finally {
                active.decrementAndGet();
            }
        }

        private void assertConcurrent(String message) {
            assertFalse(overlapTimedOut.get(),
                message + "; all handlers did not enter within " + HANDLER_OVERLAP_WINDOW);
            assertTrue(maxActive.get() > 1,
                message + "; observed maxActive=" + maxActive.get());
        }

        private int maxActive() {
            return maxActive.get();
        }
    }

    private static final class CompletionBurstProbe {

        private final CountDownLatch ready;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final AtomicBoolean releaseTimedOut = new AtomicBoolean();

        private CompletionBurstProbe(int simultaneousCompletions) {
            this.ready = new CountDownLatch(simultaneousCompletions);
        }

        private void enterAndAwaitRelease() throws InterruptedException {
            int currentActive = active.incrementAndGet();
            maxActive.accumulateAndGet(currentActive, Math::max);
            ready.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) {
                    releaseTimedOut.set(true);
                }
            }
            finally {
                active.decrementAndGet();
            }
        }

        private boolean awaitReady(Duration timeout) throws InterruptedException {
            return ready.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        private void release() {
            release.countDown();
        }

        private void assertConcurrent(String message) {
            assertFalse(releaseTimedOut.get(), message + "; completion burst timed out");
            assertTrue(maxActive.get() > 1,
                message + "; observed maxActive=" + maxActive.get());
        }

        private int maxActive() {
            return maxActive.get();
        }
    }

    private static final class StdioHarness implements AutoCloseable {

        private final ObjectMapper mapper = new ObjectMapper();
        private final BlockingQueue<Integer> clientToServer = new LinkedBlockingQueue<>();
        private final BlockingQueue<Integer> serverToClient = new LinkedBlockingQueue<>();
        private final InputStream serverInput = new QueueInputStream(clientToServer);
        private final OutputStream clientOutput = new QueueOutputStream(clientToServer);
        private final InputStream clientInput = new QueueInputStream(serverToClient);
        private final QueueOutputStream serverOutput = new QueueOutputStream(serverToClient);
        private final BufferedReader responseReader =
            new BufferedReader(new InputStreamReader(clientInput, StandardCharsets.UTF_8));
        private final BlockingQueue<JsonNode> responses = new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        private final AtomicBoolean closing = new AtomicBoolean();
        private final McpSyncServer server;
        private final Thread readerThread;

        private StdioHarness(McpToolRegistrar registrar) throws Exception {
            SingleWriterStdioTransportProvider transport =
                new SingleWriterStdioTransportProvider(mapper, serverInput, serverOutput);
            server = McpServer.sync(transport)
                .serverInfo("stdio-concurrency-test", "1.0.0")
                .requestTimeout(Duration.ofSeconds(3))
                .tools(registrar.registerAll())
                .build();

            readerThread = Thread.ofVirtual().name("stdio-test-response-reader").start(this::readOutput);
            initialize();
        }

        private void initialize() throws Exception {
            send(Map.of(
                "jsonrpc", McpSchema.JSONRPC_VERSION,
                "id", "initialize",
                "method", McpSchema.METHOD_INITIALIZE,
                "params", Map.of(
                    "protocolVersion", McpSchema.LATEST_PROTOCOL_VERSION,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "stdio-test-client", "version", "1.0.0"))));

            JsonNode response = readResponses(1, Duration.ofSeconds(1)).getFirst();
            assertEquals("initialize", response.path("id").asText(),
                "stdio initialization response ID mismatch");
            assertFalse(response.hasNonNull("error"),
                "stdio initialization failed: " + response);

            send(Map.of(
                "jsonrpc", McpSchema.JSONRPC_VERSION,
                "method", McpSchema.METHOD_NOTIFICATION_INITIALIZED,
                "params", Map.of()));
        }

        private void sendToolCall(String id, String toolName, Map<String, Object> arguments) throws IOException {
            send(Map.of(
                "jsonrpc", McpSchema.JSONRPC_VERSION,
                "id", id,
                "method", McpSchema.METHOD_TOOLS_CALL,
                "params", Map.of("name", toolName, "arguments", arguments)));
        }

        private synchronized void send(Map<String, Object> message) throws IOException {
            clientOutput.write(mapper.writeValueAsBytes(message));
            clientOutput.write('\n');
            clientOutput.flush();
        }

        private List<JsonNode> readResponses(int expected, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            List<JsonNode> found = new ArrayList<>(expected);
            while (found.size() < expected) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    fail("Timed out waiting for " + expected + " stdio responses; received=" + found
                        + ", readerFailure=" + readerFailure.get());
                }
                JsonNode response = responses.poll(remaining, TimeUnit.NANOSECONDS);
                if (response == null) {
                    fail("Timed out waiting for " + expected + " stdio responses; received=" + found
                        + ", readerFailure=" + readerFailure.get());
                }
                found.add(response);
            }
            return found;
        }

        private void assertNoResponses(Duration duration) throws InterruptedException {
            JsonNode unexpected = responses.poll(duration.toNanos(), TimeUnit.NANOSECONDS);
            assertNull(unexpected, "Received an unexpected duplicate stdio response");
            assertNull(readerFailure.get(), "Stdio response reader failed");
        }

        private void assertSingleWriter() {
            assertEquals(1, serverOutput.writerThreads().size(),
                "Multiple threads wrote to the stdio OutputStream: " + serverOutput.writerThreads());
            assertTrue(serverOutput.writerThreads().iterator().next().startsWith("agentmemory-stdio-writer-"),
                "Unexpected stdio writer thread: " + serverOutput.writerThreads());
        }

        private void readOutput() {
            try {
                String line;
                while ((line = responseReader.readLine()) != null) {
                    responses.add(mapper.readTree(line));
                }
            }
            catch (Throwable error) {
                if (!closing.get()) {
                    readerFailure.compareAndSet(null, error);
                }
            }
        }

        @Override
        public void close() throws Exception {
            closing.set(true);
            clientOutput.close();
            server.closeGracefully();
            serverOutput.close();
            responseReader.close();
            readerThread.join(1000);
        }
    }

    private static final class DirectTransportHarness implements AutoCloseable {

        private final BlockingQueue<Integer> inputBytes = new LinkedBlockingQueue<>();
        private final QueueOutputStream clientOutput = new QueueOutputStream(inputBytes);
        private final SingleWriterStdioTransportProvider provider;
        private final McpServerTransport transport;

        private DirectTransportHarness(OutputStream output, int queueCapacity) {
            AtomicReference<McpServerTransport> createdTransport = new AtomicReference<>();
            McpServerSession session = mock(McpServerSession.class);
            when(session.handle(any())).thenReturn(Mono.empty());

            provider = new SingleWriterStdioTransportProvider(
                new ObjectMapper(), new QueueInputStream(inputBytes), output, queueCapacity);
            provider.setSessionFactory(candidate -> {
                createdTransport.set(candidate);
                return session;
            });
            transport = createdTransport.get();
            assertNotNull(transport, "Transport provider did not create its MCP session transport");
        }

        private CompletableFuture<Void> send(String id) {
            return transport.sendMessage(notification(id)).toFuture();
        }

        private SingleWriterStdioTransportProvider provider() {
            return provider;
        }

        private McpServerTransport transport() {
            return transport;
        }

        @Override
        public void close() {
            clientOutput.close();
            provider.close();
        }
    }

    private static McpSchema.JSONRPCNotification notification(String id) {
        return new McpSchema.JSONRPCNotification(
            McpSchema.JSONRPC_VERSION, "test/notification", Map.of("id", id));
    }

    private static Throwable futureFailure(CompletableFuture<Void> future) throws Exception {
        ExecutionException error = assertThrows(ExecutionException.class,
            () -> future.get(1, TimeUnit.SECONDS));
        return error.getCause();
    }

    private static final class QueueInputStream extends InputStream {

        private static final int EOF = -1;

        private final BlockingQueue<Integer> bytes;
        private boolean eof;

        private QueueInputStream(BlockingQueue<Integer> bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() throws IOException {
            if (eof) {
                return EOF;
            }
            try {
                int value = bytes.take();
                if (value == EOF) {
                    eof = true;
                }
                return value;
            }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted = new InterruptedIOException("Interrupted reading stdio test pipe");
                interrupted.initCause(error);
                throw interrupted;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            int first = read();
            if (first == EOF) {
                return EOF;
            }
            buffer[offset] = (byte) first;
            int count = 1;
            while (count < length) {
                Integer next = bytes.poll();
                if (next == null) {
                    break;
                }
                if (next == EOF) {
                    eof = true;
                    break;
                }
                buffer[offset + count] = (byte) (next & 0xff);
                count++;
            }
            return count;
        }
    }

    private static final class QueueOutputStream extends OutputStream {

        private static final int EOF = -1;

        private final BlockingQueue<Integer> bytes;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Set<String> writerThreads = ConcurrentHashMap.newKeySet();

        private QueueOutputStream(BlockingQueue<Integer> bytes) {
            this.bytes = bytes;
        }

        @Override
        public void write(int value) throws IOException {
            if (closed.get()) {
                throw new IOException("stdio test pipe is closed");
            }
            writerThreads.add(Thread.currentThread().getName());
            bytes.add(value & 0xff);
        }

        private Set<String> writerThreads() {
            return Collections.unmodifiableSet(writerThreads);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                bytes.add(EOF);
            }
        }
    }

    private static final class GateOutputStream extends OutputStream {

        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final boolean failAfterRelease;

        private GateOutputStream(boolean failAfterRelease) {
            this.failAfterRelease = failAfterRelease;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] { (byte) value });
        }

        @Override
        public synchronized void write(byte[] buffer, int offset, int length) throws IOException {
            writeStarted.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release test OutputStream");
                }
            }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted =
                    new InterruptedIOException("Interrupted writing test OutputStream");
                interrupted.initCause(error);
                throw interrupted;
            }
            if (failAfterRelease) {
                throw new IOException("Synthetic OutputStream failure");
            }
            delegate.write(buffer, offset, length);
        }

        private boolean awaitWriteStarted(Duration timeout) throws InterruptedException {
            return writeStarted.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        private void release() {
            release.countDown();
        }
    }
}
