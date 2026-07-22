# AgentMemory for Claude Code

为 Claude Code 构建的持久化记忆系统。参考 [rohitg00/agentmemory](https://github.com/rohitg00/agentmemory)，使用 Java + Elasticsearch + DashScope 实现。

## 架构

```
                              ┌──> Elasticsearch 9.4.1 (BM25 + kNN 向量搜索 + 分布式租约)
                              │
Claude Code ──(stdio MCP)──> AgentMemory ──┼──> DashScope / Ollama (embedding, 可切换)
                              │
Claude Code ──(HTTP REST)──>  (端口 40080) ──┼──> DashScope / REST (rerank, 可切换)
                              │
                              └──> Ollama (consolidation 文本生成)
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
| 存储 | Elasticsearch 9.4.1 | docker compose + 持久化卷 |
| Embedding | DashScope text-embedding-v4 (1024维) / Ollama nomic-embed-text (768维) | 通过 `agentmemory.embedding` 配置切换 |
| Rerank | DashScope gte-rerank / REST 自定义 rerank | 通过 `agentmemory.rerank` 配置切换 |
| MCP SDK | io.modelcontextprotocol.sdk:mcp 0.10.0 | |
| HTTP 客户端 | OkHttp 4.12.0 | |
| 缓存 | Caffeine 3.1.8 | embedding 缓存 (1 万条, 6 小时有效期) |

## 核心特性

### 混合检索

1. **查询扩展** — 领域同义词扩展 + 词干提取
2. **BM25 多字段加权搜索** — title^3 / concepts^2.5 / tags^2 / facts^2 / filePath^1.5 / content（虚拟线程并行执行）
3. **kNN 向量搜索** — 语义相似度 (top-20)
4. **RRF 融合** — `score = Σ 1/(k + rank)`, k=60
5. **条件 Rerank** — DashScope gte-rerank 或 REST 自定义 rerank；支持跳过策略：结果≤3 或 top-1 分数远超 top-2 时跳过
6. **低分过滤** — rerank 分数低于阈值的结果被丢弃 (默认 minRerankScore=-5.0)
7. **会话去重** — 每会话最多返回 3 条
8. **Token 预算截断** — 按字符数截断 (~3 chars/token) + 头尾保留策略
9. **Embedding 缓存** — Caffeine 缓存，1 万条，6 小时有效期，命中则跳过 embedding API 调用

### 4 层记忆生命周期

| 层级 | 内容 | 提升条件 | 半衰期 |
|---|---|---|---|
| WORKING | 原始工具调用记录 | 年龄 ≥ 1 天 **或** 访问 ≥ 3 次 → EPISODIC | 3 天 |
| EPISODIC | 会话级摘要 | 年龄 ≥ 7 天 **且** 访问 ≥ 3 次 → SEMANTIC | 30 天 |
| SEMANTIC | 提取的事实/模式 | 访问 ≥ 10 次 → PROCEDURAL | 90 天 |
| PROCEDURAL | 工作流/决策模式 | 最高层级 | 180 天 |

后台任务：
- **每 6 小时** — 记忆巩固（层级提升 + 合并产物生成 + 确定性 ID 去重）
- **每天 2:00** — 衰减扫描 + 矛盾检测
- **分布式协调** — 基于 ES 乐观并发控制的分布式租约，多实例安全（参见 LifecycleCoordinator）
- **软删除** — 衰减支持软删除（`isActive=false`，可通过配置切换为硬删除）
- **标签保留** — 衰减/提升时保留原始标签

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

## MCP 工具（11 个）

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

获取项目画像：总观测数、高频文件、概念、模式。

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

### memory_reindex

将记忆索引迁移到新的 embedding 模型/维度。创建新索引，重新嵌入所有内容后原子切换别名。

```json
{
  "dryRun": true
}
```

`dryRun=true` 仅统计文档数并验证，不写入数据。设为 `false` 执行实际迁移。

### memory_lifecycle_status

返回所有生命周期作业的当前状态（consolidation + decay），包括租约持有者、上次运行时间、下次到期时间。

无参数。

### memory_lifecycle_run

手动触发生命周期作业或预览候选数据。

```json
{
  "job": "consolidation",
  "dryRun": true
}
```

`job` 可选值：`consolidation` | `decay` | `all`。`dryRun=true` 仅返回候选数量，不修改数据。

## 生命周期管理

AgentMemory 实现了基于艾宾浩斯遗忘曲线的 4 层记忆生命周期。

### 记忆层级

| 层级 | 内容 | 提升条件 | 衰减半衰期 |
|---|---|---|---|
| **WORKING** | 原始工具调用观测 | 年龄 ≥ 1 天 **或** 访问 ≥ 3 次 → EPISODIC | 3 天 |
| **EPISODIC** | 会话级摘要 | 年龄 ≥ 7 天 **且** 访问 ≥ 3 次 → SEMANTIC | 30 天 |
| **SEMANTIC** | 提取的事实 / 模式 | 访问 ≥ 10 次 → PROCEDURAL | 90 天 |
| **PROCEDURAL** | 持久化工作流 / 决策 | 最高层级，不再提升 | 180 天 |

### 触发方式

| 触发方式 | 时机 |
|---|---|
| **定时任务 (Spring)** | `memory.consolidation.cron`（默认每 6 小时）和 `memory.decay.cron`（默认每天 02:00） |
| **启动追赶** | `isJobDue()` 检查：如果距上次运行超过 `intervalMinutes`，启动时立即执行 |
| **手动触发 (MCP 工具)** | `memory_lifecycle_run` 设置 `dryRun=false` |

### 多实例安全（stdio / Spring Boot）

每个 JVM 实例持有唯一的 `instanceId`（随机 UUID）。在执行作业前，实例尝试获取存储在 `memory-lifecycle-state` 索引中的 Elasticsearch 分布式租约。如果另一个实例持有有效（未过期）的租约，当前实例将跳过本次运行。租约在 10 分钟后过期，防止崩溃实例永久阻塞执行。并发获取通过 ES 乐观并发控制（`if_seq_no` / `if_primary_term`）序列化。

### 配置参考

所有配置键均位于 `application.yml` 的 `memory:` 前缀下。

#### `memory.consolidation.*`

| 键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 启用/禁用 consolidation 作业 |
| `cron` | `0 0 */6 * * *` | Spring cron 表达式，用于定时执行 |
| `interval-minutes` | `360` | 运行间隔（分钟）；供 `isJobDue()` 启动追赶使用 |
| `summary-model` | `llama3.2` | 用于生成合并产物摘要文本的 Ollama 模型 |
| `promotion.working-to-episodic-days` | `1` | WORKING → EPISODIC 提升的年龄阈值（天） |
| `promotion.working-to-episodic-access-count` | `3` | WORKING → EPISODIC 提升的访问次数阈值（OR 关系） |
| `promotion.episodic-to-semantic-days` | `7` | EPISODIC → SEMANTIC 提升的年龄阈值（天） |
| `promotion.episodic-to-semantic-access-count` | `3` | EPISODIC → SEMANTIC 提升的访问次数阈值（AND 关系） |
| `promotion.semantic-to-procedural-access-count` | `10` | SEMANTIC → PROCEDURAL 提升的访问次数阈值 |

#### `memory.decay.*`

| 键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 启用/禁用 decay 扫描作业 |
| `cron` | `0 0 2 * * *` | Spring cron 表达式（每天 02:00） |
| `interval-minutes` | `1440` | 运行间隔（分钟）；供 `isJobDue()` 启动追赶使用 |
| `soft-delete` | `true` | `true` = 设置 `isActive=false`；`false` = 从 ES 硬删除 |
| `stale-episodic-days` | `30` | EPISODIC 记忆被视为过期的天数 |
| `stale-semantic-days` | `90` | SEMANTIC 记忆被视为过期的天数 |

### MCP 生命周期工具

#### `memory_lifecycle_status`

返回所有生命周期作业的当前状态。无参数。

响应 JSON 结构：
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

手动触发生命周期作业或预览候选数据。

```json
{
  "job": "consolidation",
  "dryRun": true
}
```

`job` 可选值：`consolidation` | `decay` | `all`。`dryRun=true` 仅返回候选数量，不修改数据。

## REST API

| 方法 | 路径 | 说明 |
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
| `GET` | `/memory/metrics/project` | 项目指标统计 |
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
| `OLLAMA_ENDPOINT` | http://localhost:11434 | Ollama 服务地址 |
| `OLLAMA_EMBEDDING_MODEL` | nomic-embed-text | Ollama 嵌入模型 |
| `OLLAMA_DIMENSIONS` | 768 | Ollama 向量维度 |
| `REST_RERANK_ENDPOINT` | http://localhost:8000/rerank | 自定义 rerank 端点 |
| `AGENTMEMORY_EMBEDDING` | DASH_SCOPE | 嵌入实现选择：`DASH_SCOPE` / `OLLAMA` |
| `AGENTMEMORY_RERANK` | DASH_SCOPE | 重排序实现选择：`DASH_SCOPE` / `REST` |

## 配置文件

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
│   │   ├── OllamaConfig.java             # Ollama 配置
│   │   ├── RestRerankConfig.java         # REST rerank 配置
│   │   ├── McpConfig.java                # MCP SSE 配置
│   │   └── MemoryProperties.java         # 记忆流水线参数
│   ├── configuration/
│   │   └── IndexMappingConfiguration.java # ES 索引映射 (4 个索引)
│   ├── controller/
│   │   ├── MemoryController.java         # 13 个 REST 端点
│   │   └── HealthController.java         # /health
│   ├── mcp/
│   │   └── McpToolRegistrar.java         # 11 个 MCP 工具注册
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
│       ├── MemoryConsolidationService.java # 后台巩固 + 衰减 + 合并产物
│       ├── LifecycleCoordinator.java     # ES 分布式租约 (CAS)
│       ├── OllamaService.java            # Ollama 文本生成
│       ├── ReindexMigrationService.java  # 索引迁移 (embedding 维度变更)
│       ├── embed/
│       │   ├── EmbeddingService.java     # Embedding 接口
│       │   ├── DashScopeEmbeddingService.java
│       │   └── OllamaEmbeddingService.java
│       └── rerank/
│           ├── RerankService.java        # Rerank 接口
│           ├── DashScopeRerankService.java
│           └── RestRerankService.java
└── src/main/resources/
    ├── application.yml
    └── logback-stdio.xml                 # stdio 日志 (stderr)
```

## 已知问题与注意事项

- ES 客户端的 `JacksonJsonpMapper` 必须注册 `JavaTimeModule`，否则 `Instant` 字段序列化失败
- 使用 `elasticsearch:9.4.1` 镜像，不要用 `latest`
- curl 访问 localhost 时加 `--noproxy localhost`，避免被代理拦截
- DashScope API key 为空时使用确定性 fallback embedding，搜索质量降低但功能可用
- stdio 模式下日志输出到 stderr，不干扰 stdout 上的 JSON-RPC 协议
- 从 Ollama nomic-embed-text (768维) 切换到 DashScope (1024维) 时需要运行 `memory_reindex` 迁移索引
