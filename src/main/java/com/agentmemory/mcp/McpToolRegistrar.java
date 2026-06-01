package com.agentmemory.mcp;

import com.agentmemory.model.LifecycleJobState;
import com.agentmemory.model.MemoryTier;
import com.agentmemory.model.SearchResult;
import com.agentmemory.model.TokenBudget;
import com.agentmemory.service.ElasticsearchService;
import com.agentmemory.service.LifecycleCoordinator;
import com.agentmemory.service.MemoryConsolidationService;
import com.agentmemory.service.MemoryPipelineService;
import com.agentmemory.service.ReindexMigrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registers all MCP tools with their schemas and handlers.
 */
@Component
public class McpToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistrar.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MemoryPipelineService pipeline;
    private final ElasticsearchService esService;
    private final ReindexMigrationService migrationService;
    private final MemoryConsolidationService consolidationService;
    private final LifecycleCoordinator coordinator;

    public McpToolRegistrar(MemoryPipelineService pipeline, ElasticsearchService esService,
                            ReindexMigrationService migrationService,
                            MemoryConsolidationService consolidationService,
                            LifecycleCoordinator coordinator) {
        this.pipeline = pipeline;
        this.esService = esService;
        this.migrationService = migrationService;
        this.consolidationService = consolidationService;
        this.coordinator = coordinator;
    }

    /** Convenience constructor for callers that don't need lifecycle tools. */
    public McpToolRegistrar(MemoryPipelineService pipeline, ElasticsearchService esService,
                            ReindexMigrationService migrationService) {
        this(pipeline, esService, migrationService, null, null);
    }

    @SuppressWarnings("unchecked")
    public List<McpServerFeatures.SyncToolSpecification> registerAll() {
        return List.of(
            memoryRecall(),
            memorySave(),
            memorySessions(),
            memoryTimeline(),
            memoryProfile(),
            memoryFileHistory(),
            memoryForget(),
            memoryPatterns(),
            memoryReindex(),
            memoryLifecycleStatus(),
            memoryLifecycleRun()
        );
    }

    private McpServerFeatures.SyncToolSpecification memoryRecall() {
        McpSchema.Tool tool = new McpSchema.Tool(
            "memory_recall",
            "Search past memory observations with hybrid BM25 + vector + rerank",
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Search query\"},\"projectId\":{\"type\":\"string\"},\"sessionId\":{\"type\":\"string\"},\"tokenBudget\":{\"type\":\"integer\",\"default\":2000,\"description\":\"Max tokens for response\"}},\"required\":[\"query\"]}"
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            String query = (String) args.get("query");
            String projectId = (String) args.get("projectId");
            String sessionId = (String) args.get("sessionId");
            int tokenBudget = args.containsKey("tokenBudget") ? ((Number) args.get("tokenBudget")).intValue() : 2000;
            try {
                List<SearchResult> results = pipeline.recall(query, projectId, sessionId);
                String text = TokenBudget.formatWithRerank(results, tokenBudget);
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false);
            } catch (Exception e) {
                log.error("memory_recall failed", e);
                return errorResult("Recall failed: " + e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncToolSpecification memorySave() {
        McpSchema.Tool tool = new McpSchema.Tool(
            "memory_save",
            "Save an insight, decision, or pattern to long-term memory",
            "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\"},\"tier\":{\"type\":\"string\",\"enum\":[\"WORKING\",\"EPISODIC\",\"SEMANTIC\",\"PROCEDURAL\"]},\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"sessionId\":{\"type\":\"string\"},\"projectId\":{\"type\":\"string\"}},\"required\":[\"content\"]}"
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            String content = (String) args.get("content");
            String tierStr = (String) args.getOrDefault("tier", "SEMANTIC");
            String sessionId = (String) args.get("sessionId");
            List<String> tags = (List<String>) args.get("tags");
            String projectId = (String) args.get("projectId");
            try {
                var result = pipeline.saveInsight(content, MemoryTier.valueOf(tierStr), sessionId, tags, projectId);
                return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("Saved insight to memory. ID: " + result.get("id"))), false);
            } catch (Exception e) {
                log.error("memory_save failed", e);
                return errorResult("Save failed: " + e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncToolSpecification memorySessions() {
        McpSchema.Tool tool = new McpSchema.Tool(
            "memory_sessions",
            "List recent coding sessions",
            "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\",\"default\":20}}}"
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 20;
            try {
                var sessions = esService.getRecentSessions(limit);
                String text = sessions.isEmpty() ? "No sessions found."
                    : "Recent sessions:\n" + sessions.stream()
                    .map(s -> "- %s | project: %s | started: %s".formatted(s.id(), s.projectPath(), s.startTime()))
                    .reduce((a, b) -> a + "\n" + b).orElse("");
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false);
            } catch (Exception e) {
                log.error("memory_sessions failed", e);
                return errorResult("Sessions list failed: " + e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncToolSpecification memoryTimeline() {
        McpSchema.Tool tool = new McpSchema.Tool(
            "memory_timeline",
            "Chronological list of recent observations",
            "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\",\"default\":50}}}"
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 50;
            try {
                var items = esService.getTimeline(limit);
                String text = items.isEmpty() ? "No observations found."
                    : "Recent observations:\n" + items.stream()
                    .map(m -> "- [%s] tool=%s file=%s".formatted(
                        m.get("timestamp"), m.get("toolName"), m.get("filePath")))
                    .reduce((a, b) -> a + "\n" + b).orElse("");
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false);
            } catch (Exception e) {
                log.error("memory_timeline failed", e);
                return errorResult("Timeline failed: " + e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncToolSpecification memoryProfile() {
        McpSchema.Tool tool = new McpSchema.Tool(
            "memory_profile",
            "Project profile: total observations, top files, concepts, patterns",
            "{\"type\":\"object\",\"properties\":{}}"
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            try {
                var profile = esService.getProfile();
                String text = "Project Profile:\n- Total observations: " + profile.get("totalObservations");
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false);
            } catch (Exception e) {
                log.error("memory_profile failed", e);
                return errorResult("Profile failed: " + e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncToolSpecification memoryFileHistory() {
        McpSchema.Tool tool = new McpSchema.Tool(
            "memory_file_history",
            "Past observations about a specific file",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"File path\"},\"limit\":{\"type\":\"integer\",\"default\":20}},\"required\":[\"path\"]}"
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            String path = (String) args.get("path");
            int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 20;
            try {
                var items = esService.getObservationsByFile(path, limit);
                String text = items.isEmpty() ? "No observations for: " + path
                    : "Observations for " + path + ":\n" + items.stream()
                    .map(m -> "- [%s] %s".formatted(m.get("timestamp"), m.get("content")))
                    .reduce((a, b) -> a + "\n" + b).orElse("");
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false);
            } catch (Exception e) {
                log.error("memory_file_history failed", e);
                return errorResult("File history failed: " + e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncToolSpecification memoryForget() {
        McpSchema.Tool tool = new McpSchema.Tool(
            "memory_forget",
            "Delete a memory by ID",
            "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"description\":\"Memory ID to delete\"}},\"required\":[\"id\"]}"
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            String id = (String) args.get("id");
            try {
                esService.deleteMemory(id);
                return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("Deleted memory: " + id)), false);
            } catch (Exception e) {
                log.error("memory_forget failed", e);
                return errorResult("Forget failed: " + e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncToolSpecification memoryPatterns() {
        McpSchema.Tool tool = new McpSchema.Tool(
            "memory_patterns",
            "Detect recurring patterns from memory tags and tool usage",
            "{\"type\":\"object\",\"properties\":{}}"
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            try {
                var patterns = esService.getPatternAggregations();
                String text = "Patterns:\n" + patterns.toString();
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false);
            } catch (Exception e) {
                log.error("memory_patterns failed", e);
                return errorResult("Patterns failed: " + e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncToolSpecification memoryReindex() {
        McpSchema.Tool tool = new McpSchema.Tool(
            "memory_reindex",
            "Migrate memory indices to a new embedding model/dimension. Creates new indices, re-embeds all content, then swaps aliases. Use dryRun=true first to preview.",
            "{\"type\":\"object\",\"properties\":{\"dryRun\":{\"type\":\"boolean\",\"default\":true,\"description\":\"If true, only counts documents and validates without writing. Set to false to perform the actual migration.\"}}}"
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            boolean dryRun = args.containsKey("dryRun") ? (Boolean) args.get("dryRun") : true;
            try {
                log.info("memory_reindex called (dryRun={})", dryRun);
                String result = migrationService.migrate(dryRun);
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result)), false);
            } catch (Exception e) {
                log.error("memory_reindex failed", e);
                return errorResult("Reindex failed: " + e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncToolSpecification memoryLifecycleStatus() {
        McpSchema.Tool tool = new McpSchema.Tool(
            "memory_lifecycle_status",
            "Return current lifecycle job state for consolidation and decay jobs",
            "{\"type\":\"object\",\"properties\":{}}"
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            if (coordinator == null || consolidationService == null) {
                return errorResult("Lifecycle coordinator not available in this mode");
            }
            try {
                Map<String, Object> result = new LinkedHashMap<>();
                long[] defaultIntervals = {60, 360};
                String[] jobs = {"memory-consolidation", "memory-decay"};
                for (int i = 0; i < jobs.length; i++) {
                    String jobName = jobs[i];
                    long interval = defaultIntervals[i];
                    Map<String, Object> info = new LinkedHashMap<>();
                    Optional<LifecycleJobState> stateOpt = coordinator.getJobState(jobName);
                    if (stateOpt.isPresent()) {
                        LifecycleJobState s = stateOpt.get();
                        info.put("leaseOwner", s.getLeaseOwner());
                        info.put("leaseUntil", s.getLeaseUntil() != null ? s.getLeaseUntil().toString() : null);
                        info.put("lastStartedAt", s.getLastStartedAt() != null ? s.getLastStartedAt().toString() : null);
                        info.put("lastCompletedAt", s.getLastCompletedAt() != null ? s.getLastCompletedAt().toString() : null);
                        info.put("lastError", s.getLastError());
                    } else {
                        info.put("note", "No state found");
                    }
                    info.put("isDue", consolidationService.isJobDue(jobName, interval));
                    result.put(jobName, info);
                }
                return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(JSON.writeValueAsString(result))), false);
            } catch (Exception e) {
                log.error("memory_lifecycle_status failed", e);
                return errorResult("Lifecycle status failed: " + e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncToolSpecification memoryLifecycleRun() {
        McpSchema.Tool tool = new McpSchema.Tool(
            "memory_lifecycle_run",
            "Trigger lifecycle jobs or get dry-run statistics without mutating data",
            "{\"type\":\"object\",\"properties\":{\"job\":{\"type\":\"string\",\"enum\":[\"consolidation\",\"decay\",\"all\"],\"description\":\"Which job to run\"},\"dryRun\":{\"type\":\"boolean\",\"default\":true,\"description\":\"If true, return candidate counts without mutating\"}},\"required\":[\"job\"]}"
        );
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            if (consolidationService == null) {
                return errorResult("Consolidation service not available in this mode");
            }
            String job = (String) args.getOrDefault("job", "all");
            boolean dryRun = args.containsKey("dryRun") ? (Boolean) args.get("dryRun") : true;
            try {
                if (dryRun) {
                    Map<String, Object> stats = consolidationService.dryRunStats();
                    return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Dry-run stats: " + JSON.writeValueAsString(stats))), false);
                } else {
                    StringBuilder sb = new StringBuilder();
                    if ("consolidation".equals(job) || "all".equals(job)) {
                        consolidationService.consolidate();
                        sb.append("Consolidation triggered. ");
                    }
                    if ("decay".equals(job) || "all".equals(job)) {
                        consolidationService.applyDecay();
                        sb.append("Decay triggered.");
                    }
                    return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent(sb.toString().trim())), false);
                }
            } catch (Exception e) {
                log.error("memory_lifecycle_run failed", e);
                return errorResult("Lifecycle run failed: " + e.getMessage());
            }
        });
    }

    private McpSchema.CallToolResult errorResult(String message) {
        return new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent("Error: " + message)), true);
    }
}
