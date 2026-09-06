# ai-agent-demo 当前状态与交接

> 文档职责：本文件是当前代码事实、验证结果、已知限制和下一阶段入口的唯一权威来源。  
> 长期定位、架构边界和 M0～M11 路线见 [PROJECT-OUTLINE.md](PROJECT-OUTLINE.md)。  
> 状态核对日期：2026-08-30（Asia/Shanghai）。

## 1. 一句话状态

`ai-agent-demo` 当前是一个以 **Spring AI 为正式主链、同步执行的 Java 后端故障诊断 Agent**。

M0、M1、M2、M3、M4 已完成并收束。当前唯一入口是：

> **M5-1：Qdrant Dense Vector RAG 端到端竖切 + 10～15 条检索种子集。**

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
| M5 | 当前唯一入口 | Qdrant Dense Vector RAG 竖切、引用、检索种子集和基线数字 |
| M6 | 后续计划 | FastAPI、Rerank、按证据实施 Hybrid、Query Rewrite、Benchmark |
| M7 | 后续计划 | Async Task、SSE、最小 Vue、Demo Compose 和可演示闭环 |
| M8 | 后续计划 | DeepSeek + AutoDL 多模型、可选微调 |
| M9 | 后续计划 | Micrometer / Spring AI Observability、可选 Langfuse、MCP |
| M10 | 选做 | Elasticsearch BM25/IK 对照；Neo4j / GraphRAG 边界研究 |
| M11 | 后续计划 | CI、部署加固、README、报告和作品集收束 |

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

### 4.3 明确作废的未采纳设计

在 M4 收尾过程中曾提出一组“确定性强制工具路由”修改，包括新增：

```text
SpringAiRequiredToolCall.java
```

以及改造 Service 在模型前强制执行 Tool。该方案是为了消除个别评测失败而提出，已经明确作废，不属于当前代码基线，也不得由新对话继续应用。

如果本地从未覆盖这些文件，不需要任何操作。

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
| RAG | `SimpleRagRetriever` 规则检索 |
| 向量库 | 尚未接入 |
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

迁移验证接口：

- `POST /spring-ai/schema-chat`：与正式入口共用 `SpringAiToolChatService`，暂时保留，不作为第二套主链。

旧 `/ai/*` 接口可能仍存在，但不再是项目主线。

---

## 7. 当前正式链路

```text
用户请求
  ↓
SpringAiToolChatService / traceId
  ↓
SpringAiRequestBoundaryPolicy
  ↓
SpringAiToolAvailabilityPolicy
  ↓
SpringAiToolRunContext
  ↓
SpringAiKnowledgeSupport
  ↓
PRE_DECISION SimpleRagRetriever
  ↓
Spring AI ChatClient / DeepSeek
  ↓
Spring AI Tool Calling Loop
  ↓
SpringAiDiagnosticTools
  ↓
ToolRegistry
  ↓
ToolAccessPolicy / ToolArgumentValidator / ToolExecutionRunner
  ↓
具体 Tool / TOOL_EXECUTION Trace
  ↓
POST_TOOL RAG（按需、去重）
  ↓
Spring AI 基于证据生成最终回答
  ↓
TraceContentSanitizer / AgentTraceSanitizer
  ↓
AgentLogService 短事务
  ↓
agent_run_log + agent_step_log
  ↓
同步 HTTP 返回
```

输入证据不足且满足明确边界条件时，可以通过 `SpringAiRequestBoundaryPolicy` 返回确定性澄清，并记录：

```text
AGENT_CLARIFICATION
```

这不等于对所有 Tool 请求进行硬编码路由。

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

## 10. 当前测试状态

M4 收口期间本地 Maven 最新可信结果：

```text
Tests run: 103
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

测试总数不是固定验收条件，以最新 Surefire 汇总为准；核心要求是 Failures 和 Errors 均为 0。

---

## 11. 当前已知限制

### Spring AI / 模型

- 当前只有 DeepSeek 一个 Provider；
- 模型 Tool Calling 存在选择差异和结构化调用不稳定；
- 这些限制已经被 M4 记录，当前不修；
- 尚未接入 token usage 和完整 Observability；
- `/spring-ai/schema-chat` 尚未移除。

### RAG

- 当前只有规则 `SimpleRagRetriever`；
- 没有 Embedding；
- 没有 Vector Store；
- 没有 Hybrid / Reranker；
- 没有完整知识 manifest、chunk、version、增量索引和 citation；
- 没有 Recall@K / MRR / nDCG。

### 多模型

- 没有 `modelId`；
- 没有 Model Registry / Router；
- 没有 AutoDL 在线模型；
- 当前项目没有接入已有 LoRA / QLoRA 产物。

### Async / 产品

- `/agent/ask` 仍为同步 HTTP；
- 没有 task table、状态机、取消、恢复和 SSE；
- 没有 Vue；
- 没有 MCP；
- 没有全栈 Compose、CI 和公开 Demo。

---

## 12. M5 当前唯一入口

### 12.1 M5-0 已完成决策

```text
Java / Agent：Spring AI 1.1.8
Chat / Tool Calling：DeepSeek OpenAI-compatible API
Dense Embedding baseline：Ollama + BGE-M3
主向量库：Qdrant
规则基线：SimpleRagRetriever（保留、可切换）
后续模型服务：Python FastAPI
Reranker：bge-reranker-v2-m3（M6 必做实验）
Hybrid：BGE-M3 dense + sparse + Qdrant RRF（按失败证据实施）
```

边界：

- Ollama 只承担 M5 dense baseline，不承担 rerank；
- Qdrant 是唯一主向量库；
- Spring AI 通用 `VectorStore` 用于 dense，M6 Hybrid 再通过 `getNativeClient()` 下沉 Qdrant 原生客户端；
- Elasticsearch 不作为 M5/M6 主库，只在 Demo 完成后选做 BM25/IK 对照；
- Langfuse 不进入 M5/M6 关键路径；
- Neo4j / GraphRAG 当前不实现。

### 12.2 下一步唯一入口

下一步不是重新讨论选型，也不是返回 M4 Agent 评测，而是：

> **M5-1：公开脱敏 Wiki Markdown → 最小 manifest / Chunk → Ollama BGE-M3 dense → Qdrant → TopK → Spring AI → 带引用回答，同时建立 10～15 条冻结检索种子集。**

新对话首先读取：

1. `CURRENT-STATUS.md`；
2. `PROJECT-OUTLINE.md`；
3. 当前项目源码；
4. `pom.xml`；
5. `compose.yaml` 或当前 Compose 文件；
6. `SimpleRagRetriever`；
7. `SpringAiKnowledgeSupport`；
8. `SpringAiToolChatService`；
9. `src/main/resources/docs/knowledge/`；
10. 相关 RAG 测试。

如果需要引入 `personal-engineering-wiki`，必须由用户另行提供或给出本地路径；只读取 manifest allowlist 中公开、脱敏的文档，不扫描或导入整个 Wiki。

检索种子集与 M4 Agent 评测集分开：

- 初始只做 10～15 条；
- 标注 expected source / section / evidence text，不写死 Chunk ID；
- 第一版记录 Recall@5、MRR 和延迟；
- 链路稳定后再扩展到约 30～50 条；
- 不因单条失败增加固定关键词、固定回答或生产路由。

---

## 13. 新对话接手规则

- M0～M4 已结束，不重新设计；
- M5-0 选型已完成，当前从 M5-1 开始；
- 不应用 `SpringAiRequiredToolCall` 强制路由方案；
- 不继续修 `sql-duplicate-key-030` 和 `business-audit-table-039`；
- 不修改 Flyway V1 / V2；
- `/agent/ask` 保持 Spring AI 正式主链；
- 旧手写链路冻结保留，不同步扩展；
- 第一版直接使用 Qdrant，不实现 `SimpleVectorStore` 过渡版；
- 不重新比较 Qdrant、pgvector、Milvus 和 Elasticsearch 作为 M5 主库；
- 不在 M5 提前接 FastAPI、Reranker、Hybrid、Elasticsearch、Langfuse、Neo4j 或 GraphRAG；
- M6 的 Reranker 是必做对照实验，但是否默认启用由指标和延迟决定；
- M6 完成后优先进入 M7 SSE、最小 Vue 和 Compose，不继续无限扩 RAG；
- 先解释原理和设计，再集中修改完整文件，并给出修改内容的解释；
- 避免“改一两行—回传—再改一两行”的微型往返；
- API 调试优先 Postman，脚本和构建可以使用 PowerShell；
- 不提供压缩包式代码交付，生产代码大改优先给完整文件；
- 不上传 `.env`、API Key、数据库真实密码或私人数据；
- 不混入工作项目；
- 未核对 Spring AI 1.1.8 兼容性前，不直接套用其他版本 API。

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
