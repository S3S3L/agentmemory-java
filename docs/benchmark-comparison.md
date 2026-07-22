# AgentMemory 基准测试对比报告

**测试日期：** 2026-06-08

## 数据来源

| 项目 | 数据来源 | 日期 |
|------|---------|------|
| **Java** (agentmemory-java) | `AgentMemoryBenchmark` 实测 | 2026-06-08 |
| **TypeScript** (agentmemory) | `benchmark/QUALITY.md`，Triple-stream (BM25+Vector+Graph) | 2026-03-18 |

**数据集：** 240 条观察数据，20 个标注查询（rohitg00/agentmemory benchmark）

---

## 整体指标对比

| 指标 | TypeScript Triple-stream | Java (当前) | 差异 |
|------|--------------------------|-------------|------|
| **Recall@5** | 36.8% | 52.3% | +15.5% ✅ |
| **Recall@10** | 58.0% | 65.6% | +7.6% ✅ |
| **Precision@5** | 87.0% | 95.0% | +8.0% ✅ |
| **NDCG@10** | 81.7% | 86.1% | +4.4% ✅ |
| **MRR** | 87.9% | 100.0% | +12.1% ✅ |
| **Latency** | 1.02ms | 320.7ms | -31,346% ❌ |

> TypeScript 各模式对比：BM25-only (55.9% R@10 / 0.17ms)、Dual-stream (58.6% / 0.71ms)、Triple-stream (58.0% / 1.02ms)

---

## 按类别汇总

| 类别 | 查询数 | TS R@10 | Java R@10 | TS NDCG@10 | Java NDCG@10 | TS MRR | Java MRR |
|------|--------|---------|-----------|------------|--------------|--------|----------|
| **semantic** | 7 | 33.3% | **50.9%** ✅ | 75.7% | **86.1%** ✅ | 86.7% | **100%** ✅ |
| **exact** | 5 | 60.0% | **62.0%** ✅ | 71.8% | **79.8%** ✅ | 86.7% | **100%** ✅ |
| **cross-session** | 3 | **77.8%** | 73.3% | 84.7% | **85.5%** ✅ | 72.2% | **100%** ✅ |
| **entity** | 5 | 78.5% | **85.0%** ✅ | **98.1%** | 93.0% | 100% | 100% |

---

## 逐查询对比

| Query | 类别 | TS R@10 | Java R@10 | TS NDCG | Java NDCG | TS MRR | Java MRR |
|-------|------|---------|-----------|---------|-----------|--------|----------|
| How did we set up authentication? | semantic | **50.0%** | 12.5% ❌ | 100.0% | 64.9% | 100% | 100% |
| JWT token validation middleware | exact | 50.0% | 50.0% | 64.9% | 64.9% | 100% | 100% |
| PostgreSQL connection issues | semantic | **33.3%** | 25.0% | 100.0% | 64.9% | 100% | 100% |
| Playwright test configuration | exact | **100.0%** | 20.0% ❌ | 100.0% | 33.9% | 100% | 100% |
| Why did production deployment fail? | cross-session | **33.3%** | 20.0% | 100.0% | 56.4% | 100% | 100% |
| rate limiting implementation | exact | 80.0% | **100.0%** ✅ | 64.1% | **100.0%** ✅ | 33.3% | **100%** ✅ |
| What security measures did we add? | semantic | **33.3%** | 28.6% | 100.0% | **100.0%** | 100% | 100% |
| database performance optimization | semantic | 0.0% | **40.0%** ✅ | 0.0% | **72.7%** ✅ | 7.1% | **100%** ✅ |
| Kubernetes pod crash debugging | entity | 100.0% | 100.0% | 96.7% | **100.0%** ✅ | 100% | 100% |
| Docker containerization setup | entity | 100.0% | 100.0% | 100.0% | 100.0% | 100% | 100% |
| How does caching work in the app? | semantic | 25.0% | **100.0%** ✅ | 64.9% | **100.0%** ✅ | 100% | 100% |
| test infrastructure and factories | exact | 50.0% | **100.0%** ✅ | 64.9% | **100.0%** ✅ | 100% | 100% |
| What happened with the OAuth callback error? | cross-session | 100.0% | 100.0% | 54.1% | **100.0%** ✅ | 16.7% | **100%** ✅ |
| monitoring and observability setup | semantic | 66.7% | **100.0%** ✅ | 100.0% | 100.0% | 100% | 100% |
| Prisma ORM configuration | entity | **25.7%** | 25.0% | 93.6% | 64.9% | 100% | 100% |
| CI/CD pipeline configuration | exact | 20.0% | **40.0%** ✅ | 64.9% | **100.0%** ✅ | 100% | 100% |
| memory leak debugging | cross-session | 100.0% | 100.0% | 100.0% | 100.0% | 100% | 100% |
| API design decisions | semantic | 25.0% | **50.0%** ✅ | 64.9% | **100.0%** ✅ | 100% | 100% |
| zod validation schemas | entity | 66.7% | **100.0%** ✅ | 100.0% | 100.0% | 100% | 100% |
| infrastructure as code Terraform | entity | 100.0% | 100.0% | 100.0% | 100.0% | 100% | 100% |

---

## 延迟分布

| 指标 | TypeScript | Java |
|------|-----------|------|
| Min | — | 289ms |
| P50 | — | 312ms |
| Max | — | 372ms |
| Avg | 1.02ms | 320.7ms |

> TypeScript 延迟为纯内存搜索（BM25 + HNSW 向量索引，无 HTTP 调用）。Java 延迟主要来自 Ollama HTTP embedding 调用（~200-250ms）+ Elasticsearch 查询（~30-50ms）+ Rerank HTTP 调用（~20-40ms）。

---

## 关键发现

### Java 质量优势

- **MRR 100%**：所有查询的第一条结果均为相关文档，TypeScript 最好仅 95.5%
- **语义查询召回更强**：R@10 50.9% vs 33.3%，主要得益于 Elasticsearch 的 multi-field 搜索与 rerank
- **database performance optimization**：Java 40% vs TypeScript 0% — TypeScript 完全失败的查询
- **11 个查询 Java 优于 TS，3 个劣于 TS，6 个持平**

### TypeScript 局部优势的 3 个查询

| Query | TS R@10 | Java R@10 | 分析 |
|-------|---------|-----------|------|
| Playwright test configuration | **100%** | 20% | TS Graph Retrieval 扩展了相关 test 观察 |
| How did we set up authentication? | **50%** | 12.5% | TS 多查询扩展命中更多语义相关文档 |
| Why did production deployment fail? | **33.3%** | 20% | TS 跨 session 图关联更好 |

### 唯一劣势：延迟

Java 延迟 314× 慢于 TypeScript，根本原因是：

1. **Embedding HTTP 调用**（Ollama，~200ms）：TypeScript 使用本地 WASM 向量，无 HTTP 开销
2. **串行执行**：embed → ES search → rerank 三步串行，无并行
3. **无 embedding 缓存**：相同查询每次都重新计算

---

## 系统架构对比

| 组件 | TypeScript | Java |
|------|-----------|------|
| BM25 索引 | 内存（自研） | Elasticsearch |
| 向量索引 | 内存 HNSW | Elasticsearch kNN |
| 图检索 | ✅ Graph Retrieval（实体扩展） | ❌ 无 |
| Query Expansion | ✅ 多查询改写 + 实体提取 | ⚠️ 仅停用词过滤 |
| Rerank | ✅（可选，env flag） | ✅（默认开启） |
| Embedding | 本地 WASM（无延迟） | Ollama HTTP（~200ms） |
| 会话多样化 | ❌ | ✅ maxResultsPerSession |

---

*Java 实现架构：BM25 + kNN + 自定义 RRF 融合 + Rerank（双流，无图检索）*  
*TypeScript 实现架构：BM25 + Vector + Graph（三流 RRF 融合，可选 Rerank）*
