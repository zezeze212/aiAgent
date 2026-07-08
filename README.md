# Java 后端排障 Agent

## 一、项目简介

本项目是一个基于 **Spring Boot + 大模型 API** 的 Java 后端排障 Agent 项目。

项目目标是面向 Java 后端开发中的常见排障场景，例如 SQL 报错、MyBatis 字段映射错误、数据库表结构不一致、接口调用异常等问题，通过 AI 自动判断是否需要调用工具，并结合工具执行结果生成排障建议。

截至目前，项目已完成：

- AI 普通问答
- Java 报错分析
- Tool Calling 工具调用
- ToolRegistry 工具注册中心
- 数据库表结构查询
- SQL 报错结合表结构分析
- Agent 全链路追踪
- Agent 调用日志落库
- MyBatis 日志模块改造
- 日志分页、筛选、详情查询
- Agent 运行统计接口

---

## 二、技术栈

- Java 17
- Spring Boot 3.x
- MyBatis
- MySQL
- Lombok
- WebClient
- DeepSeek API
- Postman

---

## 三、核心功能

### 1. AI 普通问答

接口：

```http
POST /ai/chat
```

用于普通 AI 问答。

---

### 2. Java 报错结构化分析

接口：

```http
POST /ai/analyze-error
```

用于分析 Java、Spring Boot、MyBatis、SQL 等后端报错，并返回结构化结果。

---

### 3. Agent 问答与工具调用

接口：

```http
POST /agent/ask
```

Agent 会先让 AI 判断用户问题是否需要调用工具。

如果需要调用工具，AI 会返回工具名称和参数，后端通过 `ToolRegistry` 执行对应工具，并将工具结果交给 AI 总结或直接返回。

整体流程：

```text
用户提问
 -> AI_DECISION：AI 判断是否需要工具
 -> TOOL_EXECUTION：后端执行工具
 -> AI_SUMMARY：AI 总结工具结果
 -> 返回最终答案
```

---

## 四、工具注册机制

项目中定义了统一的 `AgentTool` 接口，每个工具都需要提供：

- 工具名称
- 工具描述
- 参数结构
- 执行逻辑

通过 `ToolRegistry` 统一注册和调用工具。

目前已实现工具：

| 工具名 | 作用 |
|---|---|
| `getCurrentTime` | 获取当前北京时间 |
| `analyzeJavaError` | 分析 Java / Spring Boot / MyBatis / SQL 报错 |
| `getTableSchema` | 查询 MySQL 当前数据库中某张表的字段结构 |
| `analyzeSqlErrorWithSchema` | 结合 SQL 报错和数据库表结构进行排障分析 |

---

## 五、核心工具说明

### 1. getTableSchema

用于查询当前 MySQL 数据库中某张表的字段结构。

查询内容包括：

- 字段名
- 字段类型
- 是否允许为空
- 默认值
- 字段注释
- 字段顺序

底层查询：

```sql
information_schema.COLUMNS
```

示例问题：

```text
帮我查看 agent_run_log 表有哪些字段
```

---

### 2. 表名模糊匹配

当用户输入错误表名时，系统会基于编辑距离计算相似表名。

例如用户输入：

```text
agen_run_log
```

系统可以提示：

```text
未查询到表结构，tableName=agen_run_log。你是不是想查询：agent_run_log
```

这里使用编辑距离，而不是 KMP。

原因是：

- KMP 更适合连续字符串匹配
- 编辑距离更适合处理少字、多字、错字、局部拼写错误等场景

---

### 3. analyzeSqlErrorWithSchema

组合排障工具。

适用于用户同时提供 SQL/MyBatis 报错和相关表名的场景。

工具内部流程：

```text
接收报错日志和表名
 -> 查询真实表结构
 -> 将报错日志 + 表结构交给 AI 分析
 -> 返回排障结论
```

示例问题：

```text
帮我分析这个报错：java.sql.SQLSyntaxErrorException: Unknown column 'theme_code' in 'field list'，相关表是 agent_run_log
```

该工具可以判断：

- `theme_code` 字段是否真实存在于 `agent_run_log` 表中
- SQL 是否引用了不存在的字段
- 问题可能出现在 `mapper.xml`、实体类、`@TableField`、`resultMap` 或手写 SQL 中

由于该工具内部已经生成完整排障结论，因此外层 Agent 会跳过二次总结：

```text
summaryCostMs = 0
answer = toolResult
```

---

## 六、Agent 链路追踪

每次 Agent 调用都会生成 `traceId`，用于串联本次请求的完整执行过程。

记录的耗时字段包括：

| 字段 | 含义 |
|---|---|
| `traceId` | 一次 Agent 请求的唯一追踪 ID |
| `decisionCostMs` | AI 判断是否调用工具的耗时 |
| `toolCostMs` | 工具执行耗时 |
| `summaryCostMs` | AI 总结耗时 |
| `agentCostMs` | 整个 Agent 请求总耗时 |

---

## 七、执行步骤明细

项目中定义了 `AgentTraceStep`，用于记录每一步执行明细。

主要步骤包括：

- `AI_DECISION`
- `TOOL_EXECUTION`
- `AI_SUMMARY`

每一步记录：

- `stepName`
- `description`
- `success`
- `costMs`
- `input`
- `output`
- `errorMessage`

这样可以复盘一次 Agent 请求中：

- AI 为什么选择某个工具
- 工具入参是什么
- 工具返回了什么
- 哪一步耗时最长
- 哪一步出错

---

## 八、日志落库设计

项目中设计了两张日志表：

- `agent_run_log`
- `agent_step_log`

---

### 1. agent_run_log

用于保存一次 Agent 请求主记录。

核心字段：

| 字段 | 说明 |
|---|---|
| `trace_id` | 一次 Agent 请求追踪 ID |
| `user_message` | 用户原始问题 |
| `answer` | Agent 最终回答 |
| `used_tool` | 是否使用工具 |
| `tool_name` | 使用的工具名称 |
| `tool_result` | 工具执行结果 |
| `decision_cost_ms` | AI 决策耗时 |
| `tool_cost_ms` | 工具执行耗时 |
| `summary_cost_ms` | AI 总结耗时 |
| `agent_cost_ms` | Agent 总耗时 |
| `success` | 是否成功 |
| `error_message` | 错误信息 |
| `created_time` | 创建时间 |

---

### 2. agent_step_log

用于保存 Agent 执行步骤明细。

核心字段：

| 字段 | 说明 |
|---|---|
| `trace_id` | 一次 Agent 请求追踪 ID |
| `step_name` | 步骤名称 |
| `description` | 步骤描述 |
| `success` | 是否成功 |
| `cost_ms` | 步骤耗时 |
| `input_text` | 步骤输入 |
| `output_text` | 步骤输出 |
| `error_message` | 错误信息 |
| `step_order` | 步骤顺序 |
| `created_time` | 创建时间 |

---

## 九、Agent 运行记录接口

### 1. 分页查询调用记录

接口：

```http
GET /agent/runs?pageNum=1&pageSize=10
```

支持条件筛选：

```http
GET /agent/runs?pageNum=1&pageSize=5&toolName=analyzeSqlErrorWithSchema
```

```http
GET /agent/runs?pageNum=1&pageSize=5&success=1
```

```http
GET /agent/runs?pageNum=1&pageSize=5&startTime=2026-07-04 00:00:00&endTime=2026-07-05 23:59:59
```

```http
GET /agent/runs?pageNum=1&pageSize=5&toolName=analyzeSqlErrorWithSchema&startTime=2026-07-04 00:00:00&endTime=2026-07-05 23:59:59
```

列表页只返回 `answerSummary`，不返回完整 `answer`，避免列表内容过长。

---

### 2. 查询调用详情

接口：

```http
GET /agent/runs/{traceId}
```

返回内容包括：

- `run`：本次 Agent 主记录
- `steps`：本次 Agent 执行步骤明细

---

### 3. 查询运行统计

接口：

```http
GET /agent/runs/stats
```

支持条件筛选：

```http
GET /agent/runs/stats?toolName=analyzeSqlErrorWithSchema
```

```http
GET /agent/runs/stats?success=1
```

```http
GET /agent/runs/stats?startTime=2026-07-04 00:00:00&endTime=2026-07-05 23:59:59
```

统计内容包括：

- 总调用次数
- 成功次数
- 失败次数
- 成功率
- AI 决策平均耗时
- 工具平均耗时
- AI 总结平均耗时
- Agent 整体平均耗时
- 各工具调用次数
- 各工具平均耗时
- 各工具最大耗时

---

## 十、MyBatis 改造

日志模块已由 `JdbcTemplate` 改造成 MyBatis。

新增结构：

```text
entity
  AgentRunLog
  AgentStepLog

mapper
  AgentRunLogMapper
  AgentStepLogMapper

resources/mapper
  AgentRunLogMapper.xml
  AgentStepLogMapper.xml
```

MyBatis 当前负责：

- 保存 Agent 主记录
- 保存 Agent 步骤明细
- 分页查询运行记录
- 按 `traceId` 查询详情
- 按 `toolName` / `success` / `startTime` / `endTime` 筛选
- 统计 Agent 运行数据

`information_schema` 查询仍保留 `JdbcTemplate`，原因是系统元数据查询更直接、轻量。

---


## 十一、统一返回结构与全局异常处理

项目已统一接口响应结构，所有主要接口返回 `Result<T>`，包含 `code`、`message`、`data` 三个字段。

成功响应示例：

```md
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

失败响应示例：

```json
{
  "code": 400,
  "message": "message 不能为空",
  "data": null
}
```

项目通过 `@RestControllerAdvice` 实现全局异常处理，统一捕获业务异常、参数异常和系统异常，避免接口直接暴露 Java 异常栈。

本次改造接口包括：

- `POST /agent/ask`
- `GET /agent/tools`
- `GET /agent/runs`
- `GET /agent/runs/stats`
- `GET /agent/runs/{traceId}`
- `POST /ai/chat`
- `POST /ai/analyze-error`
- `POST /ai/analyze-error/raw`

同时增加基础参数校验：

- `message` 不能为空
- `log` 不能为空
- `pageNum` 必须大于 0
- `pageSize` 必须大于 0 且不能超过 100
- `success` 只能是 0 或 1
- `startTime` 不能晚于 `endTime

## 十三、后续计划
增加更多后端排障工具，例如 SQL 生成、接口 Body 生成、Mapper XML 分析 增加 RAG 知识库能力
部署到腾讯云服务器，支持远程演示
整理架构图、流程图和接口文档