# ai-agent-demo 当前状态与交接

> 文档职责：记录当前代码事实、测试结果、未完成问题和下一阶段入口。
> 长期定位、架构边界及 M0～M8 路线见 [PROJECT-OUTLINE.md](PROJECT-OUTLINE.md)。
> 状态核对时间：2026-08-03（Asia/Shanghai）。

## 1. 当前状态

项目目前是一个具备以下能力的 Java 后端故障排查 Agent 原型：

- 多轮模型决策；
- 本地工具调用；
- 首次决策前规则 RAG；
- 工具执行后补充 RAG；
- 知识重复注入保护；
- 运行次数和执行时间保护；
- Trace 步骤记录；
- 运行日志查询与统计。

当前 Agent 仍通过同步 HTTP 请求执行。

M0 的核心代码改造已经完成并通过测试，但本轮改动尚未提交。下一步应完成文档更新、最终回归、Git 提交和里程碑标签，然后进入 M1 可复现数据库环境建设。

## 2. 项目边界

### 2.1 项目定位

本项目是个人学习和求职展示项目，职业方向为：

> Java 后端 + AI Agent 应用工程化

重点不是单纯调用大模型接口，而是完整展示：

- Agent 编排；
- 工具调用；
- RAG；
- 运行保护；
- 可观测性；
- 数据库工程化；
- 异步任务；
- 前后端联调；
- 自动评测；
- 部署与监控。

### 2.2 隔离要求

本项目不得复制、影射或引入工作项目中的：

- 业务代码；
- 接口定义；
- 数据结构；
- 业务数据；
- 内部文档；
- 敏感业务概念。

工作中的异步、状态机、重试、线程隔离等经验，只能抽象为通用工程能力后用于本项目。

## 3. Git 状态

### 3.1 当前分支与基线

当前分支：

```text
master
```

当前远程跟踪关系：

```text
master...origin/master
```

当前 HEAD：

```text
18d569e 2026-08-02 feat: support pre-decision knowledge retrieval and refresh project roadma
```

该提交信息中的“support pre-decision knowledge retrieval”与该提交实际代码不完全一致。

该提交实际完成的是：

- 新增 5 篇 SQL 错误知识文档；
- 扩展 `KnowledgeSearchResult`；
- 将 `SimpleRagRetriever` 扩展到 5 类 SQL 错误；
- 扩展 `SimpleRagRetrieverTest`。

首次决策前知识检索、工具后按需检索、知识去重和阶段 Trace，是当前工作区中尚未提交的改动。

### 3.2 当前工作区

当前工作区不是干净状态，存在 12 个代码、测试和资源文件变更：

```text
M       src/main/java/com/example/agent/client/DeepSeekDecisionClient.java
A       src/main/java/com/example/agent/dto/KnowledgeRetrievalPhase.java
M       src/main/java/com/example/agent/rag/SimpleRagRetriever.java
M       src/main/java/com/example/agent/service/AgentOrchestrator.java
R100    docs/knowledge/sql/data-too-long.md
        src/main/resources/docs/knowledge/sql/data-too-long.md
R100    docs/knowledge/sql/duplicate-entry.md
        src/main/resources/docs/knowledge/sql/duplicate-entry.md
R100    docs/knowledge/sql/foreign-key-constraint.md
        src/main/resources/docs/knowledge/sql/foreign-key-constraint.md
R100    docs/knowledge/sql/table-not-exist.md
        src/main/resources/docs/knowledge/sql/table-not-exist.md
R100    docs/knowledge/sql/unknown-column.md
        src/main/resources/docs/knowledge/sql/unknown-column.md
M       src/test/java/com/example/agent/client/DeepSeekDecisionClientTest.java
M       src/test/java/com/example/agent/rag/SimpleRagRetrieverTest.java
M       src/test/java/com/example/agent/service/AgentOrchestratorTest.java
```

变更统计：

```text
12 files changed, 297 insertions(+), 109 deletions(-)
```

`git diff HEAD --check` 无输出，当前未发现空白符或行尾格式问题。

更新本文件后，`docs/CURRENT-STATUS.md` 也应出现在本次 Git 变更中。

## 4. 当前测试结果

本轮执行：

```text
mvn test
```

结果：

```text
Tests run: 36, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

当前测试已经覆盖：

- Spring 上下文启动；
- 5 类 SQL 错误知识匹配；
- 用户问题和工具结果联合检索；
- 关键词大小写处理；
- 空查询和未命中处理；
- 首次决策前知识检索；
- 首次检索命中后的上下文注入；
- 首次检索未命中处理；
- 工具执行后的补充检索；
- 多次工具循环中的知识检索；
- 相同知识重复注入拦截；
- `PRE_DECISION` 和 `POST_TOOL` 阶段区分；
- 工具调用次数保护；
- 重复工具调用保护；
- SQL 错误证据提取；
- 工具注册、执行和异常包装；
- 决策字段归一化；
- Agent 日志主记录和步骤保存。

当前测试通过不代表以下能力已经完成：

- 真实 MySQL 集成测试；
- Testcontainers 测试；
- Controller HTTP 集成测试；
- DeepSeek HTTP Stub 或契约测试；
- `/agent/ask → MySQL → 日志查询` 端到端测试；
- 并发和异步任务测试；
- 重试、恢复和幂等测试；
- SSE 或 WebSocket 测试；
- Agent 回答质量自动评测；
- 正式部署环境验证。

## 5. 打包与资源验证

已执行 Maven 打包并生成 Jar。

知识文档已经进入 Jar：

```text
BOOT-INF/classes/docs/knowledge/sql/unknown-column.md
BOOT-INF/classes/docs/knowledge/sql/table-not-exist.md
BOOT-INF/classes/docs/knowledge/sql/duplicate-entry.md
BOOT-INF/classes/docs/knowledge/sql/data-too-long.md
BOOT-INF/classes/docs/knowledge/sql/foreign-key-constraint.md
```

`SimpleRagRetriever` 已从以下方式：

```java
Files.readString(Path.of(rule.source()), StandardCharsets.UTF_8)
```

改为通过 `ClassPathResource` 和 `InputStream` 读取资源。

因此知识文档不再依赖：

- IDEA 的启动目录；
- Maven 执行目录；
- 操作系统当前工作目录；
- Jar 外部的 `docs/knowledge` 文件夹。

现在 IDEA、Maven 测试和打包后的 Jar 使用同一套 classpath 资源。

## 6. 当前 API

### 6.1 Agent API

`AgentController` 当前提供：

- `POST /agent/ask`：同步执行一次 Agent 请求；
- `GET /agent/tools`：查询已注册工具；
- `GET /agent/runs`：分页和条件查询运行记录；
- `GET /agent/runs/stats`：查询运行统计；
- `GET /agent/runs/{traceId}`：查询运行主记录和步骤详情。

### 6.2 早期模型接口

`AiChatController` 仍保留：

- `POST /ai/chat`；
- `POST /ai/analyze-error`；
- `POST /ai/analyze-error/raw`。

这些接口属于项目早期的普通模型调用能力，不是最终求职演示主线。

后续需要评估：

- 彻底删除；
- 移入示例模块；
- 只作为学习历史保留；
- 在 README 中明确标记为非 Agent 主链路。

## 7. 当前 Agent 主链路

`AgentOrchestrator.execute` 当前执行顺序如下。

### 7.1 初始化

1. `SimpleAgentService` 生成 8 位 `traceId`；
2. 创建 `AgentRunContext`；
3. `DeepSeekDecisionClient.createDecisionMessages(userMessage)` 创建模型消息。

### 7.2 首次决策前检索

4. 使用用户原始问题调用 `SimpleRagRetriever`；
5. 记录阶段为 `PRE_DECISION` 的 `KNOWLEDGE_RETRIEVAL`；
6. 首次检索命中时，将知识加入模型上下文；
7. 将已注入知识登记到本次运行的知识去重集合；
8. 首次检索未命中时，不向模型注入空知识。

### 7.3 第一次模型决策

9. 调用 `DeepSeekDecisionClient.decide`；
10. 记录 `AI_DECISION`；
11. 如果 `needTool=false`，直接生成最终回答；
12. 如果 `needTool=true`，进入工具执行循环。

### 7.4 工具执行与补充检索

13. 检查总执行时间；
14. 检查最大工具调用次数；
15. 检查相同工具和相同参数是否重复调用；
16. 通过 `ToolRegistry.executeWithResult` 执行工具；
17. 记录 `TOOL_EXECUTION`；
18. 工具成功后，使用“用户问题 + 工具结果”执行补充检索；
19. 记录阶段为 `POST_TOOL` 的 `KNOWLEDGE_RETRIEVAL`；
20. 如果命中的知识尚未注入，则将工具证据和知识一起追加到模型上下文；
21. 如果命中的知识已经注入，则记录知识去重步骤，不再次追加相同知识；
22. 模型再次执行决策；
23. 必要时继续下一轮工具调用。

### 7.5 结束与持久化

24. 模型不再请求工具时生成 `AI_SUMMARY`；
25. 触发保护条件时生成 `AGENT_GUARD`；
26. `AgentLogService` 保存运行主记录和步骤；
27. HTTP 同步返回执行结果。

这里的“多轮”指一次 HTTP 请求内部的多次模型决策，不代表跨 HTTP 请求的会话记忆。

## 8. RAG 当前能力

### 8.1 已支持的知识类型

`SimpleRagRetriever` 当前支持以下 5 类 SQL 错误：

| 错误类型 | 典型关键词 | 知识文件 |
|---|---|---|
| 字段不存在 | `unknown column`、`未知列`、`字段不存在` | `unknown-column.md` |
| 表不存在 | `table doesn't exist`、`table does not exist`、`表不存在` | `table-not-exist.md` |
| 唯一键冲突 | `duplicate entry`、`重复键`、`唯一键冲突` | `duplicate-entry.md` |
| 字段长度不足 | `data too long`、`数据过长`、`字段长度` | `data-too-long.md` |
| 外键约束 | `foreign key constraint`、`cannot delete or update parent row`、`外键约束` | `foreign-key-constraint.md` |

### 8.2 已完成能力

- 忽略关键词大小写；
- 支持仅根据用户问题检索；
- 支持根据“用户问题 + 工具结果”联合检索；
- 首次 AI 决策前检索；
- 工具执行成功后补充检索；
- 首次未命中后允许工具结果触发新知识；
- 使用 `PRE_DECISION` 和 `POST_TOOL` 区分检索阶段；
- 相同来源、相同内容只注入一次；
- 重复命中时保留 Trace 记录；
- 知识文件通过 classpath 加载；
- 知识资源可打包进 Jar；
- 对应单元测试已经通过。

### 8.3 当前限制

- 检索规则是硬编码关键词；
- 每次最多返回一篇知识；
- 没有知识分块；
- 没有相关度分数；
- 没有 Top-K；
- 没有知识版本；
- 没有检索质量数据集；
- 没有 Wiki 导入；
- 没有向量检索；
- 没有关键词与向量结合的混合检索。

当前阶段不应立即引入向量库。应先通过 Python 评测证明规则检索的不足，再决定是否进入 M6。

## 9. 模型客户端

`DeepSeekDecisionClient` 当前负责：

- 根据 `ToolRegistry.buildToolsPrompt()` 构建工具说明；
- 调用 OpenAI 兼容的 `/chat/completions`；
- 要求模型返回 `ToolDecision` JSON；
- 解析模型响应；
- 使用 `ToolDecisionValidator` 归一化决策；
- 创建首次决策消息；
- 注入首次检索知识；
- 注入工具执行结果和补充知识；
- 使用 `deepseek.timeout-ms` 控制单次模型调用超时。

当前限制：

- 没有供应商无关的 `ModelClient` 接口；
- 没有使用模型原生 Tool Calling 协议；
- 没有 Token usage 记录；
- 没有模型调用重试；
- 没有熔断；
- 没有 Prompt 版本；
- 没有模型版本追踪；
- 响应仍通过 `Map` 手工解析。

## 10. 工具系统

### 10.1 工具契约

`AgentTool` 当前提供：

- `name()`；
- `description()`；
- `parameterSchema()`；
- `execute(Map<String, Object>)`。

### 10.2 工具注册中心

`ToolRegistry` 当前具备：

- Spring 自动注册 `AgentTool`；
- 工具重名检测；
- 工具列表生成；
- 工具提示词生成；
- 工具查找；
- 工具执行计时；
- 工具异常包装。

### 10.3 当前工具

当前注册两个只读工具：

- `GetTableSchemaTool` / `getTableSchema`；
- `AnalyzeSqlErrorWithSchemaTool` / `analyzeSqlErrorWithSchema`。

### 10.4 当前限制

- `parameterSchema()` 只是提供给模型阅读的字符串；
- 没有运行时强类型 Schema；
- 工具参数主要由工具自行取值和校验；
- `ToolDecisionValidator` 不校验工具是否真实注册；
- 没有统一的工具错误码；
- 没有工具权限分级；
- 没有工具调用审计策略；
- 当前不允许 Agent 自动修改数据库。

## 11. 运行保护

当前已经实现：

- 最大工具调用次数为 3；
- 相同工具名和相同标准化参数的重复调用拦截；
- `agent.max-execution-time-ms` 总执行时间检查；
- `deepseek.timeout-ms` 单次模型调用超时；
- 模型决策解析失败记录；
- 工具失败后停止继续调用；
- 重复知识注入拦截。

当前限制：

- 最大工具调用次数仍是代码常量；
- 总超时只能在步骤之间检查；
- 无法主动中断正在执行的 JDBC 调用；
- 没有任务级取消；
- 没有错误分类重试；
- 没有幂等键；
- 没有进程重启恢复；
- 没有异步任务和独立线程池。

## 12. Trace、日志与查询

### 12.1 Trace 步骤

当前可能出现以下步骤：

- `KNOWLEDGE_RETRIEVAL`；
- `KNOWLEDGE_DEDUPLICATION`；
- `AI_DECISION`；
- `TOOL_EXECUTION`；
- `AI_SUMMARY`；
- `AGENT_GUARD`。

知识检索阶段通过 `KnowledgeRetrievalPhase` 区分：

- `PRE_DECISION`：第一次模型决策之前；
- `POST_TOOL`：工具执行成功之后。

### 12.2 日志能力

`AgentLogService` 当前负责：

- 保存 `agent_run_log` 主记录；
- 保存 `agent_step_log` 步骤；
- 根据失败步骤计算运行成功状态；
- 分页查询运行记录；
- 按时间、工具和成功状态筛选；
- 查询运行详情；
- 构建结构化 `inputView` 和 `outputView`；
- 统计成功率和平均耗时；
- 按工具统计调用情况。

### 12.3 当前问题

- 仓库中没有日志表 DDL；
- 没有 Flyway 数据库迁移；
- 主记录和步骤保存没有事务；
- 保存了原始用户问题、工具结果和模型回答；
- 缺少日志长度治理；
- 缺少敏感信息脱敏；
- 主记录只保存最后一次工具结果；
- 没有 `taskId`；
- 没有幂等键；
- 没有模型版本；
- 没有 Prompt 版本；
- 没有知识版本；
- 没有 Token usage。

## 13. 里程碑状态

### 13.1 M0：RAG 与主链路收尾

状态：**核心代码和测试已完成，等待提交和标签。**

已完成：

- 5 类 SQL 错误知识；
- 首次决策前检索；
- 工具执行后补充检索；
- 首次未命中、工具后命中；
- 知识重复注入保护；
- 检索阶段标识；
- 检索 Trace；
- classpath 资源加载；
- Jar 内知识资源验证；
- 36 个测试通过。

剩余收尾事项：

1. 更新本状态文档；
2. 检查最终 Git 变更；
3. 执行最终 `mvn clean package`；
4. 提交 M0；
5. 创建 `v0.3.0-rag-closeout` 标签；
6. 推送提交和标签。

### 13.2 M1：数据库可复现环境

状态：**尚未开始。**

计划内容：

- Flyway；
- 数据库 DDL；
- 初始化数据；
- Docker Compose MySQL；
- 独立测试配置；
- Testcontainers；
- 数据库工具集成测试；
- Agent 日志持久化集成测试；
- 本地一键启动说明。

### 13.3 后续阶段

- M2：工具契约、失败语义、事务和 Python 自动评测；
- M3：异步任务、状态机、线程池、超时、重试、幂等和恢复；
- M4：Vue 控制台和 SSE；
- M5：Wiki 筛选、导入、分块和版本治理；
- M6：根据评测结果决定是否引入向量或混合检索；
- M7：全栈 Compose、监控、CI 和服务器部署；
- M8：README、演示脚本、架构说明和求职材料收束。

## 14. 暂不开发内容

当前暂不开发：

- Python 重写 Agent；
- LoRA 或预训练主线；
- 多 Agent；
- 微服务；
- Kubernetes；
- 通用聊天平台；
- 自动修改数据库；
- 复杂管理后台；
- 未筛选的完整 Wiki 导入；
- 未经评测直接引入向量数据库；
- 任何工作项目代码、接口、数据或业务概念。

## 15. 当前问题优先级

### P0：M0 Git 收尾

1. 更新 `docs/CURRENT-STATUS.md`；
2. 最终检查全部代码、资源、测试和文档变更；
3. 执行 `mvn clean package`；
4. 确认 36 个测试仍然通过；
5. 确认 Jar 中仍包含 5 篇知识文档；
6. 提交 M0；
7. 创建并推送里程碑标签。

### P0：M1 可复现性

1. 没有 Flyway；
2. 没有数据库 DDL；
3. 没有 Compose MySQL；
4. 没有 Testcontainers；
5. 没有独立测试配置；
6. Maven Wrapper 在当前 Windows 环境不可用；
7. 新开发者不能仅依赖仓库从零复现数据库环境。

### P1：工具、事务与评测

1. 工具 Schema 只是字符串；
2. 工具失败语义不统一；
3. 日志主记录和步骤保存没有事务；
4. 没有 Python JSONL 自动评测；
5. 没有 Prompt、模型和知识版本；
6. 没有 Token 指标。

### P1：安全与 API

1. 没有请求长度限制；
2. 没有日志脱敏；
3. 没有认证；
4. 没有限流；
5. 没有工具访问边界；
6. `GlobalExceptionHandler` 尚未通过 HTTP 集成测试验证；
7. `/ai/*` 和 `/agent/*` 两条接口主线仍然并存。

## 16. 下一阶段唯一入口

当前不要直接开始异步任务、Vue、Wiki 导入或向量库。

完成 M0 的提交和标签后，下一阶段唯一入口是：

> 建立可复现的 MySQL 开发与测试环境，使新开发者能够通过仓库代码完成数据库创建、应用启动和基础集成测试。

M1 建议顺序：

1. 盘点当前实体、Mapper 和数据库表；
2. 确定 Flyway 版本和目录；
3. 补充基础表 DDL；
4. 增加开发环境初始化数据；
5. 增加 Docker Compose MySQL；
6. 增加独立测试配置；
7. 引入 Testcontainers；
8. 为两个数据库工具增加真实 MySQL 集成测试；
9. 为日志主记录和步骤保存增加集成测试；
10. 更新 README 和本状态文档。

## 17. 新对话接手顺序

新对话或新开发者应按以下顺序读取：

1. `docs/CURRENT-STATUS.md`；
2. `docs/PROJECT-OUTLINE.md`；
3. `README.md`；
4. `AgentOrchestrator`；
5. `DeepSeekDecisionClient`；
6. `SimpleRagRetriever`；
7. `KnowledgeRetrievalPhase`；
8. `ToolRegistry` 和 `AgentTool`；
9. 两个数据库工具；
10. `AgentLogService`、Mapper 和 Controller；
11. `AgentOrchestratorTest`；
12. `DeepSeekDecisionClientTest`；
13. `SimpleRagRetrieverTest`；
14. `git status`、`git diff HEAD` 和最近提交。

接手时必须遵守：

- 以当前代码和已通过测试为准；
- 不把提交信息或旧文档当作高于代码的事实；
- 不同时推进多个里程碑；
- 不未经评测直接引入向量库；
- 不默认引入消息队列、微服务或多 Agent；
- 不复制或影射工作项目；
- 每完成一个阶段，都要同步更新测试结果、状态文档和 Git 里程碑。

## 18. 下次交接必须更新

下次更新本文件时，必须核对：

- 状态核对时间；
- 当前分支；
- 当前 HEAD；
- 工作区是否干净；
- 最近里程碑提交；
- 最新测试命令和测试数量；
- Jar 或部署验证结果；
- 当前 Agent 主链路；
- 已完成、进行中和未开始的能力；
- 当前 P0 问题；
- 下一阶段唯一入口。