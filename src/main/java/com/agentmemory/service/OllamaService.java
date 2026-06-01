package com.agentmemory.service;

import com.agentmemory.config.OllamaConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Client for Ollama text generation via {@code /api/generate}.
 * Used by consolidation to produce EPISODIC/SEMANTIC summary text.
 */
public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);
    private static final MediaType JSON = MediaType.parse("application/json");

    private final String endpoint;
    private final String summaryModel;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;

    public OllamaService(String endpoint, String summaryModel) {
        this.endpoint = endpoint;
        this.summaryModel = summaryModel;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
        this.mapper = new ObjectMapper();
    }

    public OllamaService(OllamaConfig config, String summaryModel) {
        this(config.getEndpoint(), summaryModel);
    }

    /**
     * Calls {@code /api/generate} with the given prompt and returns the generated text.
     */
    public String generateSummary(String prompt) throws IOException {
        var payload = mapper.createObjectNode();
        payload.put("model", summaryModel);
        payload.put("prompt", prompt);
        payload.put("stream", false);

        Request request = new Request.Builder()
                .url(endpoint + "/api/generate")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(payload), JSON))
                .build();

        try (var response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Ollama generate failed: " + response.code() + " " + response.message());
            }
            String body = response.body() != null ? response.body().string() : "";
            var root = mapper.readTree(body);
            var responseNode = root.get("response");
            if (responseNode == null) {
                throw new IOException("Invalid Ollama generate response: missing 'response' field. Body: " + body);
            }
            return responseNode.asText().strip();
        }
    }
}
