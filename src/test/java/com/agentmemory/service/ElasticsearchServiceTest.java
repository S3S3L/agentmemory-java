package com.agentmemory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.agentmemory.config.MemoryProperties;
import com.agentmemory.model.MemorySearchRequest;
import com.agentmemory.model.MemoryTier;
import com.agentmemory.service.embed.EmbeddingService;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.util.ObjectBuilder;

class ElasticsearchServiceTest {

    @Test
    void search_fallsBackToBm25WhenVectorSearchFails() throws IOException {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        MemoryProperties props = new MemoryProperties();
        props.setTopKBm25(5);
        props.setTopKVector(5);
        props.setTopKFinal(5);
        props.setRrfK(60);

        ElasticsearchService service = new ElasticsearchService(client, props, embeddingService, "test-observations");

        when(embeddingService.isAvailable()).thenReturn(true);

        @SuppressWarnings("unchecked")
        SearchResponse<Map<String, Object>> bm25Response = mock(SearchResponse.class);
        @SuppressWarnings("unchecked")
        HitsMetadata<Map<String, Object>> bm25Hits = mock(HitsMetadata.class);
        @SuppressWarnings("unchecked")
        Hit<Map<String, Object>> hit = mock(Hit.class);

        when(bm25Response.hits()).thenReturn(bm25Hits);
        when(bm25Hits.hits()).thenReturn(List.of(hit));
        when(hit.id()).thenReturn("obs-1");
        when(hit.score()).thenReturn(1.25d);
        when(hit.source()).thenReturn(Map.of(
            "content", "JWT authentication flow",
            "tier", MemoryTier.WORKING.name(),
            "sessionId", "session-1",
            "toolName", "Read",
            "filePath", "src/auth.ts"
        ));

        when(client.search(
            anySearchRequestBuilder(),
            eq(mapDocumentClass())
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> fn = invocation.getArgument(0);
            SearchRequest request = fn.apply(new SearchRequest.Builder()).build();
            if (request.knn() != null && !request.knn().isEmpty()) {
                throw new IOException("knn unsupported");
            }
            return bm25Response;
        });

        var results = service.search(
            new MemorySearchRequest("authentication", null, null, null, 5, null),
            new float[] {0.1f, 0.2f, 0.3f}
        );

        assertEquals(1, results.size());
        assertEquals("obs-1", results.getFirst().id());
        assertEquals("JWT authentication flow", results.getFirst().content());
        assertEquals(MemoryTier.WORKING, results.getFirst().tier());
        assertNotNull(results.getFirst().score());
        verify(client, times(2)).search(anySearchRequestBuilder(), eq(mapDocumentClass()));
    }

    @SuppressWarnings("unchecked")
    private static Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> anySearchRequestBuilder() {
        return any(Function.class);
    }

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapDocumentClass() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }
}
