# ai-agent-demo 当前状态与交接

> 文档职责：本文件是当前代码事实、测试结果、进行中问题和新对话接手入口的唯一权威来源。  
> 长期定位、架构边界和 M0～M8 路线见 [PROJECT-OUTLINE.md](PROJECT-OUTLINE.md)。  
> 状态核对时间：2026-08-02 16:57（Asia/Shanghai）。

## 1. 一句话状态

项目当前是一个**同步的、具备基础工具调用、工具后规则 RAG、运行保护和 Trace 落库的 Java 后端故障排查 Agent 原型**。

长期目标是“异步、证据驱动、可观测”，但异步任务、首次决策前 RAG、Python 自动评测、Vue、Wiki 导入、向量检索和正式部署均未完成。

## 2. 审计基线

### 2.1 Git

审计时分支：

```text
master...origin/master
```

审计时工作区：干净。

最近提交：

```text
18d569e 2026-08-02 feat: support pre-decision knowledge retrieval and refresh project roadma
```

该提交实际修改内容：

- 新增 5 篇 `docs/knowledge/sql/*.md`；
- 修改 `KnowledgeSearchResult`；
- 将 `SimpleRagRetriever` 从单一 Unknown column 规则扩展到 5 类规则；
- 将 `SimpleRagRetrieverTest` 扩展到 9 个测试。

该提交**没有修改**：

- `AgentOrchestrator`；
- `DeepSeekDecisionClient`；
- `AgentOrchestratorTest`；
- README 路线图。

因此提交信息中的 “support pre-decision knowledge retrieval” 与实际代码不一致。当前不能将“首次 AI 决策前知识检索”标为已完成。

### 2.2 文档职责

- `README.md`：项目介绍、当前能力摘要、运行和演示入口；
- `docs/PROJECT-OUTLINE.md`：唯一长期总纲和路线图；
- `docs/CURRENT-STATUS.md`：唯一当前状态和交接入口；
- `docs/knowledge/**`：Agent 可读取的领域知识，不承担项目规划职责。

项目不存在 `AGENTS.md`，也没有其他总纲、路线图或交接文档。README 原有阶段和后续计划已经被上述两份文档替代为权威规划入口；README 保留历史能力说明，但不再作为长期路线依据。

## 3. 当前测试结果

2026-08-02 16:57 使用当前 HEAD 重新执行：

```text
mvn test
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

分项结果：

| 测试类 | 测试数 | 结果 | 已验证内容 |
|---|---:|---|---|
| `AiAgentDemoApplicationTests` | 1 | 通过 | Spring 上下文可启动 |
| `SimpleRagRetrieverTest` | 9 | 通过 | 5 类关键词、工具结果查询、大小写、空值和未命中 |
| `AgentLogServiceTest` | 2 | 通过 | 成功/失败主记录判断和步骤保存调用 |
| `AgentOrchestratorTest` | 3 | 通过 | 重复调用、最大工具次数、工具后知识回填 |
| `SqlErrorEvidenceExtractorTest` | 7 | 通过 | 6 类错误提取和未知错误 |
| `ToolRegistryTest` | 3 | 通过 | 成功、工具异常、工具不存在 |
| `ToolDecisionValidatorTest` | 5 | 通过 | 决策归一化和基础字段校验 |

注意：测试通过不代表以下能力已经验证：

- 当前没有 Testcontainers 或真实 MySQL 集成测试；
- `AiAgentDemoApplicationTests` 没有执行真实数据库查询；
- 没有 Controller HTTP 集成测试；
- 没有 DeepSeek HTTP Stub/契约测试；
- 没有完整 `/agent/ask → MySQL → 日志查询` 端到端测试；
- 没有并发、异步、恢复、SSE 或部署测试；
- 没有 Agent 回答质量评测。

在受限环境内直接执行 Maven 可能因无法解析中央仓库父 POM 而失败；获得网络权限后本次测试成功。这属于依赖解析环境问题，不是测试断言失败。

## 4. 当前代码事实

### 4.1 API

`AgentController` 当前提供：

- `POST /agent/ask`：同步执行一次 Agent 请求；
- `GET /agent/tools`：列出工具；
- `GET /agent/runs`：分页和条件查询运行记录；
- `GET /agent/runs/stats`：运行统计；
- `GET /agent/runs/{traceId}`：查询主记录和步骤详情。

`AiChatController` 仍保留：

- `POST /ai/chat`；
- `POST /ai/analyze-error`；
- `POST /ai/analyze-error/raw`。

这些 `/ai/*` 接口属于早期普通模型调用能力，不是最终求职演示主线，后续应评估退役或仅作为学习历史保留。

### 4.2 Agent 主链路

当前 `AgentOrchestrator.execute` 的真实顺序：

1. `SimpleAgentService` 生成 8 位 `traceId`；
2. `DeepSeekDecisionClient.createDecisionMessages(userMessage)` 创建 system/user 消息；
3. `DeepSeekDecisionClient.decide` 执行第一次模型决策；
4. `needTool=false` 时直接形成回答；
5. `needTool=true` 时记录 `AI_DECISION`；
6. 检查总执行时间、最大工具次数和重复工具调用；
7. `ToolRegistry.executeWithResult` 执行工具并记录 `TOOL_EXECUTION`；
8. 工具成功后，`retrieveKnowledge` 使用“原始问题 + 最后工具结果”检索；
9. 记录 `KNOWLEDGE_RETRIEVAL`；
10. `DeepSeekDecisionClient.appendToolResult` 回填工具证据和知识；
11. 模型再次决策，必要时继续工具循环；
12. 最终形成 `AI_SUMMARY` 或保护/失败回答；
13. `AgentLogService` 保存主记录和步骤；
14. 同步 HTTP 返回。

这里的“多轮”是一次 HTTP 请求内部的多次模型决策，不是跨请求会话记忆。

### 4.3 模型客户端

`DeepSeekDecisionClient` 当前负责：

- 根据 `ToolRegistry.buildToolsPrompt()` 构建工具说明；
- 调用 OpenAI 兼容的 `/chat/completions`；
- 使用提示词要求模型返回 `ToolDecision` JSON；
- 手工解析响应 `Map`；
- 使用 `ToolDecisionValidator` 做基础归一化；
- 通过 `appendToolResult` 注入工具结果和 RAG 内容；
- 使用 `deepseek.timeout-ms` 控制单次模型调用超时。

当前没有供应商无关 `ModelClient`，没有原生工具调用协议，未记录 Token usage，也没有重试和熔断。

### 4.4 工具系统

`AgentTool` 当前契约：

- `name()`；
- `description()`；
- `parameterSchema()`：返回给模型看的字符串；
- `execute(Map<String, Object>)`：返回字符串。

`ToolRegistry` 当前具备：

- Spring 自动注册所有 `AgentTool`；
- 重名检测；
- 工具列表和提示词生成；
- 工具查找、计时和异常包装。

当前注册两个只读工具：

- `GetTableSchemaTool` / `getTableSchema`；
- `AnalyzeSqlErrorWithSchemaTool` / `analyzeSqlErrorWithSchema`。

工具参数仍主要依赖各工具自行取值和校验，`parameterSchema()` 不是运行时强 Schema。`ToolDecisionValidator` 也不验证工具是否注册或参数类型。

### 4.5 运行保护

当前已完成：

- `MAX_TOOL_CALLS = 3`；
- 相同工具名和标准化顶层参数重复调用拦截；
- `agent.max-execution-time-ms` 总时间检查；
- `deepseek.timeout-ms` 单次模型请求超时；
- 模型决策解析失败形成失败步骤；
- 工具失败后不再让模型掩盖错误。

限制：

- 最大工具次数仍是代码常量；
- Agent 总超时是在步骤之间检查，不能中断正在执行的 JDBC 调用；
- 没有重试分类、任务级取消、幂等和重启恢复；
- 没有异步任务和独立线程池。

### 4.6 RAG

`SimpleRagRetriever` 当前以固定规则匹配：

- Unknown column；
- Table doesn't exist；
- Duplicate entry；
- Data too long；
- Foreign key constraint。

已完成：

- 忽略大小写；
- 用户问题与工具结果都可作为查询；
- 命中后返回来源和完整 Markdown 内容；
- 未命中返回 `KnowledgeSearchResult.notFound()`；
- 9 个检索器测试通过；
- 工具成功后回填知识的编排测试通过。

当前进行中/未完成：

- 首次 AI 决策前检索；
- 工具后“按需”而非每次固定检索；
- 同一知识去重；
- 检索阶段和知识版本；
- classpath/JAR 可靠加载；
- 检索质量评测；
- Wiki 导入、分块、向量或混合检索。

### 4.7 Trace、日志和查询

当前 `AgentTraceStep` 可能出现：

- `AI_DECISION`；
- `TOOL_EXECUTION`；
- `KNOWLEDGE_RETRIEVAL`；
- `AI_SUMMARY`；
- `AGENT_GUARD`。

`AgentLogService` 当前负责：

- 保存 `agent_run_log` 主记录；
- 保存 `agent_step_log` 步骤；
- 依据失败步骤计算运行成功状态；
- 分页、时间/工具/成功状态筛选；
- 详情和结构化 `inputView/outputView`；
- 成功率、平均耗时和按工具统计。

当前问题：

- 仓库没有日志表 DDL 或数据库迁移；
- 主记录与步骤保存没有事务；
- 保存的是原始用户问题、工具结果和回答，缺少脱敏和长度治理；
- 只在主记录保存最后一次工具结果；
- 没有 taskId、幂等键、模型/Prompt/知识版本和 Token usage。

## 5. 状态清单

### 5.1 已完成

- Java/Spring Boot Agent 主后端基础；
- 模型工具决策 JSON 解析；
- `AgentTool` 和 `ToolRegistry`；
- 两个 MySQL 只读工具；
- SQL 错误证据提取；
- 一次请求内多次模型决策和工具执行；
- 最大工具次数、总时间和重复调用保护；
- 工具后规则 RAG 和 5 类 Markdown 知识；
- traceId、步骤、耗时、运行日志和查询统计；
- 当前 30 个测试通过。

### 5.2 当前进行中

- M0：首次 AI 决策前知识检索；
- 工具执行后的按需补充检索；
- RAG 去重、阶段标识、加载可靠性和阶段收尾。

### 5.3 后续计划

- M1：Flyway、Compose MySQL、Testcontainers 和可复现运行；
- M2：工具契约、失败语义、事务和 Python 自动评测；
- M3：异步任务、状态机、线程池、超时、重试、幂等和恢复；
- M4：Vue 控制台和 SSE；
- M5：Wiki 筛选、脱敏、导入、分块和版本；
- M7：全栈 Compose、监控、CI 和服务器；
- M8：求职材料收束。

### 5.4 有条件再做

- M6：向量/混合检索；
- Ollama/vLLM 兼容演示；
- WebSocket；
- 消息队列；
- 缓存。

### 5.5 暂不开发

- Python 重写 Agent；
- LoRA/预训练主线；
- 多 Agent；
- 微服务和 Kubernetes；
- 通用聊天平台；
- 自动修改数据库；
- 复杂管理后台；
- 未筛选的完整 Wiki 导入；
- 任何工作项目代码、接口、数据或业务概念。

## 6. 当前未完成问题（按优先级）

### P0：M0 收尾

1. 代码没有首次决策前知识检索，与最近提交信息不一致；
2. 工具成功后每次都会检索，没有“按需补充”策略；
3. 知识读取依赖运行目录；
4. 缺少知识重复注入保护；
5. Trace 不能区分首次和工具后检索；
6. `AgentOrchestratorTest` 只有工具后知识回填测试。

### P0：可复现性

1. 没有数据库 DDL/Flyway；
2. 没有 Compose MySQL；
3. 没有 Testcontainers；
4. Maven Wrapper 在当前 Windows 环境不可用；
5. 没有独立测试配置。

### P1：工具、事务和评测

1. 工具 Schema 只是字符串；
2. 工具失败语义不统一；
3. 日志主记录和步骤无事务；
4. 没有 Python JSONL 评测；
5. 没有 Prompt/模型/知识版本和 Token 指标。

### P1：安全与 API

1. 没有请求长度限制；
2. 没有日志脱敏；
3. 没有认证、限流或工具访问边界；
4. `GlobalExceptionHandler` 的业务码与 HTTP 状态尚未通过集成测试确认；
5. `/ai/*` 与 `/agent/*` 两条主线并存。

## 7. 下一阶段唯一入口任务

下一次开发不要直接开始 M1、异步任务、Vue 或向量库。唯一入口是：

> **完成 M0 的“首次决策前检索 + 工具后按需补充检索”，并用调用顺序测试证明。**

建议执行顺序：

1. 先为 `AgentOrchestratorTest` 增加失败测试，要求第一次 `decisionClient.decide` 前已经完成知识检索和注入；
2. 明确初始知识进入 `DeepSeekDecisionClient` 的接口，不在测试里只验证 `retrieve()` 被调用；
3. 定义工具后补充检索条件和同源去重规则；
4. 增加首次命中、首次未命中、直接回答、工具补充、重复知识和检索异常测试；
5. 修复知识加载不依赖工作目录；
6. 跑完整测试；
7. 更新本文件的状态和测试数量；
8. 满足 PROJECT-OUTLINE 的 M0 验收后再打 `v0.3.0-rag-closeout`。

## 8. 新对话接手说明

新对话或新开发者应按以下顺序读取：

1. `docs/CURRENT-STATUS.md`：确认当前事实和唯一入口；
2. `docs/PROJECT-OUTLINE.md`：确认边界和里程碑验收；
3. `README.md`：了解接口和演示背景；
4. `AgentOrchestrator`、`DeepSeekDecisionClient`；
5. `ToolRegistry`、`AgentTool` 和两个工具；
6. `SimpleRagRetriever` 与知识文档；
7. `AgentLogService`、Mapper、Controller；
8. `AgentOrchestratorTest`、`SimpleRagRetrieverTest` 和完整测试报告；
9. `git status`、`git diff`、最近提交。

接手时必须遵守：

- 代码和已通过测试优先于提交信息、README 和旧对话描述；
- 不把首次决策前 RAG、异步任务、Python、Vue、Wiki、向量库或部署写成已完成；
- 不同时推进多个里程碑；
- 不未经评测引入向量库；
- 不默认引入消息队列、微服务或多 Agent；
- 不复制或影射工作项目；
- 每完成一个阶段，更新本文件、测试结果和 Git 里程碑。

## 9. 下一次交接时必须更新的字段

- 状态核对时间；
- 当前分支、HEAD 和工作区状态；
- 最近里程碑及其真实文件变更；
- 最新测试命令、测试数和失败数；
- 当前主链路；
- 已完成/进行中/后续状态；
- 当前 P0 问题；
- 下一阶段唯一入口任务。
