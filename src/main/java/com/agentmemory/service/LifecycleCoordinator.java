package com.agentmemory.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.OpType;
import co.elastic.clients.elasticsearch.core.GetResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.agentmemory.model.LifecycleJobState;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Distributed lifecycle lease coordinator backed by Elasticsearch.
 *
 * Each JVM instance has a unique {@code instanceId}. Before running a lifecycle
 * job, a caller must acquire a lease via {@link #acquireLease}. If another instance
 * already holds a valid (non-expired) lease, the call returns {@code false} and
 * the caller should skip the job. Expired leases can be taken over via
 * optimistic-concurrency (seqNo / primaryTerm) CAS.
 */
@Service
public class LifecycleCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LifecycleCoordinator.class);

    static final String LIFECYCLE_INDEX = "memory-lifecycle-state";

    private static final String LIFECYCLE_INDEX_MAPPING = """
            {
              "settings": {"number_of_shards": 1, "number_of_replicas": 0},
              "mappings": {
                "properties": {
                  "jobName":        {"type": "keyword"},
                  "leaseOwner":     {"type": "keyword"},
                  "leaseUntil":     {"type": "long"},
                  "lastStartedAt":  {"type": "long"},
                  "lastCompletedAt":{"type": "long"},
                  "lastError":      {"type": "text"}
                }
              }
            }""";

    private final ElasticsearchClient client;
    final String instanceId = UUID.randomUUID().toString();

    /** Returns the unique identifier for this JVM instance. */
    public String getInstanceId() { return instanceId; }

    public LifecycleCoordinator(ElasticsearchClient client) {
        this.client = client;
    }

    /**
     * Try to acquire a distributed lease for the given job.
     *
     * @param jobName job identifier (used as ES document ID)
     * @param ttl     how long the lease should be valid
     * @return {@code true} if this instance now holds the lease, {@code false} otherwise
     */
    @SuppressWarnings("unchecked")
    public boolean acquireLease(String jobName, Duration ttl) {
        try {
            Instant now = Instant.now();
            Instant leaseExpiry = now.plus(ttl);

            GetResponse<Map> existing;
            try {
                existing = client.get(g -> g.index(LIFECYCLE_INDEX).id(jobName), Map.class);
            } catch (ElasticsearchException e) {
                if (!isIndexNotFound(e)) throw e;
                // Index missing (never created, or dropped out from under us) — self-heal and retry.
                ensureIndexExists();
                return tryCreate(jobName, leaseExpiry, now);
            }

            if (!existing.found()) {
                return tryCreate(jobName, leaseExpiry, now);
            }

            Map<String, Object> src = (Map<String, Object>) existing.source();
            if (src == null) {
                return tryCreate(jobName, leaseExpiry, now);
            }

            Long leaseUntilMs = toLong(src.get("leaseUntil"));
            String owner = (String) src.get("leaseOwner");

            if (leaseUntilMs != null && leaseUntilMs > now.toEpochMilli() && !instanceId.equals(owner)) {
                log.debug("Lease for {} held by {} until {}", jobName, owner,
                        Instant.ofEpochMilli(leaseUntilMs));
                return false;
            }

            // Expired or we own it — attempt CAS update
            return tryCasUpdate(jobName, leaseExpiry, now, existing.seqNo(), existing.primaryTerm());

        } catch (Exception e) {
            log.error("acquireLease failed for job {}: {}", jobName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Release the lease by setting leaseUntil to a past timestamp.
     */
    public void releaseLease(String jobName) {
        try {
            client.update(u -> u
                    .index(LIFECYCLE_INDEX)
                    .id(jobName)
                    .doc(Map.of("leaseUntil", Instant.now().minusSeconds(1).toEpochMilli())),
                    Map.class);
            log.debug("Released lease for job {}", jobName);
        } catch (Exception e) {
            log.warn("Failed to release lease for {}: {}", jobName, e.getMessage());
        }
    }

    /**
     * Record that this job has started (updates lastStartedAt in ES).
     */
    public void recordJobStart(String jobName) {
        try {
            client.update(u -> u
                    .index(LIFECYCLE_INDEX)
                    .id(jobName)
                    .doc(Map.of("lastStartedAt", Instant.now().toEpochMilli())),
                    Map.class);
        } catch (Exception e) {
            log.warn("Failed to record job start for {}: {}", jobName, e.getMessage());
        }
    }

    /**
     * Record job completion (updates lastCompletedAt, lastError) and release the lease.
     *
     * @param jobName job identifier
     * @param error   error message if the job failed, {@code null} on success
     */
    public void recordJobComplete(String jobName, String error) {
        try {
            Map<String, Object> update = new HashMap<>();
            update.put("lastCompletedAt", Instant.now().toEpochMilli());
            update.put("leaseUntil", Instant.now().minusSeconds(1).toEpochMilli());
            if (error != null) {
                update.put("lastError", error);
            }
            client.update(u -> u
                    .index(LIFECYCLE_INDEX)
                    .id(jobName)
                    .doc(update),
                    Map.class);
            log.debug("Recorded completion for job {} (error={})", jobName, error);
        } catch (Exception e) {
            log.warn("Failed to record job complete for {}: {}", jobName, e.getMessage());
        }
    }

    /**
     * Return the current persisted state for a job, if present.
     */
    @SuppressWarnings("unchecked")
    public Optional<LifecycleJobState> getJobState(String jobName) {
        try {
            var response = client.get(g -> g.index(LIFECYCLE_INDEX).id(jobName), Map.class);
            if (!response.found()) return Optional.empty();

            Map<String, Object> src = (Map<String, Object>) response.source();
            if (src == null) return Optional.empty();

            LifecycleJobState state = new LifecycleJobState();
            state.setJobName((String) src.get("jobName"));
            state.setLeaseOwner((String) src.get("leaseOwner"));

            Long leaseUntilMs = toLong(src.get("leaseUntil"));
            if (leaseUntilMs != null) state.setLeaseUntil(Instant.ofEpochMilli(leaseUntilMs));

            Long lastStartedMs = toLong(src.get("lastStartedAt"));
            if (lastStartedMs != null) state.setLastStartedAt(Instant.ofEpochMilli(lastStartedMs));

            Long lastCompletedMs = toLong(src.get("lastCompletedAt"));
            if (lastCompletedMs != null) state.setLastCompletedAt(Instant.ofEpochMilli(lastCompletedMs));

            state.setLastError((String) src.get("lastError"));
            return Optional.of(state);
        } catch (Exception e) {
            log.warn("Failed to get job state for {}: {}", jobName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Create the lifecycle state index if it doesn't already exist. Safe to call
     * concurrently from multiple instances — a racing "already exists" create is ignored.
     */
    public void ensureIndexExists() throws IOException {
        boolean exists = client.indices().exists(e -> e.index(LIFECYCLE_INDEX)).value();
        if (exists) return;

        log.info("Creating lifecycle state index: {}", LIFECYCLE_INDEX);
        try {
            client.indices().create(c -> c
                    .index(LIFECYCLE_INDEX)
                    .withJson(new ByteArrayInputStream(LIFECYCLE_INDEX_MAPPING.getBytes())));
            log.info("Lifecycle state index created: {}", LIFECYCLE_INDEX);
        } catch (ElasticsearchException e) {
            if (e.status() != 400) throw e; // 400 == another instance created it concurrently
        }
    }

    private boolean isIndexNotFound(ElasticsearchException e) {
        return e.status() == 404 && e.error() != null
                && "index_not_found_exception".equals(e.error().type());
    }

    // ---- private helpers ----

    private boolean tryCreate(String jobName, Instant leaseExpiry, Instant now) throws IOException {
        try {
            Map<String, Object> doc = buildDoc(jobName, leaseExpiry, now);
            client.index(i -> i
                    .index(LIFECYCLE_INDEX)
                    .id(jobName)
                    .document(doc)
                    .opType(OpType.Create));
            log.debug("Acquired new lease for job {}", jobName);
            return true;
        } catch (ElasticsearchException e) {
            if (e.status() == 409) {
                // Another instance created the doc concurrently
                log.debug("Race condition creating lease for {}, failed to acquire", jobName);
                return false;
            }
            throw e;
        }
    }

    private boolean tryCasUpdate(String jobName, Instant leaseExpiry, Instant now,
                                 Long seqNo, Long primaryTerm) throws IOException {
        try {
            Map<String, Object> doc = buildDoc(jobName, leaseExpiry, now);
            client.index(i -> {
                var b = i.index(LIFECYCLE_INDEX).id(jobName).document(doc);
                if (seqNo != null) b = b.ifSeqNo(seqNo);
                if (primaryTerm != null) b = b.ifPrimaryTerm(primaryTerm);
                return b;
            });
            log.debug("Acquired/renewed lease for job {} (seqNo={})", jobName, seqNo);
            return true;
        } catch (ElasticsearchException e) {
            if (e.status() == 409) {
                log.debug("CAS failed for lease on job {}", jobName);
                return false;
            }
            throw e;
        }
    }

    private Map<String, Object> buildDoc(String jobName, Instant leaseExpiry, Instant now) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("jobName", jobName);
        doc.put("leaseOwner", instanceId);
        doc.put("leaseUntil", leaseExpiry.toEpochMilli());
        doc.put("lastStartedAt", now.toEpochMilli());
        return doc;
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        return null;
    }
}
