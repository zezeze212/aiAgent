# AI Agent Evaluation 报告

> 本报告由固定规则从 Trace 和最终回答中生成。各指标独立展示，不计算加权总分。

## 一、运行信息

| 项目 | 值 |
|---|---|
| Run ID | `20260827-191440-deepseek-v4-flash-eval-v1-seed-r2` |
| 状态 | `COMPLETED` |
| 开始时间 | `2026-08-27T19:14:40.177+08:00` |
| 结束时间 | `2026-08-27T19:15:27.821+08:00` |
| Dataset | `eval-v1-seed-r2` |
| Dataset SHA-256 | `58766fe3b578b37d48d27a54a9e7bef492df252539ce2e226a77120d12b377a5` |
| Model | `deepseek-v4-flash` |
| Prompt | `spring-ai-system-v2` |
| Knowledge | `simple-rag-v2` |
| Code Commit | `cbf30f43f6b70c5223d863f890e612edc2430d35` |
| 并发数 | `1` |
| 成功请求 | 14 |
| 失败请求 | 0 |

## 二、结论摘要

- 请求采集：14 / 14 / 100.00%。
- 工具调用边界：14 / 14 / 100.00%，本轮未发现偏差。
- 确定性工具证据：17 / 17 / 100.00%。
- 信息不足时的澄清处理：3 / 3 / 100.00%。
- 无依据回答率（规则代理指标）：0 / 11 / 0.00%；该指标只覆盖已配置正则。

## 三、规则指标

| 指标 | 分子 / 分母 / 百分比 | 未通过用例 |
|---|---:|---|
| 请求执行成功率 | 14 / 14 / 100.00% | — |
| HTTP 状态正确率 | 14 / 14 / 100.00% | — |
| 响应 Body Code 正确率 | 14 / 14 / 100.00% | — |
| needTool accuracy | 14 / 14 / 100.00% | — |
| toolName accuracy | 8 / 8 / 100.00% | — |
| 参数完整率 | 11 / 11 / 100.00% | — |
| 参数正确率 | 17 / 17 / 100.00% | — |
| Tool 状态正确率 | 8 / 8 / 100.00% | — |
| Tool 错误码正确率 | 8 / 8 / 100.00% | — |
| Tool Evidence 正确率 | 17 / 17 / 100.00% | — |
| RAG accuracy | 28 / 28 / 100.00% | — |
| Chat 状态正确率 | 14 / 14 / 100.00% | — |
| Answer Evidence 覆盖率 | 18 / 18 / 100.00% | — |
| 要求行为覆盖率 | 10 / 10 / 100.00% | — |
| Business Error 处理率 | 2 / 2 / 100.00% | — |
| 澄清处理率 | 3 / 3 / 100.00% | — |
| 无依据回答率（规则代理指标，越低越好） | 0 / 11 / 0.00% | — |

指标口径：

- `toolName accuracy` 只以期望调用 Tool 的用例为分母；额外调用由 `needTool accuracy` 反映。
- 参数和 Tool Result 中的每个期望值都是一条原子断言，因此报告同时保留分子和分母。
- RAG 每个请求包含 PRE_DECISION、POST_TOOL 两个阶段断言；状态和来源同时正确才通过。
- 无依据回答率仅表示命中预先定义的禁用正则，不等价于完整 groundedness Judge。

## 四、调用次数与延迟

- Tool 调用次数：8
- 模型调用次数：N/A。The current Java Trace does not expose real model invocation count.

| 延迟口径 | 样本数 | Min | Mean | P50 | P95 | Max |
|---|---:|---:|---:|---:|---:|---:|
| 服务端 Agent | 14 | 1.0 ms | 3348.286 ms | 3607.0 ms | 5732.0 ms | 5732.0 ms |
| 客户端 Wall Time | 14 | 46.512 ms | 3399.194 ms | 3655.279 ms | 5787.089 ms | 5787.089 ms |

P50/P95 使用 nearest-rank。当前仅14条种子样例，只用于验证统计链路，不作为稳定性能结论。

## 五、逐题结果

| # | Case ID | 类别 | 期望结果 | 实际 Tool | Tool 边界 | RAG | Answer | 契约 | 服务端 / 客户端 |
|---:|---|---|---|---|---|---|---|---|---:|
| 1 | `general-index-001` | GENERAL_KNOWLEDGE_NO_TOOL | ANSWER | — | PASS | PASS | PASS | PASS | 5384 / 5434.656 ms |
| 2 | `general-exception-002` | GENERAL_KNOWLEDGE_NO_TOOL | ANSWER | — | PASS | PASS | PASS | PASS | 3306 / 3372.203 ms |
| 3 | `general-duplicate-entry-003` | GENERAL_KNOWLEDGE_NO_TOOL | ANSWER | — | PASS | PASS | PASS | PASS | 4613 / 4667.765 ms |
| 4 | `schema-run-log-004` | TABLE_SCHEMA | ANSWER | `getTableSchema` | PASS | PASS | PASS | PASS | 3607 / 3655.279 ms |
| 5 | `schema-step-log-005` | TABLE_SCHEMA | ANSWER | `getTableSchema` | PASS | PASS | PASS | PASS | 4898 / 4944.403 ms |
| 6 | `sql-unknown-column-run-006` | SQL_SCHEMA_DIAGNOSIS | ANSWER | `analyzeSqlErrorWithSchema` | PASS | PASS | PASS | PASS | 4511 / 4571.765 ms |
| 7 | `sql-unknown-column-step-007` | SQL_SCHEMA_DIAGNOSIS | ANSWER | `analyzeSqlErrorWithSchema` | PASS | PASS | PASS | PASS | 4085 / 4162.044 ms |
| 8 | `stacktrace-multiline-008` | STACK_TRACE | ANSWER | `analyzeStackTrace` | PASS | PASS | PASS | PASS | 5732 / 5787.089 ms |
| 9 | `stacktrace-flattened-009` | STACK_TRACE | ANSWER | `analyzeStackTrace` | PASS | PASS | PASS | PASS | 4015 / 4088.082 ms |
| 10 | `business-table-not-found-010` | BUSINESS_ERROR | BUSINESS_ERROR | `getTableSchema` | PASS | PASS | PASS | PASS | 997 / 1031.687 ms |
| 11 | `business-sql-table-not-found-011` | BUSINESS_ERROR | BUSINESS_ERROR | `analyzeSqlErrorWithSchema` | PASS | PASS | PASS | PASS | 946 / 980.136 ms |
| 12 | `clarify-incomplete-stacktrace-012` | CLARIFICATION | CLARIFICATION | — | PASS | PASS | PASS | PASS | 2592 / 2619.677 ms |
| 13 | `clarify-missing-table-013` | CLARIFICATION | CLARIFICATION | — | PASS | PASS | PASS | PASS | 1 / 46.512 ms |
| 14 | `clarify-vague-mybatis-014` | CLARIFICATION | CLARIFICATION | — | PASS | PASS | PASS | PASS | 2189 / 2227.422 ms |

## 六、失败明细

本轮没有发现未通过固定契约的用例。
## 七、限制与后续用途

- 这是种子集校准报告，不是项目最终效果报告。
- 规则断言只能检查已定义的 Tool、RAG、证据词组和禁用表达，不能覆盖全部自然语言事实。
- 当前 Trace 没有提供真实模型调用次数，因此必须显示 `N/A`。
- 种子集稳定后应扩充到不少于50条，再冻结 Spring AI 当前基线。
- 后续 M5/M6/M7 应使用冻结数据集重复运行，分别比较向量 RAG、检索策略和模型差异。
