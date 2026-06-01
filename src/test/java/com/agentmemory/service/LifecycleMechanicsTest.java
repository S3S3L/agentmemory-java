package com.agentmemory.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.agentmemory.config.MemoryProperties;
import com.agentmemory.model.MemorySearchRequest;
import com.agentmemory.service.embed.EmbeddingService;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.util.ObjectBuilder;

@SuppressWarnings({"unchecked", "rawtypes"})
class LifecycleMechanicsTest {

    private ElasticsearchClient esClient;
    private MemoryProperties memProps;

    @BeforeEach
    void setUp() {
        esClient = mock(ElasticsearchClient.class);
        memProps = new MemoryProperties();
        // use fast thresholds for tests (1-second age = 0 days ago effectively just below threshold)
    }

    // -----------------------------------------------------------------------
    // 1. promotionPagination_processesAllPages
    // -----------------------------------------------------------------------
    @Test
    void promotionPagination_processesAllPages() throws Exception {
        // 3 full pages + 1 empty → 300 docs total, all with accessCount >= 3 (satisfies OR condition)
        String oldTs = Instant.now().minus(2, ChronoUnit.DAYS).toString(); // age > 1 day threshold

        SearchResponse<Map> page1 = buildPageResponse(100, oldTs, 5);
        SearchResponse<Map> page2 = buildPageResponse(100, oldTs, 5);
        SearchResponse<Map> page3 = buildPageResponse(100, oldTs, 5);
        SearchResponse<Map> empty = buildPageResponse(0, oldTs, 0);

        when(esClient.search(any(Function.class), eq(Map.class)))
            .thenReturn(page1)
            .thenReturn(page2)
            .thenReturn(page3)
            .thenReturn(empty);

        UpdateResponse<Map> updateResponse = mock(UpdateResponse.class);
        when(esClient.update(any(Function.class), eq(Map.class))).thenReturn(updateResponse);

        MemoryConsolidationService service = new MemoryConsolidationService(esClient, null, memProps);
        service.promoteToEpisodic();

        // 300 docs across 3 pages → 300 update calls
        verify(esClient, times(300)).update(any(Function.class), eq(Map.class));
        // 4 search calls (3 full pages + 1 empty page)
        verify(esClient, times(4)).search(any(Function.class), eq(Map.class));
    }

    // -----------------------------------------------------------------------
    // 2. tagsPreservation_appendsNotOverwrites
    // -----------------------------------------------------------------------
    @Test
    void tagsPreservation_appendsNotOverwrites() throws Exception {
        // 7 docs with same toolName+filePath → contradiction detected for docs 2-7
        String oldTs = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        SearchResponse<Map> pageWithContradictions = buildContradictionPage(7, oldTs);
        SearchResponse<Map> empty = buildPageResponse(0, oldTs, 0);

        when(esClient.search(any(Function.class), eq(Map.class)))
            .thenReturn(pageWithContradictions)
            .thenReturn(empty);

        ArgumentCaptor<Function> updateCaptor = ArgumentCaptor.forClass(Function.class);
        UpdateResponse<Map> updateResponse = mock(UpdateResponse.class);
        when(esClient.update(updateCaptor.capture(), eq(Map.class))).thenReturn(updateResponse);

        MemoryConsolidationService service = new MemoryConsolidationService(esClient, null, memProps);
        service.detectContradictions();

        // 6 updates (docs 2-7, since group size > 5)
        verify(esClient, atLeast(6)).update(any(Function.class), eq(Map.class));

        // Inspect the captured update requests — must use script, not doc
        List<Function> capturedFns = updateCaptor.getAllValues();
        boolean foundScriptUpdate = false;
        for (Function fn : capturedFns) {
            var builder = new UpdateRequest.Builder<Map, Map>();
            fn.apply(builder);
            UpdateRequest<Map, Map> req = (UpdateRequest<Map, Map>) builder.build();
            if (req.script() != null) {
                foundScriptUpdate = true;
                // Script source should contain tags.add (not overwrite)
                co.elastic.clients.elasticsearch._types.ScriptSource src = req.script().source();
                assertNotNull(src, "Script source must not be null");
                assertTrue(src.isScriptString(), "Expected inline scriptString source");
                String scriptSrc = src.scriptString();
                assertTrue(scriptSrc.contains("tags.add"),
                    "Script should append tag, not overwrite. Got: " + scriptSrc);
            }
        }
        assertTrue(foundScriptUpdate, "Expected at least one scripted update for tag append");
    }

    // -----------------------------------------------------------------------
    // 3. softDelete_setsIsActiveFalse
    // -----------------------------------------------------------------------
    @Test
    void softDelete_setsIsActiveFalse() throws Exception {
        memProps.getDecay().setSoftDelete(true);
        // Use 4-day-old docs → stale for WORKING (3d threshold) and EPISODIC (1d threshold)
        String staleTs = Instant.now().minus(4, ChronoUnit.DAYS).toString();
        SearchResponse<Map> pageWithStale = buildPageResponse(3, staleTs, 1);
        SearchResponse<Map> empty = buildPageResponse(0, staleTs, 0);

        // WORKING: page1(3 stale) → empty; EPISODIC: page1(3 stale) → empty; SEMANTIC/PROCEDURAL: empty
        when(esClient.search(any(Function.class), eq(Map.class)))
            .thenReturn(pageWithStale)
            .thenReturn(empty)
            .thenReturn(pageWithStale)
            .thenReturn(empty)
            .thenReturn(empty)
            .thenReturn(empty);

        ArgumentCaptor<Function> updateCaptor = ArgumentCaptor.forClass(Function.class);
        UpdateResponse<Map> updateResponse = mock(UpdateResponse.class);
        when(esClient.update(updateCaptor.capture(), eq(Map.class))).thenReturn(updateResponse);

        MemoryConsolidationService service = new MemoryConsolidationService(esClient, null, memProps);
        service.evictStaleMemories();

        // Should call update (soft-delete), not delete
        verify(esClient, never()).delete(any(Function.class));
        verify(esClient, atLeast(1)).update(any(Function.class), eq(Map.class));

        // Inspect the updates to verify isActive=false is set
        boolean foundIsActiveFalse = false;
        for (Function fn : updateCaptor.getAllValues()) {
            var builder = new UpdateRequest.Builder<Map, Map>();
            fn.apply(builder);
            UpdateRequest<Map, Map> req = (UpdateRequest<Map, Map>) builder.build();
            if (req.doc() instanceof Map<?, ?> docMap && Boolean.FALSE.equals(docMap.get("isActive"))) {
                foundIsActiveFalse = true;
                break;
            }
        }
        assertTrue(foundIsActiveFalse, "Expected at least one update setting isActive=false");
    }

    // -----------------------------------------------------------------------
    // 4. hardDelete_whenSoftDeleteFalse
    // -----------------------------------------------------------------------
    @Test
    void hardDelete_whenSoftDeleteFalse() throws Exception {
        memProps.getDecay().setSoftDelete(false);
        // 4-day-old docs → stale for WORKING (3d) and EPISODIC (1d default)
        String staleTs = Instant.now().minus(4, ChronoUnit.DAYS).toString();
        SearchResponse<Map> pageWithStale = buildPageResponse(2, staleTs, 1);
        SearchResponse<Map> empty = buildPageResponse(0, staleTs, 0);

        when(esClient.search(any(Function.class), eq(Map.class)))
            .thenReturn(pageWithStale)
            .thenReturn(empty)
            .thenReturn(pageWithStale)
            .thenReturn(empty)
            .thenReturn(empty)
            .thenReturn(empty);

        DeleteResponse deleteResponse = mock(DeleteResponse.class);
        when(esClient.delete(any(Function.class))).thenReturn(deleteResponse);

        MemoryConsolidationService service = new MemoryConsolidationService(esClient, null, memProps);
        service.evictStaleMemories();

        // Should call delete (hard-delete), not update
        verify(esClient, atLeast(1)).delete(any(Function.class));
        verify(esClient, never()).update(any(Function.class), eq(Map.class));
    }

    // -----------------------------------------------------------------------
    // 5. isActiveFilter_excludesInactiveFromRecall
    // -----------------------------------------------------------------------
    @Test
    void isActiveFilter_excludesInactiveFromRecall() throws Exception {
        MemoryProperties props = new MemoryProperties();
        props.setTopKBm25(5);
        props.setTopKVector(5);
        props.setTopKFinal(5);
        props.setRrfK(60);
        props.setMaxResultsPerSession(5);

        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.isAvailable()).thenReturn(false); // BM25 only

        // Capture the BM25 search request
        ArgumentCaptor<Function> searchCaptor = ArgumentCaptor.forClass(Function.class);

        SearchResponse<Map<String, Object>> bm25Response = mock(SearchResponse.class);
        HitsMetadata<Map<String, Object>> bm25Hits = mock(HitsMetadata.class);
        when(bm25Response.hits()).thenReturn(bm25Hits);
        when(bm25Hits.hits()).thenReturn(List.of());

        when(esClient.search(searchCaptor.capture(), eq(mapDocumentClass()))).thenReturn(bm25Response);

        ElasticsearchService service = new ElasticsearchService(
            esClient, props, embeddingService, "test-observations");
        service.search(
            new MemorySearchRequest("test query", null, null, null, 5, null),
            null
        );

        // Inspect the captured search request for isActive filter
        List<Function> captured = searchCaptor.getAllValues();
        assertFalse(captured.isEmpty(), "Expected at least one search call");

        boolean foundIsActiveFilter = false;
        for (Function fn : captured) {
            SearchRequest.Builder builder = new SearchRequest.Builder();
            fn.apply(builder);
            SearchRequest req = builder.build();
            if (req.query() != null && req.query().bool() != null) {
                var mustNot = req.query().bool().mustNot();
                for (var clause : mustNot) {
                    if (clause.term() != null && "isActive".equals(clause.term().field())) {
                        foundIsActiveFilter = true;
                        break;
                    }
                }
            }
            if (foundIsActiveFilter) break;
        }
        assertTrue(foundIsActiveFilter,
            "BM25 search query should include mustNot:{term:{isActive:false}} filter");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builds a mock SearchResponse with {@code count} hits, each old enough to qualify. */
    private SearchResponse<Map> buildPageResponse(int count, String timestamp, int accessCount) {
        SearchResponse<Map> response = mock(SearchResponse.class);
        HitsMetadata<Map> hits = mock(HitsMetadata.class);
        when(response.hits()).thenReturn(hits);

        List<Hit<Map>> hitList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Hit<Map> hit = mock(Hit.class);
            final int idx = i;
            when(hit.id()).thenReturn("doc-" + i);
            when(hit.source()).thenReturn(Map.of(
                "tier", "WORKING",
                "timestamp", timestamp,
                "accessCount", accessCount
            ));
            when(hit.sort()).thenReturn(List.of(FieldValue.of(v -> v.stringValue("doc-" + idx))));
            hitList.add(hit);
        }
        when(hits.hits()).thenReturn((List) hitList);
        return response;
    }

    /** Builds a page where all docs share the same toolName+filePath (triggers contradiction). */
    private SearchResponse<Map> buildContradictionPage(int count, String timestamp) {
        SearchResponse<Map> response = mock(SearchResponse.class);
        HitsMetadata<Map> hits = mock(HitsMetadata.class);
        when(response.hits()).thenReturn(hits);

        List<Hit<Map>> hitList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Hit<Map> hit = mock(Hit.class);
            final int idx = i;
            when(hit.id()).thenReturn("doc-" + i);
            when(hit.source()).thenReturn(Map.of(
                "toolName", "Read",
                "filePath", "src/auth.ts",
                "timestamp", Instant.now().minus(i, ChronoUnit.HOURS).toString(),
                "tags", new ArrayList<>(List.of("existing-tag"))
            ));
            when(hit.sort()).thenReturn(List.of(FieldValue.of(v -> v.stringValue("doc-" + idx))));
            hitList.add(hit);
        }
        when(hits.hits()).thenReturn((List) hitList);
        return response;
    }

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapDocumentClass() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }
}
