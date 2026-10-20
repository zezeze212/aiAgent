# ai-agent-demo 项目总纲

> 文档职责：本文件是项目定位、架构边界和 M0～M11 长期路线的唯一权威来源。  
> 当前代码事实、验证结果、已知限制和下一阶段入口见 [CURRENT-STATUS.md](CURRENT-STATUS.md)。  
> 初版制定：2026-08-02。  
> 本次更新：2026-10-20（Asia/Shanghai）。

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
- 阶段入口、重要变更和阶段封版时先判断旧证据是否仍有效，再选择必要的评测范围；不机械重跑全套，不作为持续打补丁循环。

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

Python 只承担离线工程：

- JSONL 评测；
- 知识清洗与分块实验；
- 检索指标统计；
- 报告生成；
- AutoDL 训练和模型评测。

不使用 Python 重写 Java Agent 主后端。

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

旧手写 Agent 编排与重复问答入口已清理。架构对比使用 Git 历史；`SimpleRagRetriever` 作为可切换规则检索基线继续保留，不属于被删除的编排。

---

## 3. 当前架构基线

当前正式链路（M5 收尾状态）：

1. `/agent/ask` → `SpringAiToolChatService` 创建请求上下文与 traceId。
2. `SpringAiKnowledgeSupport` 执行 PRE_DECISION 检索，通过 `KnowledgeRetriever` 选择 rule / vector，完成请求级去重与字符预算选择。
3. 边界策略判断是否澄清，可用工具策略根据原始输入选择工具；非澄清请求进入 Spring AI `ChatClient` / DeepSeek。
4. Spring AI 原生 Tool Calling 经 `SpringAiDiagnosticTools`、请求级治理和 `ToolRegistry` 执行具体 Tool，保留结构化结果与 Trace。
5. 工具后按需补充知识；前置已选入知识时跳过，补充时仍受同一请求累计预算约束。
6. 模型生成回答，执行引用 ID 审计，再进行 Trace 脱敏、日志持久化与同步响应。

当前向量实现：Ollama `bge-m3`（1024 维）+ Qdrant，受管集合 `ai_agent_knowledge_managed_v1`。`GET /rag/search` 用于检索调试，不是另一套问答主链。

当前三个正式 Tool：

- `getTableSchema`；
- `analyzeSqlErrorWithSchema`；
- `analyzeStackTrace`。

旧 `SimpleAgentService / AgentOrchestrator / DeepSeekDecisionClient` 编排及旧 `/ai/*`、`/spring-ai/*` 问答入口已清理。正式问答统一使用 `/agent/ask`。

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

个人 Wiki 可以成为知识源，但只能通过显式 manifest 筛选公开 Markdown。必须保留来源、标题、章节、哈希、版本和更新时间，并支持增量更新与删除。不得直接导入整个私人 Wiki。

---

## 5. 阶段路线总览

| 阶段 | 状态 | 核心结果 | 建议里程碑 |
|---|---|---|---|
| M0 | 已完成 | PRE_DECISION / POST_TOOL 规则 RAG、去重、Trace | `v0.3.0-rag-closeout` |
| M1 | 已完成 | Compose MySQL、Flyway、事务、Testcontainers | `v0.4.0-reproducible` |
| M2 | 已完成 | Tool Contract / Governance、多类型 Tool、Trace 安全治理 | `v0.5.0-m2-closeout` |
| M3 | 已完成 | Spring AI 主链、原生 Tool Calling、正式入口切换 | `v0.6.0-spring-ai`（本地核验） |
| M4 | 已完成 | Python Evaluation Harness、14条校准集、50条回归集、版本化报告 | `v0.7.0-evaluation`（本地核验） |
| M5 | 功能与工程验收完成；Git 封版待确认 | 受管索引、Dense RAG、预算、引用审计；全量测试 112 项通过 | `v0.8.0-vector-rag`（建议，尚未确认创建） |
| M6 | 下一开发入口，尚未实施 | Hybrid、Rerank、Query Transform、RAG Benchmark | `v0.9.0-rag-benchmark` |
| M7 | 后续计划 | DeepSeek + AutoDL 多模型、可选微调 | `v0.10.0-multi-model` |
| M8 | 后续计划 | Async Task + SSE | `v0.11.0-async-sse` |
| M9 | 后续计划 | Observability + MCP | `v0.12.0-observable-mcp` |
| M10 | 后续计划 | Vue 诊断控制台 | `v0.13.0-console` |
| M11 | 后续计划 | Compose、CI、部署与作品集 | `v1.0.0-portfolio-ready` |

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

## 7. M5：Knowledge + Embedding + Vector DB + Vector RAG

M5 功能与工程验收已完成。2026-10-20 全量测试 112 项通过，无失败、错误或跳过；实际接口证据与已知限制见 `CURRENT-STATUS.md`。最终提交与标签尚待确认，下一开发入口为 M6。

### 7.1 阶段目标

真正走通：

索引侧：公开 Knowledge Source 经 Manifest 筛选、加载清洗、分块与元数据管理，生成 Embedding 后写入 Vector Store。

在线侧：查询向量化后执行 TopK / Filter，组装受预算约束的上下文，结合工具事实生成回答并记录引用。

### 7.2 M5 分步与完成记录

#### M5-0：现状审计与选型

已完成审计与选型：Spring AI 1.1.8、Ollama `bge-m3`、Qdrant；后续不重复选型。

选型考虑：

- Spring AI 1.1.8 兼容性；
- Windows + Docker 本地开发成本；
- metadata filter；
- Java 生态；
- 数据持久化和调试便利性；
- 后续 Hybrid 扩展空间。

不同时维护多个 Vector DB。

#### M5-1：知识 Manifest 与清洗

已完成公开知识 Manifest、Markdown 加载与基础清洗，以及来源和版本/哈希等元数据管理。

#### M5-2：Chunk 与 Metadata

已实现章节分块、窗口切分与 metadata。保留简单可解释的策略；Parent-Child、HyDE、Graph RAG 不作为 M5 必做项。

#### M5-3：Embedding 与入库

已接入真实 Embedding / Vector Store，完成受管导入、增量更新、删除及状态维护。索引恢复能力的验证边界见当前状态文档，不将状态恢复检查等同于全部故障注入通过。

#### M5-4：在线检索与引用

已通过 `KnowledgeRetriever` 接入正式主链，保留 `SimpleRagRetriever` 作为可切换 baseline；支持范围过滤、请求级预算、空知识处理与引用 ID 审计。来源可通过 chunkId 与 Trace 对应；引用合法不等于语义正确。

历史教学编号 M5-3A～M5-3D 的在线链路工作统一归入本节，不改写历史提交，不重复实施。

#### M5-5：工程验收

已完成阶段全量测试（112 项，0 失败、0 错误、0 跳过）、数据库集成测试、真实 API 验证和基本检索检查。M5 验收目标是链路正确、证据可追溯、更新可维护，不围绕少数问题反复调参刷分。

本阶段不要求新增 30～50 条检索题；未执行的 M5-4A 扩题方案已取消。字符预算、引用语义未校验、模型额外推断违背工具证据等限制如实记录，后续按 M6 的比较目标处理。

### 7.3 M5 知识范围（不等于逐项考核完成记录）

- Loader / Clean / Chunk / overlap；
- token 与字符长度；
- metadata / version / hash；
- Embedding 与生成模型区别；
- vector dimension；
- cosine / dot product / L2；
- collection / index；
- TopK / metadata filter；
- ANN / HNSW 基本原理；
- recall、latency、memory 权衡；
- 增量更新、删除和模型变化后的索引重建；
- Context 去重、排序、token budget 和 citation。

---

## 8. M6：Advanced RAG 与 Benchmark

M6 是下一开发入口，尚未实施。先确认目标、基线和可比口径，再逐项引入增量；不同时堆叠多个策略。

### 8.1 入口与比较顺序

1. 复用现有语料、评估脚本与历史结果，确认 rule / dense 结果粒度及来源/片段指标的可比性。
2. 固定查询集、知识与索引版本、模型、过滤条件、TopK、上下文预算和耗时口径；只有旧结果不可比时才补跑基线。
3. 先比较关键词与语义检索互补性，再按失败类型考虑融合、重排或查询改写；逐项判断收益与成本。
4. 分开记录检索质量、最终回答证据支持和延迟，不把工具成功或引用 ID 合法当作回答完全正确。

### 8.2 计划比较的能力

按上述顺序和实际需要比较：

- rule / keyword baseline；
- dense vector；
- BM25 / sparse；
- hybrid / RRF；
- reranker；
- query rewrite / multi-query；
- chunk size / overlap；
- TopK / metadata filter；
- citation correctness；
- context token budget。

### 8.3 交付与范围控制

M6 可以使用 M4 框架进行有限、计划内的对比，但仍遵守“测量而非刷分”。阶段输出 `RAG-BENCHMARK.md`，记录配置、版本、指标、失败类型和成本，用数据解释最终检索策略。精确 token 预算、引用语义与工具证据一致性属于待比较/完善能力，不把 M5 的字符预算和 ID 审计描述为已经实现这些能力。

HyDE、Parent-Child、Contextual Compression、Agentic RAG、Graph RAG 以理解和小实验为主，不要求全部进入默认链路。

---

## 9. M7～M11

### M7：DeepSeek + AutoDL 多模型

同一 Java Agent、Tool、RAG 和接口支持多个 `modelId`。AutoDL 优先使用 vLLM 暴露 OpenAI-compatible API；实例关闭时允许本地模型 `UNAVAILABLE`，不能拖垮 DeepSeek 主链。

LoRA / QLoRA 是可选增强，只有小模型基线暴露明确领域差距时才考虑；不把训练 loss 当成项目成功标准。

### M8：异步任务 + SSE

Task 表、状态机、独立线程池、幂等、timeout、有限重试、恢复和 SSE。暂不默认引入 MQ。

### M9：Observability + MCP

Actuator / Micrometer、模型/Tool/RAG 指标、token usage、Tracing、P50/P95、Spring AI Observability、MCP 基本协议和一个小型集成。

### M10：Vue

输入诊断、模型选择、任务状态、SSE、Tool Evidence、RAG 来源、最终回答和历史 Trace。

### M11：部署与作品集

后端、Vue、向量库和 MySQL Compose；CI；健康检查；HTTPS；公开 Demo；README；架构图；评测与 RAG 报告；简历和面试材料。

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
- 为固定评测题硬编码回答或强制工具调用。

---

## 11. 路线执行原则

1. 每一步说明为什么做、解决什么、如何验收。
2. 每次修改先说明验证所针对的风险；阶段内跑受影响测试，阶段封版再跑全量测试。纯文档修改不重跑测试；已有效的真实 API 证据直接沿用。
3. 新技术先做最小链路，再迁移正式主链。
4. 不为了面试名词一次实现所有 RAG 技术。
5. 向量库必须实际接入，但只选择一个主实现。
6. Evaluation 是测量工具，不是满分目标。
7. 不以单条评测失败为理由改变整体 Agent 架构。
8. DeepSeek 与 AutoDL 共用同一业务链，避免维护两套 Agent。
9. 源码和已通过验证优先于旧文档。
10. 密钥、服务器地址、私人数据路径与 Git 隔离。
11. 生产代码修改尽量一次给出完整文件和集中验证步骤，减少微型往返。
12. 每个里程碑完成后更新 `CURRENT-STATUS.md`、测试结果、提交和标签；区分功能验收完成与 Git 封版完成，未确认的哈希或标签不得编造。
13. 教学内容使用 Markdown 标题层级，便于 Typora 大纲导航；个人项目与模型理论学习分开推进。


---

## 12. 本次修订说明

2026-10-20：更新 M5 验收状态与 M6 入口；更正旧编排、重复接口和规则检索直连的过时架构描述；补充预算与引用审计的能力边界；取消机械重复评测和未执行的扩题要求。M7～M11 的顺序与核心目标保留，不因单个回答质量问题调整整体路线。
