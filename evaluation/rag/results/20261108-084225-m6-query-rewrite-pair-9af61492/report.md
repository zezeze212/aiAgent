# 查询改写成对评测

状态：COMPLETED；comparisonReady=True。

| 策略 | SourceHit@1 | EvidenceHit@5 | EvidenceMRR@5 |
|---|---|---|---|
| original | 1.0000 | 1.0000 | 0.8556 |
| rewritten | 1.0000 | 1.0000 | 0.8778 |

改写状态：{'APPLIED': 8, 'UNCHANGED': 6, 'SKIPPED': 1, 'FALLBACK': 0}。
有效配对数：15；相同查询复用：7。

## 耗时（成功配对，毫秒）

| 环节 | 样本数 | Mean | P50 | P95 |
|---|---|---|---|---|
| originalRetrieval | 15 | 126.288 | 117.596 | 222.413 |
| rewriteCall | 15 | 626.8 | 692 | 1024 |
| rewrittenRetrieval | 15 | 123.642 | 117.596 | 199.176 |
| rewrittenStages | 15 | 750.442 | 831.176 | 1126.495 |
| pairHttp | 15 | 844.574 | 902.488 | 1385.167 |

## 逐题定位

| 用例 | 执行 | 改写状态 | 原证据排名 | 新证据排名 | ΔRR |
|---|---|---|---|---|---|
| unknown-column-error-001 | SUCCESS | APPLIED | 1 | 1 | +0.0000 |
| unknown-column-environment-002 | SUCCESS | APPLIED | 2 | 1 | +0.5000 |
| unknown-column-order-003 | SUCCESS | SKIPPED | 1 | 1 | +0.0000 |
| table-not-exist-error-004 | SUCCESS | UNCHANGED | 1 | 1 | +0.0000 |
| table-not-exist-environment-005 | SUCCESS | APPLIED | 2 | 3 | -0.1667 |
| table-not-exist-order-006 | SUCCESS | APPLIED | 2 | 2 | +0.0000 |
| duplicate-error-007 | SUCCESS | UNCHANGED | 1 | 1 | +0.0000 |
| duplicate-idempotency-008 | SUCCESS | UNCHANGED | 1 | 1 | +0.0000 |
| duplicate-logical-delete-009 | SUCCESS | APPLIED | 1 | 1 | +0.0000 |
| data-too-long-error-010 | SUCCESS | UNCHANGED | 1 | 1 | +0.0000 |
| data-too-long-json-011 | SUCCESS | APPLIED | 1 | 1 | +0.0000 |
| data-too-long-order-012 | SUCCESS | APPLIED | 1 | 1 | +0.0000 |
| foreign-key-delete-013 | SUCCESS | UNCHANGED | 1 | 1 | +0.0000 |
| foreign-key-parent-missing-014 | SUCCESS | UNCHANGED | 1 | 1 | +0.0000 |
| foreign-key-order-015 | SUCCESS | APPLIED | 3 | 3 | +0.0000 |

## 解释边界

- 主表分母为全部计划题数；ERROR/未完成不是正常未命中。
- comparisonReady=false 时，不据此宣布策略优劣。
- rewritten 是包含回退和跳过的实际策略；APPLIED 子集另见 summary.json，不能代表全体收益。
- None 排名表示本次正常返回的 Top5 没有匹配标注证据。
- 评分要求来源、章节和证据文本匹配，不证明最终答案正确或引用充分。
- rewrittenStages 为改写与检索之和；pairHttp 包含两侧实验工作，不是线上 Agent 耗时。
- 同查询复用的两侧耗时来自同一次检索，不是独立性能样本。
- 预热不计分；单轮小样本仍受缓存和服务波动影响，不能认定稳定收益。
- 本地 Git/文件哈希不证明服务端部署身份；索引状态哈希不等于在线 Qdrant 全量校验。
- 原查询、有效查询、回退原因、候选正文和错误信息保留在 details.jsonl。
