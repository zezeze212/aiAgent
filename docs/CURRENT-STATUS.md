# ai-agent-demo 当前状态与交接

> 文档职责：本文件是当前代码事实、验证结果、已知问题和下一阶段入口的唯一权威来源。  
> 长期定位、架构边界及 M0～M11 路线见 [PROJECT-OUTLINE.md](PROJECT-OUTLINE.md)。  
> 状态核对日期：2026-08-23（Asia/Shanghai）。

## 1. 一句话状态

`ai-agent-demo` 当前是一个以 **Spring AI 为正式主链、同步执行的 Java 后端故障诊断 Agent**。

M0、M1、M2、M3 已经完成并收束。当前已经具备：

- Spring AI `ChatClient` 正式模型调用链；
- Spring AI 原生 Tool Calling Loop；
- 三个诊断 Tool 的 `@Tool / @ToolParam` Schema；
- Spring AI Tool Schema 与旧 Tool Contract 对等测试；
- 首次决策前与工具后的规则 RAG；
- `SpringAiKnowledgeSupport` 的知识注入、Trace 和去重；
- DeepSeek OpenAI-compatible 模型调用；
- 通用 Tool Contract；
- Tool 参数运行时强校验；
- Tool timeout；
- Tool permission / risk / sideEffect 治理；
- SQL/MyBatis 真实 Schema 诊断；
- Java StackTrace 确定性分析；
- Trace；
- MySQL Run / Step 持久化；
- Flyway；
- Docker Compose MySQL；
- Testcontainers；
- Trace 脱敏和长度治理；
- 多类型 Tool Selection 真实联调。

正式 `/agent/ask` 已切换到：

> **`SpringAiToolChatService → Spring AI Tool Calling → ToolRegistry`。**

旧 `SimpleAgentService / AgentOrchestrator / DeepSeekDecisionClient` 手写链路与新链路解耦并冻结保留，只作为架构演进、回归对照和面试讲解基线，不再承载正式入口，也不再同步扩展后续能力。

当前正式进入：

> **M4：Evaluation Harness。**

当前仍然没有：

- 固定 Evaluation Harness；
- 向量数据库；
- 完整 Vector/Hybrid RAG；
- AutoDL 模型与 DeepSeek 多模型选择；
- 异步任务；
- SSE；
- Vue；
- MCP；
- 正式全栈部署。

---

## 2. 项目定位与边界

本项目是个人学习和求职展示项目，职业方向：

> **Java 后端 + AI Agent 应用工程化**

本项目与工作项目完全分离，不得复制、影射或引入工作项目中的：

- 业务代码；
- 接口定义；
- 数据结构；
- 业务数据；
- 内部文档；
- 敏感业务概念。

当前主场景：

- SQL / MyBatis 报错；
- 数据库 Schema 不一致；
- Java StackTrace；
- 技术知识辅助诊断。

后续主线：

- Spring AI；
- Evaluation；
- 完整 RAG；
- 向量数据库；
- DeepSeek + AutoDL 多模型；
- 异步 / SSE；
- Observability / MCP；
- Vue / 部署。

---

## 3. 当前可信 Git 基线

### 3.1 M2 封版

当前阶段按以下里程碑收束：

```text
分支：master
标签：v0.5.0-m2-closeout
里程碑：M2 通用工具调用框架与多类型工具扩展
```

本次对话没有记录 M2 最终 commit hash，因此本文**不伪造 commit hash**。新对话如需精确 Git 状态，应以本地：

```powershell
git rev-parse HEAD
git log -5 --oneline --decorate
git status
```

为准。

是否已经 push 到远程也未在本次交接中重新核验。

### 3.2 M3 待本地封版核验

M3 代码、真实 API 验收和 99 项全量测试已经完成，但本次对话尚未取得最新 HEAD、commit、tag 和 push 结果，因此不能写成已经创建 `v0.6.0-spring-ai`。

建议本地核验并封版：

```powershell
git status
git diff --stat
git rev-parse HEAD
git log -5 --oneline --decorate
```

确认提交后再创建建议标签：

```text
v0.6.0-spring-ai
```

### 3.3 历史可信基线

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

---

## 4. 当前里程碑状态

| 阶段 | 状态 | 结果 |
|---|---|---|
| M0 | 已完成 | PRE_DECISION / POST_TOOL RAG、知识去重、classpath 加载、Trace |
| M1 | 已完成 | Compose MySQL、Flyway、事务、Testcontainers、空环境复现 |
| M2 | 已完成 | Tool Contract/Governance、多类型工具、Trace 安全治理 |
| M3 | 已完成 | Spring AI 主链、Tool Calling、治理/RAG/Trace 迁移、正式入口切换 |
| M4 | 下一阶段 | Evaluation Harness |
| M5 | 后续计划 | 知识工程 + Embedding + 向量库 + Vector RAG |
| M6 | 后续计划 | Hybrid / Rerank / Query Transform / RAG Benchmark |
| M7 | 后续计划 | DeepSeek + AutoDL 多模型与可选微调 |
| M8 | 后续计划 | 异步任务 + SSE |
| M9 | 后续计划 | Observability + MCP |
| M10 | 后续计划 | Vue 诊断控制台 |
| M11 | 后续计划 | Compose / CI / 部署 / 作品集 |

**重要路线调整：**

旧总纲把向量库列为“有条件再做”。现在调整为：

> **向量数据库是 RAG 学习主线中的必做项。**

但最终采用纯向量、Hybrid、Reranker 或其他默认策略，仍由固定评测结果决定。

---

## 5. 当前技术栈

| 类型 | 当前实现 |
|---|---|
| Java | Java 17 |
| 后端 | Spring Boot 3.5.16 |
| 数据访问 | MyBatis、MySQL |
| 数据库迁移 | Flyway |
| 本地数据库 | Docker Compose、MySQL 8.4.x |
| 测试 | JUnit 5、Mockito、Testcontainers |
| 模型 | DeepSeek API |
| 模型调用 | Spring AI `ChatClient`、OpenAI-compatible HTTP |
| AI 框架 | Spring AI 1.1.8 正式主链；旧手写链路冻结保留 |
| RAG | 当前为 `SimpleRagRetriever` 规则检索 |
| 向量库 | 尚未接入 |
| 构建 | Maven Wrapper |
| Web 端口 | 8088 |
| 本地 MySQL | `127.0.0.1:3307` |
| 数据库 | `ai_agent` |

---

## 6. 当前 API

正式接口：

- `POST /agent/ask`：通过 Spring AI 正式主链同步执行一次 Agent 请求；
- `GET /agent/tools`：查询注册工具及契约；
- `GET /agent/runs`：分页/条件查询运行记录；
- `GET /agent/runs/stats`：运行统计；
- `GET /agent/runs/{traceId}`：查询 Run 和完整 Steps。

迁移验证接口：

- `POST /spring-ai/schema-chat`：与正式入口调用同一个 `SpringAiToolChatService`，暂时保留用于迁移对照，不作为第二套产品主链。

早期 `/ai/*` 接口仍可能保留，但不再是项目主线。

---

## 7. 当前 Agent 主链路

```text
用户请求
  ↓
SpringAiToolChatService / traceId
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
SpringAiToolRunContext 治理
  ↓
ToolRegistry
  ↓
ToolAccessPolicy / ToolArgumentValidator / ToolExecutionRunner
  ↓
具体 Tool / TOOL_EXECUTION Trace
  ↓
POST_TOOL RAG（按需、去重）
  ↓
结构化工具证据 + 知识回填
  ↓
Spring AI 生成最终回答
  ↓
TraceContentSanitizer / AgentTraceSanitizer
  ↓
AgentLogService 短事务
  ↓
agent_run_log + agent_step_log
  ↓
同步 HTTP 返回
```

这里的 Tool Calling Loop 是一次 HTTP 内由 Spring AI 管理的模型/工具往返，不是跨请求会话 Memory。

---

## 8. M0 已完成

M0 在 `v0.3.0-rag-closeout` 完成：

- 首次模型决策前 RAG；
- 工具后按需 RAG；
- PRE_DECISION / POST_TOOL；
- 知识去重；
- classpath Markdown；
- 5 类 SQL 错误知识；
- 自动测试。

当前规则知识主题：

- Unknown column；
- Table doesn't exist；
- Duplicate entry；
- Data too long；
- Foreign key constraint。

当前 RAG 只是**规则基线**，不是最终 RAG。

---

## 9. M1 已完成

M1 在 `v0.4.0-reproducible` 完成。

### 9.1 Docker Compose MySQL

本地只容器化 MySQL，Java 可由 IDEA 或 Maven Wrapper 启动。

### 9.2 Maven Wrapper

仓库包含：

```text
mvnw
mvnw.cmd
.mvn/wrapper/*
```

### 9.3 Flyway V1

`V1__create_agent_log_tables.sql` 创建：

- `agent_run_log`；
- `agent_step_log`；
- 主键；
- 唯一键；
- 索引；
- 外键；
- 基础约束。

V1 已成为历史，不允许修改。

### 9.4 Run / Step 短事务

一次 Agent：

```text
1 Run + N Steps
```

在一个短事务中保存。大模型、RAG、Tool 不放入数据库事务。

### 9.5 Testcontainers

真实 MySQL 集成测试覆盖 Flyway、Mapper、事务和 Database Tool。

---

## 10. M2 已完成

M2 阶段名称：

> **通用工具调用框架与多类型工具扩展**

### 10.1 Tool Contract

已经实现：

- `ToolDefinition`；
- `ToolParameterDefinition`；
- `ToolParameterType`；
- `ToolSideEffect`；
- `ToolRiskLevel`。

Tool Definition 描述：

- name；
- description；
- parameters；
- resultDescription；
- timeoutMs；
- readOnly；
- sideEffect；
- riskLevel；
- requiredPermissions。

参数支持 required、type、minLength、maxLength、pattern、allowedValues、trim。

### 10.2 Tool 参数强校验

`ToolArgumentValidator` 在执行前完成：

- null 参数归一；
- 未声明参数拒绝；
- required；
- 类型；
- 长度；
- regex；
- allowed values；
- trim。

### 10.3 ToolExecutionStatus

当前状态：

```text
SUCCESS
INVALID_ARGUMENT
TOOL_NOT_FOUND
PERMISSION_DENIED
TIMEOUT
BUSINESS_ERROR
EXECUTION_ERROR
```

`ToolExecutionResult` 包含 toolName、success、status、result、errorCode、errorMessage、costMs。

### 10.4 Business Error

增加 `ToolBusinessException`。

当前明确业务错误包括：

- `TABLE_NOT_FOUND`；
- `STACK_TRACE_NOT_RECOGNIZED`。

### 10.5 Tool Timeout

已经实现：

- `ToolExecutionConfig`；
- `ToolExecutionRunner`；
- `ToolTimeoutException`；
- Tool 独立 `ThreadPoolTaskExecutor`；
- `Future.get(timeout)`；
- timeout 后 `cancel(true)`。

Agent 总时间预算和单 Tool timeout 是不同层级。

### 10.6 Tool Access Policy

已经实现：

- `ToolAccessPolicy`；
- `ToolAccessDecision`。

治理维度：

- requiredPermissions；
- riskLevel；
- readOnly；
- sideEffect。

当前允许：

```text
DATABASE_METADATA_READ
LOCAL_DIAGNOSTIC_ANALYZE
```

当前默认：

```text
maxRiskLevel = LOW
allowSideEffectTools = false
```

### 10.7 当前三个 Tool

#### getTableSchema

读取真实 `information_schema`，readOnly / LOW / NONE / `DATABASE_METADATA_READ`。

#### analyzeSqlErrorWithSchema

结合 SQL/MyBatis 错误证据和真实 Schema 进行确定性诊断。

真实联调曾验证：

```text
Unknown column 'abc_status'
```

针对 `agent_step_log` 得到：

```text
missingColumns = ["abc_status"]
```

#### analyzeStackTrace

结构：

- `AnalyzeStackTraceTool`；
- `StackTraceParser`；
- `StackTraceAnalysisResult`；
- `StackTraceCause`；
- `StackTraceFrame`。

支持：

- top exception；
- root cause；
- cause chain；
- application frames；
- first application frame；
- frame count；
- 标准多行 StackTrace；
- 被压平的单行 StackTrace。

真实联调：

```text
com.example.order.OrderService.createOrder(OrderService.java:87)
```

成功解析：

```text
firstApplicationFrame.lineNumber = 87
```

### 10.8 Spring 自动 Tool 注册

Spring Test 已验证：

```text
List<AgentTool> → ToolRegistry
```

自动注册三个 Tool。

### 10.9 多类型 Tool Selection

真实 DeepSeek `/agent/ask` 已验证：

| 输入 | 结果 |
|---|---|
| 查询表结构 | `getTableSchema` |
| MyBatis Unknown column + table | `analyzeSqlErrorWithSchema` |
| Java StackTrace | `analyzeStackTrace` |
| Checked vs Runtime Exception 概念问题 | no tool |

当前不需要继续为三工具堆复杂 Tool Selection Prompt。

---

## 11. M3 已完成

M3 阶段名称：

> **Spring AI 渐进式迁移**

### 11.1 Spring AI 正式主链

当前正式 `/agent/ask` 已从旧 `SimpleAgentService` 切换到：

```text
SpringAiToolChatService
→ Spring AI ChatClient
→ DeepSeek OpenAI-compatible API
```

当前依赖基线：

```text
Spring Boot 3.5.16
Spring AI 1.1.8
Java 17
```

`/spring-ai/schema-chat` 暂时保留为迁移验证接口，与 `/agent/ask` 共用同一个 `SpringAiToolChatService`。

### 11.2 原生 Tool Calling 与现有治理复用

三个工具已经通过 `SpringAiDiagnosticTools` 使用 `@Tool / @ToolParam` 暴露：

- `getTableSchema`；
- `analyzeStackTrace`；
- `analyzeSqlErrorWithSchema`。

Spring AI 负责：

- Tool Schema 生成；
- 向模型暴露 Tool；
- 识别模型 Tool Call；
- Tool Calling Loop；
- 将工具结果回填模型并生成最终回答。

项目继续负责：

- `ToolRegistry`；
- 权限、risk、sideEffect；
- 参数强校验；
- 单 Tool timeout；
- Agent 总时间预算；
- 最大工具调用次数；
- 相同参数重复调用保护；
- BUSINESS_ERROR 等确定性错误语义；
- Trace、脱敏和持久化。

工具结果发送给模型前通过 `buildModelToolResult` 恢复为结构化 JSON，避免数组或对象被序列化为嵌套字符串。

### 11.3 Spring AI RAG 迁移

新增 `SpringAiKnowledgeSupport`，已经把现有规则 RAG 接入正式主链：

```text
用户原始问题
→ PRE_DECISION SimpleRagRetriever
→ 知识按证据规则注入首次模型请求
→ Tool Calling
→ POST_TOOL 检索（PRE_DECISION 未命中时）
→ 相同知识去重
→ 最终回答
```

统一优先级仍为：

> **工具真实证据 > 本地知识证据 > 模型通用知识。**

检索失败属于增强能力失败，会记录 Trace 并继续执行 Agent，不会直接中断整个请求。

### 11.4 契约、治理和行为验收

自动化测试已经覆盖：

- 新旧 Tool 名称一致；
- 参数名称一致；
- required / optional 语义一致；
- `ToolContext` 不进入模型 Schema；
- Spring AI Tool 正确进入 `ToolRegistry`；
- 最大次数、重复调用和总时间治理；
- permission、参数、timeout、business error；
- PRE_DECISION 命中与未命中；
- POST_TOOL 跳过、补充和知识去重；
- Tool 结构化结果；
- Trace 与运行记录。

真实 API 验收包括：

| 场景 | 结果 |
|---|---|
| 数据库索引概念问题 | 不调用工具，PRE_DECISION 未命中，直接回答 |
| Unknown column + `agent_run_log` | 命中知识并调用 `analyzeSqlErrorWithSchema`，确认 `model_name` 不存在 |
| 不存在的 `agent_run_logs` | `getTableSchema` 返回 `BUSINESS_ERROR / TABLE_NOT_FOUND`，使用确定性失败答案 |

失败场景中，工具治理终止后不再执行 POST_TOOL 检索，`SPRING_AI_CHAT` 记录失败状态，最终答案不会被模型猜测覆盖。

### 11.5 旧链路保留决策

旧手写链路冻结保留：

- 与 Spring AI 正式主链解耦；
- 不再承载 `/agent/ask`；
- 不再同步实现 M4～M11 新能力；
- 用于对比自研 Agent Loop 与 Spring AI Tool Calling；
- 用于讲解框架替换后仍然保留的业务治理边界。

因此 M3 不执行旧链路删除，不维护两套正式产品主链。

---

## 12. Flyway V2 与 Trace 状态

M2 新增 Flyway V2。

`agent_step_log` 增加：

```text
tool_status VARCHAR(32)
error_code VARCHAR(64)
```

因此状态可以：

```text
Tool → AgentTraceStep → agent_step_log → GET /agent/runs/{traceId}
```

完整查询。

V1、V2 都已经成为迁移历史，以后只新增 V3/V4，不直接修改已执行 migration。

---

## 13. Trace 脱敏与长度治理

已经实现：

- `TraceContentSanitizer`；
- `AgentTraceSanitizer`。

### 13.1 边界

```text
模型 / Tool / Agent 真实运行数据
→ 保持完整

API Trace / DB 审计数据
→ 脱敏 + 截断
```

### 13.2 当前脱敏范围

覆盖常见：

- password / passwd / pwd；
- apiKey / api_key / DEEPSEEK_API_KEY；
- token / accessToken / refreshToken；
- secret；
- Authorization Bearer / Basic。

### 13.3 长度

单个 Trace input/output 最大保存：

```text
20000 字符
```

超出追加：

```text
...[TRUNCATED]
```

### 13.4 真实验收

使用假值：

```text
password=m2-test-password
```

API Trace 返回：

```text
password=***
```

随后查询 `/agent/runs/{traceId}`：

```powershell
$detailJson.Contains("m2-test-password")
```

结果：

```text
False
```

```powershell
$detailJson.Contains("password=***")
```

结果：

```text
True
```

说明 API Steps、Run 持久化、Step 持久化脱敏均已通过，且 Tool 执行未被破坏。

---

## 14. 当前测试状态

M3 收口执行：

```powershell
.\mvnw.cmd test
```

完整回归。

最新 Surefire 汇总：

```text
Tests run: 99
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

本轮新增或重点验证的测试包括：

- `SpringAiDiagnosticToolsTest`；
- `SpringAiToolRunContextTest`；
- `SpringAiToolContractParityTest`；
- `SpringAiKnowledgeSupportTest`；
- 正式 ToolRegistry 治理测试；
- 原有 MySQL/Testcontainers、Trace、RAG 和旧链路回归测试。

测试期间发现 `SpringAiDiagnosticTools` 一度使用 `serializeForStorage(result)` 构造模型 Tool Payload，导致嵌套 JSON 的 `result` 成为字符串。现已改为 `buildModelToolResult(result)`，结构化数组/对象断言通过。

以后若测试数量增加，以最新 Surefire 汇总为准。

---

## 15. 当前已知限制

以下尚未完成，不能写成已有能力。

### Spring AI

- Spring AI 已成为正式主链，但当前只有 DeepSeek 一个 Provider；
- 旧手写链路冻结保留，仍会参与编译和原有测试，但不承载正式入口；
- 尚未接入 token usage 统计和完整 Spring AI Observability；
- `/spring-ai/schema-chat` 迁移验证接口尚未移除。

### Evaluation

- 没有固定 JSONL Evaluation Harness；
- 没有工具准确率、Evidence、延迟的统一报告；
- 没有 Prompt/model/knowledge 版本化评测。

### RAG

- 当前只有规则 `SimpleRagRetriever`；
- 没有 Embedding；
- 没有向量数据库；
- 没有 Vector Store；
- 没有 Hybrid；
- 没有 Reranker；
- 没有 Query Rewrite / Multi-query 实验；
- 没有 Recall@K / MRR / nDCG；
- 没有完整知识 ingest/version pipeline。

### 多模型

- 当前只使用 DeepSeek；
- 没有 `modelId`；
- 没有 Model Registry / Router；
- 没有 AutoDL 在线模型；
- 没有用户模型选择；
- 当前项目没有把已有 LoRA/QLoRA 学习产物接入 Agent。

### Async

- `/agent/ask` 仍为同步 HTTP；
- 没有 task table；
- 没有任务状态机；
- 没有取消/恢复；
- 没有 SSE。

### 产品与平台

- 没有 Vue；
- 没有 MCP；
- 没有完整 AI Observability；
- 没有全栈 Compose；
- 没有 CI；
- 没有正式公开 Demo。

---

## 16. 本次路线调整

### 16.1 向量库从“可选”变为“必做”

旧路线认为规则 RAG 足够时可以完全跳过向量库。

新路线改为：

> **M5 必须实际接入一个向量库，用来系统学习和展示完整 RAG。**

但仍坚持评测驱动：

- Vector Retrieval 必须实现；
- Hybrid 必须理解并实验；
- Reranker 至少实验一次；
- 不要求所有高级技术进入默认链路。

### 16.2 RAG 学习范围扩大

后续必须系统覆盖：

- loader / clean / chunk / overlap / metadata / version；
- Embedding / dimension / cosine；
- Vector DB / ANN / HNSW / TopK / filter；
- BM25 / dense / sparse / hybrid / RRF；
- reranker；
- query rewrite / multi-query；
- Recall@K / MRR / nDCG；
- groundedness / citation；
- token budget / latency。

Graph RAG、HyDE、Parent-Child、Agentic RAG 等以理解和实验为主，不强制进入默认链路。

### 16.3 增加 DeepSeek + AutoDL 多模型路线

后续 M7 增加：

> **DeepSeek 云模型 + AutoDL 自部署小模型。**

目标：

```text
同一个 Java Agent
同一组 Tool
同一套 RAG
同一套 Evaluation
↓
用户选择 modelId
↓
DeepSeek 或 AutoDL 模型
```

AutoDL 优先通过 vLLM 暴露 OpenAI-compatible API。

模型微调属于可选：

- 先 baseline；
- 再 Evaluation；
- 有明确领域差距再 LoRA/QLoRA；
- 微调后同集评测；
- 无提升不进入默认列表。

---

## 17. M4 唯一入口

当前唯一下一阶段：

> **M4：Evaluation Harness**

### M4-0 必须先做

先冻结 M3 评测基线并读取：

1. `SpringAiToolChatService`；
2. `SpringAiDiagnosticTools`；
3. `SpringAiToolRunContext`；
4. `SpringAiKnowledgeSupport`；
5. `ToolRegistry`；
6. `AgentAskResponse / AgentTraceStep`；
7. 当前三个 Tool 的确定性返回结构；
8. M3 的真实 Postman 验收用例；
9. 现有 Python 评测脚本与个人微调评测资产中可复用的通用方法，但不直接绑定训练项目数据。

M4 首先定义 `eval-v1` 数据契约，至少覆盖：

- 普通问题不调用工具；
- `getTableSchema`；
- `analyzeSqlErrorWithSchema`；
- `analyzeStackTrace`；
- 工具参数提取；
- BUSINESS_ERROR；
- 错表名、缺参数和模糊表达；
- 工具证据覆盖；
- RAG 命中与未命中；
- 无依据回答；
- 延迟与模型调用次数。

第一版固定数据集建议不少于 50 条，但先完成格式、执行器和 10～15 条种子样例的最小闭环，再扩充到完整规模。

### M4 原则

- Python 负责离线评测执行、聚合和报告，不重写 Java Agent；
- 评测统一调用正式 `/agent/ask`，不把旧链路作为默认被测对象；
- Tool Selection、参数、Evidence、错误语义和延迟分别计分，不把所有分数简单相加；
- 固定 dataset、prompt、model、knowledge 和代码版本；
- 先得到 Spring AI 当前基线，再进入 M5 Vector RAG；
- M5/M6/M7 的检索、模型和微调实验复用同一评测框架。

M4 完成标准不是“脚本能请求接口”，而是能够重复回答：当前 Prompt、Tool、RAG 和模型在固定任务集上的能力、错误分布、延迟及版本差异是什么。

---

## 18. 后续路线

```text
M4  Evaluation Harness
 ↓
M5  Knowledge + Embedding + Vector DB + Vector RAG
 ↓
M6  Hybrid + Rerank + Query Transform + RAG Benchmark
 ↓
M7  DeepSeek + AutoDL 本地模型 + 可选 LoRA/QLoRA
 ↓
M8  Async Task + SSE
 ↓
M9  Observability + MCP
 ↓
M10 Vue Console
 ↓
M11 Compose + CI + Deploy + Portfolio
```

---

## 19. RAG 后续学习要求

后续 RAG 不能只教“怎么调用向量库 API”。必须系统覆盖：

### Knowledge

loader、clean、chunk、chunk size、overlap、metadata、version、incremental update。

### Embedding

vector、dimension、cosine、dot product、embedding model、索引重建。

### Vector DB

collection、metadata filter、ANN、HNSW、TopK、recall 与 latency。

### Retrieval

keyword、BM25、dense、sparse、hybrid、RRF、rerank。

### Query

rewrite、multi-query、HyDE 概念。

### Evaluation

Recall@K、MRR、nDCG、groundedness、citation、latency、token cost。

至少一个真实向量库必须进入项目，但不同时实现多个 Vector DB。

---

## 20. AutoDL 模型后续要求

M7 的重点不是重新做训练项目，而是学习：

> **模型服务如何成为 Java AI 应用后端的一种 Provider。**

建议链路：

```text
AutoDL GPU
→ 小型 Instruct 模型
→ vLLM
→ OpenAI-compatible API
→ Spring AI
→ ai-agent-demo Model Registry
```

长期 API：

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

Run/Trace 必须记录 modelId/provider。

AutoDL 实例关闭时本地模型允许 unavailable，不能拖垮 DeepSeek 主链。

---

## 21. 新对话接手顺序

新对话按以下顺序：

1. `docs/CURRENT-STATUS.md`；
2. `docs/PROJECT-OUTLINE.md`；
3. 最新项目源码；
4. `pom.xml`；
5. `SpringAiToolChatService`；
6. `SpringAiDiagnosticTools`；
7. `SpringAiToolRunContext`；
8. `SpringAiKnowledgeSupport`；
9. `ToolRegistry`；
10. `AgentTool` / `ToolDefinition`；
11. 三个现有 Tool；
12. `SimpleRagRetriever`；
13. AgentLog / Flyway V1/V2；
14. M3 Spring AI 相关测试；
15. M4 Evaluation 目录与已有离线脚本。

接手规则：

- M0/M1/M2/M3 已结束，不重新设计；
- M2 不再扩 Tool Framework；
- 不修改 Flyway V1/V2；
- `/agent/ask` 已经是 Spring AI 正式主链，不切回旧实现；
- 旧手写链路冻结保留，不同步扩展 M4～M11；
- 当前从 M4 固定 Evaluation Harness 开始；
- 一次推进一个可验证阶段；
- 生产代码大改优先给完整文件；
- 不上传 `.env`、数据库真实密码、API Key；
- 不混入工作项目；
- 后续向量库必须真正实现；
- RAG 技术选型结合 Evaluation；
- AutoDL 微调必须可选且评测驱动；
- 不同时接 Spring AI 和 LangChain4j 两套主框架。

---

## 22. 下次更新必须核对

每次阶段封版至少更新：

- 日期；
- 当前 HEAD；
- tag；
- 测试总数；
- 新 Flyway migration；
- 当前 Agent 主链；
- Model Provider；
- RAG 策略；
- Vector Store；
- Knowledge Version；
- 已完成 / 进行中 / 后续；
- 已知限制；
- 下一阶段唯一入口。
