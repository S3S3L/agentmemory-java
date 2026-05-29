package com.agentmemory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.agentmemory.config.MemoryProperties;
import com.agentmemory.model.MemoryTier;
import com.agentmemory.model.SearchResult;
import com.agentmemory.service.embed.EmbeddingService;
import com.agentmemory.service.rerank.RerankService;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.util.ObjectBuilder;

class AccessTrackingTest {

    @Test
    void bulkUpdateAccessStats_sendsScriptedUpdatePayload() throws IOException {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        MemoryProperties props = new MemoryProperties();

        BulkResponse bulkResponse = mock(BulkResponse.class);
        when(bulkResponse.errors()).thenReturn(false);

        BulkRequest[] requestHolder = new BulkRequest[1];
        when(client.bulk(anyBulkRequestBuilder())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Function<BulkRequest.Builder, ObjectBuilder<BulkRequest>> fn = inv.getArgument(0);
            requestHolder[0] = fn.apply(new BulkRequest.Builder()).build();
            return bulkResponse;
        });

        ElasticsearchService service = new ElasticsearchService(client, props, embeddingService, "test-observations");
        service.bulkUpdateAccessStats(List.of("id-1", "id-2"));

        assertNotNull(requestHolder[0]);
        assertEquals(2, requestHolder[0].operations().size());

        var op0 = requestHolder[0].operations().get(0);
        assertTrue(op0.isUpdate());
        assertEquals("id-1", op0.update().id());
        assertEquals("test-observations", op0.update().index());

        String src = op0.update().action().script().source().scriptString();
        assertTrue(src.contains("accessCount"), "Script must update accessCount");
        assertTrue(src.contains("lastAccessed"), "Script must update lastAccessed");
        assertTrue(op0.update().action().script().params().containsKey("ts"),
            "Script params must contain ts");

        var op1 = requestHolder[0].operations().get(1);
        assertTrue(op1.isUpdate());
        assertEquals("id-2", op1.update().id());
    }

    @Test
    void bulkUpdateAccessStats_emptyList_doesNothing() throws IOException {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        MemoryProperties props = new MemoryProperties();

        ElasticsearchService service = new ElasticsearchService(client, props, embeddingService, "test-observations");
        service.bulkUpdateAccessStats(List.of());

        verifyNoInteractions(client);
    }

    @Test
    void recall_returnsResultsWhenAccessUpdateFails() throws IOException {
        ElasticsearchService esService = mock(ElasticsearchService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        RerankService rerankService = mock(RerankService.class);

        MemoryProperties props = new MemoryProperties();
        props.setTopKFinal(5);
        props.setMinRerankScore(-5.0);

        SearchResult result = new SearchResult("id-1", "some content", MemoryTier.WORKING, "s1", "Read", null, 1.0, 1.0, 0.0, 0.0);
        when(embeddingService.embed(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(embeddingService.isAvailable()).thenReturn(true);
        when(esService.search(any(), any())).thenReturn(List.of(result));
        doThrow(new RuntimeException("ES connection refused")).when(esService).bulkUpdateAccessStats(any());

        MemoryPipelineService pipeline = new MemoryPipelineService(esService, embeddingService, rerankService, props);

        List<SearchResult> results = pipeline.recall("some query", null, null);

        assertEquals(1, results.size());
        assertEquals("id-1", results.getFirst().id());
        assertEquals("some content", results.getFirst().content());
    }

    @SuppressWarnings("unchecked")
    private static Function<BulkRequest.Builder, ObjectBuilder<BulkRequest>> anyBulkRequestBuilder() {
        return any(Function.class);
    }
}
