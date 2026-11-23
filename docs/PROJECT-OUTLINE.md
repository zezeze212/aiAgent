# ai-agent-demo 项目总纲

> 文档职责：本文件是项目定位、架构边界和 M0～M11 长期路线的唯一权威来源。  
> 当前代码事实、验证结果、已知限制和下一阶段入口见 [CURRENT-STATUS.md](CURRENT-STATUS.md)。  
> 初版制定：2026-08-02。  
> 本次更新：2026-11-23（Asia/Shanghai）。

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

### 验证方式：自动测试与真实接入检查并行，效果比较按需

用户明确要求：减少的是反复编写评测脚本、在少量样本上比较效果、调 Prompt 和追分；不是减少必要测试，更不是仅凭 test 通过认定功能完成。

1. **自动测试**：验证确定性逻辑、异常处理、状态、预算、契约和回归；按改动影响选择范围。修改核心调用链或公共配置时安排必要集成回归，阶段收尾按实际风险做一次整体检查。
2. **真实功能接入检查**：对新引入或改变的在线能力，启动实际应用，通过正式入口验证配置装配、外部服务调用、开关或模型选择、结果返回与必要持久化。`/agent/ask` 的新能力不能只在独立 Benchmark 中验证。
3. **观察依据**：查看实际生效配置、Trace、工具执行、选入上下文、响应字段和必要日志。HTTP 200、自然语言声称“已使用”、或 mock 测试通过均不能单独证明真实接入。
4. **范围与停止条件**：按功能选择少量有代表性的请求，不固定题数。开关功能检查相关启停行为；失败/回退行为按改动风险和已有证据选择，不强制每次重做全部故障。预期路径可观察、关键结果符合约束、没有阻塞性异常即可结束功能验证。
5. **效果比较**：只有需要回答明确的策略选择问题时才安排，先说明问题、比较条件、样本局限和停止条件。小样本可发现接入错误和具体失败，不足以证明广泛效果提升或稳定性能；不将效果评测作为每个新功能的默认作业。
6. **结果复用**：已经验证且未受本次改动影响的证据沿用。不把一次成功推广为系统整体正确，也不因纯文档修改重跑服务。真实请求揭示功能故障时可修复并定向复验，不为了改善分数反复运行。

验证统一放在单元末尾，说明操作、预期现象、判断依据和反馈内容。默认优先 Postman 或简短 PowerShell 请求；已有工具足够时不新建评测框架或批量比较脚本。

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

当前为 M8 核心交付后、M9 待开始的架构。同步 `/agent/ask` 与异步 `/agent/tasks` 共用 Spring AI 执行服务：

1. `/agent/ask` → `SpringAiToolChatService` 解析 modelId、选择注册的生成客户端，再创建请求上下文与 traceId。
2. `SpringAiKnowledgeSupport` 在首次检索前可选改写查询，通过 `KnowledgeRetriever` 选择 rule / vector / bm25 / hybrid。
3. 可选重排后，执行请求级去重、数量、字符、可选来源配额与可选 token 估算预算选择。
4. 边界与可用工具策略继续依据原始请求工作。非澄清请求进入 所选模型的 Spring AI ChatClient。
5. Spring AI 原生 Tool Calling 经适配器、请求上下文与 ToolRegistry 治理并执行，保留真实结果与 Trace。
6. 按现有条件补充工具后知识，预算在同一请求中累计。
7. 正常回答执行引用 ID 审计，关联实际选入的片段快照；保存 Run / Steps，在 HTTP 边界治理展示内容并返回结构化出处。

异步入口先持久化任务，再由独立有界线程池执行共同主链；数据库负责幂等、状态条件更新、截止时间和唯一重试关联。GET 查询和 SSE 读取当前快照，最终回答由关联 Run 获取。SSE 断开不取消任务，任务超时不承诺立即终止底层模型调用。

当前生成主链支持默认 DeepSeek 和显式 AutoDL Qwen，复用同一套业务治理。查询改写、Embedding 和重排保持独立配置，不随回答模型自动切换。MODEL_EXECUTION 与响应记录所选模型和实际返回元数据。

向量实现继续为 Ollama bge-m3（1024 维）+ Qdrant，受管集合 ai_agent_knowledge_managed_v1。三个正式工具为 getTableSchema、analyzeSqlErrorWithSchema、analyzeStackTrace。

引用合法不等于语义支持，工具成功不等于完整回答正确。旧 `/ai/*`、`/spring-ai/*` 和手写编排不恢复；规则检索器作为基线保留。

各项“已实现”和“已在真实开启状态验证”的区别，以 CURRENT-STATUS.md 的证据记录为准。

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

M10 用于提交诊断、选择模型、展示异步状态、SSE 状态、Tool Evidence、RAG 来源、最终回答、耗时和历史 Trace。当前已有原生 HTML/JS 调试页，仅用于验收，不继续扩展成正式前端；当前 SSE 不提供步骤级事件或逐 token 流。

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
| M5 | 功能、验收与提交已确认；标签未确认 | 受管索引、Dense RAG、预算、引用审计；全量测试 112 项通过 | `v0.8.0-vector-rag`（建议，尚未确认创建） |
| M6 | 功能与真实接入已验证；保留历史归档边界 | 正式检索策略、可选重排/改写、预算与引用增强、历史报告 | `v0.9.0-rag-benchmark` |
| M7 | 核心能力与正式成功路径已验证；保留历史证据边界 | DeepSeek + AutoDL 多模型，LoRA 非必做 | `v0.10.0-multi-model` |
| M8 | 本轮核心交付与已确认验收完成，代码已推送；文档本次收尾 | Async Task + SSE + 最小客户端验收 | `v0.11.0-async-sse`（建议，未确认创建） |
| M9 | 下一阶段，尚未开始 | Observability + MCP，从 M9-1 开始 | `v0.12.0-observable-mcp` |
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

M5 功能与工程验收已完成。2026-10-20 全量测试 112 项通过，无失败、错误或跳过；实际接口证据与已知限制见 `CURRENT-STATUS.md`。后续已确认收尾提交 b27e29e，标签未确认；M6 历史边界见第 8 节，当前下一功能入口为第 9 节的 M9。

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

M6 功能已实现，各单元定向测试和既有实验/审阅已有反馈。M6-7 的历史归档未确认项保留，不新增策略或连续评测加固单元；当前不再以 M6 为开发入口。

### 8.1 已实现能力

- rule / dense / BM25 / Hybrid-RRF 检索与历史比较。
- BM25、Hybrid 接入正式 Agent，可通过配置选择。
- 可选重排接入正式知识链路，并有固定候选成对报告。
- 可选查询改写接入正式首次检索，并有成对接口、批量评分与归档。
- 请求级来源配额与知识片段 token 估算预算。
- 引用 ID 审计、结构化出处与展示治理。
- 三例回答证据使用检查，区分知识引用、工具事实和附加推测。

工程验证的完成状态以 CURRENT-STATUS.md 为准，不能将每行“已实现”理解为所有开关、环境和失败情况已真实验证。

### 8.2 已有结果与策略选择

- 重排首轮未取得整体指标改善，保留可选能力。
- 查询改写的 MRR 小幅提升，没有增加命中题数，额外延迟明显，目前不支持默认开启。
- 来源配额和预算用于工程约束；规则正确不等于回答必然更好。
- 有效引用 ID 不证明语义正确，有限人工审阅不构成整体回答正确率。
- 仓库默认配置、实际启动配置、推荐策略分别记录。本轮文档更新不修改启动参数。

原始报告保留在 evaluation/rag/results/，三例审阅位于 evaluation/rag/reviews/m6-6b/。阶段总结文件为 docs/RAG-BENCHMARK.md；已提供正文，其项目内保存与提交情况以最新源码确认，不重新生成实验数据。

### 8.3 完成边界

- 固定 15 题保留，不修改标注追分。
- 查询改写覆盖本轮查询变换实现，不追加 Multi-query 为必做项。
- token 预算是 CL100K_BASE_PROXY 估算，不是生成模型精确用量。
- 引用归属检查与人工审阅已开展，自动语义 Judge、自动修复未实现，也不追加为收尾要求。
- 现有分块与过滤继续使用，不追加 chunk size / overlap 参数搜索。
- HyDE、Parent-Child、Contextual Compression、Agentic RAG、Graph RAG 不属于本轮必做范围。

小样本可以验证功能路径、发现具体错误，但不足以证明广泛质量提升或稳定性能。后续默认验收目标是新能力真正进入正式应用并可使用，不是每项能力都产出效果对比脚本。

### 8.4 收尾证据更新

来源配额、token 预算、空上下文、工具后预算以及历史一致性已完成真实接入检查，traceId 与边界见 CURRENT-STATUS.md。不得继续标记“缺少开启状态证据”，不重复请求或已有评测。归档提交、临时配置恢复和历史阶段汇总的未确认事项按已有材料核对。

M7/M8 已实际推进，当前下一入口为 M9-1。M6/M7 既有功能不重做，历史归档缺口不阻塞新阶段；提交、push、tag 分开记录。

---

## 9. M7～M11

### M7：DeepSeek + AutoDL 多模型（历史阶段）

目标是在同一 `/agent/ask`、Spring AI Agent、Tool、RAG 和治理链路中选择生成模型。当前已接入两个真实服务，不是同一模型的两个别名。

#### M7-1：模型注册与请求级选择

- 已实现业务 `modelId`、模型注册、默认选择与显式选择，复用原主链。
- 默认业务标识 `deepseek-main`；自部署标识 `autodl-qwen`。业务标识与供应商实际模型名分离。
- 未知、禁用或能力不支持的选择显式失败；运行调用失败不默默切换其他模型。
- 响应及 `MODEL_EXECUTION` 记录 requestedModelId、modelId、provider、configuredModel、reportedModel、providerResponseId、callStatus。配置值不能代替服务端返回值作为实际调用证据。
- 用户反馈相关自动测试通过；未回传本单元独立提交哈希，不编造。

#### M7-2A：AutoDL 部署与协议验证

- 已部署 `Qwen/Qwen3.8-27B-FP8`，服务名 `qwen3.8-27b-fp8-agent`。
- 模型固定 revision：`c3a462cc3d38cf351f3b26cbd763469eeb74ef81`。
- 环境反馈：Python 3.12、vLLM 0.30.0、PyTorch 2.13.0+cu130、Transformers 5.23.0、huggingface-hub 2.4.0；GPU 报告 RTX 4090、计算能力 8.9。产品显存档位不能代替真实设备信息。
- vLLM 监听 AutoDL 本机 `127.0.0.1:8000`，开发机通过 SSH 映射到 `127.0.0.1:18000`；自设 API key 由环境注入。
- `/v1/models`、普通聊天、结构化 tool_calls、人工回填工具结果后的最终回答均已验证。协议工具样例不等于 Java 真实工具执行，后者见 M7-2B。
- 已处理下载网络/Xet、CUDA 编译与链接环境、ninja/PATH 等阻塞；模型启动成功。协议请求顶层必须是对象，不能直接提交 messages 数组。
- 已确认提交 `b7e4a79`；部署记录为 `docs/M7-2A-AUTODL.md`、脚本为 `deployment/autodl/start-vllm.sh`。后续修复是否全部落入此脚本需以最新工作区为准。

#### M7-2B：接入正式治理链路

- 已实现 `AutodlModelProperties` 与独立 Qwen 客户端，复用现有 ToolCallingManager、工具治理、RAG、引用审计和日志保存。
- 回答模型选择不改变查询改写、Embedding 或重排配置；Embedding 仍为 Ollama bge-m3 + Qdrant。
- 调用排障中采用 HTTP/1.1；针对约 45 秒取消，指导将 Qwen 读取超时调整为 90000 ms，随后实际请求成功。当前生效环境变量仍应由运行配置确认，不能从响应倒推出配置值。
- 读取超时不等于 Agent 整体硬截止时间；工具回合可产生多次模型请求。M8 已补充异步任务和期限治理；任务超时不等于模型调用硬取消。
- 自动测试：用户明确反馈全部通过，沿用；没有最新总数，不累加历史批次，不把 M5 的 112 项当作本轮结果。

| 正式验证 | traceId | 实际证据 |
|---|---|---|
| Qwen + RAG | e1e1b204 | RETURNED；reportedModel=qwen3.8-27b-fp8-agent；响应 ID chatcmpl-9aa5d7b60d3047df；VALID_IDS、unknownIds=[]；usedTool=false |
| Qwen + Java 工具 | 84cab609 | getTableSchema SUCCESS；RETURNED；响应 ID chatcmpl-a01f06aad41ba899；回答 trace_id varchar(64)、created_time datetime(3)，均非空 |

RAG 请求 `SPRING_AI_CHAT.costMs=55468`，工具请求为 12089；是单次运行观察，不作为性能基线。前者计时包含前置知识准备。工具回答 NO_CITATIONS 合理，工具事实不要求伪造知识引用；semanticSupportChecked=false 继续保留。

已确认提交 `bb16096 feat(model): integrate AutoDL Qwen into the governed Agent pipeline`，6 文件、373 插入、8 删除；包括 `docs/M7-2B-AUTODL-AGENT.md`、`AutodlModelProperties.java`、`AgentModelConfigurationTest.java`。提交记录不等于推送或标签已完成。

#### M7 历史边界

正式成功路径已通过，不重复测试。旧记录中未独立回传的历史明细和断线隔离材料保留，不扩写为全部异常路径已验证，也不作为重新启动 AutoDL 或回退到 M7 的前置要求。

M8 已实际交付，下一入口为 M9。LoRA / QLoRA 仍是可选增强，不强制微调，不新增自动 Judge 或反复小样本评分。

### M8：异步任务 + SSE（本轮交付收尾）

目标范围已落地：Task 表、条件状态机、独立有界线程池、幂等、整体期限、显式有限重试、启动恢复和 SSE；没有默认引入 MQ。

#### M8-1：任务生命周期（已实现，沿用既有验收）

- 同步 `/agent/ask` 保留，异步入口为 `/agent/tasks`，共用 SpringAiToolChatService。
- 任务先落库再投递，QUEUED/RUNNING 与四类终态条件更新；线程池满载明确拒绝。
- 可选幂等键与请求指纹；截止时间覆盖排队/执行，迟到结果不能覆盖超时终态。
- 启动恢复收敛遗留任务，不自动重放模型调用。
- M8-1E 保存原始请求并提供显式有限重试；每个原任务唯一子任务，子任务不再派生，已开始执行的原任务不能重试。
- 相关源码与测试已核对；真实恢复/重试成功及 test 通过反馈沿用，不追加重复实验。具体证据见 CURRENT-STATUS.md 第 17 节。

#### M8-2A：后端 SSE（已实现并有真实验收）

- 首次当前快照、每秒只读检查、变化推送、注释心跳、终态后结束；不存在任务返回 404 JSON。
- MVC 适配 Reactor 事件流；独立查询和写入资源；单条连接 60 秒，客户端重连获取当前快照。
- 真实 RUNNING → keepalive → SUCCEEDED、终态关闭、再次手动订阅和普通 GET 一致性均已验证；用户反馈 test 通过。
- 提交 `04c11a2`；状态流不等于逐 token 输出、步骤流或完整事件回放。

#### M8-2B：客户端生命周期验收（范围已收缩）

- 原生 HTML/JS 调试页已使用，实际验证已完成任务展示、主动断开、同一任务之后成功、查询不存在任务。
- 用户不希望提前学习/建设完整前端：不继续扩展调试页，正式 Vue 仍在 M10；已有页面可保留为验收工具，不要求重写。
- 浏览器 60 秒自动重连日志和回答正文未回传，不记为独立验证；可复用已有记录或在 M10 顺带覆盖，不阻塞 M9。

#### M8 收尾结论与边界

代码已确认推送至 `1896c88`，推送前工作区干净。本次更新文档尚待用户放回项目并提交；没有确认新标签或新的全量测试数。

当前是单实例本地执行池方案，没有多实例租约、持久消息投递、任务取消或生产容量验收。TIMED_OUT 限制状态与结果接纳，不保证模型/工具硬取消。SSE 结束后迟到 Trace 可查询；快照轮询可能跳过短暂中间状态。

### M9：Observability + MCP（下一阶段，尚未开始）

目标：在既有同步/异步主链上解释“请求是否成功、耗时在哪里、模型/工具/RAG 使用情况、任务与执行记录如何关联”，再完成一个小型 MCP 集成。保留 Actuator / Micrometer、模型/Tool/RAG 指标、token usage、Tracing、P50/P95、Spring AI Observability 和 MCP 的原总纲范围。

以下是建议推进顺序，具体 API、依赖和 A/B 拆分必须先核对用户最新 ZIP、Spring Boot 3.5.x / Spring AI 1.1.8 的实际兼容性；不视为已实施。

| 建议单元 | 目标 | 验收方向 |
|---|---|---|
| M9-1 可观测性基础与口径 | 盘点现有观测，接通 Actuator/Micrometer 的最小健康与指标入口，确定任务/执行统计边界 | 少量真实请求能够在正确入口观察计数或计时变化，关键标签有界 |
| M9-2 模型、工具、RAG 与用量 | 优先复用 Spring AI 标准观测，补必要业务指标，区分阶段耗时、调用结果与 token usage | 确认正式链路确实产生数据，不重复计数、不用估算冒充实际用量 |
| M9-3 异步关联与 Tracing | 区分并关联 taskId、业务 traceId、标准 trace/span；明确跨线程传播及延迟分布口径 | 能从任务定位执行阶段；说明 P50/P95 的样本和统计窗口，不做小样本性能宣传 |
| M9-4 MCP 最小集成 | 先确定本项目承担 client 或 server 的角色及受控场景，再接一个小型真实协议集成 | 验证发现/调用及必要失败处理；工具治理继续生效 |

设计约束：

1. 先完成一个可独立运行的单元，不先搭完整监控平台或迁移架构。Prometheus/Grafana/追踪后端是否需要，按对应单元目标决定，不预先强制增加部署负担。
2. 先查现有框架自动观测，再补业务埋点，避免同一次调用重复计数。
3. HTTP 提交耗时、排队耗时、Agent 执行耗时、单次模型/工具调用耗时、SSE 连接时长分别定义；不得将它们直接混合或相加。
4. taskId、traceId、用户原文、原始异常文本不能作为无界指标标签；模型、工具、结果等维度也要限制取值来源。敏感正文不默认进入遥测。
5. 模型实际 usage、RAG token 估算、调用次数三者区分；缺失用量保留未知，不填假 0、不推导费用结论。
6. 指标验证以接入主链路可用为主，不重跑模型效果评测。任务超时后的迟到返回应分别反映任务与实际调用状态。
7. 不把现有业务 traceId 直接声称为标准分布式 traceId；跨线程和 providerResponseId 的关联按实际实现记录。
8. MCP 不重写 Agent 编排，不把普通 REST 工具接口直接称为 MCP。继续遵守只读、参数校验、权限、超时和日志脱敏边界。

**新对话直接从 M9-1 开始：先核对最新实现，再完整展开第一个适当大小的单元。** 不因 M6/M7 历史归档或 M8 未回传的非阻塞证据而退回旧阶段。

### M10：Vue

输入诊断、模型选择、任务状态、SSE、Tool Evidence、RAG 来源、最终回答和历史 Trace。把 M8 的订阅、终态主动关闭、重连、幂等提交和旧请求隔离规则落实到 Vue；当前调试页不作为正式前端完成记录。

### M11：部署与作品集

部署目标：一台常驻应用服务器运行 Java、Vue/Nginx、MySQL、Qdrant，Ollama bge-m3 可同机运行；AutoDL GPU 实例独立运行 vLLM。Ollama 在 CPU 上的实际延迟和内存需届时检查，资源不足可单独部署，不预先购买额外 GPU。

- 用 Docker Compose 描述应用依赖、私有网络、持久化卷、健康检查、版本与重启策略；CI、HTTPS、公开 Demo 和作品集随后落地。
- 同一 Compose 网络内使用服务名：MySQL `mysql:3306`、Ollama `http://ollama:11434`、Qdrant 按客户端协议使用 `qdrant:6334`（gRPC）或 `http://qdrant:6333`（REST）。这些是规划示例，实际服务名与配置键以部署文件和源码为准。
- 容器内 localhost 指向当前容器。Java 宿主机运行和容器运行采用不同连接配置，不把 Windows 的 3307 映射端口搬成容器内端口。
- AutoDL baseUrl 是 Java 能到达的模型服务根地址，不是任意应用服务器 IP，也不在当前配置中追加 /v1。若模型仍只绑定 AutoDL 回环地址，单改 IP 无效。
- 采用受控私网、受管理隧道或带认证的 HTTPS 代理连通 AutoDL；不能依赖开发者 Windows SSH 窗口。容器化时需要设计隧道的网络可达性与生命周期。
- MySQL 持久化业务数据；Flyway 初始化/升级结构，不自动搬迁本地历史记录。需要历史数据时单独备份恢复。
- Qdrant 持久化向量和 payload；迁移时选择一致的快照恢复或从原始知识重建索引。知识 manifest、内容与本项目索引状态须一起协调，不能把旧状态文件直接指向空集合。
- Ollama 持久化模型目录；服务器需拉取同一 bge-m3 模型版本或迁移模型文件。切换回答模型不改变 Embedding；若改变 Embedding 模型/版本，不能仅凭同为 1024 维就沿用旧向量。
- 只公开产品入口；数据库、向量库和 Ollama 不直接暴露到公网。密钥使用部署环境配置，不提交 Git；配置数据库业务账号、备份及恢复验证。
- 部署验收覆盖真实 ask、工具、RAG、历史保存与重启后数据保留；不重做模型质量评测。公开数据源与只读诊断范围须明确。

本节是部署计划，尚未执行；不因此把 M11 提前为当前开发单元。README、架构图、既有评测/RAG 报告、简历和面试材料随部署收尾。

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
2. 每次修改说明验证风险；按影响安排自动测试，并通过正式入口做必要真实接入检查。只跑 test 不足以确认新在线能力可用；有效且未受影响的证据直接沿用。阶段整体回归按风险安排，纯文档修改不重跑。
3. 新技术先做最小链路，适合项目的能力应接入正式主链并可选择；不能长期只存在于独立 Benchmark。
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
14. 默认对话指导、用户自行修改；完整讲解一个适当大小的单元。代码注明路径、操作和位置，提供完整受影响方法及关键注释，验证统一置于单元末尾。
15. 项目不安排机械验收题；每单元安排 Git 提交，验证完成后说明下一单元。推送和标签按实际情况确认，不作为学习的形式门槛。
16. 不默认新增效果对比脚本。必要效果实验先明确待回答问题、条件与停止点；样本不足时记录局限，不能无止境调参、补标注和重跑。


---

## 12. 本次修订说明

2026-10-20：更新 M5 验收状态与 M6 入口；更正旧编排、重复接口和规则检索直连的过时架构描述；补充预算与引用审计的能力边界；取消机械重复评测和未执行的扩题要求。M7～M11 的顺序与核心目标保留，不因单个回答质量问题调整整体路线。

2026-11-08：补齐 M6 已实现能力、已有证据与未确认收尾项；明确自动测试、真实接入验证和效果评测的职责。用户要求减少的是重复效果比较，不是正常测试和正式链路验收。下一功能入口为 M7-1，M7～M11 顺序保持。

2026-11-14：M7 按实际模型选择、协议验证与正式接口结果展开，记录已提交与未确认边界；M11 补充应用/模型分离部署、容器地址、数据迁移与持久化。保留 M8→M9→M10→M11 顺序，不把部署计划写成已实施。

2026-11-23：补齐 M8-1 / M8-2A / M8-2B 的交付、验收和已推送状态；更正旧阶段入口与同步/异步架构说明；收缩调试页范围，正式前端留在 M10；M9 为下一阶段，给出从 M9-1 开始的建议顺序与统计边界。保留未回传证据及未确认标签，不追加重复验收。
