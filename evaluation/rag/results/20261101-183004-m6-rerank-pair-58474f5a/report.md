# RRF / Reranker 固定候选评测

状态：COMPLETED；comparisonReady=True。

| 策略 | SourceHit@1 | EvidenceHit@5 | EvidenceMRR@5 |
|---|---|---|---|
| hybrid | 1.0000 | 1.0000 | 0.8556 |
| rerank | 0.9333 | 1.0000 | 0.8444 |

分母为全部计划查询；失败保留为 ERROR，不视为正常未命中。
comparisonReady=false 时，不据此宣布排序方案优劣。

耗时统计见 summary.json：hybridStages 为原检索阶段之和；
rerankIncrement 为新增重排适配耗时；pairHttp 为一次完整成对请求耗时。
pairHttp 不能拆成两个独立策略的 HTTP 耗时。单轮 15 题不证明稳定性能或泛化收益。

| 用例 | 候选数 | 并集有目标证据 | RRF 证据排名 | Rerank 证据排名 |
|---|---|---|---|---|
| unknown-column-error-001 | 5 | True | 1 | 1 |
| unknown-column-environment-002 | 6 | True | 2 | 2 |
| unknown-column-order-003 | 7 | True | 1 | 1 |
| table-not-exist-error-004 | 5 | True | 1 | 3 |
| table-not-exist-environment-005 | 7 | True | 2 | 3 |
| table-not-exist-order-006 | 8 | True | 2 | 2 |
| duplicate-error-007 | 7 | True | 1 | 1 |
| duplicate-idempotency-008 | 6 | True | 1 | 1 |
| duplicate-logical-delete-009 | 5 | True | 1 | 1 |
| data-too-long-error-010 | 6 | True | 1 | 1 |
| data-too-long-json-011 | 7 | True | 1 | 1 |
| data-too-long-order-012 | 6 | True | 1 | 1 |
| foreign-key-delete-013 | 6 | True | 1 | 1 |
| foreign-key-parent-missing-014 | 7 | True | 1 | 1 |
| foreign-key-order-015 | 5 | True | 3 | 1 |
