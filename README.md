# ai-agent-demo

一个基于 Spring Boot + DeepSeek API 的 Java 后端排障 Agent Demo。

项目目标是模拟一个后端智能排障助手：用户输入 SQL / MyBatis / 数据库字段相关报错后，Agent 会先判断是否需要调用后端工具，再结合真实数据库表结构、错误证据和工具诊断结果生成排查建议，并记录完整调用链路。

## 一、项目定位

本项目不是简单的 AI 问答接口，而是一个带有工具调用、运行保护和调用追踪的后端 Agent 示例。

主要解决的问题：

- SQL 报错分析；
- MyBatis 字段映射错误排查；
- 数据库表结构不一致分析；
- 工具调用过程追踪；
- Agent 运行结果落库与查询。

典型输入示例：

```text
帮我分析这个报错：
java.sql.SQLSyntaxErrorException: Unknown column 'theme_code' in 'field list'，
相关表是 agent_run_log
```

Agent 会自动判断需要查询表结构，并调用工具分析 `theme_code` 是否存在于 `agent_run_log` 表中。

## 二、技术栈

- Java 17
- Spring Boot 3.x
- MyBatis
- MySQL
- Lombok
- WebClient
- DeepSeek API
- JUnit 5
- Mockito
- Postman

## 三、核心能力

### 1. Agent 工具决策

用户请求进入 `/agent/ask` 后，系统会先由大模型判断是否需要调用工具。

如果需要工具，模型返回类似结构：

```json
{
  "needTool": true,
  "toolName": "analyzeSqlErrorWithSchema",
  "arguments": {
    "log": "java.sql.SQLSyntaxErrorException: Unknown column 'theme_code' in 'field list'",
    "tableName": "agent_run_log"
  },
  "directAnswer": ""
}
```

如果不需要工具，则直接返回自然语言答案。

### 2. 工具调用

当前重点工具：

```text
analyzeSqlErrorWithSchema
```

该工具会：

- 提取 SQL 报错中的字段名；
- 查询目标表真实结构；
- 对比报错字段和真实字段；
- 找出缺失字段；
- 生成确定性诊断结果；
- 返回结构化工具证据。

工具结果示例：

```json
{
  "errorEvidence": {
    "errorType": "UNKNOWN_COLUMN",
    "rawMessage": "java.sql.SQLSyntaxErrorException: Unknown column 'theme_code' in 'field list'",
    "columns": ["theme_code"]
  },
  "tableName": "agent_run_log",
  "referencedColumns": ["theme_code"],
  "existingColumns": ["id", "trace_id", "user_message"],
  "missingColumns": ["theme_code"],
  "diagnosis": "SQL 引用了 agent_run_log 表中不存在的字段：theme_code",
  "suggestedActions": [
    "全局搜索字段 theme_code，定位 Mapper XML、注解 SQL 或 QueryWrapper 中的引用位置。",
    "确认实体字段、resultMap、表字段三者是否一致。",
    "确认当前连接的数据库环境是否执行了最新 DDL。"
  ]
}
```

### 3. 多轮 Agent 编排

Agent 支持多轮工具调用流程：

```text
用户问题
  -> AI 判断是否需要工具
  -> 执行工具
  -> 将工具结果追加回上下文
  -> AI 基于工具证据继续判断
  -> 输出最终答案
```

当前设置了最大工具调用次数，避免 Agent 无限循环。

### 4. 运行保护机制

当前已实现的保护机制：

- 最大工具调用次数保护；
- 重复工具调用保护；
- Agent 总执行时间保护；
- DeepSeek 单次请求超时；
- AI 决策解析失败保护。

例如，当模型重复调用相同工具和相同参数时，Agent 会终止执行并记录保护步骤。

### 5. 调用链路追踪

每一次 `/agent/ask` 请求都会生成一个 `traceId`。

主记录保存在：

```text
agent_run_log
```

步骤记录保存在：

```text
agent_step_log
```

每次运行会记录：

- 用户原始问题；
- 最终回答；
- 是否使用工具；
- 工具名称；
- 工具结果；
- AI 决策耗时；
- 工具执行耗时；
- AI 总结耗时；
- Agent 总耗时；
- 是否成功；
- 错误信息。

每个步骤会记录：


- `id`：主键 ID；
- `trace_id`：一次 Agent 请求追踪 ID；
- `step_name`：步骤名称，例如 `AI_DECISION`、`TOOL_EXECUTION`、`AI_SUMMARY`、`AGENT_GUARD`；
- `description`：步骤描述；
- `success`：步骤是否成功；
- `cost_ms`：步骤耗时；
- `input_text`：步骤输入；
- `output_text`：步骤输出；
- `error_message`：错误信息；
- `step_order`：步骤顺序；
- `created_time`：创建时间。
- `id`：主键 ID；
- `trace_id`：一次 Agent 请求追踪 ID；
- `step_name`：步骤名称，例如 `AI_DECISION`、`TOOL_EXECUTION`、`AI_SUMMARY`、`AGENT_GUARD`；
- `description`：步骤描述；
- `success`：步骤是否成功；
- `cost_ms`：步骤耗时；
- `input_text`：步骤输入；
- `output_text`：步骤输出；
- `error_message`：错误信息；
- `step_order`：步骤顺序；
- `created_time`：创建时间。

### 6. 查询接口 DTO 优化

项目中区分了数据库实体和接口响应 DTO。

例如：

- `AgentRunLog`：数据库实体；
- `AgentRunResponse`：运行详情响应；
- `AgentRunListItemResponse`：分页列表响应；
- `AgentTraceStepResponse`：步骤详情响应。

这样可以避免直接把数据库实体暴露给前端。

分页列表只返回轻量摘要：

```json
{
  "traceId": "xxxx",
  "userMessageSummary": "帮我分析这个报错...",
  "answerSummary": "根据报错信息...",
  "usedTool": true,
  "toolName": "analyzeSqlErrorWithSchema",
  "success": true
}
```

详情接口则返回完整排查信息，包括：

- `toolResult`
- `toolResultView`
- `steps`
- `inputView`
- `outputView`

## 四、主要接口

### 1. Agent 问答

```http
POST /agent/ask
```

请求示例：

```json
{
  "message": "帮我分析这个报错：java.sql.SQLSyntaxErrorException: Unknown column 'theme_code' in 'field list'，相关表是 agent_run_log"
}
```

响应核心字段：

```json
{
  "answer": "SQL 中引用了 agent_run_log 表中不存在的字段 theme_code...",
  "usedTool": true,
  "toolName": "analyzeSqlErrorWithSchema",
  "toolResult": {},
  "traceId": "xxxx",
  "steps": []
}
```

### 2. 查询工具列表

```http
GET /agent/tools
```

用于查看当前 Agent 可用工具。

### 3. 分页查询 Agent 运行记录

```http
GET /agent/runs
```

支持按工具名、成功状态、时间范围筛选。

### 4. 查询单次 Agent 运行详情

```http
GET /agent/runs/{traceId}
```

返回主记录和完整步骤链路。

## 五、核心类说明

### AgentOrchestrator

Agent 编排核心类，负责：

- 创建运行上下文；
- 调用 DeepSeek 进行工具决策；
- 执行工具；
- 检查运行保护；
- 追加工具结果；
- 构建最终响应。

### DeepSeekDecisionClient

DeepSeek 调用客户端，负责：

- 构造 system prompt；
- 调用 DeepSeek API；
- 解析工具决策 JSON；
- 将工具结果追加到消息上下文；
- 控制模型基于工具证据生成最终回答。

### ToolRegistry

工具注册中心，负责：

- 管理所有 AgentTool；
- 根据工具名称执行工具；
- 统一处理工具不存在、工具异常、工具耗时。

### AnalyzeSqlErrorWithSchemaTool

SQL 报错分析工具，负责：

- 提取错误证据；
- 查询数据库表结构；
- 对比字段是否存在；
- 生成缺失字段诊断；
- 返回结构化诊断结果。

### AgentLogService

日志服务，负责：

- 保存 Agent 主记录；
- 保存 Agent 步骤记录；
- 分页查询运行记录；
- 查询单次运行详情；
- 将数据库实体转换成响应 DTO。

### AgentJsonHelper

JSON 辅助组件，负责：

- 将 JSON 字符串解析成对象用于接口展示；
- 将对象序列化成字符串用于数据库保存。

## 六、运行配置

核心配置示例：

```yaml
server:
  port: 8088

deepseek:
  api-key: ${DEEPSEEK_API_KEY}
  base-url: https://api.deepseek.com
  model: deepseek-chat
  timeout-ms: 30000

agent:
  max-execution-time-ms: 120000
```

注意：

- 不要把真实 API Key 写入代码或 README；
- 本地通过环境变量配置 `DEEPSEEK_API_KEY`；
- MySQL 密码也应通过环境变量配置。

## 七、当前已完成阶段

### M1：Agent 编排闭环

已完成：

- Agent 三类职责拆分；
- AI 工具决策；
- 工具调用；
- 多轮消息上下文；
- 重复调用保护；
- 最大工具次数保护；
- 总执行时间保护；
- DeepSeek 请求超时；
- AI 决策失败落库；
- Agent 运行日志与步骤日志。

### M2：接口结构与工具诊断增强

已完成：

- `toolResult` 结构化展示；
- `inputView/outputView` 展示字段；
- 详情接口 DTO 化；
- 分页接口 DTO 化；
- `AgentJsonHelper` 抽取；
- SQL 错误工具增强；
- 缺失字段诊断；
- 排查建议结构化输出；
- 模型总结优先使用工具诊断结果。

## 八、演示路径

建议按下面顺序演示：

1. 启动项目；
2. 使用 Postman 调用 `/agent/ask`；
3. 输入 Unknown column 报错；
4. 查看返回的 `answer`、`toolResult`、`traceId`；
5. 调用 `/agent/runs/{traceId}`；
6. 查看完整 steps；
7. 调用 `/agent/runs`；
8. 查看分页摘要；
9. 说明 Agent 的保护机制和日志追踪能力。

推荐演示问题：

```text
帮我分析这个报错：
java.sql.SQLSyntaxErrorException: Unknown column 'theme_code' in 'field list'，
相关表是 agent_run_log
```

## 九、项目亮点

- 不是简单 AI 问答，而是带工具调用的 Agent；
- 使用真实数据库表结构作为排障证据；
- Agent 决策、工具执行、总结回答全链路可追踪；
- 具备重复调用、最大调用次数、超时保护；
- 支持工具结果结构化展示；
- 区分数据库实体和接口响应 DTO；
- 工具输出不仅返回原始表结构，还能生成确定性诊断结果；
- 适合作为 Java 后端转 AI Agent 开发的学习 Demo。

## 十、后续计划

短期计划：

- 补充 README 示例截图；
- 补充 Postman 示例；
- 增加关键单元测试；
- 整理项目学习记录。

可选扩展：

- 增加 Mapper XML 分析工具；
- 增加 SQL 片段分析工具；
- 接入本地大模型或 OpenAI 兼容接口；
- 增加简单前端页面；
- 支持更多数据库错误类型。