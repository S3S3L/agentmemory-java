package com.agentmemory.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DashScopeEmbeddingServiceTest {

    private DashScopeEmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        var config = new com.agentmemory.config.DashScopeConfig();
        config.setApiKey("");
        config.setEmbeddingDimensions(1024);
        embeddingService = new DashScopeEmbeddingService(config);
    }

    @Test
    void fallbackEmbed_returnsCorrectDimensions() {
        var config = new com.agentmemory.config.DashScopeConfig();
        config.setEmbeddingDimensions(1024);
        embeddingService = new DashScopeEmbeddingService(config);

        float[] result = embeddingService.embed("test text");
        assertEquals(1024, result.length);
    }

    @Test
    void fallbackEmbed_isDeterministic() {
        float[] a = embeddingService.embed("hello world");
        float[] b = embeddingService.embed("hello world");
        assertArrayEquals(a, b);
    }

    @Test
    void fallbackEmbed_differentText_differentVectors() {
        float[] a = embeddingService.embed("hello world");
        float[] b = embeddingService.embed("goodbye world");

        double distance = 0;
        for (int i = 0; i < a.length; i++) {
            distance += Math.pow(a[i] - b[i], 2);
        }
        assertTrue(distance > 0, "Different texts should produce different vectors");
    }

    @Test
    void fallbackEmbed_isUnitVector() {
        float[] result = embeddingService.embed("test text");
        float norm = 0;
        for (float v : result) norm += v * v;
        norm = (float) Math.sqrt(norm);

        assertEquals(1.0f, norm, 0.01f, "Should be a unit vector");
    }

    @Test
    void fallbackEmbed_blankText_returnsUnitVector() {
        float[] result = embeddingService.embed("");
        float norm = 0;
        for (float v : result) norm += v * v;
        norm = (float) Math.sqrt(norm);

        assertEquals(1.0f, norm, 0.01f, "Blank text should still produce unit vector");
    }

    @Test
    void embed_null_returnsFallback() {
        float[] result = embeddingService.embed(null);
        assertNotNull(result);
        assertEquals(1024, result.length);
    }

    @Test
    void fallbackEmbed_differentDimensions() {
        var config = new com.agentmemory.config.DashScopeConfig();
        config.setEmbeddingDimensions(512);
        var service = new DashScopeEmbeddingService(config);

        float[] result = service.embed("test");
        assertEquals(512, result.length);
    }

    @Test
    void dimensions_returnsConfiguredValue() {
        var config = new com.agentmemory.config.DashScopeConfig();
        config.setEmbeddingDimensions(768);
        embeddingService = new DashScopeEmbeddingService(config);

        assertEquals(768, embeddingService.dimensions());
    }

    @Test
    void isAvailable_false_withoutApiKey() {
        var config = new com.agentmemory.config.DashScopeConfig();
        config.setApiKey("");
        embeddingService = new DashScopeEmbeddingService(config);

        assertFalse(embeddingService.isAvailable());
    }
}
