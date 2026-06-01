package com.agentmemory.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.util.ObjectBuilder;

@SuppressWarnings({"unchecked", "rawtypes"})
class LifecycleCoordinatorTest {

    private ElasticsearchClient client;
    private LifecycleCoordinator coordinator;

    @BeforeEach
    void setUp() {
        client = mock(ElasticsearchClient.class);
        coordinator = new LifecycleCoordinator(client);
    }

    @Test
    void acquireLease_succeeds_when_index_is_empty() throws Exception {
        // Mock: document not found
        GetResponse<Map> notFound = mock(GetResponse.class);
        when(notFound.found()).thenReturn(false);
        when(client.get(any(Function.class), eq(Map.class))).thenReturn(notFound);

        // Mock: index (create) succeeds
        IndexResponse indexResponse = mock(IndexResponse.class);
        when(client.index(any(Function.class))).thenReturn(indexResponse);

        boolean result = coordinator.acquireLease("test-job", Duration.ofMinutes(10));

        assertTrue(result, "Expected acquireLease to return true when no existing doc");
        verify(client).index(any(Function.class));
    }

    @Test
    void acquireLease_fails_when_lease_held_by_other_owner() throws Exception {
        long futureMs = Instant.now().plusSeconds(600).toEpochMilli();
        Map<String, Object> src = Map.of(
                "jobName", "test-job",
                "leaseOwner", "some-other-instance-uuid",
                "leaseUntil", futureMs
        );

        GetResponse<Map> existing = mock(GetResponse.class);
        when(existing.found()).thenReturn(true);
        when(existing.source()).thenReturn(src);
        when(client.get(any(Function.class), eq(Map.class))).thenReturn(existing);

        boolean result = coordinator.acquireLease("test-job", Duration.ofMinutes(10));

        assertFalse(result, "Expected acquireLease to return false when lease held by other owner");
        verify(client, never()).index(any(Function.class));
    }

    @Test
    void acquireLease_succeeds_when_lease_expired() throws Exception {
        long pastMs = Instant.now().minusSeconds(60).toEpochMilli();
        Map<String, Object> src = Map.of(
                "jobName", "test-job",
                "leaseOwner", "expired-owner-uuid",
                "leaseUntil", pastMs
        );

        GetResponse<Map> existing = mock(GetResponse.class);
        when(existing.found()).thenReturn(true);
        when(existing.source()).thenReturn(src);
        when(existing.seqNo()).thenReturn(7L);
        when(existing.primaryTerm()).thenReturn(1L);
        when(client.get(any(Function.class), eq(Map.class))).thenReturn(existing);

        // CAS update (index with ifSeqNo/ifPrimaryTerm) succeeds
        IndexResponse indexResponse = mock(IndexResponse.class);
        when(client.index(any(Function.class))).thenReturn(indexResponse);

        boolean result = coordinator.acquireLease("test-job", Duration.ofMinutes(10));

        assertTrue(result, "Expected acquireLease to return true when taking over expired lease");
        verify(client).index(any(Function.class));
    }

    @Test
    void releaseLease_clears_leaseUntil() throws Exception {
        UpdateResponse<Map> updateResponse = mock(UpdateResponse.class);

        ArgumentCaptor<Function> fnCaptor = ArgumentCaptor.forClass(Function.class);
        when(client.update(fnCaptor.capture(), eq(Map.class))).thenReturn(updateResponse);

        coordinator.releaseLease("test-job");

        verify(client).update(any(Function.class), eq(Map.class));

        // Apply the captured lambda to a real builder and inspect the doc
        Function capturedFn = fnCaptor.getValue();
        var builder = new UpdateRequest.Builder<Map<String, Object>, Map<String, Object>>();
        capturedFn.apply(builder);
        UpdateRequest<Map<String, Object>, Map<String, Object>> req =
                (UpdateRequest<Map<String, Object>, Map<String, Object>>) builder.build();

        Map<String, Object> doc = req.doc();
        long leaseUntilMs = ((Number) doc.get("leaseUntil")).longValue();
        assertTrue(leaseUntilMs < Instant.now().toEpochMilli(),
                "Expected leaseUntil to be set to a past timestamp but was: " + leaseUntilMs);
    }
}
