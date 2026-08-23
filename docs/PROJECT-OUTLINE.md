# ai-agent-demo 项目总纲

> 文档职责：本文件是项目定位、架构边界和 M0～M11 长期路线的唯一权威来源。  
> 当前真实进度、最近测试结果和下一阶段入口见 [CURRENT-STATUS.md](CURRENT-STATUS.md)。  
> 初版制定：2026-08-02。  
> 本次路线更新：2026-08-23（Asia/Shanghai）。

## 1. 项目定位

`ai-agent-demo` 是个人学习和求职展示项目，长期定位为：

> **面向 Java 后端故障诊断的、证据驱动、可评测、可观测、支持多模型与完整 RAG 的 AI Agent 应用。**

职业主线：

> **Java 后端 + AI Agent 应用工程化**

当前核心场景是 SQL/MyBatis 排障、数据库真实 Schema 分析和 Java StackTrace 分析。后续重点不是继续扩大自研 Agent Framework，而是逐步进入 Spring AI、Evaluation、完整 RAG、多模型、异步任务、可观测、MCP、Vue 和部署。

最终希望用代码、测试、Trace、评测报告和演示界面证明：

- Java/Spring Boot AI 应用后端能力；
- Spring AI 标准生态使用与迁移能力；
- Tool Calling、工具契约、权限、超时和失败治理；
- 工具证据、RAG 证据和模型推测的优先级控制；
- 从知识处理、Embedding、向量库、检索到评测的完整 RAG 能力；
- DeepSeek 云模型和 AutoDL 自部署小模型的统一接入；
- 可选 LoRA/QLoRA 微调与统一评测；
- 异步任务、状态机、SSE、Trace、Observability 和 MCP；
- Vue、Docker Compose、CI、部署和作品集收束。

目标岗位：

- Java 后端开发工程师（AI 应用方向）；
- AI Agent 应用开发工程师；
- 大模型应用后端工程师；
- 企业智能化平台后端工程师。

项目不以算法研究、预训练、训练框架研发或纯模型训练岗位为目标。

---

## 2. 核心原则与边界

### 2.1 证据优先级

项目统一遵守：

> **工具真实证据 > 可追溯 RAG 证据 > 模型通用推测。**

当结论依赖数据库真实结构、StackTrace 或知识文档时，模型不能用“看起来合理”的猜测替代可验证证据。

### 2.2 与工作项目完全隔离

不得复制或影射工作项目中的代码、接口、数据结构、业务数据、内部文档、私有地址和敏感业务概念。

可以学习通用工程模式，例如异步任务、线程池、状态机、超时、幂等、SSE 和可观测性，但必须围绕公开的 Java 后端故障诊断场景重新设计。

### 2.3 Java / Python 边界

Java/Spring Boot 承担在线主链：

- API；
- Agent；
- Spring AI；
- Tool Calling；
- RAG 在线检索；
- 模型路由；
- 异步任务；
- SSE；
- Trace；
- 持久化。

Python 主要承担离线工程：

- JSONL 评测；
- 知识清洗与分块实验；
- 检索指标统计；
- 评测报告；
- AutoDL 上的训练和模型评测。

不使用 Python 重写 Java Agent 主后端。

### 2.4 工具与数据库安全

数据库工具默认只读。不允许根据用户输入执行任意 SQL，不自动执行 DDL、UPDATE、DELETE，也不自动修改业务数据库。

### 2.5 不继续无限扩自研框架

M2 已经完成自研 Tool Contract / Registry / Governance，学习目标已经达到。

从 M3 开始：

> **优先使用 Spring AI 标准抽象，不再继续扩自研通用 Agent Framework。**

只保留具有业务价值、安全价值或明确教学价值的自研部分。

---

## 3. 技术职责

### 3.1 Java / Spring Boot

负责 Agent 后端、Spring AI、Tool、RAG 在线链路、模型路由、异步任务、SSE、Trace、MySQL、API、Observability 和 MCP。

### 3.2 Spring AI

M3 起作为主 Java AI 框架，重点学习：

- ChatModel；
- ChatClient；
- Prompt / Message；
- Tool Calling；
- Advisors / 扩展点；
- Embedding Model；
- Vector Store；
- RAG 相关抽象；
- Observability；
- OpenAI-compatible 模型服务接入。

具体 Spring AI 版本与 Spring Boot 兼容关系必须在 M3 开始时以当前官方文档为准，不在总纲中写死版本。

### 3.3 Vue

后期用于提交诊断、选择模型、展示异步状态、SSE 步骤、Tool Evidence、RAG 来源、最终回答、耗时和历史 Trace。

### 3.4 知识源

个人 Wiki 可以成为知识源，但只能通过显式 manifest 筛选公开 Markdown。需要保留来源、标题、章节、哈希、版本和更新时间，并支持增量更新与删除。不得直接导入整个私人 Wiki。

---

## 4. 向量库与完整 RAG 定位

**从本次路线开始，向量数据库由“有条件再做”调整为“必学、必实现”。**

原因不是堆技术，而是为了系统掌握 RAG，并覆盖面试中常见问题。

M5 必须实际接入一个向量存储；最终默认使用纯向量、Hybrid 还是其他策略，则由 M6 固定评测决定。

向量库选型不在总纲阶段写死。M5 开始时结合 Spring AI 当前支持、本地 Docker 复杂度、metadata filter、hybrid 能力、Java 生态和运维成本，在 Qdrant、pgvector、Elasticsearch/OpenSearch、Milvus 等候选中选择**一个主实现**，不同时维护多套向量库。

### 4.1 RAG 必须系统掌握的知识

#### 知识处理

- Loader；
- Markdown 解析；
- Clean；
- Chunk；
- chunk size；
- overlap；
- token 与字符长度；
- metadata；
- 文档版本；
- 增量更新；
- 删除与重建。

#### Embedding

- 文本向量化；
- 向量维度；
- cosine / dot product / L2；
- Embedding 模型与生成模型区别；
- query/document embedding；
- 模型变化后的索引重建。

#### Vector DB

至少理解：

- collection / index；
- vector + metadata/payload；
- TopK；
- metadata filter；
- ANN；
- HNSW 基本原理与关键参数；
- 精确检索与近似检索；
- recall、latency、memory 的权衡。

项目只深入一个主向量库，但面试层面要能说明 pgvector、Qdrant、Milvus、Elasticsearch 类方案的定位差异。

#### Retrieval

项目至少实现并比较：

- 当前规则/关键词 baseline；
- dense vector retrieval；
- metadata filter；
- hybrid retrieval；
- RRF 或等价融合；
- TopK 与 context 数量控制。

#### Rerank

需要理解 bi-encoder 与 cross-encoder 的职责差异，至少做一次 reranker 实验。是否进入默认链路由评测决定。

#### Query Transformation

学习和实验：

- query rewrite；
- multi-query；
- HyDE 的概念和适用条件。

不要求全部进入默认链路。

#### Context Construction

需要处理去重、相关度排序、token budget、来源引用、知识冲突，以及 Tool Evidence 与 RAG Evidence 的优先级。

#### RAG Evaluation

至少使用：

- Recall@K；
- MRR；
- nDCG；
- groundedness / faithfulness；
- citation correctness；
- P50/P95 latency；
- token / 模型调用成本；
- 无依据回答率。

必须能回答：

> 为什么最终采用这个 Chunk、TopK、Embedding、Hybrid 或 Reranker？

答案必须来自固定数据集和评测结果，而不是主观感觉。

### 4.2 学习但不强制进入默认链路

- Parent-Child Retrieval；
- Contextual Compression；
- HyDE；
- Graph RAG；
- Knowledge Graph；
- Agentic RAG。

这些用于扩展面试知识面，不为了名词堆叠全部实现。

---

## 5. 多模型与 AutoDL 本地模型路线

后续计划支持两类 Provider：

### 5.1 DeepSeek

当前云模型基线，稳定、无需维护 GPU。

### 5.2 AutoDL 自部署小模型

计划在 AutoDL 上部署一个适合单卡环境的小型 Instruct 模型，优先使用 vLLM 暴露 OpenAI-compatible API，再由 Spring AI 接入。

长期请求示例：

```json
{
  "message": "...",
  "modelId": "deepseek-chat"
}
```

或：

```json
{
  "message": "...",
  "modelId": "local-qwen"
}
```

模型注册至少记录：

- modelId；
- provider；
- endpoint；
- modelName；
- enabled；
- availability；
- capability；
- timeout；
- Tool Calling 能力。

Run/Trace 要记录 provider、modelId、modelName 和 Prompt version。

AutoDL 实例关闭时，本地模型允许 UNAVAILABLE，不能成为 DeepSeek 主链的硬依赖，也不默认静默 fallback。

### 5.3 可选微调

微调是**评测驱动的可选增强**，不是项目必经流程：

```text
Instruct 小模型 baseline
→ 固定 Evaluation
→ 分析失败类型
→ 有明确领域差距时构造数据
→ LoRA / QLoRA
→ 同一 Evaluation
→ 有提升才注册 tuned modelId
```

不从头预训练，也不把训练 loss 当成项目成功标准。

---

## 6. 当前代码基线

截至 2026-08-23，M0、M1、M2、M3 已完成。

当前已有：

- `/agent/ask`；
- `/agent/tools`；
- `/agent/runs`；
- `/agent/runs/stats`；
- `/agent/runs/{traceId}`；
- Spring AI `ChatClient` 正式主链；
- Spring AI 原生 Tool Calling；
- `SpringAiToolChatService`；
- `SpringAiDiagnosticTools`；
- `SpringAiToolRunContext`；
- `SpringAiKnowledgeSupport`；
- PRE_DECISION / POST_TOOL 规则 RAG；
- Tool Contract / Tool Registry / Tool Governance；
- Tool 参数校验；
- Tool timeout；
- permission / risk / sideEffect；
- MySQL Run/Step；
- Flyway V1/V2；
- Docker Compose MySQL；
- Testcontainers；
- Trace 脱敏和长度治理。

旧 `SimpleAgentService / AgentOrchestrator / DeepSeekDecisionClient` 手写链路冻结保留，作为从自研编排迁移到 Spring AI 的架构对照基线，不再承载正式 `/agent/ask`，也不再同步扩展新能力。

当前三个正式 Tool：

- `getTableSchema`；
- `analyzeSqlErrorWithSchema`；
- `analyzeStackTrace`。

当前 ToolExecutionStatus：

- SUCCESS；
- INVALID_ARGUMENT；
- TOOL_NOT_FOUND；
- PERMISSION_DENIED；
- TIMEOUT；
- BUSINESS_ERROR；
- EXECUTION_ERROR。

---

## 7. 当前链路与长期目标

### 7.1 当前链路

```text
POST /agent/ask
  → SpringAiToolChatService
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
  → Spring AI 根据工具证据生成回答
  → Trace 脱敏
  → AgentLogService
  → MySQL
  → 同步 HTTP 返回
```

### 7.2 长期目标

```text
Vue
 ↓
Task / Async / SSE
 ↓
Spring AI
 ├─ Model Router → DeepSeek
 │              → AutoDL Local Model
 ├─ Tool Calling → DB / StackTrace Tools
 └─ RAG → Clean / Chunk / Embedding / Vector DB
          → Hybrid / Rerank / Context / Citation
 ↓
Trace / Observability / MCP
 ↓
MySQL + Evaluation
```

---

## 8. 阶段路线总览

| 阶段 | 状态 | 核心结果 | 建议里程碑 |
|---|---|---|---|
| M0 | 已完成 | RAG 首次决策前/工具后调用时机、去重、Trace | `v0.3.0-rag-closeout` |
| M1 | 已完成 | Compose MySQL、Flyway、事务、Testcontainers、空环境复现 | `v0.4.0-reproducible` |
| M2 | 已完成 | Tool Contract/Governance、多类型 Tool、Trace 安全治理 | `v0.5.0-m2-closeout` |
| M3 | 已完成 | Spring AI 主链、Tool Calling、治理/RAG/Trace 迁移、正式入口切换 | `v0.6.0-spring-ai`（待本地封版核验） |
| M4 | 下一阶段 | Evaluation Harness | `v0.7.0-evaluation` |
| M5 | 后续计划 | Knowledge + Embedding + Vector DB + Vector RAG | `v0.8.0-vector-rag` |
| M6 | 后续计划 | Hybrid + Rerank + Query Transform + RAG Benchmark | `v0.9.0-rag-benchmark` |
| M7 | 后续计划 | DeepSeek + AutoDL 多模型 + 可选微调 | `v0.10.0-multi-model` |
| M8 | 后续计划 | Async Task + SSE | `v0.11.0-async-sse` |
| M9 | 后续计划 | Observability + MCP | `v0.12.0-observable-mcp` |
| M10 | 后续计划 | Vue 诊断控制台 | `v0.13.0-console` |
| M11 | 后续计划 | Compose + CI + Deploy + Portfolio | `v1.0.0-portfolio-ready` |

---

## 9. M0～M3 已完成摘要

### M0：RAG 调用时机修正

已完成 PRE_DECISION / POST_TOOL 检索、知识去重、classpath 文档加载、5 类 SQL 规则知识和 Trace。

### M1：可复现数据库环境

已完成 Docker Compose MySQL、Flyway V1、Run/Step 短事务、Testcontainers、Maven Wrapper 和空环境复现。

### M2：通用工具调用框架与多类型工具

已完成：

- ToolDefinition；
- ToolParameterDefinition；
- ToolArgumentValidator；
- ToolExecutionResult；
- 7 类 Tool 状态；
- ToolBusinessException；
- ToolExecutionRunner；
- 独立 Tool Executor；
- ToolAccessPolicy；
- Flyway V2：tool_status / error_code；
- SQL Tool 业务失败语义；
- analyzeStackTrace；
- Spring 自动 Tool 注册；
- 真实多类型 Tool Selection；
- Trace 脱敏和长度治理；
- 全量测试与真实 API 验收。

M2 到此停止，不继续增加 HTTP Tool、复杂 RBAC、审批框架或通用工作流。

### M3：Spring AI 渐进式迁移

已完成 Spring AI `ChatClient` 最小接入、三个 Tool 的原生 Schema 暴露、Tool Calling Loop、现有治理复用、Trace/持久化接回、PRE_DECISION / POST_TOOL 规则 RAG 迁移、契约对等测试和正式 `/agent/ask` 切换。

旧手写链路冻结保留，用于说明“原来手写了什么、Spring AI 接管了什么、哪些业务治理仍需自行实现”，不再作为正式主链。

---

## 10. M3：Spring AI 渐进式迁移

状态：**已完成。**

完成结果：

1. 使用 Spring AI `ChatClient` 通过 OpenAI-compatible 接口调用 DeepSeek；
2. 三个现有 Tool 通过 `@Tool / @ToolParam` 暴露给模型；
3. Spring AI 负责 Tool Schema 生成、模型工具决策和 Tool Calling Loop；
4. `SpringAiDiagnosticTools` 将 Spring AI 回调适配到现有 `ToolRegistry`；
5. `SpringAiToolRunContext` 保留总时间、调用次数、重复调用和失败终止治理；
6. permission、参数校验、timeout、business error 继续复用 M2 确定性治理；
7. `SpringAiKnowledgeSupport` 接回 PRE_DECISION / POST_TOOL 规则 RAG、Trace 和知识去重；
8. Tool 返回给模型前恢复为结构化 JSON，避免嵌套 JSON 被当作普通字符串；
9. 完成新旧 Tool 名称、参数与 required 语义的契约对等测试；
10. 正式 `/agent/ask` 切换到 `SpringAiToolChatService`；
11. 普通问答、SQL Schema 诊断、StackTrace、业务失败、Trace 和持久化完成验收；
12. 全量测试 99 项通过，Failures=0、Errors=0。

保留决策：

- 旧手写链路与新链路保持解耦并冻结保留；
- 旧链路用于架构演进、回归对照和面试讲解；
- 后续 Evaluation、Vector RAG、多模型、异步和 SSE 只进入 Spring AI 正式主链；
- 不再要求旧链路持续能力对等，也不因保留旧代码而维护两套产品主链。

M3 必须掌握的结论：

> Spring AI 替代的是通用模型调用、Tool Schema 和 Tool Calling Loop；Tool 的确定性业务逻辑、访问控制、参数校验、超时、错误语义、RAG 证据优先级、Trace 和持久化仍由项目自己负责。

---

## 11. M4：Evaluation Harness

阶段目标：建立 Prompt、Tool、RAG、模型和微调都能复用的固定评测基线。

初始 JSONL 建议不少于 50 条，指标至少包含：

- needTool accuracy；
- toolName accuracy；
- 参数完整率；
- Evidence 覆盖率；
- 无依据回答率；
- 平均模型调用次数；
- P50/P95；
- 失败分类；
- Prompt / model / knowledge version。

没有 Evaluation，不进入大规模 RAG 优化，也不判断微调是否成功。

---

## 12. M5：知识工程 + Embedding + 向量库 + Vector RAG

必须真正走通：

```text
Knowledge Source
→ Clean
→ Chunk
→ Metadata
→ Embedding
→ Vector Store
→ Query Embedding
→ TopK
→ Context
→ Citation
→ Answer
```

阶段必须交付：

- manifest；
- Markdown 清洗；
- Chunk 策略；
- metadata；
- knowledge version；
- Embedding；
- 一个真实向量库；
- TopK；
- metadata filter；
- citation；
- 增量导入和删除；
- 第一版 Recall@K；
- 与规则 RAG baseline 对比。

---

## 13. M6：Advanced RAG 与 Benchmark

必须实验或比较：

- rule/keyword baseline；
- dense vector；
- hybrid；
- RRF；
- reranker；
- query rewrite；
- multi-query；
- chunk size / overlap；
- TopK；
- metadata filter；
- citation correctness；
- context token budget。

至少理解：BM25、sparse/dense、HNSW、bi-encoder/cross-encoder、Recall@K、MRR、nDCG、HyDE、Parent-Child、Contextual Compression、Agentic RAG、Graph RAG 的适用边界。

阶段输出 `RAG-BENCHMARK.md`，用数据解释最终检索策略。

---

## 14. M7：DeepSeek + AutoDL 多模型与可选微调

目标：同一 Java Agent / Tool / RAG / Evaluation 支持多个 modelId。

主要任务：

- Model Registry / Router；
- DeepSeek Provider；
- AutoDL Provider；
- vLLM OpenAI-compatible 服务；
- health/availability；
- 请求级模型选择；
- Run/Trace 模型记录；
- 同集 Evaluation。

可选微调只在小模型基线暴露明确领域差距时进行 LoRA/QLoRA；微调后必须和原模型在同一数据集上比较，有收益才注册 tuned modelId。

---

## 15. M8～M11

### M8：异步任务 + SSE

Task 表、状态机、独立线程池、幂等、timeout、有限重试、恢复、SSE。暂不默认引入 MQ。

### M9：Observability + MCP

Actuator/Micrometer、模型/Tool/RAG 指标、token usage、Tracing、P50/P95、Spring AI Observability、MCP 基本协议和一个小型集成。

### M10：Vue

输入诊断、模型选择、任务状态、SSE、Tool Evidence、RAG 来源、最终回答和历史 Trace。

### M11：部署与作品集

后端/Vue/向量库/MySQL Compose、CI、健康检查、HTTPS、个人服务器部署、README、架构图、Evaluation、RAG Benchmark、Demo、简历和面试材料。

---

## 16. 面试知识目标

项目完成后应能够解释：

### Agent

Tool Calling、function schema、Agent Loop、tool selection、deterministic evidence、timeout、permission、error semantics，以及为什么从自研迁移 Spring AI。

### RAG

Chunk、Embedding、cosine、Vector DB、HNSW、TopK、metadata filter、BM25、dense/sparse、hybrid、RRF、reranker、query rewrite、Recall@K、MRR、nDCG、groundedness、citation、知识更新和索引重建。

### Model

API 云模型与自部署模型、vLLM、OpenAI-compatible API、model router、Tool Calling 能力差异、LoRA/QLoRA，以及为什么微调不能替代 RAG、RAG 不能替代 Tool。

### Java Engineering

Spring AI、WebClient、事务、Flyway、Testcontainers、线程池、异步状态机、SSE、Observability、MCP。

---

## 17. 当前明确不做或不深入

暂不作为项目主线：

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
- 未经筛选导入整个私人 Wiki。

LoRA/QLoRA 只作为 M7 的可选、评测驱动实验。

---

## 18. 路线执行原则

1. 每一步都说明为什么做、解决什么、如何验收。
2. 阶段内跑受影响测试，阶段封版再跑全量测试。
3. 新技术先最小链路，再迁移核心链路。
4. 不为了面试名词一次实现所有 RAG 技术。
5. 向量库必须实际接入，但默认检索策略由评测决定。
6. 微调不是必选，只有固定评测证明有价值时才做。
7. DeepSeek 与 AutoDL 使用同一业务链，避免维护两套 Agent。
8. 源码和已通过测试优先于旧文档。
9. 密钥、服务器地址、私人数据路径与 Git 隔离。
10. 每个里程碑完成后更新 CURRENT-STATUS、测试、提交和标签。
