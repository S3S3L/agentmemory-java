package com.agentmemory.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Stdio MCP transport with concurrent inbound dispatch and a bounded, single-writer
 * outbound path.
 */
public final class SingleWriterStdioTransportProvider implements McpServerTransportProvider {

    public static final int DEFAULT_OUTBOUND_QUEUE_CAPACITY = 256;

    private static final Logger log = LoggerFactory.getLogger(SingleWriterStdioTransportProvider.class);
    private static final AtomicInteger TRANSPORT_IDS = new AtomicInteger();

    private final ObjectMapper objectMapper;
    private final ObjectWriter compactObjectWriter;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final int outboundQueueCapacity;
    private final AtomicBoolean sessionFactorySet = new AtomicBoolean();

    private volatile McpServerSession session;
    private volatile StdioSessionTransport transport;

    public SingleWriterStdioTransportProvider(ObjectMapper objectMapper) {
        this(objectMapper, System.in, System.out);
    }

    public SingleWriterStdioTransportProvider(
            ObjectMapper objectMapper, InputStream inputStream, OutputStream outputStream) {
        this(objectMapper, inputStream, outputStream, DEFAULT_OUTBOUND_QUEUE_CAPACITY);
    }

    public SingleWriterStdioTransportProvider(
            ObjectMapper objectMapper,
            InputStream inputStream,
            OutputStream outputStream,
            int outboundQueueCapacity) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.compactObjectWriter = objectMapper.writer().without(SerializationFeature.INDENT_OUTPUT);
        this.inputStream = Objects.requireNonNull(inputStream, "inputStream must not be null");
        this.outputStream = Objects.requireNonNull(outputStream, "outputStream must not be null");
        if (outboundQueueCapacity <= 0) {
            throw new IllegalArgumentException("outboundQueueCapacity must be greater than zero");
        }
        this.outboundQueueCapacity = outboundQueueCapacity;
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        Objects.requireNonNull(sessionFactory, "sessionFactory must not be null");
        if (!sessionFactorySet.compareAndSet(false, true)) {
            throw new IllegalStateException("stdio transport supports exactly one MCP session");
        }

        StdioSessionTransport sessionTransport = new StdioSessionTransport();
        McpServerSession createdSession = sessionFactory.create(sessionTransport);
        this.transport = sessionTransport;
        this.session = createdSession;
        sessionTransport.start(createdSession);
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        McpServerSession currentSession = this.session;
        if (currentSession == null) {
            return Mono.error(new McpError("No stdio session is available"));
        }
        return currentSession.sendNotification(method, params)
            .doOnError(error -> log.error("Failed to send stdio notification {}", method, error));
    }

    @Override
    public Mono<Void> closeGracefully() {
        StdioSessionTransport currentTransport = this.transport;
        return currentTransport == null ? Mono.empty() : currentTransport.closeGracefully();
    }

    @Override
    public void close() {
        StdioSessionTransport currentTransport = this.transport;
        if (currentTransport != null) {
            currentTransport.close();
        }
    }

    private final class StdioSessionTransport implements McpServerTransport {

        private final int transportId = TRANSPORT_IDS.incrementAndGet();
        private final ArrayBlockingQueue<OutboundMessage> outboundQueue =
            new ArrayBlockingQueue<>(outboundQueueCapacity);
        private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor(
            runnable -> daemonThread(runnable, "agentmemory-stdio-reader-" + transportId));
        private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(
            runnable -> daemonThread(runnable, "agentmemory-stdio-writer-" + transportId));
        private final Sinks.Many<McpSchema.JSONRPCMessage> inbound =
            Sinks.many().unicast().onBackpressureBuffer();
        private final Sinks.One<Void> closeResult = Sinks.one();
        private final ReentrantLock lifecycleLock = new ReentrantLock();
        private final AtomicBoolean inboundStopping = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
        private final AtomicReference<OutboundMessage> activeWrite = new AtomicReference<>();

        private volatile boolean acceptingOutbound = true;
        private volatile boolean writerDraining;
        private volatile boolean aborting;
        private volatile Disposable inboundSubscription;

        private void start(McpServerSession currentSession) {
            inboundSubscription = inbound.asFlux()
                .flatMap(currentSession::handle)
                .doOnError(error -> log.error("Stdio inbound processing failed", error))
                .doFinally(signal -> beginWriterDrain())
                .subscribe(ignored -> {
                }, error -> {
                    terminalFailure.compareAndSet(null, error);
                });

            writerExecutor.execute(this::writeLoop);
            readerExecutor.execute(this::readLoop);
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            Objects.requireNonNull(message, "message must not be null");
            return Mono.defer(() -> {
                Sinks.One<Void> completion = Sinks.one();
                OutboundMessage outbound = new OutboundMessage(message, completion);

                lifecycleLock.lock();
                try {
                    Throwable failure = terminalFailure.get();
                    if (failure != null) {
                        return Mono.error(new IllegalStateException(
                            "stdio transport failed and cannot accept outbound messages", failure));
                    }
                    if (!acceptingOutbound) {
                        return Mono.error(new IllegalStateException(
                            "stdio transport is closed and cannot accept outbound messages"));
                    }
                    if (!outboundQueue.offer(outbound)) {
                        return Mono.error(new RejectedExecutionException(
                            "stdio outbound queue is full (capacity=" + outboundQueueCapacity + ")"));
                    }
                }
                finally {
                    lifecycleLock.unlock();
                }

                return completion.asMono();
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
            return objectMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.defer(() -> {
                requestInboundStop();
                return closeResult.asMono();
            });
        }

        @Override
        public void close() {
            abort(new IllegalStateException(
                "stdio transport closed before queued messages were written"));
        }

        private void readLoop() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                while (!inboundStopping.get()) {
                    String line = reader.readLine();
                    if (line == null) {
                        completeInbound();
                        return;
                    }
                    if (inboundStopping.get()) {
                        completeInbound();
                        return;
                    }

                    try {
                        McpSchema.JSONRPCMessage message =
                            McpSchema.deserializeJsonRpcMessage(objectMapper, line);
                        emitInbound(message);
                    }
                    catch (Exception error) {
                        failInbound(new IOException("Invalid JSON-RPC message received over stdio", error));
                        return;
                    }
                }
                completeInbound();
            }
            catch (IOException error) {
                if (inboundStopping.get()) {
                    completeInbound();
                }
                else {
                    failInbound(new IOException("Failed reading JSON-RPC message from stdin", error));
                }
            }
            catch (Throwable error) {
                failInbound(error);
            }
            finally {
                readerExecutor.shutdown();
            }
        }

        private void emitInbound(McpSchema.JSONRPCMessage message) {
            synchronized (inbound) {
                Sinks.EmitResult result = inbound.tryEmitNext(message);
                if (result.isFailure() && result != Sinks.EmitResult.FAIL_TERMINATED) {
                    failInbound(new IllegalStateException(
                        "Failed to dispatch inbound stdio message: " + result));
                }
            }
        }

        private void completeInbound() {
            synchronized (inbound) {
                inbound.tryEmitComplete();
            }
        }

        private void failInbound(Throwable error) {
            if (inboundStopping.compareAndSet(false, true)) {
                terminalFailure.compareAndSet(null, error);
                log.error("Stdio inbound reader failed", error);
            }
            synchronized (inbound) {
                inbound.tryEmitError(error);
            }
        }

        private void requestInboundStop() {
            if (inboundStopping.compareAndSet(false, true)) {
                try {
                    inputStream.close();
                }
                catch (IOException error) {
                    log.warn("Failed to close stdio input during shutdown", error);
                }
                readerExecutor.shutdownNow();
            }
            completeInbound();
        }

        private void beginWriterDrain() {
            lifecycleLock.lock();
            try {
                acceptingOutbound = false;
                writerDraining = true;
            }
            finally {
                lifecycleLock.unlock();
            }
        }

        private void writeLoop() {
            try {
                while (true) {
                    if (aborting) {
                        return;
                    }

                    OutboundMessage outbound;
                    try {
                        outbound = outboundQueue.poll(50, TimeUnit.MILLISECONDS);
                    }
                    catch (InterruptedException error) {
                        if (aborting) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        continue;
                    }

                    if (outbound == null) {
                        if (writerDraining) {
                            return;
                        }
                        continue;
                    }

                    activeWrite.set(outbound);
                    try {
                        writeMessage(outbound.message());
                        outbound.completion().tryEmitEmpty();
                    }
                    catch (Exception error) {
                        outbound.completion().tryEmitError(error);
                        if (!aborting) {
                            failWriter(error);
                        }
                        return;
                    }
                    finally {
                        activeWrite.compareAndSet(outbound, null);
                    }
                }
            }
            finally {
                finishWriter();
            }
        }

        private void writeMessage(McpSchema.JSONRPCMessage message) throws IOException {
            String json = compactObjectWriter.writeValueAsString(message);
            outputStream.write(json.getBytes(StandardCharsets.UTF_8));
            outputStream.write('\n');
            outputStream.flush();
        }

        private void failWriter(Exception error) {
            IOException failure = new IOException("Failed writing JSON-RPC message to stdout", error);
            terminalFailure.compareAndSet(null, failure);
            log.error("Stdio outbound writer failed", failure);

            lifecycleLock.lock();
            try {
                acceptingOutbound = false;
                writerDraining = true;
            }
            finally {
                lifecycleLock.unlock();
            }

            failQueued(failure);
            Disposable subscription = inboundSubscription;
            if (subscription != null) {
                subscription.dispose();
            }
            requestInboundStop();
        }

        private void abort(Throwable reason) {
            if (terminated.get()) {
                return;
            }

            lifecycleLock.lock();
            try {
                acceptingOutbound = false;
                writerDraining = true;
                aborting = true;
            }
            finally {
                lifecycleLock.unlock();
            }

            OutboundMessage current = activeWrite.get();
            if (current != null) {
                current.completion().tryEmitError(reason);
            }
            failQueued(reason);

            Disposable subscription = inboundSubscription;
            if (subscription != null) {
                subscription.dispose();
            }
            requestInboundStop();
            writerExecutor.shutdownNow();
        }

        private void failQueued(Throwable error) {
            OutboundMessage queued;
            while ((queued = outboundQueue.poll()) != null) {
                queued.completion().tryEmitError(error);
            }
        }

        private void finishWriter() {
            Throwable failure = terminalFailure.get();
            if (!outboundQueue.isEmpty()) {
                failQueued(failure != null ? failure : new IllegalStateException(
                    "stdio writer stopped before queued messages were written"));
            }

            acceptingOutbound = false;
            terminated.set(true);
            requestInboundStop();
            writerExecutor.shutdown();

            if (failure != null) {
                closeResult.tryEmitError(failure);
            }
            else {
                closeResult.tryEmitEmpty();
            }
        }
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private record OutboundMessage(
            McpSchema.JSONRPCMessage message, Sinks.One<Void> completion) {
    }
}
