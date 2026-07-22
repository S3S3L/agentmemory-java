# AgentMemory for Claude Code

A persistent memory system built for Claude Code. Inspired by [rohitg00/agentmemory](https://github.com/rohitg00/agentmemory), implemented with Java + Elasticsearch + DashScope.

## Architecture

```
                              ┌──> Elasticsearch 9.4.1 (BM25 + kNN vector search + distributed lease)
                              │
Claude Code ──(stdio MCP)──> AgentMemory ──┼──> DashScope / Ollama (embedding, switchable)
                              │
Claude Code ──(HTTP REST)──>  (port 40080) ──┼──> DashScope / REST (rerank, switchable)
                              │
                              └──> Ollama (consolidation text generation)
```

Both modes share the same business logic:

| Mode | Use Case | Port |
|---|---|---|
| **stdio** | Claude Code MCP stdio connection | None (stdin/stdout JSON-RPC) |
| **HTTP** | REST API + MCP SSE | 40080 |

## Tech Stack

| Component | Choice | Notes |
|---|---|---|
| Framework | Java 21 + Spring Boot 3.4.5 | |
| Storage | Elasticsearch 9.4.1 | docker compose + persistent volumes |
| Embedding | DashScope text-embedding-v4 (1024dim) / Ollama nomic-embed-text (768dim) | Switch via `agentmemory.embedding` config |
| Rerank | DashScope gte-rerank / REST custom rerank | Switch via `agentmemory.rerank` config |
| MCP SDK | io.modelcontextprotocol.sdk:mcp 0.10.0 | |
| HTTP Client | OkHttp 4.12.0 | |
| Cache | Caffeine 3.1.8 | Embedding cache (10K entries, 6h TTL) |

## Core Features

### Hybrid Search

1. **Query Expansion** — Domain synonym expansion + stemming
2. **BM25 Multi-field Weighted Search** — title^3 / concepts^2.5 / tags^2 / facts^2 / filePath^1.5 / content (virtual thread parallel execution)
3. **kNN Vector Search** — Semantic similarity (top-20)
4. **RRF Fusion** — `score = Σ 1/(k + rank)`, k=60
5. **Conditional Rerank** — DashScope gte-rerank or REST custom rerank; skip strategy when results ≤ 3 or top-1 score far exceeds top-2
6. **Low-score Filtering** — Drop results below rerank score threshold (default `minRerankScore=-5.0`)
7. **Session Diversity** — Max 3 results per session
8. **Token Budget Truncation** — Character-based truncation (~3 chars/token) with head+tail strategy
9. **Embedding Cache** — Caffeine cache, 10K entries, 6h TTL, skips embedding API call on hit

### 4-Tier Memory Lifecycle

| Tier | Contents | Promotion Criteria | Half-life |
|---|---|---|---|
| WORKING | Raw tool-call observations | Age ≥ 1 day **OR** accessed ≥ 3 times → EPISODIC | 3 days |
| EPISODIC | Session-level summaries | Age ≥ 7 days **AND** accessed ≥ 3 times → SEMANTIC | 30 days |
| SEMANTIC | Extracted facts/patterns | Accessed ≥ 10 times → PROCEDURAL | 90 days |
| PROCEDURAL | Durable workflows/decisions | Highest tier | 180 days |

Background Jobs:
- **Every 6 hours** — Memory consolidation (tier promotion + consolidated artifact generation + deterministic ID dedup)
- **Daily 02:00** — Decay sweep + conflict detection
- **Distributed coordination** — ES optimistic-concurrency-based distributed lease, multi-instance safe (see LifecycleCoordinator)
- **Soft-delete** — Decay supports soft-delete (`isActive=false`), configurable to hard-delete
- **Tag preservation** — Original tags preserved during decay/promotion

### Security & Deduplication

- SHA-256 content hash, 5-minute dedup window
- Privacy filtering: auto-redact API keys, secrets, tokens

## Quick Start

### 1. Start Elasticsearch

```bash
cd agentmemory-java
docker compose up -d
```

### 2. HTTP Mode (REST API)

```bash
mvn spring-boot:run -DskipTests
```

### 3. stdio Mode (MCP)

```bash
mvn package -P stdio -DskipTests
```

### 4. Configure Claude Code

Edit `~/.claude/settings.json`:

```json
{
  "mcpServers": {
    "agentmemory": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/agentmemory-java/target/agentmemory-0.1.0-SNAPSHOT.jar",
        "--logging.config=classpath:logback-stdio.xml"
      ],
      "env": {
        "ES_HOST": "localhost",
        "ES_PORT": "9200",
        "DASHSCOPE_API_KEY": "your-key-here"
      }
    }
  }
}
```

## MCP Tools (11)

### memory_recall

Search historical memories with hybrid BM25 + vector + rerank.

```json
{
  "query": "database optimization",
  "projectId": "optional-project-id",
  "sessionId": "optional-session-id",
  "tokenBudget": 2000
}
```

### memory_save

Save an insight, decision, or pattern to long-term memory.

```json
{
  "content": "Use composite indexes for join queries",
  "tier": "SEMANTIC",
  "tags": ["database", "performance"],
  "sessionId": "sess-123"
}
```

Tier values: `WORKING` | `EPISODIC` | `SEMANTIC` | `PROCEDURAL`

### memory_sessions

List recent coding sessions.

```json
{
  "limit": 20
}
```

### memory_timeline

List recent observations in chronological order.

```json
{
  "limit": 50
}
```

### memory_profile

Get project profile: total observations, top files, concepts, patterns.

No parameters.

### memory_file_history

View historical observations for a specific file.

```json
{
  "path": "src/auth/middleware.ts",
  "limit": 20
}
```

### memory_forget

Delete a specific memory.

```json
{
  "id": "memory-uuid-here"
}
```

### memory_patterns

Detect recurring patterns from memory tags and tool usage.

No parameters.

### memory_reindex

Migrate memory indices to a new embedding model/dimension. Creates new indices, re-embeds all content, and atomically swaps aliases.

```json
{
  "dryRun": true
}
```

`dryRun=true` only counts documents and validates without writing. Set to `false` to perform the actual migration.

### memory_lifecycle_status

Returns current state of all lifecycle jobs (consolidation + decay), including lease holder, last run time, and next due time.

No parameters.

### memory_lifecycle_run

Manually trigger lifecycle jobs or preview candidate counts.

```json
{
  "job": "consolidation",
  "dryRun": true
}
```

`job` values: `consolidation` | `decay` | `all`. `dryRun=true` returns candidate counts only without mutating data.

## Lifecycle Management

AgentMemory implements a 4-tier memory lifecycle modelled after Ebbinghaus forgetting curves.

### Memory Tiers

| Tier | Contents | Promotion Criteria | Decay Half-life |
|---|---|---|---|
| **WORKING** | Raw tool-call observations | Age ≥ 1 day **or** accessed ≥ 3 times → EPISODIC | 3 days |
| **EPISODIC** | Session-level summaries | Age ≥ 7 days **and** accessed ≥ 3 times → SEMANTIC | 30 days |
| **SEMANTIC** | Extracted facts / patterns | Accessed ≥ 10 times → PROCEDURAL | 90 days |
| **PROCEDURAL** | Durable workflows / decisions | Highest tier, never promoted | 180 days |

### What Triggers Lifecycle

| Trigger | When |
|---|---|
| **Scheduled (Spring)** | `memory.consolidation.cron` (default every 6 h) and `memory.decay.cron` (default 02:00 daily) |
| **Startup catch-up** | `isJobDue()` check: if last run was > `intervalMinutes` ago, the job fires immediately on start |
| **Manual (MCP tool)** | `memory_lifecycle_run` with `dryRun=false` |

### Multi-Instance Safety (stdio / Spring Boot)

Each JVM instance holds a unique `instanceId` (random UUID). Before executing a job, the instance attempts to acquire an Elasticsearch-backed distributed lease stored in the `memory-lifecycle-state` index. If another instance holds a valid (non-expired) lease, the current instance skips the run. Leases expire after 10 minutes, preventing a crashed instance from permanently blocking execution. Concurrent acquisitions are serialised via ES optimistic concurrency (`if_seq_no` / `if_primary_term`).

### Configuration Reference

All keys are under the `memory:` prefix in `application.yml`.

#### `memory.consolidation.*`

| Key | Default | Description |
|---|---|---|
| `enabled` | `true` | Enable/disable the consolidation job |
| `cron` | `0 0 */6 * * *` | Spring cron expression for scheduled runs |
| `interval-minutes` | `360` | Minutes between runs; used by `isJobDue()` for startup catch-up |
| `summary-model` | `llama3.2` | Ollama model used to generate summary text for consolidated artifacts |
| `promotion.working-to-episodic-days` | `1` | Age threshold (days) for WORKING → EPISODIC promotion |
| `promotion.working-to-episodic-access-count` | `3` | Access count threshold for WORKING → EPISODIC (OR with age) |
| `promotion.episodic-to-semantic-days` | `7` | Age threshold (days) for EPISODIC → SEMANTIC |
| `promotion.episodic-to-semantic-access-count` | `3` | Access count threshold for EPISODIC → SEMANTIC (AND with age) |
| `promotion.semantic-to-procedural-access-count` | `10` | Access count threshold for SEMANTIC → PROCEDURAL |

#### `memory.decay.*`

| Key | Default | Description |
|---|---|---|
| `enabled` | `true` | Enable/disable the decay sweep job |
| `cron` | `0 0 2 * * *` | Spring cron expression (daily at 02:00) |
| `interval-minutes` | `1440` | Minutes between runs; used by `isJobDue()` for startup catch-up |
| `soft-delete` | `true` | `true` = set `isActive=false`; `false` = hard-delete from ES |
| `stale-episodic-days` | `30` | Days before an EPISODIC memory is considered stale |
| `stale-semantic-days` | `90` | Days before a SEMANTIC memory is considered stale |

### MCP Lifecycle Tools

#### `memory_lifecycle_status`

Returns the current state of all lifecycle jobs. No parameters.

Response JSON shape:
```json
{
  "instanceId": "<uuid>",
  "consolidation": {
    "jobName": "memory-consolidation",
    "enabled": true,
    "intervalMinutes": 360,
    "isDue": false,
    "leaseOwner": "<uuid>",
    "leaseUntil": "2024-01-01T06:10:00Z",
    "lastStartedAt": "2024-01-01T06:00:00Z",
    "lastCompletedAt": "2024-01-01T06:00:05Z",
    "lastError": null
  },
  "decay": { "..." }
}
```

#### `memory_lifecycle_run`

Manually trigger lifecycle jobs or preview candidate counts.

```json
{
  "job": "consolidation",
  "dryRun": true
}
```

`job` values: `consolidation` | `decay` | `all`. `dryRun=true` returns candidate counts only without mutating data.

## REST API

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Health check |
| `POST` | `/memory/observe` | Write an observation |
| `POST` | `/memory/recall` | Hybrid memory search |
| `POST` | `/memory/save` | Manually save an insight |
| `POST` | `/memory/session/start` | Start a new session |
| `POST` | `/memory/session/end` | End a session |
| `POST` | `/memory/prompt` | Record a user prompt |
| `GET` | `/memory/sessions` | List sessions |
| `GET` | `/memory/timeline` | Timeline of observations |
| `GET` | `/memory/profile` | Project profile |
| `GET` | `/memory/file-history?path=xxx` | File history |
| `GET` | `/memory/patterns` | Pattern aggregation |
| `GET` | `/memory/metrics/project` | Project metrics |
| `DELETE` | `/memory/{id}` | Delete a memory |

### Examples

```bash
# Health check
curl http://localhost:40080/health

# Write an observation
curl -X POST http://localhost:40080/memory/observe \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "Read",
    "input": "Read auth.ts",
    "output": "Loaded 200 lines",
    "filePath": "src/auth.ts",
    "sessionId": "sess-1"
  }'

# Search memories
curl -X POST http://localhost:40080/memory/recall \
  -H "Content-Type: application/json" \
  -d '{"query": "authentication middleware", "tokenBudget": 1000}'

# Save an insight
curl -X POST http://localhost:40080/memory/save \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Use jose library for JWT, supports Edge runtime",
    "tier": "SEMANTIC",
    "tags": ["auth", "jwt"]
  }'

# Project profile
curl http://localhost:40080/memory/profile
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `ES_HOST` | localhost | Elasticsearch host |
| `ES_PORT` | 9200 | Elasticsearch port |
| `ES_SCHEME` | http | http or https |
| `DASHSCOPE_API_KEY` | (empty) | DashScope API key; uses fallback embedding when empty |
| `DASHSCOPE_EMBEDDING_MODEL` | text-embedding-v4 | Embedding model |
| `DASHSCOPE_EMBEDDING_DIMENSIONS` | 1024 | Vector dimensions |
| `DASHSCOPE_RERANK_MODEL` | gte-rerank | Rerank model |
| `OLLAMA_ENDPOINT` | http://localhost:11434 | Ollama server URL |
| `OLLAMA_EMBEDDING_MODEL` | nomic-embed-text | Ollama embedding model |
| `OLLAMA_DIMENSIONS` | 768 | Ollama vector dimensions |
| `REST_RERANK_ENDPOINT` | http://localhost:8000/rerank | Custom rerank endpoint |
| `AGENTMEMORY_EMBEDDING` | DASH_SCOPE | Embedding implementation: `DASH_SCOPE` / `OLLAMA` |
| `AGENTMEMORY_RERANK` | DASH_SCOPE | Rerank implementation: `DASH_SCOPE` / `REST` |

## Configuration

### application.yml

```yaml
server:
  port: 40080

agentmemory:
  embedding: DASH_SCOPE            # DASH_SCOPE | OLLAMA
  rerank: DASH_SCOPE               # DASH_SCOPE | REST

elasticsearch:
  host: localhost
  port: 9200
  scheme: http

dashscope:
  api-key: ${DASHSCOPE_API_KEY:}
  embedding:
    model: text-embedding-v4
    dimensions: 1024
  rerank:
    model: gte-rerank

ollama:
  endpoint: http://localhost:11434
  embeddingModel: nomic-embed-text
  dimensions: 768

rest:
  rerank:
    endpoint: http://localhost:8000/rerank

memory:
  token-budget: 2000
  dedup-window-minutes: 5
  rrf-k: 60
  max-results-per-session: 3
  top-k-bm25: 20
  top-k-vector: 20
  top-k-final: 10
  min-rerank-score: -5.0
  max-embed-chars: 24000
  consolidation:
    enabled: true
    cron: "0 0 */6 * * *"
    interval-minutes: 360
    summary-model: llama3.2
    promotion:
      working-to-episodic-days: 1
      working-to-episodic-access-count: 3
      episodic-to-semantic-days: 7
      episodic-to-semantic-access-count: 3
      semantic-to-procedural-access-count: 10
  decay:
    enabled: true
    cron: "0 0 2 * * *"
    interval-minutes: 1440
    soft-delete: true
    stale-episodic-days: 30
    stale-semantic-days: 90
```

## Project Structure

```
agentmemory-java/
├── pom.xml
├── docker-compose.yml
├── scripts/
│   ├── stdio.sh                          # stdio mode launcher
│   └── claude-hooks/                     # Claude Code hook script examples
├── src/main/java/com/agentmemory/
│   ├── AgentMemoryApplication.java       # Spring Boot entry point
│   ├── StdioMcpServer.java               # stdio standalone process entry
│   ├── config/
│   │   ├── ElasticsearchConfig.java      # ES connection + index initialization
│   │   ├── DashScopeConfig.java          # DashScope model config
│   │   ├── OllamaConfig.java             # Ollama config
│   │   ├── RestRerankConfig.java         # REST rerank config
│   │   ├── McpConfig.java                # MCP SSE config
│   │   └── MemoryProperties.java         # Memory pipeline parameters
│   ├── configuration/
│   │   └── IndexMappingConfiguration.java # ES index mappings (4 indices)
│   ├── controller/
│   │   ├── MemoryController.java         # 13 REST endpoints
│   │   └── HealthController.java         # /health
│   ├── mcp/
│   │   └── McpToolRegistrar.java         # 11 MCP tool registrations
│   ├── model/
│   │   ├── Observation.java              # Raw observation
│   │   ├── MemoryTier.java               # 4-tier memory enum
│   │   ├── SessionRecord.java            # Session record
│   │   ├── SearchResult.java             # Search result (with RRF score)
│   │   ├── MemorySearchRequest.java      # Search request
│   │   └── TokenBudget.java              # Token budget formatting
│   └── service/
│       ├── MemoryPipelineService.java    # Dedup → filter → embed → save
│       ├── ElasticsearchService.java     # BM25 + kNN + RRF search
│       ├── MemoryConsolidationService.java # Background consolidation + decay + artifacts
│       ├── LifecycleCoordinator.java     # ES distributed lease (CAS)
│       ├── OllamaService.java            # Ollama text generation
│       ├── ReindexMigrationService.java  # Index migration (embedding dim changes)
│       ├── embed/
│       │   ├── EmbeddingService.java     # Embedding interface
│       │   ├── DashScopeEmbeddingService.java
│       │   └── OllamaEmbeddingService.java
│       └── rerank/
│           ├── RerankService.java        # Rerank interface
│           ├── DashScopeRerankService.java
│           └── RestRerankService.java
└── src/main/resources/
    ├── application.yml
    └── logback-stdio.xml                 # stdio logging (stderr)
```

## Known Issues & Notes

- ES client's `JacksonJsonpMapper` must register `JavaTimeModule`, otherwise `Instant` field serialization fails
- Use `elasticsearch:9.4.1` image; do not use `latest`
- When accessing localhost via curl, add `--noproxy localhost` to avoid proxy interception
- When DashScope API key is empty, a deterministic fallback embedding is used — search quality degrades but remains functional
- In stdio mode, logs go to stderr and do not interfere with the JSON-RPC protocol on stdout
- When switching from Ollama nomic-embed-text (768dim) to DashScope (1024dim), run `memory_reindex` to migrate indices
