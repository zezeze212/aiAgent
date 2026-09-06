# ai-agent-demo 项目总纲

> 文档职责：本文件是项目定位、架构边界和 M0～M11 长期路线的唯一权威来源。  
> 当前代码事实、验证结果、已知限制和下一阶段入口见 [CURRENT-STATUS.md](CURRENT-STATUS.md)。  
> 初版制定：2026-08-02。  
> 本次更新：2026-08-30（Asia/Shanghai）。

## 1. 项目定位

`ai-agent-demo` 是个人学习和求职展示项目，长期定位为：

> **面向 Java 后端故障诊断的、证据驱动、可评测、可观测、支持多模型与完整 RAG 的 AI Agent 应用。**

职业主线：

> **Java 后端 + AI Agent 应用工程化**

当前核心场景：

- SQL / MyBatis 报错诊断；
- 数据库真实 Schema 分析；
- Java StackTrace 分析；
- 技术知识辅助诊断。

项目最终应通过代码、测试、Trace、知识引用和阶段报告证明：

- Java / Spring Boot AI 应用后端能力；
- Spring AI 标准生态使用能力；
- Tool Calling、参数、权限、超时和失败治理；
- Tool、RAG 与模型推测之间的证据优先级；
- 知识处理、Embedding、向量库和检索的完整 RAG 能力；
- DeepSeek 与 AutoDL 自部署模型的统一接入；
- 异步任务、SSE、Observability、MCP、Vue 和部署能力。

项目不以算法研究、预训练、训练框架研发或纯模型训练岗位为目标。

---

## 2. 核心原则与边界

### 2.1 证据优先级

项目统一遵守：

> **工具真实证据 > 可追溯 RAG 证据 > 模型通用推测。**

依赖真实数据库、异常日志或知识文档的结论，不得用模型猜测替代可验证证据。

### 2.2 Evaluation 的职责边界

Evaluation 用来测量系统，不用来制造满分。

统一原则：

- 固定数据集用于观察能力、失败分布和版本变化；
- 基线允许存在失败和模型随机性；
- 只有具备独立产品价值、安全价值或架构价值的问题，才进入业务修复；
- 不根据单条评测用例硬编码答案、参数或工具路由；
- 不为了把指标刷到 100% 反复修改 Prompt、数据集和生产逻辑；
- 数据集或评分器自身出现误判时可以修正规则，但必须保留版本；
- 评测在阶段入口、重要变更和阶段封版时运行，不作为持续打补丁循环。

M4 已经完成。除非用户明确要求，不再返回 M4 继续扩题、刷分或修模型个例。

### 2.3 与工作项目完全隔离

不得复制、影射或引入工作项目中的：

- 业务代码；
- 接口定义；
- 数据结构；
- 业务数据；
- 内部文档；
- 私有地址和敏感业务概念。

可以学习异步任务、线程池、状态机、超时、幂等、SSE 和可观测性等通用工程模式，但必须围绕公开的 Java 后端故障诊断场景重新设计。

### 2.4 Java / Python 边界

Java / Spring Boot 承担在线主链：

- API；
- Agent；
- Spring AI；
- Tool Calling；
- RAG 在线检索；
- 模型路由；
- 异步任务与 SSE；
- Trace 与持久化。

Python 承担两类明确受限的职责：

- JSONL 评测；
- 知识清洗与分块实验；
- 检索指标统计；
- 报告生成；
- AutoDL 训练和模型评测；
- 独立 Embedding / Sparse Embedding / Reranker 推理服务。

Python 模型服务只暴露模型推理 API，不承载 Agent 编排、Tool、业务状态、权限和主数据。Java 仍是唯一在线业务主链，不使用 Python 重写 Java Agent 主后端。

### 2.5 工具与数据库安全

数据库工具默认只读：

- 不执行任意用户 SQL；
- 不自动执行 DDL；
- 不自动执行 UPDATE / DELETE；
- 模型生成的 Tool 参数必须经过确定性校验；
- Tool 业务失败必须保留结构化状态和 Trace。

### 2.6 不继续无限扩自研框架

M2 已完成 Tool Contract / Registry / Governance；M3 已完成 Spring AI 正式主链迁移。

后续原则：

> **优先使用 Spring AI 标准抽象，只保留具有业务、安全或明确教学价值的自研部分。**

旧手写 Agent 链路冻结保留，用于架构对比，不再同步扩展新能力。

---

## 3. 当前架构基线

当前正式链路：

```text
POST /agent/ask
  → SpringAiToolChatService
  → SpringAiRequestBoundaryPolicy
  → SpringAiKnowledgeSupport
  → PRE_DECISION SimpleRagRetriever
  → Spring AI ChatClient / DeepSeek
  → Spring AI Tool Calling Loop
  → SpringAiDiagnosticTools
  → SpringAiToolRunContext
  → ToolRegistry
  → ToolAccessPolicy / 参数校验 / timeout / business error
  → 具体 Tool
  → POST_TOOL RAG（按需、去重）
  → Spring AI 根据证据生成回答
  → Trace 脱敏
  → AgentLogService
  → MySQL
  → 同步 HTTP 返回
```

当前三个正式 Tool：

- `getTableSchema`；
- `analyzeSqlErrorWithSchema`；
- `analyzeStackTrace`。

旧 `SimpleAgentService / AgentOrchestrator / DeepSeekDecisionClient` 手写链路冻结保留，不承载正式入口。

---

## 4. 技术职责

### 4.1 Java / Spring Boot

负责 Agent 后端、Spring AI、Tool、在线 RAG、模型路由、异步任务、SSE、Trace、MySQL、API、Observability 和 MCP。

### 4.2 Spring AI

作为主 Java AI 框架，重点覆盖：

- ChatModel / ChatClient；
- Prompt / Message；
- Tool Calling；
- Advisors / 扩展点；
- Embedding Model；
- Vector Store；
- RAG 抽象；
- Observability；
- OpenAI-compatible Provider。

项目当前使用 Spring AI 1.1.8。新增依赖前必须核对该版本的真实兼容性，不凭印象套用其他版本 API。

### 4.3 Vue

后期用于提交诊断、选择模型、展示异步状态、SSE 步骤、Tool Evidence、RAG 来源、最终回答、耗时和历史 Trace。

### 4.4 知识源

个人 Wiki 可以成为知识源，但只能通过显式 manifest 筛选公开、脱敏 Markdown。必须保留来源、标题、章节、哈希、版本和更新时间，并支持增量更新与删除。不得直接导入整个私人 Wiki。

M5 第一版优先选择约 15～30 篇与 Java、Spring、MySQL、Docker、模型部署和通用排障相关的高质量文档。不得导入工作项目代码、接口、表结构、日志、内部文档、人员或服务器信息。

PDF 解析在 Markdown 链路稳定后，以一个公开或自有 PDF 做独立实验，不阻塞第一版 Vector RAG。

---

## 5. 阶段路线总览

| 阶段 | 状态 | 核心结果 | 建议里程碑 |
|---|---|---|---|
| M0 | 已完成 | PRE_DECISION / POST_TOOL 规则 RAG、去重、Trace | `v0.3.0-rag-closeout` |
| M1 | 已完成 | Compose MySQL、Flyway、事务、Testcontainers | `v0.4.0-reproducible` |
| M2 | 已完成 | Tool Contract / Governance、多类型 Tool、Trace 安全治理 | `v0.5.0-m2-closeout` |
| M3 | 已完成 | Spring AI 主链、原生 Tool Calling、正式入口切换 | `v0.6.0-spring-ai`（本地核验） |
| M4 | 已完成 | Python Evaluation Harness、14条校准集、50条回归集、版本化报告 | `v0.7.0-evaluation`（本地核验） |
| M5 | 当前唯一入口 | Qdrant Dense Vector RAG 竖切、引用、检索种子集和基线数字 | `v0.8.0-vector-rag` |
| M6 | 后续计划 | FastAPI 模型服务、Rerank、按证据实施 Hybrid、Query Rewrite、Benchmark | `v0.9.0-rag-benchmark` |
| M7 | 后续计划 | Async Task、SSE、最小 Vue、Demo Compose 和可演示闭环 | `v0.10.0-demo-mvp` |
| M8 | 后续计划 | DeepSeek + AutoDL 多模型、可选微调 | `v0.11.0-multi-model` |
| M9 | 后续计划 | Micrometer / Spring AI Observability、可选 Langfuse、MCP | `v0.12.0-observable-mcp` |
| M10 | 选做 | Elasticsearch BM25/IK 对照；Neo4j / GraphRAG 只做边界研究 | `v0.13.0-retrieval-lab` |
| M11 | 后续计划 | CI、部署加固、README、评测报告与作品集收束 | `v1.0.0-portfolio-ready` |

---

## 6. M0～M4 已完成摘要

### M0：规则 RAG 基线

完成首次决策前和工具后知识检索、知识去重、classpath Markdown、SQL 故障知识和 Trace。当前 `SimpleRagRetriever` 是后续 Vector RAG 的对照基线，不是最终检索实现。

### M1：可复现数据库环境

完成 Docker Compose MySQL、Flyway V1、Run/Step 短事务、Testcontainers、Maven Wrapper 和空环境复现。

### M2：通用 Tool 治理

完成 Tool Contract、参数强校验、权限、风险、副作用、timeout、业务错误、三个诊断 Tool、Flyway V2 和 Trace 安全治理。M2 不再扩展为通用工作流或审批平台。

### M3：Spring AI 正式主链

完成 Spring AI `ChatClient`、三个原生 Tool Schema、Tool Calling Loop、现有治理复用、PRE/POST RAG 迁移、Trace/持久化接回和 `/agent/ask` 正式入口切换。

### M4：Evaluation Harness

完成：

- JSONL 数据契约；
- Python 执行器、评分器和报告；
- Dataset / Evaluator / Prompt / Knowledge / Code 版本记录；
- 14条种子校准集；
- 50条回归集；
- Tool、参数、证据、RAG、业务错误、澄清和无依据回答指标；
- Manifest 与 Git commit 一致性核对；
- 失败用例和检查项定位。

M4 的结论是“评测框架可用”，不是“所有模型行为必须达到 100%”。已发现的模型工具选择和结构化调用不稳定作为基线事实保留，不在 M4 继续打补丁。

---

## 7. M5：Dense Vector RAG 竖切与检索基线

M5 是当前唯一入口。目标不是先完成一套抽象设计，而是在最小数据契约下尽快得到第一个“可检索、可引用、可测量”的真实回答，再根据运行结果补齐知识工程。

### 7.1 已确定选型

```text
Java / Agent：Spring AI 1.1.8
Chat / Tool Calling：DeepSeek OpenAI-compatible API
Dense Embedding baseline：Ollama + BGE-M3
主向量库：Qdrant
在线集成：Spring AI VectorStore.similaritySearch()
规则基线：SimpleRagRetriever（保留、可切换）
```

Qdrant 是唯一主向量库；M5 不引入 pgvector、Elasticsearch、Milvus、Neo4j 或第二套 Vector Store。

Ollama 只承担 M5 的 dense embedding baseline。它不承担后续 rerank，不能被描述为最终统一模型服务。

### 7.2 M5 分步

#### M5-0：现状审计与选型——已完成

已完成规则 RAG、Spring AI 1.1.8、Windows + Docker、本地依赖、Qdrant、Ollama 和后续扩展边界审计。新对话不重新讨论 Qdrant、pgvector、Milvus 或 Elasticsearch 谁更适合作为 M5 主库。

#### M5-1：端到端竖切 + 检索种子集——当前入口

同步完成两条最小链路：

```text
公开脱敏 Wiki Markdown
→ 最小 manifest
→ 简单章节切块
→ Ollama BGE-M3 dense embedding
→ Qdrant
→ TopK
→ Spring AI Prompt
→ 带来源回答
```

以及：

```text
10～15 条冻结检索种子题
→ expected source / section / evidence text
→ Recall@5 / MRR / latency baseline
```

评测集不得把正确 `chunkId` 写死。Chunk 边界会随策略变化，正确证据应标注为来源文档、章节和证据文本，由评测器判断返回 Chunk 是否覆盖证据。

第一版直接使用 Qdrant，不再为了过渡实现 `SimpleVectorStore`。

#### M5-2：知识工程与可维护入库

在第一条链路可运行后补齐：

- Markdown 清洗；
- 章节感知 Chunk；
- 可解释的 chunk size / overlap；
- source、title、section、category、visibility、version、hash 等 metadata；
- manifest allowlist；
- checksum 增量新增、更新、删除和重建；
- Embedding 模型或维度变化后的 collection 重建规则。

只比较少量预先约定的 Chunk 配置，不进行无终点调参。

#### M5-3：正式主链接入、拒答与引用

把 Vector Retrieval 接入 `SpringAiKnowledgeSupport`，保留 PRE_DECISION / POST_TOOL、知识去重、Trace 和 Tool Evidence 优先级。

必须完成：

- 多 Chunk 结果契约；
- TopK、threshold 和 metadata scope filter；
- Context 去重、排序和 token budget；
- 无可靠知识时明确拒答或降级；
- 来源、标题、章节和 Chunk 级引用；
- `SimpleRagRetriever` / `VectorKnowledgeRetriever` 可切换 baseline。

当前没有真实多租户认证，不为了简历制造虚假的租户和角色体系。M5 只实现真实可验证的知识范围过滤。

#### M5-4：扩展检索集与工程验收

链路稳定后，把检索集扩展到约 30～50 条，并冻结 seed / regression 版本。题目至少覆盖：

- 自然语言改写；
- 精确类名、错误码、配置项；
- 中英文混合与代码；
- 可回答与不可回答问题；
- 多个相似文档之间的区分。

M5 验收：

- 真实 Qdrant 可启动、写入、检索、更新和删除；
- 回答带可追溯引用；
- 检索不到时不会伪造知识；
- 输出 Recall@5、MRR、P50/P95 或等价延迟数据；
- 受影响测试、真实 API 验证和阶段文档完成；
- 不为单条失败添加固定答案、关键词路由或生产补丁。

### 7.3 M5 必须学习

- Loader / Clean / Chunk / overlap；
- token 与字符长度；
- metadata / version / hash；
- Embedding 与生成模型区别；
- vector dimension；
- cosine / dot product / L2；
- collection / index；
- TopK / threshold / metadata filter；
- ANN / HNSW 基本原理；
- Recall@K、MRR 和延迟；
- 增量更新、删除和索引重建；
- Context 去重、排序、token budget、拒答和 citation。

---

## 8. M6：FastAPI 模型服务、Rerank、Hybrid 与 Benchmark

M6 的目标是用同一冻结检索集验证 Advanced RAG 的真实收益，而不是实现所有 RAG 名词。

### 8.1 Python FastAPI 模型服务

建立独立 Python 推理服务，至少提供：

```text
GET  /health
POST /v1/embeddings
POST /v1/rerank
```

第一步接入 `bge-reranker-v2-m3`；随后才把 BGE-M3 dense / sparse 统一迁入该服务。Java 通过清晰的 HTTP 契约调用，Python 服务不承担 Agent 编排和业务状态。

这一阶段证明模型加载、推理 API、Java/Python 对接、超时和失败处理；它本身不等于已经完成流式接口、长任务异步回调或任务队列。

### 8.2 Rerank——必做实验，不保证默认启用

实现：

```text
Dense TopN 候选
→ bge-reranker-v2-m3
→ TopK 上下文
```

比较启用前后的 Recall@5、MRR、P50/P95、候选数量和失败类别。Reranker 必须接入并评测，但最终是否默认启用由收益和延迟决定。

### 8.3 Sparse / Hybrid——按失败证据实施

当 dense 基线明确暴露错误码、类名、表名、配置项或专有缩写等精确匹配失败时，再实现：

```text
BGE-M3 dense + lexical sparse
→ Qdrant named vectors
→ prefetch
→ RRF
→ 可选 rerank
```

Spring AI 通用 `VectorStore` 继续承担 dense baseline；Qdrant hybrid 通过 `getNativeClient()` 下沉原生客户端实现。不得把 Qdrant 特有代码泄漏到所有业务层。

M6 不为了 Hybrid 强制引入 Elasticsearch。

### 8.4 Query Rewrite 优先级

按实际价值依次考虑：

1. 多轮对话指代消解与 query rewrite；
2. Multi-Query 小实验；
3. HyDE 只做理解或有限实验。

一次只引入一种策略并运行同一冻结数据集。Parent-Child、Contextual Compression、Agentic RAG 不作为 M6 必做项。

### 8.5 Benchmark 与停止线

计划内比较：

- rule / keyword baseline；
- dense vector；
- dense + rerank；
- dense + sparse + RRF（有失败证据时）；
- hybrid + rerank（有必要时）；
- 选定的 query rewrite 策略；
- 少量 Chunk / TopK 参数组合；
- citation correctness、拒答和 context token budget。

阶段输出 `RAG-BENCHMARK.md`，至少报告 Recall@5、MRR、延迟、失败类型、参数版本和最终取舍。

M6 完成后停止继续堆 RAG 组件，优先进入 M7 可演示闭环。Elasticsearch、Langfuse、Neo4j 和 GraphRAG 均不得阻塞 M7。

---

## 9. M7～M11

### M7：可演示产品闭环

M7 把原异步、前端和基础部署工作压缩为一个 Demo MVP：

- Task 表和状态机；
- 独立线程池、幂等、timeout、有限重试和恢复；
- SSE 推送 Agent / Tool / RAG 步骤；
- 最小 Vue 页面，展示输入、状态、Tool Evidence、RAG 引用、最终回答、耗时和历史 Trace；
- Spring Boot、Vue、MySQL、Qdrant 的 Demo Compose；
- README、架构图、启动步骤和固定演示场景。

暂不默认引入 MQ，不在 M7 追求复杂权限后台和完整视觉设计。

### M8：DeepSeek + AutoDL 多模型

同一 Java Agent、Tool、RAG 和接口支持多个 `modelId`。AutoDL 优先使用 vLLM 暴露 OpenAI-compatible API；实例关闭时允许自部署模型 `UNAVAILABLE`，不能拖垮 DeepSeek 主链。

LoRA / QLoRA 是可选增强，只有小模型基线暴露明确领域差距时才考虑；不把训练 loss 当成项目成功标准。

### M9：Observability + MCP

第一层保留并完善现有 `agent_run_log`、`agent_step_log` 和业务 Trace；第二层接入 Actuator / Micrometer、模型 / Tool / RAG 指标、token usage、Tracing、P50/P95 和 Spring AI Observability。

Langfuse 是 LLM Trace、Token、Prompt 和评测增强项，只能在 M7 可演示闭环后接入，不替代应用指标和现有业务审计，也不得成为 M5/M6 的阻塞项。

MCP 学习基本协议并完成一个小型、可解释集成，不扩展为多 Agent 平台。

### M10：检索扩展实验——选做

项目已经可演示且时间充足时，可以使用 Elasticsearch 做有限 BM25 / IK 对照，重点观察中文分词、错误码、专有名词和聚合能力。不再重复实现一套完整 Elasticsearch dense Vector RAG。

Neo4j / GraphRAG 当前只要求形成一篇 Wiki 笔记，说明实体、关系、多跳问题、适用条件、成本和为什么本项目暂不实现。只有出现稳定实体关系、大量跨文档多跳问题及专用评测集时，才重新评估实现。

Kibana 不作为 LLM 可观测性默认方案；只有实际采用 Elasticsearch 日志或检索场景时才随之学习。

### M11：部署与作品集收束

完成 CI、健康检查、HTTPS、环境变量和密钥治理、部署验证、README、架构图、评测与 RAG 报告、演示脚本、简历描述和面试问答。

### 9.1 时间盒

按每周约 10 小时估算：

- M5～M6：约 4 周，完成有指标的后端 RAG；
- M7：再用约 2～4 周完成 SSE、最小 Vue 和 Demo Compose；
- M8～M10：在可演示成品之后按求职价值和时间选择，不要求全部实现。

---

## 10. 当前明确不做或不深入

- Python 重写 Java Agent；
- 预训练；
- DeepSpeed / ZeRO / FSDP；
- DPO / GRPO；
- 多 Agent 平台；
- 自研向量数据库；
- 同时接多个 Vector DB；
- 同时以 Spring AI 和 LangChain4j 为主框架；
- 自动数据库写操作；
- 复杂 Spring Cloud；
- Kubernetes；
- 复杂权限/菜单后台；
- 未经筛选导入整个私人 Wiki；
- 在 M5/M6 同时引入 Elasticsearch、Langfuse、Neo4j 或 GraphRAG；
- 在可演示闭环前持续堆叠 RAG 技术名词；
- 为固定评测题硬编码回答或强制工具调用。

---

## 11. 路线执行原则

1. 每一步说明为什么做、解决什么、如何验收。
2. 阶段内跑受影响测试，阶段封版再跑全量测试。
3. 新技术先做最小链路，再迁移正式主链。
4. 不为了面试名词一次实现所有 RAG 技术。
5. 向量库必须实际接入，但只选择一个主实现。
6. Evaluation 是测量工具，不是满分目标。
7. 不以单条评测失败为理由改变整体 Agent 架构。
8. 检索评测标注来源、章节和证据文本，不把 Chunk ID 当成跨策略不变的标准答案。
9. Rerank 是必做对照实验，Hybrid 和 Query Rewrite 必须有失败证据或明确评测目标。
10. M6 完成后优先形成 SSE、Vue、Compose 的可演示闭环。
11. DeepSeek 与 AutoDL 共用同一业务链，避免维护两套 Agent。
12. 源码和已通过验证优先于旧文档。
13. 密钥、服务器地址、私人数据路径与 Git 隔离。
14. 生产代码修改尽量一次给出完整文件和集中验证步骤，减少微型往返。
15. 每个里程碑完成后更新 `CURRENT-STATUS.md`、测试、提交和标签。
