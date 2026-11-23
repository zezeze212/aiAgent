# ai-agent-demo 当前状态与交接

> 文档职责：本文件是当前代码事实、验证结果、已知限制和下一阶段入口的唯一权威来源。  
> 长期定位、架构边界和 M0～M11 路线见 [PROJECT-OUTLINE.md](PROJECT-OUTLINE.md)。  
> 状态核对日期：2026-11-23（Asia/Shanghai）。

## 1. 一句话状态

`ai-agent-demo` 是以 **Spring AI 为共同执行主链，支持同步问答、持久化异步任务和 SSE 状态订阅的 Java 后端故障诊断 Agent**。

M0～M5 已完成相应功能与工程验收；M6 RAG 增强、M7 多模型核心能力已接入并有既有验证。M8 已完成本轮异步任务与 SSE 核心交付、已确认的功能验收及代码推送；本次完成文档收尾，**下一开发入口为 M9-1：可观测性基础与指标口径**，M9 尚未实施。

最近确认的远端分支为 `feature/m3-spring-ai-chat`，已推送到 `1896c88`。推送前工作区干净；M8-2A 提交为 `04c11a2`。本次新生成的文档尚未确认放回项目、提交或推送，阶段标签未确认。

M8-1E 与 M8-2A 自动测试均有用户通过反馈，不编造最新全量数量。SSE 运行态、心跳、成功终态、重新订阅、404，以及浏览器主动断开后同一任务继续成功均有实际输出。浏览器自动重连日志、回答正文展示尚未回传，保留证据边界，不写成已独立验证，也不阻塞进入 M9。详细记录见第 17 节。

依据：本轮审阅的源码 ZIP 截至 `c7320c4`，随后 M8-2A/B 的修改指导、用户实际响应、测试反馈与推送输出。未对 `1896c88` 的完整源码重新逐文件审计；新对话须以用户新提供的 ZIP 为准。本次不是新一轮全量测试或部署审计。

用户需要正常的自动测试与真实主链路接入验证，但不接受重复小样本效果比较、追分或为文档补证据而反复运行。历史有效结果直接沿用。

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
| M6 | 功能与真实接入已验证；历史归档未确认项保留 | 正式 BM25/Hybrid、可选重排/改写、上下文配额与 token 估算、结构化引用 |
| M7 | 核心接入与正式成功路径已验证；历史证据边界保留 | DeepSeek + AutoDL 统一模型选择、实际执行元数据 |
| M8 | 本轮核心交付与已确认验收完成；代码已推送，文档本次收尾 | 异步任务、幂等、期限、启动恢复、有限重试、SSE；证据边界见第 17 节 |
| M9 | 下一阶段，尚未开始 | Observability + MCP；从 M9-1 开始 |
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
- M7 历史提交：`b7e4a79`、`bb16096`；当时未单独回传推送及标签。
- M8-1E：`c7320c4 feat(task): persist requests and add bounded explicit retries`。
- M8-2A：`04c11a2 feat(task): add SSE task status subscriptions`。
- 页面验收后，用户提供 `git status -sb`：工作区干净、ahead 2；随后 `git push` 成功，远端由 `c7320c4` 更新到 `1896c88`。
- 远端仓库：`https://github.com/zezeze212/aiAgent.git`；分支 `feature/m3-spring-ai-chat`。没有提供 `1896c88` 的完整提交说明和文件清单，新 ZIP 可核对，不猜测。
- 先前遗留的 `AgentLogServiceMySqlContainerTest.java` diff 仅换行/空格调整；后续工作区干净，但不据此推断具体在哪个提交收录。
- 本次文档的项目内替换、提交与推送尚未确认；没有确认新阶段标签。
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
| 生成模型 | 默认 DeepSeek + AutoDL Qwen3.8-27B-FP8，按请求选择 |
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
| 异步 / SSE | 数据库任务状态机 + 有界执行池；Spring MVC 适配 Reactor 事件流 |
| 本地 MySQL | `127.0.0.1:3307` |
| 数据库 | `ai_agent` |

---

## 6. 当前正式 API

- `POST /agent/ask`：通过 Spring AI 正式主链同步执行 Agent 请求；
- `POST /agent/tasks`：创建持久化异步任务，可传 `Idempotency-Key`；
- `GET /agent/tasks/{taskId}`：读取任务快照；
- `POST /agent/tasks/{taskId}/retry`：显式创建或复用唯一重试子任务，不传请求体；
- `GET /agent/tasks/{taskId}/events`：订阅任务状态 SSE；
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

用户已确认清理旧 `/ai/*`、`/spring-ai/*` 重复问答入口和手写编排。同步入口为 `/agent/ask`，异步入口为 `/agent/tasks`，共同复用 Spring AI 执行服务。旧编排可通过 Git 历史复习；不要重新引入第二套主链。`SimpleRagRetriever` 属于保留的规则基线，不等于旧 Agent 编排。

---

## 7. 当前正式链路

### 7.1 共同 Agent 执行链与同步入口

1. `/agent/ask` 校验输入，`SpringAiToolChatService` 通过注册表解析 modelId（缺省选 DeepSeek），然后创建 traceId 与请求级 `SpringAiToolRunContext`。
2. `SpringAiKnowledgeSupport` 在 PRE_DECISION 阶段可选调用正式查询改写组件，再通过 `KnowledgeRetriever` 执行配置的 rule / vector / bm25 / hybrid 检索。
3. 可选重排调整候选顺序。请求上下文执行去重、片段数、单片段字符、总字符、可选来源配额与可选 token 估算预算选择。
4. 只有实际选入片段才登记为可引用证据；未选入的检索候选不能成为引用依据。
5. 边界与可用工具策略继续依据原始用户输入工作。改写文本不构成新的用户授权或表名证据。
6. 非确定性澄清请求进入所选模型的 Spring AI ChatClient，模型自主产生结构化 Tool Call。
7. `SpringAiDiagnosticTools`、请求上下文与 `ToolRegistry` 完成参数、权限、次数、重复调用和超时等治理，记录 `TOOL_EXECUTION`。
8. 工具后按原策略补充知识：前置已准备知识时跳过，否则 POST_TOOL 检索与选择复用同一个请求累计预算。工具事实与知识分开保留。
9. 正常生成回答后执行 `KNOWLEDGE_CITATION_CHECK`，依据本次已选入快照关联出处，不重新检索补造引用，不自动改写答案。
10. 记录 MODEL_EXECUTION 与响应 modelExecution；`AgentLogService` 保存 Run/Steps；HTTP 输出边界治理 Trace 和结构化引用展示，返回同步响应。历史接口仍通过 Steps 查看执行元数据。

确定性澄清记录 `AGENT_CLARIFICATION`，不是强制执行工具。未执行引用审计的路径使用 `NOT_CHECKED`；已检查但没有约定格式引用使用 `NO_CITATIONS`。

`SPRING_AI_CHAT` 计时包含前置知识准备，不能与检索耗时简单相加。

### 7.2 异步入口与状态订阅

1. `POST /agent/tasks` → `AgentTaskService.submit()`：校验请求/模型，按可选幂等键复用任务，或插入 QUEUED 任务；任务落库后再投递独立有界线程池。
2. 工作线程通过条件 SQL `markRunning()` 获取执行资格，再调用共同的 `SpringAiToolChatService.chat()`，不另建 Agent 编排。
3. 根据返回的 traceId 和已持久化 Run 的 success 判定完成结果；完成 SQL 检查状态及截止时间，拒绝迟到结果覆盖超时终态。
4. 独立超时扫描收敛过期 QUEUED/RUNNING；启动恢复处理进程遗留任务，不自动重放模型调用。受控重试通过新 taskId 和 retryOfTaskId 表达。
5. 客户端用 GET 查询或 SSE 获得当前状态。SSE 内部每秒只读数据库，快照变化推送 task 事件，无变化发送注释心跳，终态后结束；不修改任务执行状态。
6. 最终内容通过 `GET /agent/runs/{traceId}` 读取；当前回答字段为 `data.run.answer`。历史接口可能返回 `run=null`，不能仅凭 HTTP 200 判断有记录。

任务 taskId、业务执行 traceId、模型 providerResponseId 各有职责。M9 还要区分业务 traceId 与可观测性系统的 trace/span ID，不能默认它们已经贯通。

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

各测试集合有重叠，不能相加为当前全量测试数。M6-7 的独立阶段回归汇总属于历史未回传记录；后续 M8 有通过反馈但范围未明，不能倒填历史数量。此记录不作为返回 M6 或重跑测试的前置要求。

### 11.2 当前真实请求观察

2026-11-08 两份原始记录位于 `evaluation/rag/reviews/m6-6b/`：

| 文件 / traceId | 已观察行为 | 不可推广的结论 |
|---|---|---|
| 02-rag-answer.json / c2d20efa | 正式问答中 Hybrid、查询改写 APPLIED、选入 5 个片段、引用 4 个，VALID_IDS；主要知识内容有对应支持 | 不证明重排、来源配额或 token 预算开启后的效果 |
| 03-tool-boundary.json / 4e70854e | getTableSchema 成功返回 14 列；created_time 默认值准确；承认无法确认 id 自增；NO_CITATIONS | 不表示回答全部正确；附加经验判断和检查方法仍有问题 |

本次记录的本地 HEAD 为 fd3dadc，serverIdentity 明确为未核实服务端提交，不能当成部署身份的独立证明。

上述两份历史响应的来源配额与 token 预算关闭；后续真实接入已补齐，不再列为缺口：

| traceId | 已验证结果 |
|---|---|
| df089841 | 来源上限 1；候选 5、选入 3，三个来源各 1，配额排除 2 |
| 6f7e2d13 | token 预算 256；选入 1、估算 125，预算排除 4 |
| 98846d9b | token 预算 1；候选 5 全排除、选入 0、NO_CITATIONS |
| bdf87880 | getTableSchema 成功；前置与工具后候选均受预算约束，工具事实正常用于回答 |
| 查询 df089841 | 历史上下文选择、引用审计、保存回答与原响应一致 |

本轮固定 Hybrid、关闭查询改写和重排；来源快照与引用归属一致，semanticSupportChecked=false；工具后进入 POST_JSON_CHUNK_SUM，未绕过预算。正式接入检查已通过，无需追加请求。归档建议为 evaluation/rag/reviews/m6-main-chain-smoke/；归档提交与临时配置恢复属于历史未独立确认项，日常不能保留 max-estimated-tokens=1；接手时只核对最新配置，不重复这些功能实验。

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

仓库默认值、最近运行配置、推荐策略分开记录。M6 审阅曾启用查询改写；后续 M6 预算接入检查使用 Hybrid 并关闭查询改写和重排；本次文档更新不改变用户启动配置。

`docs/RAG-BENCHMARK.md` 已在上一单元给出完整整理内容，但当前附件不含该文件，是否已落地需以最新项目为准；本次不把“已讲解”记为“已保存并提交”。原始各批报告仍可直接复用。

上述为 M6 当时的归档边界；本轮 M8 代码已确认推送至 1896c88，本次更新文档仍需用户放回项目并提交。不要把历史归档未回传解释为功能未完成。

---

## 12. 当前已知限制

### 12.1 Spring AI / 模型与证据一致性

- 当前生成模型已接入 DeepSeek 和 AutoDL Qwen；历史 M4 记录了工具选择差异和结构化调用不稳定，不根据少数题硬编码路由。
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

- modelId、注册与 AutoDL 已接入；正式成功路径已验证。历史保存和断线隔离的本单元结果尚待回传，不能宣称全部异常路径已真实覆盖。
- 当前项目没有接入已有 LoRA / QLoRA 产物。

### 12.4 Async / 产品

- 同步 `/agent/ask` 保留；异步任务、状态机、期限、有限重试、启动恢复和 SSE 已接入。
- 当前为单应用实例下的本地执行池方案，不是持久化消息队列；数据库保存任务不等于进程重启后自动恢复执行。启动恢复按遗留任务统一收敛，不具备多实例任务归属/租约机制。
- TIMED_OUT 是业务状态及结果接纳边界，不保证底层模型 HTTP 调用或工具立即停止；允许补写迟到 Trace，但不恢复为成功。
- 没有任务取消 API。关闭 SSE 仅停止订阅，不能称为取消任务。
- SSE 是数据库快照订阅，不是 token 流或完整事件日志；不承诺每个中间状态必达，不实现 Last-Event-ID 回放。
- 每连接独立查询；当前没有共享订阅、分布式事件广播或完整连接容量治理，不宣称已完成高并发生产验收。
- 已有原生 HTML/JS 调试页；正式 Vue、MCP、完整 Observability、全栈 Compose、CI 和公开 Demo 仍属后续阶段。

---

## 13. M7 当前实现、证据与收尾

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

#### M7 历史证据边界

正式 Qwen + RAG/工具成功路径已通过，不重复部署或测试。旧文档曾保留 `/agent/runs/84cab609` 历史明细、隧道断开后失败与无静默回退、同时 DeepSeek 可用的独立回传缺口。这些按历史记录保留，不扩写为全部异常路径已验证，也不作为重启 AutoDL、回退到 M7 的要求。

后续学习已实际进入并交付 M8，现转入 M9。LoRA / QLoRA 非必做，不新增自动 Judge 或反复小样本评分。

### 交接与历史收尾

新对话读取用户最新 ZIP、本文、更新后的总纲、2026-11-23 交接文档和指导约定。源码事实以新 ZIP 为准，验证事实沿用已有输出；M6/M7 的历史归档缺口不阻塞 M9。

---

## 14. 新对话接手规则

- 下一开发入口为 M9-1 可观测性基础；M8 本轮核心交付和已确认验收已完成。先核对最新源码，再完整展开一个单元，不重做 M6/M7/M8 或要求重启 AutoDL。
- 不恢复已取消的 M5-4A，也不恢复 `SpringAiRequiredToolCall` 强制路由方案。
- 不为 M4 少数失败题硬编码工具调用或回答。
- 保留 `/agent/ask` 的 Spring AI 正式主链；旧编排和重复问答入口已清理，规则检索基线继续保留。
- 不修改已应用的 Flyway migration（已审阅基线为 V1～V6，最新版本以新 ZIP 为准）；如有数据库变更，新增 migration。
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
- 测试范围与已有结果；未提供总数时明确未知，不为补文档重复测试；
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

2026-11-14：补齐 M6 已完成真实接入、M7 模型选择、自部署协议与正式 RAG/工具结果；记录 b7e4a79/bb16096，保留未确认的历史/断线隔离/推送状态。下一阶段为 M8，M11 部署边界见总纲。

2026-11-23：完成 M8 本轮文档收尾，补齐异步接口、任务/重试语义、SSE、实际验收和已推送至 1896c88 的事实；保留自动重连与回答正文未回传边界，停止扩展调试页，下一入口改为 M9-1。本次文档尚待项目内提交。


## 17. M8 异步任务与 SSE 本轮收尾

### 17.1 状态与依据

截至 2026-11-23：M8 本轮核心功能已交付，以下列出的真实路径已验证，代码已推送至 `1896c88`；本次为文档收尾，下一入口 M9。此结论不包含全故障覆盖、分布式执行、生产容量测试或阶段标签。

- M8-1 的源码事实来自本轮已读 ZIP（HEAD c7320c4）与 2026-11-22 交接；M8-2A/B 来自指导代码、用户运行输出和 Git 反馈。
- 用户反馈 M8-1E 测试全部通过，M8-2A 又反馈“test 也通过”。没有本轮全量汇总，不复用 M5 的 112 项或累加不同批次。
- `1896c88` 全量源码尚未重新审阅；新对话必须读最新 ZIP，不把指导片段当作最终 diff。

### 17.2 异步任务基线

- 状态：QUEUED、RUNNING、SUCCEEDED、FAILED、REJECTED、TIMED_OUT。执行资格和终态变更使用条件更新，避免并发覆盖。
- 已审阅迁移：V3 创建任务表；V4 幂等键与指纹；V5 整体截止时间；V6 保存请求正文与重试关联，`retry_of_task_id` 唯一索引。
- 独立任务池为 2 个执行线程、8 个等待位置，满载拒绝，不让 HTTP 请求线程代替执行 Agent；这些是项目策略，不是通用标准。
- 任务先持久化再投递，禁止外围事务造成“工作线程读不到未提交任务”。这不是数据库与内存投递的原子消息事务；进程中断留下的任务由启动恢复收敛。
- 同一幂等键、同一请求复用原任务；不同请求冲突，不自动重跑失败任务。内部键、指纹、requestMessage 不随任务查询和 SSE 暴露。
- 截止时间包含排队和执行；过期任务不能在迟到结果返回后改写成 SUCCEEDED。真实输出的 createdTime 到 deadlineAt 为 600 秒；application.yml 默认 120 秒由环境变量覆盖，不能把默认值写成此次实际预算。
- 启动恢复将遗留 QUEUED/RUNNING 按截止时间收敛为 TIMED_OUT 或 FAILED/TASK_PROCESS_RESTARTED，不自动重新调用模型。

### 17.3 显式有限重试

1. 原任务必须没有 startedTime，且状态为 REJECTED、TIMED_OUT，或 FAILED/TASK_PROCESS_RESTARTED；必须保存可用 requestMessage。
2. 每个原任务最多派生一个子任务；重复重试返回已有子任务，数据库唯一约束兜底并发竞争。
3. 子任务不能再派生重试任务。startedTime 已存在就拒绝，不能用 traceId=null 推断尚未执行。
4. 新建 taskId，用 retryOfTaskId 关联原任务；重新计算时间预算；固定原任务已选定的业务 modelId，不因默认模型配置改变而切换。
5. 已有测试包含重复请求复用、并发插入失败后读取获胜任务、拒绝已开始任务和子任务等；不把这些单元测试冒充真实并发压力测试。

### 17.4 SSE 与调试页

- 后端组件：AgentTaskSnapshot、AgentTaskSseConfig、AgentTaskSseService，入口 `GET /agent/tasks/{taskId}/events`。以 Spring MVC 适配 `Flux<ServerSentEvent<AgentTaskSnapshot>>`，未切换整个后端到 WebFlux。
- 建流前查询一次，任务不存在返回 HTTP 404 + JSON；建立连接先发送当前快照。之后每秒查询，变化时发送 task 事件，否则发送 `:keepalive` 注释。
- 四种终态发出后结束；单条连接最多 60 秒，MVC 异步默认超时 65 秒。客户端获得 `retry:2000`；浏览器可重连读取当前快照，但终态处理器须主动 close。
- 查询调度、定时信号和 MVC 网络写入独立于 Agent 执行池。取消订阅停止后续轮询，不承诺已开始的 JDBC 操作被即时取消。
- SSE 网络断开或查询异常不修改任务状态。终态订阅结束后，迟到 Trace 可由普通 GET 或新订阅获取。
- 已添加/使用 `static/task-console.html`、`static/task-console.js` 的原生调试页，提供提交、订阅、断开、查询和按 Trace 取历史记录；最终文件以新 ZIP 为准。
- 用户明确不希望提前建设前端：停止扩展此调试页，正式 Vue 仍在 M10。M8-2B 归为客户端生命周期验收；已有页面可保留，不要求重写或删除。

### 17.5 已确认真实验收

| 场景 | 标识 | 已观察结果 |
|---|---|---|
| 执行前进程中断后启动恢复 | T1 `bfbe2281f5754eb0bdb9373170f8df1d` | FAILED / TASK_PROCESS_RESTARTED，startedTime=null |
| 显式重试 | T2 `7a616a9ae8144cb99d1a0bb2e38cbd4b`，Trace `f0e83404` | 新 taskId、retryOfTaskId 正确，重新计算预算，最终 SUCCEEDED；对子任务 retry 返回 409 |
| 订阅已结束任务 | `8c0638b668094174bd2e3fe8a9327aa0`，Trace `12c57b7c` | HTTP 200 text/event-stream，发送 SUCCEEDED 后 curl 结束；普通 GET 一致 |
| 运行态到终态 SSE | `232ba70eb57449c1ad17a54970c28ee5`，Trace `a7c3a176` | RUNNING → keepalive → SUCCEEDED，连接结束；再次订阅返回成功终态 |
| 浏览器订阅已有成功任务 | 同上 | 显示 SUCCEEDED、Trace 和“任务已结束，订阅已关闭” |
| 主动断开后任务继续 | `aae149b3a2dc42bea38d2e7077d0334b`，Trace `508415c3` | 先 RUNNING，主动断开；之后同一任务查询为 SUCCEEDED |
| 不存在任务 | `not-exist` | SSE 请求 HTTP 404/application/json，TASK_NOT_FOUND；页面普通查询显示错误并清空旧任务展示 |

### 17.6 证据边界与停止条件

- 没有回传浏览器“60 秒连接结束 → 自动重连”的日志，不记为已独立验证。重复手动订阅已完成任务与自动重连是两件事。
- 页面状态和 Trace 已有输出，但回答正文未贴出；Run 请求曾返回 200 success 的折叠输出，不等于回答内容已逐项核对。
- 原重试 Run 的保存原文、MODEL_EXECUTION.callStatus 和供应商元数据未独立贴出；不倒填为已核验。
- 这些保留为证据边界，不要求为了收尾重新启动服务或模型。用户若已有日志直接补记；后续 M10 或相关功能修改时可顺带覆盖，不阻塞 M9。
- 不重做已通过的幂等、启动恢复、有限重试、真实 SSE 或模型效果比较。本次文档更新不要求新增测试或再跑全量。
- 若以后调试需要暂停运行任务，只暂停工作线程（Thread），不能暂停 All 导致 SSE/HTTP 一起停住。此前 IDEA Stop 的精确行为未证实，不承诺它会在断点位置瞬间终止进程。

## 18. 下一入口：M9 可观测性与 MCP

M9 尚未开始。建议先进入 **M9-1：Actuator / Micrometer 基础与指标口径**，具体依赖、配置键、API 和单元拆分由新对话核对最新源码与实际版本后确定。

1. 先盘点现有日志、Run/Step、任务表、Spring AI 自动观测及现有依赖，避免重复埋点。
2. 先接通有限的健康/指标能力，明确计数和耗时范围；一次给出一个可落地主链路单元，不先铺完整监控基础设施。
3. 指标区分提交/排队/执行/任务终态/模型与工具调用/SSE 连接；超时后模型仍可能返回，统计边界不能混淆。
4. taskId、traceId、请求原文、原始异常文本不能作为无界指标标签。标签采用有界维度；敏感正文不默认写入遥测。
5. 业务 traceId 不自动等于标准 tracing traceId；跨异步线程关联和标准 tracing 在对应单元明确处理。
6. 模型 token usage 以实际可获得的响应/框架元数据为依据，缺失时记录未知，不能用 RAG token 估算冒充供应商用量。
7. MCP 保留为后续子单元：先明确本项目作为客户端还是服务端、目标和真实协议边界，再做一个受控的小型集成。保留工具校验、只读、安全和超时治理，不重写 Agent 编排。

长期计划以 PROJECT-OUTLINE.md 的 M9 节为准；这里是下一入口和设计约束，不表示已经添加指标、Tracing 或 MCP。
