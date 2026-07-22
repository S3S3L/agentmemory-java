package com.agentmemory.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agentmemory.service.ElasticsearchService;
import com.agentmemory.service.MemoryPipelineService;

class MemoryControllerTest {

    @Test
    void forgetReturnsDeletedStatus() throws IOException {
        ElasticsearchService esService = mock(ElasticsearchService.class);
        when(esService.deleteMemory("memory-1"))
            .thenReturn(ElasticsearchService.DeleteResult.DELETED);
        MemoryController controller = new MemoryController(mock(MemoryPipelineService.class), esService);

        var response = controller.forget("memory-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of("status", "deleted", "id", "memory-1"), response.getBody());
    }

    @Test
    void forgetReturnsNotFoundStatus() throws IOException {
        ElasticsearchService esService = mock(ElasticsearchService.class);
        when(esService.deleteMemory("missing-memory"))
            .thenReturn(ElasticsearchService.DeleteResult.NOT_FOUND);
        MemoryController controller = new MemoryController(mock(MemoryPipelineService.class), esService);

        var response = controller.forget("missing-memory");

        assertEquals(404, response.getStatusCode().value());
        assertEquals(Map.of("status", "not_found", "id", "missing-memory"), response.getBody());
    }
}
