# M6-0：规则 / Dense 基线接口与评分适配

> 日期：2026-10-22。基于上传代码 HEAD f2db3a8。  
> 本次交付：完整修改文件与定向测试；12 项 Python 离线测试通过。  
> 待用户本地验证：Java 编译/定向测试、真实 rule/dense 基线。当前尚无新的在线检索成绩。

## 1. 本单元目标与边界

让同一个评测脚本调用真实 Java rule / dense，在报告中明确结果粒度、证据命中、排序、耗时与生效配置。

这一步为后续 BM25 提供比较入口。正式 /agent/ask、旧 /rag/search、历史评测脚本与 15 条种子题保持原行为。本次不增加知识题、不修改 Prompt、不调用回答模型、不写入向量索引。

### 1.1 新链路

1. Python 对每个原始问题请求 GET /rag/benchmark/search。
2. strategy=rule：复用 RuleKnowledgeRetriever → SimpleRagRetriever，最多一篇全文。
3. strategy=dense：复用 VectorKnowledgeService，返回实际向量片段。
4. Java 回传结果粒度、真实策略配置、当前 classpath 语料指纹及本地索引状态指纹。
5. Python 检查契约及运行配置是否稳定，分层评分并写入报告。

评测入口默认关闭；开启参数为 agent.rag.benchmark.enabled=true。这个开关是显式启用机制，不代表用户鉴权或网络访问控制；用于现有本地实验环境。

## 2. 文件清单

压缩包按项目相对路径组织，包含下面的完整文件。

| 操作 | 文件 | 职责 |
|---|---|---|
| 修改 | src/main/java/com/example/agent/rag/SimpleRagRetriever.java | 仅增加 sources()，暴露规则实际来源清单 |
| 修改 | src/main/java/com/example/agent/rag/vector/VectorKnowledgeService.java | 仅增加 searchPolicy() 与 SearchPolicy record，回显实例实际使用的阈值和范围 |
| 新增 | src/main/java/com/example/agent/dto/RagBenchmarkResponse.java | 返回粒度、请求 K、有效 K、耗时、知识结果及配置 |
| 新增 | src/main/java/com/example/agent/rag/RagBenchmarkService.java | 调用真实检索器、核对语料范围并记录配置 |
| 新增 | src/main/java/com/example/agent/controller/RagBenchmarkController.java | 显式 strategy 参数的评测入口 |
| 新增 | src/test/java/com/example/agent/rag/RagBenchmarkServiceTest.java | 8 项：真实规则、dense 映射、范围、失败和索引漂移边界 |
| 新增 | src/test/java/com/example/agent/controller/RagBenchmarkControllerTest.java | 2 项：默认关闭、HTTP 绑定及现有业务错误包裹格式 |
| 新增 | evaluation/rag/scripts/run_baseline_eval.py | 双策略执行、评分、身份记录、报告、逐题错误留痕 |
| 新增 | evaluation/rag/tests/test_baseline_eval.py | 12 项离线评分与报告流程测试 |
| 新增 | docs/M6-0-BASELINE-GUIDE.md | 本文 |

不新增依赖、不修改 pom.xml。新代码通过既有 VectorKnowledgeService 调用已经使用的 Spring AI 1.1.8 接口，没有引入其他版本的 Spring AI API。

### 2.1 sources() 为什么需要

rule 使用固定规则来源，dense 使用 manifest 建立索引。评测必须先确认双方讨论的是同一批文档；如果两者来源清单不一致，接口返回错误，不能事后过滤 rule 结果再声称保留了原规则行为。

### 2.2 searchPolicy() 为什么需要

评测读取 VectorKnowledgeService 实例持有的阈值、category、visibility，避免自己复制一份配置却与实际调用不一致。集合和 Embedding 身份以 configured 前缀回显，明确它们来自服务配置；本地索引状态哈希并不证明在线 Qdrant 没有被外部修改。

## 3. 安装文件

先将压缩包解压到临时目录，核对清单后，将 src、evaluation、docs 三个目录的内容合并到项目对应目录。两个既有 Java 文件提供完整替换版本；如果你在 f2db3a8 之后修改过它们，只合并新增方法与 record，保留自己的其他修改。

以下命令假设压缩包位于 Downloads，项目为 D:\code\ai-agent-demo；实际路径不同时修改变量：

```powershell
$packagePath = "$env:USERPROFILE\Downloads\M6-0-baseline-files.zip"
$stagePath = "$env:TEMP\ai-agent-m6-0-files"
$projectPath = "D:\code\ai-agent-demo"

Expand-Archive -LiteralPath $packagePath -DestinationPath $stagePath -Force
Copy-Item -Path "$stagePath\src" -Destination $projectPath -Recurse -Force
Copy-Item -Path "$stagePath\evaluation" -Destination $projectPath -Recurse -Force
Copy-Item -Path "$stagePath\docs" -Destination $projectPath -Recurse -Force
Set-Location $projectPath
git diff --stat
git status --short
```

若选择手动合并，按第 2 节的完整路径放置即可。压缩包还包含 DELIVERY-MANIFEST.json 说明文件哈希与验证状态，不需要复制它到项目。

## 4. 只运行受影响的 Java 测试

目的：确认本地依赖下新接口可以编译，并确认新增适配不会改变旧规则与向量检索行为。

```powershell
.\mvnw.cmd "-Dtest=RagBenchmarkServiceTest,RagBenchmarkControllerTest,SimpleRagRetrieverTest,VectorKnowledgeServiceTest" test
```

这四个类预计合计 22 项（新增 10 项、既有 12 项），以本地 Maven 实际输出为准。它们不启动真实 MySQL、Ollama、Qdrant 或 DeepSeek；不需要先运行整个全量测试。

本次制作环境有 Java 17，但 Maven Wrapper 下载时无法解析 repo.maven.apache.org，因此这里不能声称 Java 编译或上述 JUnit 已通过。依赖版本保持项目原值，没有为迁就制作环境降级。

Python 评分器的 12 项离线测试已通过，无需把本地重跑作为进入下一步的门槛。如以后修改评分器，需要复查时使用：

```powershell
python -m unittest discover -s evaluation/rag/tests -p "test_baseline_eval.py" -v
```

## 5. 开启评测入口并重启应用

在当前 IDEA 启动配置的 Program arguments 中增加：

```text
--agent.rag.benchmark.enabled=true
```

保留原有数据库、模型和向量服务配置，工作目录继续使用项目根目录。正式 agent.rag.mode 不需要切换，评测由每个 HTTP 请求的 strategy 单独选择。

若使用 Maven 启动，并且原来需要的环境变量已经设置，可使用：

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--agent.rag.benchmark.enabled=true"
```

本轮固定：category=mysql、visibility=public、dense similarity-threshold=0.0、受管集合 ai_agent_knowledge_managed_v1。应用原配置已是这些值时不需要再次修改。

## 6. 用 Postman 检查两个新请求

### 6.1 rule

方法：GET。URL：

```text
http://localhost:8088/rag/benchmark/search
```

在 Params 中填入：

| Key | Value |
|---|---|
| strategy | rule |
| query | Duplicate entry 'A001' for key 'uk_code' 是什么错误 |
| topK | 5 |

预期：业务 code=200，data.strategy=rule，resultUnit=document，requestedTopK=5，effectiveTopK=1，hits 最多一篇；命中时 section=全文，chunkId 以 rule: 开头，score=null。

### 6.2 dense

复制同一请求，只将 strategy 改成 dense。

预期：业务 code=200，resultUnit=chunk，effectiveTopK=5，hits 至多 5 条，包含实际片段 ID、章节与分数。configuration 中检查：

| 字段 | 预期 |
|---|---|
| corpusSha256 | 与 rule 相同 |
| category / visibility | mysql / public |
| sourceFilter | null |
| similarityThreshold | 0.0 |
| configuredCollection | ai_agent_knowledge_managed_v1 |
| configuredEmbeddingModel | 当前实际配置的 bge-m3 名称 |
| configuredEmbeddingRevision | 当前已维护的 Embedding 修订标识 |
| indexStatePresent | true |
| indexStateSha256 | 非空 |

不要把评测题的 expected.source 填成过滤条件，这会泄露目标文档。本接口也不提供 source 参数。

这里验证的是新入口的路由、返回契约和配置，不需要重复旧 M5 的工具调用或引用审计请求。

### 6.3 常见问题

| 现象 | 处理 |
|---|---|
| 404 | 确认开关在 Program arguments、应用已重启、请求的是新路由 |
| HTTP 200 但 body code 非 200 | 现有全局异常处理采用业务码；按 body code/message 判断，脚本也会这样处理 |
| dense 未启用 | 确认现有 agent.rag.vector.enabled=true 及 VectorKnowledgeService 依赖；不会静默降级为 rule |
| rule/Manifest 或过滤范围不一致 | 检查语料清单和实际范围，不通过删除评分断言掩盖差异 |
| 存在 pending 恢复记录 | 检查已有索引同步状态，按原恢复流程完成后再评测；不要直接删除恢复记录 |
| indexStatePresent=false | 先检查 IDEA 工作目录及已有 data/knowledge-index 状态文件位置；不要为消除报错直接重建或清空集合 |
| 运行期间索引状态变化 | 停止并发索引同步，保持语料/配置稳定后再运行；异常结果不会被当成普通 MISS |

## 7. 运行一次 15 题双策略基线

两个新接口正常后，从项目根目录执行：

```powershell
python evaluation/rag/scripts/run_baseline_eval.py --base-url http://localhost:8088
```

脚本使用 Python 3.10+ 标准库，复用旧 run_retrieval_eval.py 的数据加载和严格证据匹配函数，不需要额外 pip 安装。请将旧脚本保留在原位置。

总共 32 次检索：rule/dense 各一次预热，再分别执行既有 15 题。预热排除出评分与延迟统计；逐题交替策略先后顺序。这些请求不调用 DeepSeek 生成答案。

输出目录形如：

```text
evaluation/rag/results/20261022-xxxxxx-m6-baseline-xxxxxxxx/
  manifest.json
  details.jsonl
  summary.json
  report.md
```

manifest.json 记录服务端观察配置、语料/索引指纹、数据集哈希、评分器版本、脚本及本地代码哈希。本地 runner 工作区身份不等于已核实服务器部署身份，安装后需要重启服务。

## 8. 怎样读报告

### 8.1 首先判断运行是否有效

- status=COMPLETED，comparisonReady=true。
- 两个策略各 planned=15、succeeded=15、errors=0、notRun=0。
- 正常返回空列表是可计分的未命中；连接失败、业务错误、配置漂移是 ERROR。
- 某题失败仍保存后续结果，不把失败题从计划分母里删掉。
- 中断会保存 PARTIAL 报告；不可将部分完成的结果当完整基线。
- comparisonReady=false 时，先排查运行/配置问题，再讨论检索质量。错误码 2 表示基线尚不完整可比。

### 8.2 各指标含义

| 指标 | rule | dense | 比较注意事项 |
|---|---|---|---|
| SourceHit@1 | 首篇来源正确 | 首个片段来源正确 | 可比较各自第一条结果的来源命中 |
| SourceHitWithinRawK | 最多 1 篇 | 最多 5 个片段 | 返回粒度与预算不同，不能直接宣布算法胜负 |
| EvidenceAvailable | 全文中有目标证据 | 返回片段中有目标证据 | 系统是否提供证据，不是相同片段预算的排序对比 |
| EvidenceHit@5 | N/A | source、section、evidence 同时匹配 | 真实片段命中 |
| EvidenceMRR@5 | N/A | 首个严格命中片段的倒数排名平均 | 不把全文误当第一名片段 |

报告表格使用全部计划题为分母。summary.json 同时保留 successfulOnly 条件指标；发生 ERROR 时两种分母都要看，但应先修复运行问题。没有成功请求时，条件指标与延迟为 null，不伪造 0ms。

返回字符数以 UTF-16 单位统计，与 Java String.length() 一致；它表示返回内容量，不是完整 Prompt token，也不是最终选入上下文的长度。

### 8.3 质量结论边界

Hit@5 不变而 MRR 上升，表示目标片段更靠前。当前脚本并不执行上下文预算选择、工具调用或回答生成，不能从这里推导最终回答正确率。

当前仅 5 篇文档，来源覆盖很容易饱和；15 题全部有已知目标，又是开发中已查看的种子集。用它建立可复查的开发基线，不声称大型知识库或独立盲测效果。

本轮没有答案语义评分，也没有修改旧 M4 scorer。多片段 Trace 来源解析在后续进入正式 /agent/ask 回答对比前适配。

### 8.4 延迟口径

costMs 包围每路真实检索及结果适配，不包含语料哈希和状态文件读取；clientCostMs 是客户端观察到的完整 HTTP 时间，包含这些准备工作和网络。两者不能相加。

成功与失败延迟分开保存。一次 15 题测量不足以证明稳定性能，尤其 nearest-rank P95 在 15 个成功样本时就是最大值。先分析证据命中差异，不因单轮耗时调参。

## 9. 下一次需要提供的结果

1. 本地 Java 定向测试末尾汇总，或首个编译/测试错误。
2. 运行目录中的 report.md 与 summary.json。
3. 若存在 ERROR，提供 details.jsonl 中对应行；正常 MISS 不等于接口异常。

拿到这些结果后，先看 rule 与 dense 的差异和实际失败类型，再进入 BM25 单独基线。本轮不把规则漏题改成追加固定关键词，也不为了满分调整题目。
