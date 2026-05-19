package com.agentmemory.mcp;

import com.agentmemory.model.MemoryTier;
import com.agentmemory.model.SearchResult;
import com.agentmemory.model.TokenBudget;
import com.agentmemory.service.ElasticsearchService;
import com.agentmemory.service.MemoryPipelineService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Registers all MCP tools with their schemas and handlers.
 */
@Component
public class McpToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistrar.class);

    private final MemoryPipelineService pipeline;
    private final ElasticsearchService esService;

    public McpToolRegistrar(MemoryPipelineService pipeline, ElasticsearchService esService) {
        this.pipeline = pipeline;
        this.esService = esService;
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
            memoryPatterns()
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

    private McpSchema.CallToolResult errorResult(String message) {
        return new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent("Error: " + message)), true);
    }
}
