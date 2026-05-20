package com.agentmemory.service;

import com.agentmemory.config.DashScopeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class DashScopeRerankService implements RerankService {

    private static final Logger log = LoggerFactory.getLogger(DashScopeRerankService.class);
    private static final String RERANK_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final DashScopeConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;
    private final boolean available;

    public DashScopeRerankService(DashScopeConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        this.mapper = new ObjectMapper();
        this.available = config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> documents) {
        if (documents.isEmpty() || !available) {
            return List.of();
        }

        try {
            var inputNode = mapper.createObjectNode();
            inputNode.put("query", query);
            inputNode.set("documents", mapper.valueToTree(documents));
            var payload = mapper.createObjectNode();
            payload.put("model", config.getRerankModel());
            payload.set("input", inputNode);

            Request request = new Request.Builder()
                .url(RERANK_URL)
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(payload), MediaType.parse("application/json")))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("DashScope rerank failed: {} {}", response.code(), response.body() != null ? response.body().string() : "");
                    return List.of();
                }
                JsonNode root = mapper.readTree(response.body().string());
                var results = new ArrayList<RerankResult>();
                for (JsonNode r : root.path("output").path("results")) {
                    results.add(new RerankResult(
                        r.path("index").asInt(),
                        r.path("relevance_score").asDouble()
                    ));
                }
                return results;
            }
        } catch (Exception e) {
            log.error("DashScope rerank error", e);
            return List.of();
        }
    }
}
