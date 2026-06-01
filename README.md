# AgentMemory for Claude Code

为 Claude Code 构建的持久化记忆系统。参考 [rohitg00/agentmemory](https://github.com/rohitg00/agentmemory)，使用 Java + Elasticsearch + DashScope 实现。

## 架构

```
Claude Code ──(stdio)──> AgentMemory stdio 进程 ──┐
                                                 ├──> Elasticsearch (存储 + 向量搜索)
                                                 └──> DashScope (embedding + rerank)

Claude Code ──(HTTP)──> AgentMemory HTTP 服务 (端口 40080) ── 同上
```

两种运行模式共用同一套业务代码：

| 模式 | 适用场景 | 端口 |
|---|---|---|
| **stdio** | Claude Code MCP stdio 连接 | 无 (stdin/stdout JSON-RPC) |
| **HTTP** | REST API + MCP SSE | 40080 |

## 技术栈

| 组件 | 选择 | 说明 |
|---|---|---|
| 框架 | Java 21 + Spring Boot 3.4.5 | |
| 存储 | Elasticsearch 8.15.2 | docker compose + 持久化卷 |
| Embedding | DashScope text-embedding-v4 | 1024 维向量 |
| Rerank | DashScope gte-rerank | 相关性重排序 |
| MCP SDK | io.modelcontextprotocol.sdk:mcp 0.10.0 | |

## 核心特性

### 混合检索 (Hybrid Search)

1. **BM25** — 关键词匹配 (top-20)
2. **kNN 向量搜索** — 语义相似度 (top-20)
3. **RRF 融合** — `score = Σ 1/(k + rank)`, k=60
4. **DashScope rerank** — 相关性重排序
5. **Session 多样化** — 每会话最多返回 3 条
6. **Token 预算截断** — 按字符数截断 (~3 chars/token)

### 4 层记忆生命周期

| 层级 | 内容 | 提升条件 | 半衰期 |
|---|---|---|---|
| WORKING | 原始工具调用记录 | 1 小时后自动提升 | 1 天 |
| EPISODIC | 会话级摘要 | 7 天 + 访问 ≥ 3 次 → SEMANTIC | 30 天 |
| SEMANTIC | 提取的事实/模式 | 30 天 + 访问 ≥ 10 次 → PROCEDURAL | 60 天 |
| PROCEDURAL | 工作流/决策模式 | 最高层级 | 90 天 |

后台任务：
- **每 6 小时** — 记忆巩固（tier 提升）
- **每天 2:00** — 衰减扫描（3 倍半衰期自动淘汰）+ 矛盾检测

### 安全与去重

- SHA-256 内容哈希，5 分钟窗口去重
- 隐私过滤：自动脱敏 API key、secret、token

## 快速开始

### 1. 启动 Elasticsearch

```bash
cd agentmemory-java
docker compose up -d
```

### 2. HTTP 模式（REST API）

```bash
mvn spring-boot:run -DskipTests
```

### 3. stdio 模式（MCP）

```bash
mvn package -P stdio -DskipTests
```

### 4. 配置 Claude Code

编辑 `~/.claude/settings.json`：

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

## MCP 工具（8 个）

### memory_recall

搜索历史记忆，使用混合 BM25 + 向量 + rerank。

```json
{
  "query": "database optimization",
  "projectId": "optional-project-id",
  "sessionId": "optional-session-id",
  "tokenBudget": 2000
}
```

### memory_save

保存洞察、决策或模式到长期记忆。

```json
{
  "content": "Use composite indexes for join queries",
  "tier": "SEMANTIC",
  "tags": ["database", "performance"],
  "sessionId": "sess-123"
}
```

tier 可选值：`WORKING` | `EPISODIC` | `SEMANTIC` | `PROCEDURAL`

### memory_sessions

列出最近的编码会话。

```json
{
  "limit": 20
}
```

### memory_timeline

按时间顺序列出最近的工具调用记录。

```json
{
  "limit": 50
}
```

### memory_profile

获取项目画像：总观测数、top 文件、概念、模式。

无参数。

### memory_file_history

查看特定文件的历史观测记录。

```json
{
  "path": "src/auth/middleware.ts",
  "limit": 20
}
```

### memory_forget

删除指定记忆。

```json
{
  "id": "memory-uuid-here"
}
```

### memory_patterns

检测标签和工具使用的重复模式。

无参数。

## Lifecycle Management

AgentMemory implements a 4-tier memory lifecycle modelled after Ebbinghaus forgetting curves.

### Memory Tiers

| Tier | Contents | Promotion criteria | Decay half-life |
|---|---|---|---|
| **WORKING** | Raw tool-call observations | Age ≥ 1 day **or** accessed ≥ 3 times → EPISODIC | 3 days |
| **EPISODIC** | Session-level summaries | Age ≥ 7 days **and** accessed ≥ 3 times → SEMANTIC | 30 days |
| **SEMANTIC** | Extracted facts / patterns | accessed ≥ 10 times → PROCEDURAL | 90 days |
| **PROCEDURAL** | Durable workflows / decisions | Highest tier, never promoted | 180 days |

### What Triggers Lifecycle

| Trigger | When |
|---|---|
| **Scheduled (Spring)** | `memory.consolidation.cron` (default every 6 h) and `memory.decay.cron` (default 02:00 daily) |
| **Startup catch-up** | `isJobDue()` check: if last run was > `intervalMinutes` ago, the job fires immediately on start |
| **Manual (MCP tool)** | `memory_lifecycle_run` with `dryRun=false` |

### Multi-Instance Safety (stdio / Spring Boot)

Each JVM instance holds a unique `instanceId` (random UUID). Before executing a job, the
instance attempts to acquire an Elasticsearch-backed distributed lease stored in the
`memory-lifecycle-state` index. If another instance holds a valid (non-expired) lease, the
current instance skips the run. Leases expire after 10 minutes, preventing a crashed instance
from permanently blocking execution. Concurrent acquisitions are serialised via ES optimistic
concurrency (`if_seq_no` / `if_primary_term`).

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
|---|---|---|
| `GET` | `/health` | 健康检查 |
| `POST` | `/memory/observe` | 写入观测记录 |
| `POST` | `/memory/recall` | 混合搜索记忆 |
| `POST` | `/memory/save` | 手动保存洞察 |
| `POST` | `/memory/session/start` | 开始新会话 |
| `POST` | `/memory/session/end` | 结束会话 |
| `POST` | `/memory/prompt` | 记录用户 prompt |
| `GET` | `/memory/sessions` | 列出会话 |
| `GET` | `/memory/timeline` | 时间线 |
| `GET` | `/memory/profile` | 项目画像 |
| `GET` | `/memory/file-history?path=xxx` | 文件历史 |
| `GET` | `/memory/patterns` | 模式聚合 |
| `DELETE` | `/memory/{id}` | 删除记忆 |

### 示例

```bash
# 健康检查
curl http://localhost:40080/health

# 写入观测
curl -X POST http://localhost:40080/memory/observe \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "Read",
    "input": "Read auth.ts",
    "output": "Loaded 200 lines",
    "filePath": "src/auth.ts",
    "sessionId": "sess-1"
  }'

# 搜索记忆
curl -X POST http://localhost:40080/memory/recall \
  -H "Content-Type: application/json" \
  -d '{"query": "authentication middleware", "tokenBudget": 1000}'

# 保存洞察
curl -X POST http://localhost:40080/memory/save \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Use jose library for JWT, supports Edge runtime",
    "tier": "SEMANTIC",
    "tags": ["auth", "jwt"]
  }'

# 项目画像
curl http://localhost:40080/memory/profile
```

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `ES_HOST` | localhost | ES 地址 |
| `ES_PORT` | 9200 | ES 端口 |
| `ES_SCHEME` | http | http 或 https |
| `DASHSCOPE_API_KEY` | (空) | DashScope API key，为空时使用 fallback embedding |
| `DASHSCOPE_EMBEDDING_MODEL` | text-embedding-v4 | 嵌入模型 |
| `DASHSCOPE_EMBEDDING_DIMENSIONS` | 1024 | 向量维度 |
| `DASHSCOPE_RERANK_MODEL` | gte-rerank | 重排序模型 |

## 配置文件

### application.yml

```yaml
server:
  port: 40080

elasticsearch:
  host: localhost
  port: 9200

dashscope:
  api-key: ${DASHSCOPE_API_KEY:}
  embedding:
    model: text-embedding-v4
    dimensions: 1024
  rerank:
    model: gte-rerank

memory:
  token-budget: 2000
  dedup-window-minutes: 5
  rrf-k: 60
  max-results-per-session: 3
  consolidation:
    enabled: true
    cron: "0 0 */6 * * *"    # 每 6 小时巩固
  decay:
    enabled: true
    cron: "0 0 2 * * *"      # 每天 2 点衰减
```

## 项目结构

```
agentmemory-java/
├── pom.xml
├── docker-compose.yml
├── scripts/
│   ├── stdio.sh                          # stdio 模式启动脚本
│   └── claude-hooks/                     # Claude Code hook 脚本示例
├── src/main/java/com/agentmemory/
│   ├── AgentMemoryApplication.java       # Spring Boot 入口
│   ├── StdioMcpServer.java               # stdio 独立进程入口
│   ├── config/
│   │   ├── ElasticsearchConfig.java      # ES 连接 + 索引初始化
│   │   ├── DashScopeConfig.java          # DashScope 模型配置
│   │   ├── McpConfig.java                # MCP SSE 配置
│   │   └── MemoryProperties.java         # 记忆流水线参数
│   ├── controller/
│   │   ├── MemoryController.java         # 12 个 REST 端点
│   │   └── HealthController.java         # /health
│   ├── mcp/
│   │   └── McpToolRegistrar.java         # 8 个 MCP 工具注册
│   ├── model/
│   │   ├── Observation.java              # 原始观测
│   │   ├── MemoryTier.java               # 4 层记忆枚举
│   │   ├── SessionRecord.java            # 会话记录
│   │   ├── SearchResult.java             # 搜索结果 (含 RRF 分数)
│   │   ├── MemorySearchRequest.java      # 搜索请求
│   │   └── TokenBudget.java              # Token 预算格式化
│   └── service/
│       ├── MemoryPipelineService.java    # 去重 → 过滤 → embedding → 存储
│       ├── ElasticsearchService.java     # BM25 + kNN + RRF 搜索
│       ├── DashScopeService.java         # embedding + rerank HTTP 调用
│       └── MemoryConsolidationService.java # 后台巩固 + 衰减
└── src/main/resources/
    ├── application.yml
    ├── es-index-mappings.json            # 3 个索引映射
    └── logback-stdio.xml                 # stdio 日志 (stderr)
```

## 已知问题与注意事项

- ES 客户端的 `JacksonJsonpMapper` 必须注册 `JavaTimeModule`，否则 `Instant` 字段序列化失败
- 使用 `elasticsearch:8.15.2` 镜像，不要用 `latest`
- curl 访问 localhost 时加 `--noproxy localhost`，避免被代理拦截
- DashScope API key 为空时使用确定性 fallback embedding，搜索质量降低但功能可用
- stdio 模式下日志输出到 stderr，不干扰 stdout 上的 JSON-RPC 协议
