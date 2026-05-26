package com.agentmemory.service.rerank;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.util.StringUtils;

import com.agentmemory.config.RestRerankConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class RestRerankService implements RerankService {
    private final RestRerankConfig config;

    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;

    public RestRerankService(RestRerankConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> documents) {
        var payload = mapper.createObjectNode();
        payload.put("query", query);
        payload.set("documents", mapper.valueToTree(documents));
        payload.put("top_k", documents.size());

        try {
            Request request = new Request.Builder()
                    .url(config.getEndpoint())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(mapper.writeValueAsString(payload), MediaType.parse("application/json")))
                    .build();
            try (var response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return List.of();
                }
                
                var responseBody = response.body() != null ? response.body().string() : "";
                if (!StringUtils.hasLength(responseBody)) {
                    throw new RuntimeException("Empty response from rerank service");
                }

                var resultsNode = mapper.readTree(responseBody).get("results");
                if (resultsNode == null || !resultsNode.isArray()) {
                    throw new RuntimeException(
                            "Invalid response format from rerank service: 'results' field is missing or not an array");
                }

                return mapper.treeToValue(resultsNode, new TypeReference<List<RerankResult>>() {
                });
            } catch (IOException e) {
                throw new RuntimeException("Failed to execute rerank request", e);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize rerank request payload", e);
        }
    }
}
