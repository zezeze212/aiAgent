# ai-agent-demo 当前状态与交接

> 文档职责：本文件是当前代码事实、验证结果、已知限制和下一阶段入口的唯一权威来源。  
> 长期定位、架构边界和 M0～M11 路线见 [PROJECT-OUTLINE.md](PROJECT-OUTLINE.md)。  
> 状态核对日期：2026-10-20（Asia/Shanghai）。

## 1. 一句话状态

`ai-agent-demo` 当前是一个以 **Spring AI 为正式主链、同步执行的 Java 后端故障诊断 Agent**。

M0～M4 已完成并收束，M5 功能与工程验收已完成。2026-10-20 本地全量测试 112 项通过，无失败、错误或跳过；M5 封版提交与标签尚未提供，不能视为已经创建。

> **下一开发入口：M6：Advanced RAG 与 Benchmark。先确认比较目标和已有基线，再实施有明确收益假设的增量。**

本文依据已提供的代码、后续修改记录、用户回传的测试汇总与真实 API Trace 更新；不是对用户本地最新工作区的重新扫描。历史评测分数不代表当前 M5 代码已重新取得相同成绩。

M4 Evaluation 已结束。后续不得为了评测满分继续修改 Prompt、数据集或业务逻辑；现有失败作为基线事实保留。

---

## 2. 当前里程碑状态

| 阶段 | 状态 | 结果 |
|---|---|---|
| M0 | 已完成 | PRE_DECISION / POST_TOOL 规则 RAG、知识去重、Trace |
| M1 | 已完成 | Compose MySQL、Flyway、事务、Testcontainers、空环境复现 |
| M2 | 已完成 | Tool Contract / Governance、多类型 Tool、Trace 安全治理 |
| M3 | 已完成 | Spring AI 主链、原生 Tool Calling、正式入口切换 |
| M4 | 已完成 | Python Evaluation Harness、14条校准集、50条回归集、版本报告 |
| M5 | 功能与验收已完成；Git 封版待确认 | Manifest、分块、增量索引、Dense Vector RAG、上下文预算与引用审计 |
| M6 | 下一开发入口，尚未实施 | Hybrid、Rerank、Query Transform、RAG Benchmark |
| M7 | 后续计划 | DeepSeek + AutoDL 多模型、可选微调 |
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
- 最近提供的提交：`ee27a37 feat(rag): 完成检索范围过滤与请求级知识上下文预算`。
- 该提交是已知历史节点，不等同于本次 112 项测试所对应的最新 HEAD。
- 当时 `.env.example` 仍有修改；本次未提供 `git status`，不推定它已经提交或撤销。
- M5 建议标签：`v0.8.0-vector-rag`；尚未确认创建。
- 最终提交哈希、工作区状态及标签在用户本地提交后补充，不能编造。

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
| RAG | `KnowledgeRetriever` 统一入口，rule / vector 可切换 |
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

用户已确认清理旧 `/ai/*`、`/spring-ai/*` 重复问答入口和手写编排。正式问答统一使用 `/agent/ask`。旧编排可通过 Git 历史复习；不要重新引入第二套主链。`SimpleRagRetriever` 属于保留的规则基线，不等于旧 Agent 编排。

---

## 7. 当前正式链路

1. `/agent/ask` 校验输入，`SpringAiToolChatService` 创建 traceId 和请求级 `SpringAiToolRunContext`。
2. `SpringAiKnowledgeSupport` 执行 PRE_DECISION 检索：通过 `KnowledgeRetriever` 选择规则或向量实现；请求上下文完成去重、片段数量和字符预算控制。
3. `SpringAiRequestBoundaryPolicy` 判断是否直接澄清；`SpringAiToolAvailabilityPolicy` 根据原始用户输入决定本次可提供的工具。
4. 无确定性澄清时，Spring AI `ChatClient` 调用 DeepSeek，模型自主产生结构化 Tool Call。
5. `SpringAiDiagnosticTools` 复用请求上下文与 `ToolRegistry`，经过权限、参数、超时、重复调用等治理后执行具体工具，记录 `TOOL_EXECUTION`。
6. 工具后按需补充知识：前置阶段已选入知识时跳过；否则执行 POST_TOOL 检索与同一请求累计预算选择。工具事实与知识内容分别保留。
7. 模型根据工具证据和知识生成回答；成功且非确定性澄清的回答执行 `KNOWLEDGE_CITATION_CHECK`，仅审计引用 ID，不重写答案。
8. 经 Trace 脱敏与 `AgentLogService` 短事务持久化 Run / Steps，返回同步 HTTP 响应。

确定性澄清记录 `AGENT_CLARIFICATION`；它不是在模型前强制执行所有工具。表名证据来自原始用户输入，不从知识库示例补造。

注意：`SPRING_AI_CHAT` 的计时范围包含前置知识准备，不能将其与检索耗时简单相加计算总耗时。

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
| 工具 + RAG `eeed4174` | `getTableSchema(agent_run_log)` 成功；14 个字段名与类型一致；选入 5 个片段、1051 字符；3 个引用 ID 合法 | 补充解释仍存在错误，见第 11 节 |

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

**M5 功能与工程验收完成，保留已知限制；文档已更新，最终 Git 提交与标签待确认。** 本次不重跑 M4 模型回归、不扩充 30 条检索集、不为满分调整 Prompt。后续仅有文档变化时无需再次运行上述测试。

### 10.5 历史教学编号对应

此前 M5-3A～M5-3D 涉及统一检索、范围过滤、请求级预算、空知识处理与引用审计；在当前权威总纲中归入 **M5-4：在线检索与引用**。保留历史提交命名，不回退或重复实施。

---

## 11. 当前已知限制

### 11.1 Spring AI / 模型与证据一致性

- 当前生成模型仅接入 DeepSeek；历史 M4 记录了工具选择差异和结构化调用不稳定，不根据少数题硬编码路由。
- 工具成功不代表回答完全正确。`eeed4174` 的字段表格正确，但模型将已有默认值 `CURRENT_TIMESTAMP(3)` 的 `created_time` 归为“无默认值”；工具未返回自增属性，也不足以支持“省略 id 一定报错”的推断。
- 上述问题属于额外解释违背或超出工具证据，应作为后续证据一致性评估样例保留，不将本次请求记为回答完全正确。
- 尚未接入 token usage 和完整 Observability。

### 11.2 RAG

- 字符预算采用正文 `String.length()`，不包含全部提示词、包装元数据和工具结果，不是精确 token 预算。
- 引用审计只核对 ID 是否属于本次选入集合，不验证语义支持、引用是否充分，也不是完整引用格式校验或输出拦截器。
- 默认相似度阈值尚未经过系统校准；不能从单题表现推导全局最优阈值。
- category / visibility / source 过滤不是完整的用户权限或租户授权体系。
- 空上下文实测覆盖预算排除；不能扩展为所有无关问题与所有故障情形均已验证。
- 受管索引已实现恢复相关逻辑，状态恢复已检查；未完成所有中断点的故障注入验证，不声称全面容灾验收。
- 尚未完成 BM25、Hybrid / RRF、Reranker、Query Rewrite 的受控比较；现有检索评估工具和历史记录可复用，不伪造当前版本的 Recall / MRR 成绩。

### 11.3 多模型

- 尚无 `modelId`、Model Registry / Router 和 AutoDL 在线模型。
- 当前项目没有接入已有 LoRA / QLoRA 产物。

### 11.4 Async / 产品

- `/agent/ask` 仍为同步 HTTP；尚无任务状态机、取消、恢复和 SSE。
- 尚无 Vue、MCP、全栈 Compose、CI 和公开 Demo；不影响本次 M5 范围内验收。

---

## 12. 下一开发入口：M6

先完成本地 M5 提交与标签记录，然后进入 **Advanced RAG 与 Benchmark**。M6 尚未实施。

### 12.1 第一单元建议：比较目标与基线设计

1. 阅读最新代码与现有检索评估脚本，确认 rule / dense 的调用入口、结果粒度和已有数据集能否公平比较。
2. 明确要解决的失败类型，并固定知识版本、查询集、过滤范围、TopK、预算及耗时口径。
3. 区分检索指标（来源/片段命中、排序）和回答指标（证据支持、引用正确性），不混成一个总分。
4. 优先复用已有基线证据；只有代码、语料或比较口径变化使旧结果不可比时才补跑。
5. 之后按 BM25 / Hybrid、Rerank、Query Transform 等增量逐项比较；没有收益的方案不要求进入默认链路。

交付 `RAG-BENCHMARK.md`。当前不预设必须新增多少题，也不要求每次修改都重跑整个模型评测集。

### 12.2 接手时读取

`CURRENT-STATUS.md`、`PROJECT-OUTLINE.md`、最新源码、`pom.xml`、Compose、检索配置、`KnowledgeRetriever` 及其实现、`VectorKnowledgeService`、`SpringAiKnowledgeSupport`、`SpringAiToolRunContext`、引用审计、知识 manifest 和现有评估脚本。

9 月 6 日和 10 月 10 日压缩包均是历史快照，不能替代 10 月 20 日后的本地最新代码。

---

## 13. 新对话接手规则

- M0～M5 功能与验收已完成；只补未确认的 Git 封版信息，下一开发入口为 M6。
- 不恢复已取消的 M5-4A，也不恢复 `SpringAiRequiredToolCall` 强制路由方案。
- 不为 M4 少数失败题硬编码工具调用或回答。
- 保留 `/agent/ask` 的 Spring AI 正式主链；旧编排和重复问答入口已清理，规则检索基线继续保留。
- 不修改既有 Flyway V1 / V2；如有数据库变更，新增 migration。
- 主向量库继续使用 Qdrant，不重复选型或同时引入多个实现。
- 每次先说明验证要解决的具体风险；单元内按影响范围测试，阶段封版才全量测试，文档更新无需重新跑测试。
- 先解释原理和设计，生产代码修改集中给完整文件或完整方法，避免微型往返。
- 教学材料使用真正的 Markdown 标题层级，方便 Typora 大纲展示。
- API 调试优先 Postman；构建和脚本兼容 PowerShell。
- 不上传密钥、真实密码或私人数据；项目与工作业务完全隔离。
- 新增 Spring AI API 或依赖前核对 1.1.8 兼容性。
- 个人项目与模型理论学习分线推进，不因已学习 GraphRAG 等概念而立即扩展项目范围。

---

## 14. 下次阶段更新必须核对

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


## 15. 本次更新记录

2026-10-20：补齐 M5 索引与正式主链进展；记录全量测试 112 项通过；更正旧入口、旧编排和“尚无向量库”等过时描述；保留 M4 历史基线与 M5 已知质量限制；将下一开发入口切换到 M6；最终 Git 提交与标签待补。
