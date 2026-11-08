# ai-agent-demo 当前状态与交接

> 文档职责：本文件是当前代码事实、验证结果、已知限制和下一阶段入口的唯一权威来源。  
> 长期定位、架构边界和 M0～M11 路线见 [PROJECT-OUTLINE.md](PROJECT-OUTLINE.md)。  
> 状态核对日期：2026-11-08（Asia/Shanghai）。

## 1. 一句话状态

`ai-agent-demo` 是以 **Spring AI 为正式主链、同步执行的 Java 后端故障诊断 Agent**。

M0～M5 已完成。M6 功能实现、各单元定向测试和已安排的实验/审阅已有反馈，当前处于 **M6-7 最小收尾与 M7 交接**，尚不能据此宣布整个 M6 已完成封版。

当前未确认项仅为：M6 阶段 Java 回归结果、来源配额/token 预算开启后的正式接口证据是否已有、阶段文档在项目内的落地与提交。先复用已有记录；确有缺口才补最小验证，不追加效果评测单元。

> **下一功能入口：M7-1 模型注册与请求级模型选择。**

本文依据提供的代码快照、后续实现记录、用户测试/Git 反馈和真实响应更新，不是对用户最新工作区的完整扫描。可读取的旧 ZIP 停在 b942f5a，不能代替后续修改后的源码。

2026-10-20 的 112 项是 M5 历史全量结果，不是 M6 当前全量数量。最新“git 完成”确认的是此前 M6-6B 归档提交，未收到 M6-7 全量汇总和收尾提交信息。

用户强调：需要正常的自动测试和真实功能接入验证；不希望反复编写脚本，在小样本上比较效果或追分。具体规则见第 14 节。

---

## 2. 当前里程碑状态

| 阶段 | 状态 | 结果 |
|---|---|---|
| M0 | 已完成 | PRE_DECISION / POST_TOOL 规则 RAG、知识去重、Trace |
| M1 | 已完成 | Compose MySQL、Flyway、事务、Testcontainers、空环境复现 |
| M2 | 已完成 | Tool Contract / Governance、多类型 Tool、Trace 安全治理 |
| M3 | 已完成 | Spring AI 主链、原生 Tool Calling、正式入口切换 |
| M4 | 已完成 | Python Evaluation Harness、14条校准集、50条回归集、版本报告 |
| M5 | 功能、验收与提交已确认；标签未确认 | Manifest、分块、增量索引、Dense Vector RAG、上下文预算与引用审计 |
| M6 | 功能已实现，最小收尾待确认 | 正式 BM25/Hybrid、可选重排/改写、上下文配额与 token 估算、结构化引用 |
| M7 | 下一功能阶段，尚未实施 | DeepSeek + AutoDL 多模型、可选微调 |
| M8 | 后续计划 | 异步任务 + SSE |
| M9 | 后续计划 | Observability + MCP |
| M10 | 后续计划 | Vue 诊断控制台 |
| M11 | 后续计划 | Compose、CI、部署和作品集 |

---

## 3. 项目定位与边界

职业方向：

> **Java 后端 + AI Agent 应用工程化**

当前主场景：

- SQL / MyBatis 报错；
- 数据库 Schema 不一致；
- Java StackTrace；
- 技术知识辅助诊断。

项目与工作项目完全隔离，不得引入工作项目中的业务代码、接口、表结构、数据、内部文档或敏感概念。

---

## 4. 当前可信代码与 Git 信息

### 4.1 已知历史里程碑

```text
98eb72a
v0.3.0-rag-closeout
M0 RAG 调用时机修正与收尾
```

```text
f57d01c
v0.4.0-reproducible
M1 可复现数据库与持久化环境
```

M2 建议里程碑：

```text
v0.5.0-m2-closeout
```

M3 建议里程碑：

```text
v0.6.0-spring-ai
```

### 4.2 M4 已验证代码身份

14条正式种子评测的 Manifest 和本地 HEAD 一致：

```text
cbf30f43f6b70c5223d863f890e612edc2430d35
```

运行：

```text
20260827-191440-deepseek-v4-flash-eval-v1-seed-r2
```

50条回归运行也已验证 `CommitMatches=True`，但当前对话没有记录该次完整 commit hash，因此本文不伪造。

新对话如需最新身份，以本地命令为准：

```powershell
git rev-parse HEAD
git log -5 --oneline --decorate
git status --short
```

### 4.3 最近确认的 Git 信息

- 分支：`feature/m3-spring-ai-chat`。
- M5 收尾提交：`b27e29e`，历史输出已确认提交和推送；标签未确认。
- 正式重排：`6d4c233`；正式 BM25/Hybrid：`2c833df`；正式查询改写：`56784d5`。
- 查询改写成对接口：`065fa02`；批量评分：`086bb43`；首轮结果归档：`b942f5a`。
- 来源配额：`5815d63`；token 估算预算：`3ecc674`；结构化引用：`fd3dadc`。
- M6-6B 三例证据检查：用户确认 Git 已完成，具体哈希未回传。
- M6-7 收尾文档：本次更新了交付文件，尚未确认用户替换到项目并提交。
- 最近明确的源码功能提交为 `fd3dadc`，不是当前 HEAD 的断言。最新 HEAD、工作区、推送和标签以用户最新 Git 输出为准。
- 不把用户说的“git 完成”自动解释为“已推送”。

### 4.4 明确作废的未采纳设计

在 M4 收尾过程中曾提出一组“确定性强制工具路由”修改，包括新增：

```text
SpringAiRequiredToolCall.java
```

以及改造 Service 在模型前强制执行 Tool。该方案是为了消除个别评测失败而提出，已经明确作废，不属于当前代码基线，也不得由新对话继续应用。

如果本地从未覆盖这些文件，不需要任何操作。

此前提出的 M5-4A 扩充 30 条检索评测及配套脚本修改也已取消，用户没有执行。它不是 M5 验收要求，接手时不得恢复为待办。

---

## 5. 当前技术栈

| 类型 | 当前实现 |
|---|---|
| Java | Java 17 |
| 后端 | Spring Boot 3.5.16 |
| AI 框架 | Spring AI 1.1.8 |
| 模型 | DeepSeek OpenAI-compatible API |
| 数据访问 | MyBatis、MySQL |
| 数据库迁移 | Flyway |
| 本地数据库 | Docker Compose、MySQL 8.4.x |
| 测试 | JUnit 5、Mockito、Testcontainers |
| RAG | `KnowledgeRetriever` 统一入口，rule / vector / bm25 / hybrid 可切换 |
| 规则基线 | `SimpleRagRetriever`，经适配器接入 |
| Embedding | Ollama `bge-m3`，1024 维 |
| 向量库 | Qdrant 1.19，受管集合 `ai_agent_knowledge_managed_v1` |
| 本地向量服务 | Qdrant REST 6333 / gRPC 6334；Ollama 11434 |
| 构建 | Maven Wrapper |
| Web 端口 | 8088 |
| 本地 MySQL | `127.0.0.1:3307` |
| 数据库 | `ai_agent` |

---

## 6. 当前正式 API

- `POST /agent/ask`：通过 Spring AI 正式主链同步执行 Agent 请求；
- `GET /agent/tools`：查询工具及契约；
- `GET /agent/runs`：分页和条件查询运行记录；
- `GET /agent/runs/stats`：运行统计；
- `GET /agent/runs/{traceId}`：查询 Run 和完整 Steps。

RAG 调试接口：

- `GET /rag/search`：检索知识，支持 query、topK、similarityThreshold、source；用于观察命中内容，不执行 Agent 对话。

实验接口（与正式问答分开）：

- `GET /rag/benchmark/search`：检索策略比较。
- `GET /rag/benchmark/rerank-pair`：固定候选重排比较。
- `GET /rag/benchmark/query-rewrite-pair`：查询改写成对检索比较。

实验开关不等于访问鉴权；不能把实验接口成功当成正式问答已接入。

用户已确认清理旧 `/ai/*`、`/spring-ai/*` 重复问答入口和手写编排。正式问答统一使用 `/agent/ask`。旧编排可通过 Git 历史复习；不要重新引入第二套主链。`SimpleRagRetriever` 属于保留的规则基线，不等于旧 Agent 编排。

---

## 7. 当前正式链路

1. `/agent/ask` 校验输入，`SpringAiToolChatService` 创建 traceId 与请求级 `SpringAiToolRunContext`。
2. `SpringAiKnowledgeSupport` 在 PRE_DECISION 阶段可选调用正式查询改写组件，再通过 `KnowledgeRetriever` 执行配置的 rule / vector / bm25 / hybrid 检索。
3. 可选重排调整候选顺序。请求上下文执行去重、片段数、单片段字符、总字符、可选来源配额与可选 token 估算预算选择。
4. 只有实际选入片段才登记为可引用证据；未选入的检索候选不能成为引用依据。
5. 边界与可用工具策略继续依据原始用户输入工作。改写文本不构成新的用户授权或表名证据。
6. 非确定性澄清请求进入 Spring AI ChatClient，模型自主产生结构化 Tool Call。
7. `SpringAiDiagnosticTools`、请求上下文与 `ToolRegistry` 完成参数、权限、次数、重复调用和超时等治理，记录 `TOOL_EXECUTION`。
8. 工具后按原策略补充知识：前置已准备知识时跳过，否则 POST_TOOL 检索与选择复用同一个请求累计预算。工具事实与知识分开保留。
9. 正常生成回答后执行 `KNOWLEDGE_CITATION_CHECK`，依据本次已选入快照关联出处，不重新检索补造引用，不自动改写答案。
10. `AgentLogService` 保存 Run/Steps；HTTP 输出边界治理 Trace 和结构化引用展示，返回同步响应。

确定性澄清记录 `AGENT_CLARIFICATION`，不是强制执行工具。未执行引用审计的路径使用 `NOT_CHECKED`；已检查但没有约定格式引用使用 `NO_CITATIONS`。

`SPRING_AI_CHAT` 计时包含前置知识准备，不能与检索耗时简单相加。

---

## 8. 当前 Tool 与治理

三个正式 Tool：

- `getTableSchema`；
- `analyzeSqlErrorWithSchema`；
- `analyzeStackTrace`。

治理能力：

- Tool Contract；
- required / type / length / regex / allowed values；
- 权限、risk、sideEffect；
- Tool timeout；
- Agent 总时间预算；
- 最大工具调用次数；
- 重复调用保护；
- 表名来源证据校验；
- BUSINESS_ERROR；
- Trace、脱敏和持久化。

Tool 状态：

```text
SUCCESS
INVALID_ARGUMENT
TOOL_NOT_FOUND
PERMISSION_DENIED
TIMEOUT
BUSINESS_ERROR
EXECUTION_ERROR
```

当前数据库 Tool 只读，不允许执行用户任意 SQL 或自动修改数据库。

---

## 9. M4 Evaluation 已完成

### 9.1 已完成能力

`evaluation/` 已具备：

- JSONL 数据集；
- 配置文件；
- `/agent/ask` 执行器；
- 逐项评分器；
- Markdown 报告；
- Manifest；
- Dataset / Evaluator / Prompt / Knowledge / Code 版本记录；
- 失败用例和失败检查定位。

评估维度包括：

- needTool；
- toolName；
- 参数完整性和准确性；
- Tool 结果证据；
- RAG 状态；
- 回答证据；
- 无依据回答代理指标；
- BUSINESS_ERROR；
- 澄清处理。

### 9.2 14条正式校准基线

运行目录：

```text
evaluation/results/20260827-191440-deepseek-v4-flash-eval-v1-seed-r2
```

身份：

```text
Status         = COMPLETED
Evaluator      = python-rule-evaluator-v2
ManifestCommit = cbf30f43f6b70c5223d863f890e612edc2430d35
CommitMatches  = True
Succeeded      = 14
Failed         = 0
```

评分：

```text
needToolAccuracy=14/14
toolNameAccuracy=8/8
argumentCompleteness=11/11
argumentAccuracy=17/17
toolEvidenceAccuracy=17/17
ragAccuracy=28/28
answerEvidenceCoverage=18/18
unsupportedAnswerProxyRate=0/11
businessErrorHandlingRate=2/2
clarificationHandlingRate=3/3
```

### 9.3 50条回归基线

运行目录：

```text
evaluation/results/20260827-200018-deepseek-v4-flash-eval-v1-regression-v1
```

请求结果：

```text
succeededRequests=50
failedRequests=0
Dataset=eval-v1-regression-v1
Evaluator=python-rule-evaluator-v2
CommitMatches=True
```

评分：

```text
needToolAccuracy=49/50 (98.00%)
toolNameAccuracy=28/30 (93.33%)
argumentCompleteness=39/41 (95.12%)
argumentAccuracy=65/69 (94.20%)
toolEvidenceAccuracy=67/72 (93.06%)
ragAccuracy=100/100 (100.00%)
answerEvidenceCoverage=68/69 (98.55%)
unsupportedAnswerProxyRate=5/41 (12.20%)
businessErrorHandlingRate=5/6 (83.33%)
clarificationHandlingRate=12/12 (100.00%)
```

首轮失败定位到7条。后续确认其中包含评分正则误判和模型真实 Tool 行为差异。

数据集修订为：

```text
eval-v1-regression-v1-r1.jsonl
```

修订只处理评测断言误判，不把模型真实 Tool 失败改成通过。

目标复查结果：

- 六条修订相关用例中仍有 `sql-duplicate-key-030` 出现真实 Tool 选择差异；
- `business-audit-table-039` 重复3次均只生成调用意图文本，没有真实结构化 Tool Call。

### 9.4 M4 最终决策

M4 到此结束，不再：

- 继续扩充用例；
- 追求全部指标 100%；
- 围绕上述两条用例调整生产逻辑；
- 强制硬编码 Tool 路由；
- 反复运行模型评测；
- 为了分数修改数据集、Prompt 或回答。

这两个问题作为模型基线限制保留，不是当前开发任务。

M4 的验收结论：

> **评测 Harness 能稳定执行、记录版本、计算指标、定位失败并生成报告，已经达到建立评测框架的目标。**

---

## 10. M5 已完成功能与工程验收

本节保留 M5 当时的实现与验证记录；M6 增量及当前能力见第 7、11 节。

### 10.1 索引与检索

- 已接入公开知识 manifest、Markdown 清洗、按章节分块与窗口切分、来源及版本/哈希元数据。
- 已接入 Ollama `bge-m3` 与 Qdrant，完成受管索引的新增、更新、删除、幂等同步及状态维护。
- 最近已知知识基线为 5 篇 SQL 故障文档、20 个片段；最新数量以本地 manifest 和索引结果为准。
- 当前受管集合为 `ai_agent_knowledge_managed_v1`，不是历史实验集合。
- `/rag/search` 支持 TopK、相似度阈值、来源精确过滤；category / visibility 基础过滤在检索端应用。
- 已验证 TopK、阈值、指定来源和不存在来源返回空结果。基础过滤有单测，但不能据此宣称已验证多租户权限隔离。

### 10.2 正式主链与上下文

- `KnowledgeRetriever` 统一规则与向量结果，保留 chunkId、source、title、section、score、content。
- `agent.rag.mode` 选择 rule / vector；vector 模式同时要求 `agent.rag.vector.enabled=true` 与服务依赖可用。
- `agent.mode=rule` 不等于配置了 `agent.rag.mode=rule`；独立上下文测试须显式配置所需属性与依赖。
- 请求级知识预算默认最多 5 个片段、每片段 2000 字符、累计正文 6000 字符；跨工具回调累计计算。
- 超长或超过剩余预算的片段整体跳过，继续考虑后续可容纳片段；只对实际选入的片段登记去重。
- 已区分检索失败、没有结果和候选被预算排除，避免将连接失败解释成知识库不存在相关资料。
- 已增加无可用知识时的回答约束；工具事实不因没有附加知识而丢失。
- 引用 ID 审计状态为 `VALID_IDS` / `INVALID_IDS` / `NO_CITATIONS`，不自动修改回答。
- 已修复“orders 的实际表结构”等明确表名表达未被识别的问题，并增加相应测试；不是为某张表硬编码路由。

### 10.3 真实 API 验证

| 场景 | 已观察结果 | 结论边界 |
|---|---|---|
| duplicate-entry 通用问题 | TopK、阈值、来源过滤和空结果符合预期 | 单次耗时差异不能视作稳定性能提升 |
| 请求级预算 | 候选 5 个、选入 2 个，剩余按预算排除 | 验证预算生效，不等于最佳检索参数 |
| 正常引用 `29a3ef1d` | 知识被选入，引用 ID 合法 | 不证明所有回答结论均有语义支持 |
| 空上下文 `fd1f5964` | 候选因片段预算被排除，回答未假装取得数据库事实 | 覆盖预算排除，不代表所有无关问题场景 |
| 工具 + RAG `eeed4174` | `getTableSchema(agent_run_log)` 成功；14 个字段名与类型一致；选入 5 个片段、1051 字符；3 个引用 ID 合法 | 补充解释仍存在错误，见第 12 节 |

`eeed4174` 中工具耗时 26 ms，Agent 耗时 5737 ms；仅作为本次运行记录，不用单样本推导性能基线。前置知识已准备，因此工具后跳过补充检索符合当前策略。

### 10.4 全量测试与验收结论

用户在本地执行阶段全量测试：

```powershell
.\mvnw.cmd clean test
```

回传结果：

```text
Tests run: 112, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 02:14 min
Finished at: 2026-10-20T07:53:58+08:00
```

项目已有数据库 Testcontainers 集成测试随测试阶段执行。本次依据用户提供的全量汇总确认验收，不编造每个测试类的最新数量。此前 4 / 17 / 23 项为不同阶段的定向测试结果，103 项为 M4 历史结果，均不再作为当前全量数。

**M5 功能与工程验收完成，保留已知限制；后续已确认收尾提交 b27e29e，标签尚未确认。** 本次不重跑 M4 模型回归、不扩充 30 条检索集、不为满分调整 Prompt。后续仅有文档变化时无需再次运行上述测试。

### 10.5 历史教学编号对应

此前 M5-3A～M5-3D 涉及统一检索、范围过滤、请求级预算、空知识处理与引用审计；在当前权威总纲中归入 **M5-4：在线检索与引用**。保留历史提交命名，不回退或重复实施。

---

## 11. M6 实现、测试与真实接入证据

### 11.1 实现与定向测试

| 单元/能力 | 已确认反馈 | Git |
|---|---|---|
| 正式重排 | 已实现、验证并提交 | 6d4c233 |
| 正式 BM25 / Hybrid | 已实现、验证并提交 | 2c833df |
| 正式查询改写 | 已实现、验证并提交 | 56784d5 |
| M6-4C2A 成对接口 | 相关 Java 测试 56 项通过 | 065fa02 |
| M6-4C2B 批量评分 | Python 29 项通过；真实配对 15/15 有效 | 086bb43 / b942f5a |
| M6-5A 来源配额 | 已实现；相关 Java 测试 46 项通过 | 5815d63 |
| M6-5B token 估算预算 | 已实现；相关 Java 测试 47 项通过 | 3ecc674 |
| M6-6A 结构化引用 | 已实现；相关 Java 测试 21 项通过 | fd3dadc |
| M6-6B 证据使用审阅 | 一个历史样例、两个当前请求已审阅；用户确认已提交 | 哈希未回传 |

各测试集合有重叠，不能相加为当前全量测试数。M6-7 的阶段 Java 回归曾安排，尚未收到结果；如用户已运行则直接接收已有汇总，不重跑。

### 11.2 当前真实请求观察

2026-11-08 两份原始记录位于 `evaluation/rag/reviews/m6-6b/`：

| 文件 / traceId | 已观察行为 | 不可推广的结论 |
|---|---|---|
| 02-rag-answer.json / c2d20efa | 正式问答中 Hybrid、查询改写 APPLIED、选入 5 个片段、引用 4 个，VALID_IDS；主要知识内容有对应支持 | 不证明重排、来源配额或 token 预算开启后的效果 |
| 03-tool-boundary.json / 4e70854e | getTableSchema 成功返回 14 列；created_time 默认值准确；承认无法确认 id 自增；NO_CITATIONS | 不表示回答全部正确；附加经验判断和检查方法仍有问题 |

本次记录的本地 HEAD 为 fd3dadc，serverIdentity 明确为未核实服务端提交，不能当成部署身份的独立证明。

**来源配额与 token 预算在这两份响应中均关闭。** 它们已有实现和定向测试，但当前可见资料未展示开启状态的正式 API 行为。先检查用户是否有其他有效 Trace；没有才补少量正式请求，观察启用字段、选择/排除计数、请求累计预算及引用集合。可合并检查，不做效果 A/B，不重复已验证的其他能力。

M6-6A 的结构化引用已在上述真实问答中观察到。没有知识引用的工具回答不自动失败，工具证据可独立支持结论。

### 11.3 预算与引用实现边界

- `max-chunks-per-source=0` 关闭来源配额，计数范围为同一请求；仅实际选入片段占用配额。
- `max-estimated-tokens=0` 关闭估算预算；启用时采用 `CL100K_BASE_PROXY`。
- 前置阶段按单片段格式化文本、工具后阶段按单片段 JSON 估算并累计；不是 DeepSeek 精确 tokenizer，也不含完整系统提示、用户消息、工具定义/结果、消息包装和输出预算。
- `knowledgeCitations` 返回 status、citedIds、unknownIds、citations，且 `semanticSupportChecked=false`。
- 片段来自本次选入快照。展示治理可设置 displayModified，不能把该标记当成客户端编码正确性的保证。
- 未新增引用数据库表；历史接口仍通过 Step 查看审计记录，不声称与 ask 拥有相同结构化响应字段。

### 11.4 已有实验结论，不重复运行

固定集：`evaluation/rag/datasets/retrieval-seed-v1.jsonl`，15 题、5 篇文档。保留标注，不为当前成绩修改它。

- 固定候选重排：`evaluation/rag/results/20261101-183004-m6-rerank-pair-58474f5a`。SourceHit@1 为 1.0000→0.9333，EvidenceHit@5 均为 1.0000，MRR 为 0.8556→0.8444。该轮未体现整体收益，保留可选能力。
- 查询改写：`evaluation/rag/results/20261108-084225-m6-query-rewrite-pair-9af61492`。15/15 有效，comparisonReady=true；SourceHit@1 与 EvidenceHit@5 均保持 1.0000，MRR 为 0.8556→0.8778；1 改善、1 退化、13 不变。
- 改写状态 8 APPLIED、6 UNCHANGED、1 SKIPPED、0 FALLBACK；原查询检索平均 126.288 ms，改写组件 626.800 ms，改写加有效查询检索 750.442 ms。
- 判断：小幅排名改善但未增加命中题数，额外延迟明显，目前不支持默认开启。UNCHANGED 可能调用过模型。
- 成对改写范围为 RETRIEVAL_BEFORE_RERANK_AND_CONTEXT，不包含重排、上下文选择或回答生成。
- 原 Manifest 记录 HEAD=065fa02、脚本 AM 状态，随后提交实现与结果；保留真实历史，不修改 Manifest 或为此重跑。

这些是有限样本上的开发观察，不能推导普遍效果。comparisonReady 表示实验有效，不表示策略有收益。

### 11.5 配置、报告与收尾状态

仓库默认值、最近运行配置、推荐策略分开记录。最近真实请求使用 Hybrid 和查询改写；本次文档更新不改变用户启动配置。

`docs/RAG-BENCHMARK.md` 已在上一单元给出完整整理内容，但当前附件不含该文件，是否已落地需以最新项目为准；本次不把“已讲解”记为“已保存并提交”。原始各批报告仍可直接复用。

本次更新了当前状态、总纲和交接材料；用户将文件放入项目后的 Git 提交、推送与标签仍需单独确认。

---

## 12. 当前已知限制

### 12.1 Spring AI / 模型与证据一致性

- 当前生成模型仅接入 DeepSeek；历史 M4 记录了工具选择差异和结构化调用不稳定，不根据少数题硬编码路由。
- 工具成功不代表回答完全正确。`eeed4174` 的字段表格正确，但模型将已有默认值 `CURRENT_TIMESTAMP(3)` 的 `created_time` 归为“无默认值”；工具未返回自增属性，也不足以支持“省略 id 一定报错”的推断。
- 上述问题属于额外解释违背或超出工具证据，应作为后续证据一致性评估样例保留，不将本次请求记为回答完全正确。
- 尚未接入 token usage 和完整 Observability。

### 12.2 RAG

- 字符预算采用正文 `String.length()`，不包含全部提示词、包装元数据和工具结果，不是精确 token 预算。
- 引用审计只核对 ID 是否属于本次选入集合，不验证语义支持、引用是否充分，也不是完整引用格式校验或输出拦截器。
- 默认相似度阈值尚未经过系统校准；不能从单题表现推导全局最优阈值。
- category / visibility / source 过滤不是完整的用户权限或租户授权体系。
- 空上下文实测覆盖预算排除；不能扩展为所有无关问题与所有故障情形均已验证。
- 受管索引已实现恢复相关逻辑，状态恢复已检查；未完成所有中断点的故障注入验证，不声称全面容灾验收。
- BM25、Hybrid/RRF、重排与查询改写已实现并有历史实验；小语料和单轮结果不证明广泛收益。token 估算与结构化出处已补充，仍未实现精确用量控制或自动语义验证。
- M6-6B 响应存在可逆编码错读，审阅时还原用于阅读，原件保留；尚未定位具体发生环节。后续真实接入若被编码问题阻塞，再定向处理。

### 12.3 多模型

- 尚无 `modelId`、Model Registry / Router 和 AutoDL 在线模型。
- 当前项目没有接入已有 LoRA / QLoRA 产物。

### 12.4 Async / 产品

- `/agent/ask` 仍为同步 HTTP；尚无任务状态机、取消、恢复和 SSE。
- 尚无 Vue、MCP、全栈 Compose、CI 和公开 Demo；不影响本次 M5 范围内验收。

---

## 13. 最小收尾与下一功能入口

### 13.1 M6-7 只确认真实缺口

1. 接收已有阶段 Java 回归汇总；没有执行过才运行一次已安排的检查。真实外部重排连通测试按计划关闭，跳过原因如实记录。
2. 核对是否已有来源配额/token 预算开启后的正式接口 Trace。没有时补最小功能接入检查，目的仅为确认开关、选择与引用约束生效，不比较回答质量或耗时优劣。
3. 将更新文档放入项目，确认阶段报告是否已保存，完成一次收尾提交。Git 哈希、push、tag 分别记录。

不重跑固定 15 题，不重做三例审阅，不新增自动 Judge 或回答修复循环。已完成的检查直接沿用，不因交接重新验证。

### 13.2 下一单元：M7-1 模型注册与请求级模型选择

先读最新项目，围绕同一 `/agent/ask`、Agent、工具、RAG 与治理链路加入生成模型选择。区分业务 modelId、供应商实际模型名与能力/可用状态；不要复制第二套 Agent 服务。

查询改写客户端与回答生成客户端职责分开；切换回答模型不自动切换 Embedding、重排或查询改写模型。具体类名、配置与接口修改以最新源码为准。

首单元完成后通过真实正式请求确认默认路径、显式选择与无效/不可用选择的相关行为，结合响应/Trace 证明实际路由。只有一个真实模型时，不把两个别名当成两个模型已经接入。第二个真实服务在后续 AutoDL 接入单元验收，不用 mock 冒充。

### 13.3 新对话材料

最新完整源码 ZIP（含后续修改与文档）、CURRENT-STATUS.md、PROJECT-OUTLINE.md、交接对话、最新 Git 输出，以及已存在但尚未回传的阶段测试/接入结果。若全部包含在 ZIP 中，不必重复上传。

旧 ai-agent-demo(4).zip 停在 b942f5a，不能用于准确修改 fd3dadc 之后的 Service、DTO、上下文和 Controller。

---

## 14. 新对话接手规则

- M0～M5 已完成；M6 已实现增量，先核对最小收尾缺口，下一功能单元为 M7-1。不得按旧文档从头重做 M6。
- 不恢复已取消的 M5-4A，也不恢复 `SpringAiRequiredToolCall` 强制路由方案。
- 不为 M4 少数失败题硬编码工具调用或回答。
- 保留 `/agent/ask` 的 Spring AI 正式主链；旧编排和重复问答入口已清理，规则检索基线继续保留。
- 不修改既有 Flyway V1 / V2；如有数据库变更，新增 migration。
- 主向量库继续使用 Qdrant，不重复选型或同时引入多个实现。
- 每次先说明验证目标；自动测试与必要真实接入检查都要安排。效果比较不是每单元必做项，文档更新不触发重复运行。
- 先解释原理和设计，生产代码修改集中给完整文件或完整方法，避免微型往返。
- 教学材料使用真正的 Markdown 标题层级，方便 Typora 大纲展示。
- API 调试优先 Postman；构建和脚本兼容 PowerShell。
- 默认用户自行修改代码；完整讲解一个单元，给准确路径、完整受影响方法及 Java/Python 关键注释。
- 项目单元不安排机械验收题；验证结束说明下一单元。区分已讲解、已修改、已验证、已提交和已推送。
- 不上传密钥、真实密码或私人数据；项目与工作业务完全隔离。
- 新增 Spring AI API 或依赖前核对 1.1.8 兼容性。
- 个人项目与模型理论学习分线推进，不因已学习 GraphRAG 等概念而立即扩展项目范围。

---

### 验证方式：自动测试与真实接入检查并行，效果比较按需

用户明确要求：减少的是反复编写评测脚本、在少量样本上比较效果、调 Prompt 和追分；不是减少必要测试，更不是仅凭 test 通过认定功能完成。

1. **自动测试**：验证确定性逻辑、异常处理、状态、预算、契约和回归；按改动影响选择范围。修改核心调用链或公共配置时安排必要集成回归，阶段收尾按实际风险做一次整体检查。
2. **真实功能接入检查**：对新引入或改变的在线能力，启动实际应用，通过正式入口验证配置装配、外部服务调用、开关或模型选择、结果返回与必要持久化。`/agent/ask` 的新能力不能只在独立 Benchmark 中验证。
3. **观察依据**：查看实际生效配置、Trace、工具执行、选入上下文、响应字段和必要日志。HTTP 200、自然语言声称“已使用”、或 mock 测试通过均不能单独证明真实接入。
4. **范围与停止条件**：按功能选择少量有代表性的请求，不固定题数。开关功能检查相关启停行为；失败/回退行为按改动风险和已有证据选择，不强制每次重做全部故障。预期路径可观察、关键结果符合约束、没有阻塞性异常即可结束功能验证。
5. **效果比较**：只有需要回答明确的策略选择问题时才安排，先说明问题、比较条件、样本局限和停止条件。小样本可发现接入错误和具体失败，不足以证明广泛效果提升或稳定性能；不将效果评测作为每个新功能的默认作业。
6. **结果复用**：已经验证且未受本次改动影响的证据沿用。不把一次成功推广为系统整体正确，也不因纯文档修改重跑服务。真实请求揭示功能故障时可修复并定向复验，不为了改善分数反复运行。

验证统一放在单元末尾，说明操作、预期现象、判断依据和反馈内容。默认优先 Postman 或简短 PowerShell 请求；已有工具足够时不新建评测框架或批量比较脚本。

---

## 15. 下次阶段更新必须核对

- 日期；
- 当前 HEAD 和 tag；
- 测试总数；
- 新 Flyway migration；
- 当前 Agent 主链；
- Embedding Provider；
- Vector Store；
- Knowledge Version；
- Chunk / Metadata 策略；
- 已完成、进行中和后续；
- 已知限制；
- 下一阶段唯一入口。


## 16. 本次更新记录

2026-10-20：补齐 M5 索引与正式主链进展；记录全量测试 112 项通过；更正旧入口、旧编排和“尚无向量库”等过时描述；保留 M4 历史基线与 M5 已知质量限制；将下一开发入口切换到 M6；最终 Git 提交与标签待补。

2026-11-08：依据 M6 实现、测试和真实响应补齐进度，保留 M4/M5 历史证据；澄清自动测试、真实功能接入和效果比较三者职责。M6-7 的实际收尾结果尚未回传，不提前标记完成。下一功能入口为 M7-1。
