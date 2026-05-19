package com.agentmemory.controller;

import com.agentmemory.model.MemoryTier;
import com.agentmemory.model.SearchResult;
import com.agentmemory.service.ElasticsearchService;
import com.agentmemory.service.MemoryPipelineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/memory")
public class MemoryController {

    private final MemoryPipelineService pipelineService;
    private final ElasticsearchService esService;

    public MemoryController(MemoryPipelineService pipelineService, ElasticsearchService esService) {
        this.pipelineService = pipelineService;
        this.esService = esService;
    }

    @PostMapping("/observe")
    public ResponseEntity<?> observe(@RequestBody Map<String, Object> body) throws IOException {
        String tool = (String) body.get("tool");
        String input = (String) body.get("input");
        String output = (String) body.get("output");
        String filePath = (String) body.get("filePath");
        String sessionId = (String) body.get("sessionId");
        String projectId = (String) body.get("projectId");

        var result = pipelineService.observe(tool, input, output, filePath, sessionId, projectId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/recall")
    public ResponseEntity<?> recall(@RequestBody Map<String, Object> body) throws IOException {
        String query = (String) body.get("query");
        String projectId = (String) body.get("projectId");
        String sessionId = (String) body.get("sessionId");

        var results = pipelineService.recall(query, projectId, sessionId);
        return ResponseEntity.ok(Map.of("results", results, "count", results.size()));
    }

    @PostMapping("/save")
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body) throws IOException {
        String content = (String) body.get("content");
        String tierStr = (String) body.getOrDefault("tier", "SEMANTIC");
        String sessionId = (String) body.get("sessionId");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) body.get("tags");
        String projectId = (String) body.get("projectId");

        var result = pipelineService.saveInsight(content, MemoryTier.valueOf(tierStr), sessionId, tags, projectId);
        return ResponseEntity.ok(Map.of("status", "saved", "id", result.get("id")));
    }

    @PostMapping("/session/start")
    public ResponseEntity<?> sessionStart(@RequestBody(required = false) Map<String, Object> body) throws IOException {
        String projectPath = body != null ? (String) body.get("path") : "unknown";
        String sessionId = UUID.randomUUID().toString();

        var session = new com.agentmemory.model.SessionRecord(sessionId, projectPath, Instant.now(), null, 0, null);
        esService.saveSession(session);

        return ResponseEntity.ok(Map.of("sessionId", sessionId, "status", "started"));
    }

    @PostMapping("/session/end")
    public ResponseEntity<?> sessionEnd(@RequestParam String sessionId) throws IOException {
        var session = esService.getSession(sessionId);
        if (session.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var updated = new com.agentmemory.model.SessionRecord(
            session.get().id(), session.get().projectPath(),
            session.get().startTime(), Instant.now(), 0, session.get().summary()
        );
        esService.saveSession(updated);
        return ResponseEntity.ok(Map.of("status", "ended"));
    }

    @PostMapping("/prompt")
    public ResponseEntity<?> prompt(@RequestBody Map<String, Object> body) {
        String text = (String) body.get("text");
        return ResponseEntity.ok(Map.of("status", "recorded", "text", text));
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> sessions(@RequestParam(defaultValue = "20") int limit) throws IOException {
        var sessions = esService.getRecentSessions(limit);
        return ResponseEntity.ok(Map.of("sessions", sessions));
    }

    @GetMapping("/timeline")
    public ResponseEntity<?> timeline(@RequestParam(defaultValue = "50") int limit) throws IOException {
        var items = esService.getTimeline(limit);
        return ResponseEntity.ok(Map.of("timeline", items, "count", items.size()));
    }

    @GetMapping("/profile")
    public ResponseEntity<?> profile() throws IOException {
        return ResponseEntity.ok(esService.getProfile());
    }

    @GetMapping("/file-history")
    public ResponseEntity<?> fileHistory(@RequestParam String path, @RequestParam(defaultValue = "20") int limit) throws IOException {
        var items = esService.getObservationsByFile(path, limit);
        return ResponseEntity.ok(Map.of("file", path, "observations", items));
    }

    @GetMapping("/patterns")
    public ResponseEntity<?> patterns() throws IOException {
        var patterns = esService.getPatternAggregations();
        return ResponseEntity.ok(Map.of("patterns", patterns));
    }

    @GetMapping("/metrics/project")
    public ResponseEntity<?> projectMetrics(@RequestParam(required = false) String projectId) throws IOException {
        return ResponseEntity.ok(esService.getProjectMetrics(projectId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> forget(@PathVariable String id) throws IOException {
        esService.deleteMemory(id);
        return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
    }
}
