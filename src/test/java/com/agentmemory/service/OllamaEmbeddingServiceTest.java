package com.agentmemory.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agentmemory.config.OllamaConfig;
import com.agentmemory.service.embed.OllamaEmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class OllamaEmbeddingServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void embed_usesApiEmbedAndParsesFirstEmbedding() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/embed", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] body = """
                {
                  "model": "nomic-embed-text",
                  "embeddings": [[0.25, -0.5, 0.75]]
                }
                """.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        var config = new OllamaConfig();
        config.setEndpoint("http://localhost:" + server.getAddress().getPort());
        config.setEmbeddingModel("nomic-embed-text");
        config.setDimensions(512);

        var service = new OllamaEmbeddingService(config);

        float[] embedding = service.embed("hello world");

        assertEquals("/api/embed", requestPath.get());
        assertArrayEquals(new float[] {0.25f, -0.5f, 0.75f}, embedding);

        var payload = mapper.readTree(requestBody.get());
        assertEquals("nomic-embed-text", payload.get("model").asText());
        assertEquals("hello world", payload.get("input").asText());
        assertEquals(512, payload.get("dimensions").asInt());
    }
}