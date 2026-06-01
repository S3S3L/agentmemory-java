package com.agentmemory.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.agentmemory.config.MemoryProperties;
import com.agentmemory.model.ConsolidatedArtifact;
import com.agentmemory.model.MemoryTier;
import com.agentmemory.model.SearchResult;
import com.agentmemory.service.embed.EmbeddingService;
import com.agentmemory.service.rerank.RerankService;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

@SuppressWarnings({"unchecked", "rawtypes"})
class ConsolidationArtifactTest {

    private ElasticsearchClient esClient;
    private ElasticsearchService esService;
    private OllamaService ollamaService;
    private EmbeddingService embeddingService;
    private MemoryProperties props;

    @BeforeEach
    void setUp() throws Exception {
        esClient = mock(ElasticsearchClient.class);
        esService = mock(ElasticsearchService.class);
        ollamaService = mock(OllamaService.class);
        embeddingService = mock(EmbeddingService.class);
        props = new MemoryProperties();
        props.setTopKFinal(10);
        props.setMinRerankScore(-5.0);

        when(ollamaService.generateSummary(anyString())).thenReturn("Generated summary text");
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(embeddingService.isAvailable()).thenReturn(true);
    }

    // -----------------------------------------------------------------------
    // 1. consolidateToArtifacts_producesIdempotentArtifact
    // -----------------------------------------------------------------------
    @Test
    void consolidateToArtifacts_producesIdempotentArtifact() throws Exception {
        // First call: artifact does not exist
        when(esService.existsConsolidatedArtifact(anyString())).thenReturn(false);

        MemoryConsolidationService service = new MemoryConsolidationService(
                esClient, null, props, ollamaService, embeddingService, esService);

        List<Map<String, Object>> sources = List.of(
                Map.of("id", "id1", "content", "obs content 1", "sessionId", "sess1",
                       "tags", new ArrayList<>(List.of("tag1"))),
                Map.of("id", "id2", "content", "obs content 2", "sessionId", "sess1",
                       "tags", new ArrayList<>(List.of("tag2")))
        );

        ConsolidatedArtifact artifact1 = service.consolidateToArtifacts(sources, "EPISODIC");

        // Verify artifact was produced
        assertNotNull(artifact1, "Expected a consolidated artifact to be produced");
        assertEquals("EPISODIC", artifact1.getTier());
        assertEquals("consolidation-v1", artifact1.getGeneratedBy());
        assertEquals(List.of("id1", "id2"), artifact1.getSourceIds());

        // Verify deterministic ID: sha256("id1,id2")
        String expectedId = MemoryConsolidationService.sha256("id1,id2");
        assertEquals(expectedId, artifact1.getId(), "Artifact ID must be sha256 of sorted joined source IDs");

        // First upsert was called once
        verify(esService, times(1)).upsertConsolidatedArtifact(any(ConsolidatedArtifact.class));

        // Second call with same sources → same deterministic ID → exists check returns true
        when(esService.existsConsolidatedArtifact(expectedId)).thenReturn(true);
        ConsolidatedArtifact artifact2 = service.consolidateToArtifacts(sources, "EPISODIC");

        // Second call returns null (idempotent skip)
        assertNull(artifact2, "Second call with same sources should be a no-op (idempotent)");

        // Upsert still called only once total
        verify(esService, times(1)).upsertConsolidatedArtifact(any(ConsolidatedArtifact.class));
    }

    // -----------------------------------------------------------------------
    // 2. consolidateToArtifacts_skipsUpsert_when_artifact_already_exists
    // -----------------------------------------------------------------------
    @Test
    void consolidateToArtifacts_skipsUpsert_when_artifact_already_exists() throws Exception {
        // ES already has this artifact
        when(esService.existsConsolidatedArtifact(anyString())).thenReturn(true);

        MemoryConsolidationService service = new MemoryConsolidationService(
                esClient, null, props, ollamaService, embeddingService, esService);

        List<Map<String, Object>> sources = List.of(
                Map.of("id", "idA", "content", "some content", "sessionId", "s1",
                       "tags", new ArrayList<>(List.of("x")))
        );

        ConsolidatedArtifact result = service.consolidateToArtifacts(sources, "SEMANTIC");

        // Should return null (skip)
        assertNull(result, "Should return null when artifact already exists");

        // upsert must NOT be called
        verify(esService, never()).upsertConsolidatedArtifact(any());
        // Ollama summary generation and embedding should also be skipped
        verify(ollamaService, never()).generateSummary(anyString());
        verify(embeddingService, never()).embed(anyString());
    }

    // -----------------------------------------------------------------------
    // 3. recall_includesConsolidatedArtifacts
    // -----------------------------------------------------------------------
    @Test
    void recall_includesConsolidatedArtifacts() throws Exception {
        RerankService rerankService = null; // no reranking in this test

        SearchResult obsResult = new SearchResult(
                "obs-1", "observation content", MemoryTier.WORKING,
                "sess1", "Read", null, 1.0, 1.0, 0.0, 0.0);
        SearchResult consolidatedResult = new SearchResult(
                "cons-1", "consolidated summary", MemoryTier.EPISODIC,
                "sess1", null, null, 0.8, 0.0, 0.8, 0.0);

        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(embeddingService.isAvailable()).thenReturn(true);
        when(esService.search(any(), any())).thenReturn(List.of(obsResult));
        when(esService.searchConsolidated(any(), anyString(), anyInt()))
                .thenReturn(List.of(consolidatedResult));
        doNothing().when(esService).bulkUpdateAccessStats(any());

        MemoryPipelineService pipeline = new MemoryPipelineService(
                esService, embeddingService, rerankService, props);

        List<SearchResult> results = pipeline.recall("test query", null, null);

        assertEquals(2, results.size(), "Expected both observation and consolidated results");
        assertTrue(results.stream().anyMatch(r -> "obs-1".equals(r.id())),
                "obs-1 should be present");
        assertTrue(results.stream().anyMatch(r -> "cons-1".equals(r.id())),
                "cons-1 (consolidated) should be present");

        // Results should be sorted by score (obs-1 score=1.0 before cons-1 score=0.8)
        assertEquals("obs-1", results.get(0).id(), "Higher-score result should come first");
        assertEquals("cons-1", results.get(1).id());
    }
}
