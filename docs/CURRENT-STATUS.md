# ai-agent-demo 当前状态与交接

> 文档职责：本文件是当前代码事实、验证结果、已知问题和下一阶段入口的唯一权威来源。  
> 长期定位、架构边界及 M0～M8 路线见 [PROJECT-OUTLINE.md](PROJECT-OUTLINE.md)。  
> 状态核对日期：2026-08-09（Asia/Shanghai）。

## 1. 一句话状态

`ai-agent-demo` 当前是一个同步执行的 Java 后端故障诊断 Agent：已经完成受控工具调用、首次决策前与工具后的规则 RAG、运行保护、Trace、MySQL 持久化、Flyway 数据库迁移、短事务保存、Docker Compose 本地数据库和 Testcontainers 集成测试。

当前结论：

- M0 已完成并封版；
- M1 已完成并封版；
- M2 尚未开始；
- 当前仍是同步 HTTP，不包含异步任务、SSE、Vue、Python 自动评测、Wiki 导入、向量检索或正式部署。

## 2. 项目定位与边界

本项目是个人学习和求职展示项目，职业方向为：

> Java 后端 + AI Agent 应用工程化

项目重点是用可运行代码和测试展示：

- Agent 编排；
- 受控工具调用；
- 规则 RAG；
- 运行保护；
- Trace 与持久化；
- 数据库工程化；
- 后续的异步任务、评测、前端和部署能力。

本项目与工作项目完全分离，不得复制、影射或引入工作项目中的：

- 业务代码；
- 接口定义；
- 数据结构；
- 业务数据；
- 内部文档；
- 敏感业务概念。

## 3. 可信 Git 基线

### 3.1 当前封版

```text
分支：master
提交：f57d01c
标签：v0.4.0-reproducible
里程碑：M1 可复现数据库与持久化环境
工作区：封版时干净
远程状态：尚未执行 git push
```

提交信息为：

```text
flyway配置添加和验证
```

该提交信息比实际变更范围窄，但不影响提交内容和版本正确性，不修改历史提交。

### 3.2 上一可信基线

```text
提交：98eb72a
标签：v0.3.0-rag-closeout
里程碑：M0 RAG 调用时机修正与收尾
```

后续开发必须以 `f57d01c / v0.4.0-reproducible` 为唯一可信起点，不再回到 M0 或重新实现 M1。

## 4. 里程碑状态

| 阶段   | 当前状态           | 已交付结果                                                   |
| ------ | ------------------ | ------------------------------------------------------------ |
| M0     | 已完成             | 首次决策前 RAG、工具后补充检索、知识去重、阶段 Trace、classpath 资源加载 |
| M1     | 已完成             | Compose MySQL、Flyway、Run/Step 持久化事务、Testcontainers、空环境复现 |
| M2     | 下一阶段，尚未开始 | 最新交接口径为“工具扩展与数据库对比”；实施范围和验收标准需先结合封版源码确认 |
| M3～M8 | 后续计划           | 不在当前阶段提前开发                                         |

注意：`PROJECT-OUTLINE.md` 是 2026-08-02 制定的长期路线，其中 M2 的旧描述包含“工具契约、失败语义、事务和 Python 自动评测”。M1 实施期间，日志事务已经提前完成；最新交接将下一阶段称为“工具扩展与数据库对比”。开始 M2 前，应先同步路线文档中的阶段边界，不能把已经完成的事务重复列为主要开发任务，也不能未经确认提前进入 Python 评测。

## 5. 当前技术栈

| 类型            | 当前实现                                 |
| --------------- | ---------------------------------------- |
| Java            | Java 17                                  |
| 后端            | Spring Boot 3.x                          |
| 数据访问        | MyBatis、MySQL                           |
| 数据库迁移      | Flyway                                   |
| 本地数据库      | Docker Compose、MySQL 8.4.11             |
| 集成测试        | JUnit 5、Mockito、Testcontainers         |
| 模型调用        | DeepSeek API、WebClient、OpenAI 兼容协议 |
| 构建            | Maven Wrapper                            |
| Web 端口        | 8088                                     |
| 本地 MySQL 端口 | `127.0.0.1:3307`                         |
| 数据库名        | `ai_agent`                               |

## 6. 当前 API

`AgentController` 当前提供：

- `POST /agent/ask`：同步执行一次 Agent 请求；
- `GET /agent/tools`：查询已注册工具；
- `GET /agent/runs`：分页和条件查询运行记录；
- `GET /agent/runs/stats`：查询运行统计；
- `GET /agent/runs/{traceId}`：查询一次运行的主记录和完整步骤。

早期 `/ai/*` 接口仍保留：

- `POST /ai/chat`；
- `POST /ai/analyze-error`；
- `POST /ai/analyze-error/raw`。

这些接口属于早期普通模型调用能力，不是当前 Agent 求职演示主线。

## 7. 当前 Agent 主链路

一次 `/agent/ask` 的主要执行顺序为：

1. `SimpleAgentService` 生成 8 位 `traceId`；
2. 创建本次请求的 `AgentRunContext`；
3. 根据用户问题执行首次决策前规则检索；
4. 记录 `PRE_DECISION` 阶段的 `KNOWLEDGE_RETRIEVAL`；
5. 命中知识时，将知识加入第一次模型决策上下文；
6. `DeepSeekDecisionClient` 执行模型决策；
7. `needTool=false` 时形成直接回答；
8. `needTool=true` 时检查总执行时间、最大工具次数和重复调用；
9. `ToolRegistry` 执行只读工具并记录 `TOOL_EXECUTION`；
10. 工具成功后，根据新增证据按需进行 `POST_TOOL` 检索；
11. 对已经注入的知识进行去重；
12. 将工具证据和补充知识回填模型；
13. 模型继续决策或生成最终总结；
14. 形成 `AI_SUMMARY`、失败步骤或 `AGENT_GUARD`；
15. Agent 执行结束后，进入一个短数据库事务；
16. 保存一条 `agent_run_log` 和对应的多条 `agent_step_log`；
17. 事务成功提交后同步返回 HTTP 响应。

这里的“多轮”是一次 HTTP 请求内部的多次模型决策，不是跨请求会话记忆。

## 8. M0 已完成内容

M0 已在 `98eb72a / v0.3.0-rag-closeout` 完成：

- 首次 AI 决策前知识检索；
- 首次检索结果注入模型上下文；
- 工具执行后的按需补充检索；
- `PRE_DECISION` 与 `POST_TOOL` 阶段区分；
- 同一次运行中的知识重复注入保护；
- 5 类 SQL 错误规则知识；
- 知识文档迁移到 `src/main/resources`；
- 使用 classpath 读取知识，不依赖工作目录；
- Jar 中包含知识文档；
- 调用顺序和去重测试。

当前规则知识覆盖：

- Unknown column；
- Table doesn't exist；
- Duplicate entry；
- Data too long；
- Foreign key constraint。

## 9. M1 已完成内容

### 9.1 Docker Compose MySQL

已经加入 `compose.yaml`，本地只运行 MySQL，Java 继续由 IDEA 或 Maven Wrapper 启动。

当前数据库环境：

- 镜像：`mysql:8.4.11`；
- 宿主机绑定：`127.0.0.1:${AI_AGENT_DB_PORT:-3307}:3306`；
- 默认数据库：`ai_agent`；
- 默认业务用户：`ai_agent`；
- 时区：`Asia/Shanghai`；
- 健康检查：`mysqladmin ping`；
- 数据卷：`ai_agent_mysql_data`。

`.env` 保存本机真实配置并被 Git 忽略，`.env.example` 只保留可提交的变量模板。

### 9.2 Maven Wrapper

Maven Wrapper 已完整纳入版本库：

```text
mvnw
mvnw.cmd
.mvn/wrapper/maven-wrapper.jar
.mvn/wrapper/maven-wrapper.properties
```

Windows 上统一使用：

```powershell
.\mvnw.cmd clean package
```

### 9.3 Flyway

已经加入 Flyway，并按 profile 拆分数据源配置：

```text
src/main/resources/application.yml
src/main/resources/application-local.yml
src/main/resources/application-test.yml
src/main/resources/db/migration/V1__create_agent_log_tables.sql
```

V1 创建：

- `agent_run_log`；
- `agent_step_log`；
- 主键、唯一键和查询索引；
- Step 到 Run 的外键；
- 基础检查约束。

Flyway 自动维护：

```text
flyway_schema_history
```

已验证：

- 空数据库能够自动创建历史表；
- V1 能从空库成功执行；
- 数据库版本升级到 v1；
- 再次启动时不会重复执行 V1；
- 已处于 v1 时显示 Schema 已是最新版本。

V1 已成为封版历史。以后数据库结构变化必须新增 V2、V3，不得直接修改已经执行过的 V1。

### 9.4 Run/Step 持久化

一次 Agent 执行会保存：

```text
1 条 agent_run_log
N 条 agent_step_log
```

当前持久化支持：

- 保存运行主记录；
- 保存完整步骤列表；
- 根据失败步骤计算 Run 成功状态；
- 根据 `traceId` 查询详情；
- 分页和条件查询；
- 成功率、平均耗时和按工具统计；
- JSON 字段的存储与查询展示转换。

核心持久化组件包括：

- `AgentLogService`；
- `AgentRunLogMapper`；
- `AgentStepLogMapper`；
- 两个 Mapper XML；
- Run/Step 实体和查询 DTO。

### 9.5 事务边界

Run 主记录和所有 Step 是一个业务整体，当前由 `AgentLogService` 的保存入口建立短事务：

```text
开始事务
  → 插入 Run
  → 插入 Step 1
  → 插入 Step 2
  → ...
全部成功：COMMIT
任一数据库写入异常：ROLLBACK
```

事务设计结论：

- Run 与 Step 要么全部保存，要么全部回滚；
- Agent 执行结果中的 `success=false` 是需要保存的业务事实，不触发日志回滚；
- Mapper 写入等持久化异常才触发回滚；
- 当前使用 `@Transactional(rollbackFor = Exception.class)` 明确异常回滚规则；
- 大模型调用、RAG 和工具执行不放入数据库事务；
- 事务只覆盖执行完成后的短时间数据库写入，避免长期占用连接和持锁。

M1 曾通过故障注入发现“Run 已保存、Step 失败但 Run 未回滚”的问题，随后将 Run 与 Step 调整到同一事务中，并通过回滚测试确认修复。

## 10. 配置与敏感信息

本机运行需要提供数据源配置和 DeepSeek API Key。真实值不得写入 Git。

可提交：

- `.env.example`；
- `${SPRING_DATASOURCE_*}` 形式的配置占位；
- Docker Compose 默认的非敏感开发参数。

不得提交：

- `.env`；
- 真实数据库密码；
- `DEEPSEEK_API_KEY`；
- 个人服务器地址或私人数据路径。

## 11. 已完成验证

M1 封版前已经完成以下验证：

### 11.1 自动构建

```powershell
.\mvnw.cmd clean package
```

最终结果：

```text
BUILD SUCCESS
```

当前交接记录确认全部测试通过，但没有保留最终一次 Surefire 汇总中的精确总测试数，因此本文件不猜测数字。开始 M2 前，应在 `f57d01c` 上重新执行一次构建，并把实际总数回填到本文档。

### 11.2 关键测试类

- `FlywayMySqlContainerTest`：验证空 MySQL 自动迁移和重复启动；
- `AgentLogMapperMySqlContainerTest`：验证 Mapper 在真实 MySQL 上的写入、查询、分页与统计；
- `AgentLogServiceMySqlContainerTest`：验证 Run/Step 整体保存和异常回滚；
- `DatabaseToolMySqlContainerTest`：验证数据库工具读取真实表结构；
- `SimpleAgentServicePersistenceTest`：验证 Agent 响应进入持久化服务；
- 原有 RAG、编排、工具注册、错误证据提取和决策校验测试继续保留。

### 11.3 空环境复现

已从空 Compose 数据卷完成：

1. 创建网络和数据卷；
2. 启动 MySQL 8.4.11；
3. 健康检查通过；
4. 创建 `ai_agent` 数据库和用户；
5. Spring Boot 连接空库；
6. Flyway 创建历史表并执行 V1；
7. 应用启动；
8. Agent 请求成功；
9. 分页、详情和统计接口查询到持久化结果。

### 11.4 接口闭环

真实联调已验证：

```text
POST /agent/ask
  → 调用 getTableSchema
  → 生成回答
  → 保存 Run 和 Steps
  → GET /agent/runs
  → GET /agent/runs/{traceId}
  → GET /agent/runs/stats
```

## 12. 当前已知限制

以下内容尚未完成，不能写成现有能力：

- Agent 仍是同步 HTTP，请求期间客户端需要等待；
- 没有异步任务表、状态机、独立线程池、取消、恢复或幂等；
- 没有 SSE 或 WebSocket 进度输出；
- 没有 Vue 控制台；
- 没有 Python JSONL 自动评测和回答质量报告；
- 没有 Wiki 知识筛选、脱敏、导入、分块和版本管理；
- 没有向量或混合检索；
- 没有供应商无关 `ModelClient`；
- 没有 Token usage 记录；
- 没有模型调用重试、错误分类、熔断或限流；
- 工具参数 Schema 仍未形成统一的运行时强校验；
- 日志仍缺少完整的脱敏和长度治理；
- 没有 DeepSeek HTTP Stub/契约测试；
- 没有完整 Controller HTTP 集成测试；
- 没有并发、性能和正式部署环境验证。

MySQL 8.4 运行时曾出现 Flyway 兼容性升级建议警告，但 V1 校验和迁移均成功。该警告不是 M1 失败原因，后续升级依赖时应重新验证兼容性。

## 13. M2 唯一入口

下一阶段是：

> M2：工具扩展与数据库对比

M2 尚未开始。第一步不是直接生成新工具代码，而是基于 `f57d01c / v0.4.0-reproducible` 完成源码审计并明确：

1. “数据库对比”的具体对象、输入和输出；
2. 新工具提供的确定性证据是什么；
3. 与现有 `getTableSchema`、`analyzeSqlErrorWithSchema` 的职责边界；
4. 是否需要统一工具参数契约和失败语义；
5. 只读权限边界和禁止执行的数据库操作；
6. 最小实现范围；
7. 自动测试、真实 MySQL 集成测试和手工验收标准；
8. M2 的提交和标签名称。

范围未确认前，不提前进入：

- Python 重写或 Python 自动评测；
- Spring AI 重构；
- Vue；
- SSE；
- MCP；
- 多 Agent；
- 本地模型部署；
- Wiki 导入；
- 向量数据库；
- Kubernetes；
- 与工作项目集成。

## 14. 新对话接手顺序

新对话或新开发者应按以下顺序检查：

1. `docs/CURRENT-STATUS.md`；
2. `docs/PROJECT-OUTLINE.md`；
3. `README.md`；
4. `pom.xml`、`compose.yaml`、`.env.example`；
5. `application.yml`、`application-local.yml`、`application-test.yml`；
6. `db/migration/V1__create_agent_log_tables.sql`；
7. `SimpleAgentService`、`AgentOrchestrator`、`DeepSeekDecisionClient`；
8. `ToolRegistry`、`AgentTool` 和当前两个工具；
9. `SimpleRagRetriever` 与 classpath 知识文档；
10. `AgentLogService`、Mapper、实体和 Controller；
11. 全部单元测试和 MySQL Testcontainers 集成测试；
12. `git status`、`git log -5 --oneline --decorate`、标签和最新构建结果。

接手时必须遵守：

- 源码和已通过测试优先于 README、旧文档和旧对话描述；
- `f57d01c / v0.4.0-reproducible` 是唯一可信起点；
- 不修改已经执行过的 Flyway V1，数据库变化使用 V2、V3；
- 不重做已经封版的 M0、M1；
- 不把 M2 计划写成已完成；
- 一次只推进一个可验证、可提交的小阶段；
- 不复制或影射工作项目；
- 每个里程碑完成后更新本文件、测试结果、提交和标签。

## 15. 下次更新必须核对

- 状态核对时间；
- 当前分支、HEAD、标签、远程和工作区状态；
- 最新测试命令、精确测试数和失败数；
- 新增或修改的 Flyway 版本；
- 当前 Agent 主链路；
- 已完成、进行中和后续计划；
- 当前已知限制；
- 下一阶段唯一入口和验收标准。