package com.agentmemory.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.agentmemory.config.MemoryProperties;
import com.agentmemory.service.embed.EmbeddingService;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;

/**
 * Migrates existing ES indices from one embedding dimension to another.
 *
 * Strategy:
 *   1. Create target index (e.g. memory-observations-768) with current mapping.
 *   2. Scroll all documents from the source index, re-embed each document's
 *      content field using the active EmbeddingService.
 *   3. Bulk-save to the target index in configurable batch sizes.
 *   4. Delete the source physical index.
 *   5. Create an alias with the original name pointing to the target index,
 *      so the application works without any further changes.
 *
 * Trigger via the memory_reindex MCP tool.
 */
@Service
public class ReindexMigrationService {

    private static final Logger log = LoggerFactory.getLogger(ReindexMigrationService.class);

    private static final int BATCH_SIZE = 100;
    private static final int SCROLL_SIZE = 500;

    /** Indices that carry dense_vector embeddings and need re-embedding on dimension change. */
    private static final List<String> VECTOR_INDICES = List.of(
        "memory-observations",
        "memory-consolidated"
    );

    private final ElasticsearchClient client;
    private final EmbeddingService embeddingService;
    private final MemoryProperties props;

    public ReindexMigrationService(ElasticsearchClient client, EmbeddingService embeddingService, MemoryProperties props) {
        this.client = client;
        this.embeddingService = embeddingService;
        this.props = props;
    }

    /**
     * Run the full migration for all vector indices.
     *
     * @param dryRun if true, count documents and validate but do not write.
     * @return human-readable progress/result summary.
     */
    public String migrate(boolean dryRun) throws IOException {
        int targetDims = embeddingService.dimensions();
        var sb = new StringBuilder();
        sb.append("Migration to ").append(targetDims).append("-dim embeddings");
        if (dryRun) sb.append(" [DRY RUN]");
        sb.append("\n\n");

        for (String indexName : VECTOR_INDICES) {
            sb.append(migrateIndex(indexName, targetDims, dryRun));
            sb.append("\n");
        }
        return sb.toString();
    }

    private String migrateIndex(String sourceName, int targetDims, boolean dryRun) throws IOException {
        boolean sourceExists = client.indices().exists(e -> e.index(sourceName)).value();
        if (!sourceExists) {
            return "  " + sourceName + ": skipped (does not exist)\n";
        }

        long totalDocs = client.count(c -> c.index(sourceName)).count();
        if (totalDocs == 0) {
            return "  " + sourceName + ": skipped (empty index)\n";
        }

        String targetName = sourceName + "-" + targetDims;
        log.info("Migrating {} ({} docs) → {}", sourceName, totalDocs, targetName);

        List<String> aliasBackings = getAliasBackings(sourceName);
        if (aliasBackings.size() == 1 && aliasBackings.contains(targetName)) {
            return "  " + sourceName + ": already points to " + targetName + "\n";
        }

        if (dryRun) {
            return "  " + sourceName + ": would migrate " + totalDocs + " docs → " + targetName + "\n";
        }

        deleteIndexIfExists(targetName);

        // 1. Create target index with current mapping (dims from es-index-mappings.json)
        ensureTargetIndex(targetName);

        // 2. Scroll + re-embed + bulk save
        int migrated = scrollAndReembed(sourceName, targetName, totalDocs);

        // 3. Delete old source physical index or old alias backing indices.
        if (!aliasBackings.isEmpty()) {
            client.indices().deleteAlias(da -> da.index("*").name(sourceName));
            for (var backingIndex : aliasBackings) {
                if (!backingIndex.equals(targetName)) {
                    client.indices().delete(d -> d.index(backingIndex));
                    log.info("Deleted old backing index: {}", backingIndex);
                }
            }
        } else {
            client.indices().delete(d -> d.index(sourceName));
            log.info("Deleted old physical index: {}", sourceName);
        }

        // 4. Create alias original-name → target index (with write access)
        client.indices().putAlias(a -> a
            .index(targetName)
            .name(sourceName)
            .isWriteIndex(true)
        );
        log.info("Created alias {} → {}", sourceName, targetName);

        return String.format("  %s: migrated %d/%d docs → %s (alias created)\n",
            sourceName, migrated, totalDocs, targetName);
    }

    private void ensureTargetIndex(String targetName) throws IOException {
        boolean exists = client.indices().exists(e -> e.index(targetName)).value();
        if (!exists) {
            log.info("Creating target index: {}", targetName);
            var mappings = new ClassPathResource("es-index-mappings.json").getInputStream();
            client.indices().create(c -> c.index(targetName).withJson(mappings));
        } else {
            log.info("Target index already exists: {}", targetName);
        }
    }

    private List<String> getAliasBackings(String aliasName) {
        try {
            return new ArrayList<>(client.indices().getAlias(a -> a.name(aliasName)).aliases().keySet());
        } catch (Exception e) {
            return List.of();
        }
    }

    private void deleteIndexIfExists(String indexName) throws IOException {
        if (client.indices().exists(e -> e.index(indexName)).value()) {
            log.info("Deleting existing migration target index: {}", indexName);
            client.indices().delete(d -> d.index(indexName));
        }
    }

    @SuppressWarnings("unchecked")
    private int scrollAndReembed(String sourceName, String targetName, long totalDocs) throws IOException {
        int migrated = 0;
        int from = 0;
        var batch = new ArrayList<Map<String, Object>>();
        var ids = new ArrayList<String>();

        while (from < totalDocs) {
            int currentFrom = from;
            SearchResponse<Map<String, Object>> page = client.search(s -> s
                .index(sourceName)
                .from(currentFrom)
                .size(SCROLL_SIZE)
                .sort(sort -> sort.field(f -> f.field("timestamp").order(SortOrder.Asc)))
                .source(src -> src.filter(f -> f.excludes("embedding"))),
                (Class<Map<String, Object>>) (Class<?>) Map.class
            );

            List<Hit<Map<String, Object>>> hits = page.hits().hits();
            if (hits.isEmpty()) break;

            for (var hit : hits) {
                Map<String, Object> src = hit.source();
                if (src == null) continue;

                String content = (String) src.get("content");
                float[] embedding = embedSafely(hit.id(), content);

                Map<String, Object> doc = new LinkedHashMap<>(src);
                doc.put("embedding", toDoubleList(embedding));

                batch.add(doc);
                ids.add(hit.id());

                if (batch.size() >= BATCH_SIZE) {
                    bulkSave(targetName, batch, ids);
                    migrated += batch.size();
                    log.info("  Progress: {}/{}", migrated, totalDocs);
                    batch.clear();
                    ids.clear();
                }
            }

            from += hits.size();
            if (hits.size() < SCROLL_SIZE) break;
        }

        // Flush remaining
        if (!batch.isEmpty()) {
            bulkSave(targetName, batch, ids);
            migrated += batch.size();
        }

        log.info("  Re-embed complete: {} docs", migrated);
        return migrated;
    }

    private float[] embedSafely(String id, String content) throws IOException {
        if (content == null || content.isBlank()) {
            return new float[embeddingService.dimensions()];
        }
        try {
            float[] embedding = embeddingService.embed(truncateForEmbed(content));
            if (embedding.length != embeddingService.dimensions()) {
                throw new IOException("Embedding dimension mismatch for doc " + id + ": expected "
                    + embeddingService.dimensions() + " but got " + embedding.length);
            }
            return embedding;
        } catch (Exception e) {
            throw new IOException("Embed failed for doc " + id + ": " + e.getMessage(), e);
        }
    }

    private String truncateForEmbed(String text) {
        int max = props.getMaxEmbedChars();
        if (text.length() <= max) return text;
        int headLen = (max * 2) / 3;
        int tailLen = max - headLen;
        return text.substring(0, headLen) + "\n...[truncated]...\n" + text.substring(text.length() - tailLen);
    }

    private void bulkSave(String indexName, List<Map<String, Object>> docs, List<String> ids) throws IOException {
        var ops = new ArrayList<BulkOperation>();
        for (int i = 0; i < docs.size(); i++) {
            final int idx = i;
            ops.add(BulkOperation.of(o -> o
                .index(bi -> bi.index(indexName).id(ids.get(idx)).document(docs.get(idx)))));
        }
        var response = client.bulk(b -> b.operations(ops));
        if (response.errors()) {
            log.warn("Bulk save had errors for index {}", indexName);
        }
    }

    private List<Double> toDoubleList(float[] arr) {
        var list = new ArrayList<Double>(arr.length);
        for (float v : arr) list.add((double) v);
        return list;
    }
}
