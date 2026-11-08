# M6-6B 回答证据使用检查

## 1. 检查范围

本次检查包含一个历史工具样例和两个当前首次运行样例：

1. 历史表结构回答：核对回答是否忠实转述工具结果。
2. 当前知识回答：核对引用片段是否支持回答内容。
3. 当前工具边界回答：核对明确事实与无法确认的信息是否被区分。

本次不新增评测框架，不调整 Prompt，不修改固定 15 题检索基线，
不通过重复请求挑选更好的回答。

检查标签：

- SUPPORTED：被本次可用证据支持。
- UNSUPPORTED：本次可用证据不足以支持。
- CONTRADICTED：与可用证据直接冲突。
- NOT_ASSESSABLE：因证据缺失或不可读而无法判断。

标签针对具体断言，不直接代表整份回答的正确性。
外部文档核查与 Agent 当次获得的证据分开记录。

## 2. 运行身份与资料限制

当前两个样例记录的本地 Git HEAD：

fd3dadc34bc22c34a5de578f51532743b2afe333

请求前记录的 localGitStatus 为空。

serverIdentity：

LOCAL_HEAD_RECORDED_SERVER_REVISION_NOT_VERIFIED

因此，只确认请求脚本记录了本地代码版本，不把它当成服务端
实际运行提交的独立证明。

两个请求均返回 code=200，requestError=null。

原始响应中文存在编码错读。审阅时对受影响字符串进行
Latin-1 编码回字节、UTF-8 解码，确认可逆后用于阅读。
原始 JSON 保留，不覆盖、不重新请求模型。

displayModified=false 是服务端展示治理的标记，
不能证明后续 HTTP 解码或文件保存过程没有改变文本。

## 3. 样例 A：历史表结构回答

来源：

evaluation/results/20260827-191440-deepseek-v4-flash-eval-v1-seed-r2/details.jsonl

caseId：schema-run-log-004
traceId：2d6cba02

历史 Manifest 记录的代码版本：

cbf30f43f6b70c5223d863f890e612edc2430d35

该样例成功调用 getTableSchema。选取以下三个断言检查：

| 回答断言 | 工具证据 | 判断 |
|---|---|---|
| 追踪 ID 保存在 trace_id | columnName=trace_id，注释为 Agent 运行追踪 ID | SUPPORTED |
| 用户问题保存在 user_message | columnName=user_message，注释为用户原始问题 | SUPPORTED |
| created_time 默认值为 CURRENT_TIMESTAMP(3) | 对应列 defaultValue=CURRENT_TIMESTAMP(3) | SUPPORTED |

结论：这三个历史断言与当次工具结果一致。

限制：这是历史证据复查，不证明当前数据库结构，
也不作为当前版本接口功能验证。

## 4. 样例 B：当前知识回答

文件：02-rag-answer.json
traceId：c2d20efa
问题：MySQL 的 Duplicate entry 报错一般表示什么？

### 4.1 实际链路

- 查询改写状态：APPLIED。
- 检索器：hybrid。
- 候选片段：5。
- 选入片段：5。
- 来源配额：关闭。
- token 估算预算：关闭。
- usedTool=false。
- 引用状态：VALID_IDS。
- citedIds 数量：4。
- unknownIds：空。
- semanticSupportChecked=false。

上述是本次 Trace 的观察，不作为推荐默认配置。

### 4.2 引用映射

四个被引用片段均来自：

docs/knowledge/sql/duplicate-entry.md

| 标识 | chunkId | 章节 |
|---|---|---|
| B1 | 30bdef17-4620-3894-940a-21232b3da7ff | 典型报错 |
| B2 | 70f4340b-4a7f-304b-8e08-59415e2648a1 | 常见原因 |
| B3 | 1dcf4645-e94d-3464-ad84-5cc54785323c | 排查顺序 |
| B4 | df14beaa-aa10-3bbc-a172-52a2e7f0c9c8 | 回答要求 |

### 4.3 断言检查

| 回答内容 | 证据 | 判断 |
|---|---|---|
| 插入或更新违反唯一约束，目标唯一键已有相同值 | B1 直接说明该含义 | SUPPORTED |
| 重复提交、重复数据、新增更新混用等常见原因 | B2 包含对应原因 | SUPPORTED |
| 提取重复值和键名，确认字段，查询已有记录并检查幂等 | B3 包含对应步骤 | SUPPORTED |
| 无法确认唯一键字段时补充表结构或唯一索引信息 | B4 明确提出该要求 | SUPPORTED |

回答明确说明这是通用解释，不代表已检查当前环境，
与本次未执行数据库工具一致。

另一个 Data too long 片段被选入，但未被引用。
未要求模型使用全部上下文，也不把引用数量作为质量目标。

结论：本例检查的主要知识内容获得对应引用支持。
这是语义阅读后的局部结论，不是由 VALID_IDS 自动推导。

## 5. 样例 C：当前工具证据边界

文件：03-tool-boundary.json
traceId：4e70854e

问题要求查询 agent_run_log 的真实字段结构，
说明 created_time 默认值，并判断是否能确认 id 自增。

### 5.1 实际执行

- getTableSchema 执行成功。
- 参数：tableName=agent_run_log。
- 返回 14 列。
- created_time：datetime(3)，nullable=NO，
  defaultValue=CURRENT_TIMESTAMP(3)。
- id：bigint unsigned，nullable=NO，defaultValue=null，
  columnComment=主键。
- 返回结果没有 EXTRA、自增属性或结构化索引元数据。
- 引用状态：NO_CITATIONS。
- semanticSupportChecked=false。

工具结果中的“主键”是列注释，不等同于独立查询到的主键约束。

### 5.2 断言检查

| 回答断言 | 证据及限制 | 判断 |
|---|---|---|
| created_time 默认值为 CURRENT_TIMESTAMP(3) | 对应列直接返回该值 | SUPPORTED |
| 当前结果无法确认 id 是否自增 | 工具没有返回该属性 | SUPPORTED |
| 实际业务中这种主键绝大多数自增 | 本次工具和选入知识均没有支持这一普遍性判断 | UNSUPPORTED |
| TABLES.AUTO_INCREMENT 非空可作为确认 id 自增的独立办法 | 本次未查询该信息，且表级值不标识具体列 | UNSUPPORTED |

外部文档补充核查：

MySQL 的 TABLES.AUTO_INCREMENT 表示表的下一个自增值。
由此不能单独确定自增列就是 id。

确认具体列时，应检查该列的 COLUMNS.EXTRA，
或检查建表定义中该列的 AUTO_INCREMENT 属性。

这些外部核查资料不计入 Agent 当次已获得的证据。

### 5.3 结论

回答正确报告了可确认的默认值，并保留了 id 自增属性的不确定性。

同时，附加说明包含缺少支持的经验判断，
以及把表级信息用于确认具体列的检查方法问题。

因此，不将本例记录为整份回答完全正确。

NO_CITATIONS 本身不构成失败：
数据库事实主要由工具结果支持，无须添加无关知识引用。

## 6. 本轮结论与停止条件

本轮完成了以下观察：

1. 历史工具回答中的三个选定断言与工具结果一致。
2. 当前知识回答中的主要内容获得对应片段支持。
3. 当前工具回答能区分已知默认值与未知自增属性。
4. 核心结论有依据，不代表附加说明也可靠。
5. 引用合法性检查仍不能代替语义检查。
6. 检索选入的内容不一定都与问题直接相关，也不需要全部引用。

本轮不计算整体正确率，不推断策略增益，
不将三个样例推广为系统整体可靠性结论。

已达到本单元停止条件：
完成三个样例的证据检查，如实记录问题，保留首次运行结果。

不因本轮发现的问题重复调 Prompt 或重跑相同请求。
编码问题及回答扩展问题作为阶段已知限制记录。

下一单元：M6-7 阶段收尾，核对总纲交付项、
整理策略结论与已知限制、更新阶段文档。

## 7. 外部核查资料

仅用于审阅补充，不属于 Agent 当次上下文：

- MySQL 8.4 INFORMATION_SCHEMA TABLES：
  https://dev.mysql.com/doc/refman/8.4/en/information-schema-tables-table.html
- MySQL 8.4 INFORMATION_SCHEMA COLUMNS：
  https://dev.mysql.com/doc/refman/8.4/en/information-schema-columns-table.html