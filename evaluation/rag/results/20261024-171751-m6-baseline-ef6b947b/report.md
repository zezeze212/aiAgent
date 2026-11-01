# M6-0 rule / dense 基线报告

运行状态：COMPLETED。
完整可比：True。仅检索，不含模型回答。

| 策略 | 粒度 | 成功/计划 | 错误 | 未执行 | SourceHit@1 | EvidenceAvailable | EvidenceHit@5 | EvidenceMRR@5 |
|---|---|---|---|---|---|---|---|---|
| rule | 全文，最多1篇 | 15/15 | 0 | 0 | 0.6667 | 0.6667 | N/A | N/A |
| dense | 片段，最多5块 | 15/15 | 0 | 0 | 0.9333 | 1.0000 | 1.0000 | 0.8278 |

表中分母为全部计划查询；错误/未执行不会获得命中分，但在状态中单独保留，不当作正常 MISS。
successfulOnly 指标及成功/失败延迟分别见 summary.json。含错误或未执行时，不据此宣布策略优劣。

## 解释边界

- rule 是全文返回，片段排名指标为 N/A。SourceHitWithinRawK 的原始 K/粒度不同，不作等预算胜负判断。
- EvidenceAvailable 仅匹配来源与证据文本；不证明上下文选入或最终答案正确。
- 15 个成功样本的 nearest-rank P95 是最大观测值；单轮不是稳定性能或容量结论。
- 配置由服务端回显；本地索引状态哈希不是对在线 Qdrant 数据的完整核验。
- 语料哈希/过滤范围在两策略间比较，策略配置在各自运行中冻结；配置漂移计为 ERROR。
- server costMs 包围检索适配调用；clientCostMs 还包含元数据读取、HTTP 与响应读取。
- 运行期间请勿改语料、重建索引或切换服务；本地工作区哈希不能证明服务器部署了同一代码。

## 失败定位

- rule 错误：[]；成功请求中未提供目标证据：['table-not-exist-error-004', 'table-not-exist-order-006', 'duplicate-logical-delete-009', 'data-too-long-order-012', 'foreign-key-parent-missing-014']。
- dense 错误：[]；成功请求中未提供目标证据：[]。
