"""M6-4C2B：评测正式查询改写策略，仅比较检索，不生成最终回答。"""
from __future__ import annotations

import argparse
import json
import math
import re
import time
import uuid
from collections import Counter
from datetime import datetime
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import run_baseline_eval as baseline

VERSION = "query-rewrite-pair-evaluator-v1"
ENDPOINT = "/rag/benchmark/query-rewrite-pair"
SIDES = ("original", "rewritten")
STATES = ("APPLIED", "UNCHANGED", "SKIPPED", "FALLBACK")
METRICS = ("sourceHitAt1", "evidenceHitAtK", "evidenceReciprocalRankAtK")


class ConfigurationDrift(ValueError):
    """配置漂移后停止本轮，避免继续支付无效实验的模型成本。"""


def require(condition, message):
    if not condition:
        raise ValueError(message)


def text(value):
    return isinstance(value, str) and bool(value.strip())


def fingerprint(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def build_url(base_url, case, rewrite_first):
    # 预期来源、章节和证据仅用于本地评分，不发送给检索服务。
    params = {
        "query": case["query"],
        "topK": case["topK"],
        "rewriteFirst": str(rewrite_first).lower(),
    }
    return base_url.rstrip("/") + ENDPOINT + "?" + urlencode(params)


def request_pair(base_url, case, rewrite_first, timeout):
    request = Request(
        build_url(base_url, case, rewrite_first),
        headers={"Accept": "application/json"},
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        # C2A 在单次配对内部发现配置漂移时返回 409，也必须停止本轮。
        if error.code == 409:
            raise ConfigurationDrift(
                "服务端拒绝配对：HTTP 409，请检查配置/索引是否变化"
            ) from error
        raise

    require(
        isinstance(payload, dict) and payload.get("code") == 200,
        "接口返回业务失败或非法响应",
    )
    require(isinstance(payload.get("data"), dict), "data 必须是对象")
    return payload["data"]


def validate_pair(pair, case, collection, rewrite_first):
    """先检查实验契约，再评分；缺失耗时、错误配置不能默认为成功。"""
    require(isinstance(pair, dict), "成对响应必须是对象")
    require(
        pair.get("schemaVersion") == "rag-query-rewrite-pair-v1"
        and pair.get("originalQuery") == case["query"]
        and pair.get("topK") == case["topK"],
        "schema/query/TopK 不匹配",
    )

    config = pair["configuration"]
    require(
        config["evaluationScope"] == "RETRIEVAL_BEFORE_RERANK_AND_CONTEXT",
        "评测范围发生变化",
    )
    require(
        all(config[k] is False for k in (
            "rerankApplied",
            "contextSelectionApplied",
            "answerGenerationApplied",
        )),
        "本轮不能混入重排、上下文筛选或答案生成",
    )

    retrieval, rewrite_config = config["retrieval"], config["rewrite"]
    required = {
        "retriever": "hybrid",
        "retrieverImplementation": "com.example.agent.rag.HybridKnowledgeRetriever",
        "candidateTopK": 5,
        "outputTopK": 5,
        "category": "mysql",
        "visibility": "public",
        "sourceFilter": None,
        "similarityThreshold": 0.0,
        "configuredCollection": collection,
        "indexVerification": "LOCAL_STATE_ONLY_NOT_LIVE_QDRANT_VERIFICATION",
    }
    require(
        all(k in retrieval and retrieval[k] == v for k, v in required.items()),
        "正式 Hybrid 配置不符合本轮固定条件",
    )
    require(retrieval["indexStatePresent"] is True, "索引状态缺失")

    for key in ("corpusSha256", "indexStateSha256"):
        require(fingerprint(retrieval[key]), f"非法指纹：{key}")

    for key in (
        "knowledgeVersion",
        "configuredEmbeddingModel",
        "configuredEmbeddingRevision",
    ):
        require(text(retrieval[key]), f"缺少配置：{key}")

    require(
        retrieval["fusion"] == {
            "engine": "rrf",
            "rankConstant": 60,
            "weights": [1, 1],
            "tieBreak": "chunkId-asc",
        },
        "RRF 配置变化",
    )

    bm25 = retrieval["bm25"]
    require(
        all(bm25[k] == v for k, v in {
            "engine": "lucene",
            "analyzer": "SmartChineseAnalyzer",
            "queryOperator": "SHOULD",
            "k1": 1.2,
            "b": 0.75,
        }.items())
        and text(bm25["luceneVersion"]),
        "BM25 配置不完整或变化",
    )

    inventory = retrieval["sourceInventory"]
    require(
        isinstance(inventory, list)
        and bool(inventory)
        and retrieval["corpusDocumentCount"] == len(inventory),
        "语料清单无效",
    )
    sources = set()
    for item in inventory:
        require(
            text(item["source"])
            and item["source"] not in sources
            and text(item["title"])
            and fingerprint(item["contentSha256"])
            and item["category"] == "mysql"
            and item["visibility"] == "public",
            "来源身份、范围或指纹无效",
        )
        sources.add(item["source"])

    require(case["expected"]["source"] in sources, "目标来源不在声明语料中")

    require(
        text(rewrite_config["model"])
        and rewrite_config["promptVersion"] == "query-rewrite-v1"
        and rewrite_config["modelIdentity"] == "PROVIDER_ALIAS_ONLY",
        "改写模型或 Prompt 身份不完整",
    )
    for key in (
        "maxInputChars",
        "maxQueryChars",
        "maxResponseChars",
        "readTimeoutMs",
    ):
        require(
            type(rewrite_config[key]) is int and rewrite_config[key] > 0,
            f"非法改写配置：{key}",
        )
    require(text(rewrite_config["protectedTokenPattern"]), "缺少标识保护规则")

    rewrite = pair["rewrite"]
    trace, effective = rewrite["trace"], rewrite["query"]
    state = trace["status"]
    require(state in STATES and text(effective), "非法改写状态或有效查询")
    require(
        trace["effectiveQuery"] == effective
        and trace["model"] == rewrite_config["model"]
        and trace["promptVersion"] == rewrite_config["promptVersion"],
        "改写 Trace 与配置/查询不一致",
    )

    if state in ("APPLIED", "UNCHANGED"):
        require(trace["reason"] is None, "正常改写状态不应带失败原因")
    else:
        require(text(trace["reason"]), "跳过或回退必须记录原因")

    # 按后端契约，只有 APPLIED 会使用不同于原文的有效查询。
    same = effective == case["query"]
    require((state == "APPLIED") == (not same), "状态与查询变化不一致")
    require(pair["sameQueryReused"] is same, "相同查询复用标记错误")

    order = ["original"] if same else (
        ["rewritten", "original"] if rewrite_first else ["original", "rewritten"]
    )
    require(pair["retrievalOrder"] == order, "实际检索顺序与请求不一致")

    identities = {}
    for side, expected_query in (
        ("original", case["query"]),
        ("rewritten", effective),
    ):
        branch = pair[side]
        require(branch["query"] == expected_query, "分支查询不匹配")
        require(baseline.finite_nonnegative(branch["costMs"]), "检索耗时非法")

        result = branch["result"]
        hits = result["chunks"]
        require(
            result["retriever"] == "hybrid"
            and isinstance(hits, list)
            and len(hits) <= case["topK"],
            "检索器或候选数量错误",
        )
        require(result["matched"] is bool(hits), "matched 与候选不一致")

        ids = set()
        for hit in hits:
            require(
                all(text(hit[k]) for k in (
                    "chunkId", "source", "section", "content"
                ))
                and hit["source"] in sources
                and hit["chunkId"] not in ids
                and baseline.finite_nonnegative(hit["score"]),
                "候选字段、ID 或分数非法",
            )
            ids.add(hit["chunkId"])

            # 两侧候选可以不同，但相同 ID 必须指向相同内容。
            identity = {
                k: hit[k] for k in ("source", "title", "section", "content")
            }
            require(
                hit["chunkId"] not in identities
                or identities[hit["chunkId"]] == identity,
                "两侧同一 chunkId 的正文或元数据不同",
            )
            identities[hit["chunkId"]] = identity

    require(
        baseline.finite_nonnegative(rewrite["costMs"])
        and baseline.finite_nonnegative(pair["rewrittenStageCostMs"]),
        "改写耗时非法",
    )
    require(
        math.isclose(
            pair["rewrittenStageCostMs"],
            rewrite["costMs"] + pair["rewritten"]["costMs"],
            rel_tol=1e-9,
            abs_tol=1e-6,
        ),
        "改写阶段耗时求和不一致",
    )
    if same:
        require(
            pair["original"] == pair["rewritten"],
            "相同查询未完整复用结果与耗时",
        )

def score_pair(pair, case):
    # 两侧使用完全相同的标注，复用已有评分口径。
    return {
        side: baseline.score_hits(
            pair[side]["result"]["chunks"],
            case["expected"],
            "chunk",
            case["topK"],
        )
        for side in SIDES
    }


def metric_summary(rows, planned):
    """失败/未完成不会获得命中分，但仍单独保留，不能伪装成正常 MISS。"""
    return {
        side: {
            name: {
                "allPlanned": (
                    sum(r["scores"][side][name] for r in rows) / planned
                    if planned else None
                ),
                "successfulOnly": (
                    sum(r["scores"][side][name] for r in rows) / len(rows)
                    if rows else None
                ),
            }
            for name in METRICS
        }
        for side in SIDES
    }


def summarize(rows, planned, warmup_ok, interrupted=False):
    ok = [r for r in rows if r["status"] == "SUCCESS"]
    errors = [r for r in rows if r["status"] == "ERROR"]
    applied = [
        r for r in ok
        if r["response"]["rewrite"]["trace"]["status"] == "APPLIED"
    ]

    changes = {"improved": [], "worsened": [], "unchanged": []}
    for row in ok:
        delta = (
            row["scores"]["rewritten"]["evidenceReciprocalRankAtK"]
            - row["scores"]["original"]["evidenceReciprocalRankAtK"]
        )
        key = "improved" if delta > 0 else "worsened" if delta < 0 else "unchanged"
        changes[key].append(row["caseId"])

    states = Counter(
        r["response"]["rewrite"]["trace"]["status"] for r in ok
    )
    status = (
        "PARTIAL" if interrupted
        else "PRECHECK_FAILED" if not warmup_ok
        else "PARTIAL" if len(rows) < planned
        else "COMPLETED_WITH_ERRORS" if errors
        else "COMPLETED"
    )

    latency = {
        "originalRetrieval": baseline.latency([
            r["response"]["original"]["costMs"] for r in ok
        ]),
        "rewriteCall": baseline.latency([
            r["response"]["rewrite"]["costMs"] for r in ok
        ]),
        "rewrittenRetrieval": baseline.latency([
            r["response"]["rewritten"]["costMs"] for r in ok
        ]),
        "rewrittenStages": baseline.latency([
            r["response"]["rewrittenStageCostMs"] for r in ok
        ]),
        "pairHttp": baseline.latency([
            r["clientCostMs"] for r in ok
        ]),
    }

    return {
        "status": status,
        # 表示实验执行完整，不表示改写有效，也不要求每题都 APPLIED。
        "comparisonReady": (
            warmup_ok and not interrupted and len(ok) == planned
        ),
        "plannedPairs": planned,
        "succeededPairs": len(ok),
        "errorPairs": len(errors),
        "notCompletedPairs": planned - len(rows),
        "errorCaseIds": [r["caseId"] for r in errors],
        "metrics": metric_summary(ok, planned),
        "rewriteStateDenominator": len(ok),
        "rewriteStates": {state: states[state] for state in STATES},
        "sameQueryReusedPairs": sum(
            r["response"]["sameQueryReused"] for r in ok
        ),
        "pairedMrrChanges": changes,
        "appliedSubset": {
            "count": len(applied),
            "metrics": metric_summary(applied, len(applied)),
        },
        "latencySuccessMs": latency,
        "latencyErrorMs": baseline.latency([
            r["clientCostMs"] for r in errors
        ]),
    }


def cell(value):
    return str(value).replace("|", "\\|").replace("\n", " ").replace("\r", " ")


def report_text(summary, rows):
    lines = [
        "# 查询改写成对评测",
        "",
        f"状态：{summary['status']}；comparisonReady={summary['comparisonReady']}。",
        "",
        "| 策略 | SourceHit@1 | EvidenceHit@5 | EvidenceMRR@5 |",
        "|---|---|---|---|",
    ]
    for side in SIDES:
        values = [
            summary["metrics"][side][name]["allPlanned"]
            for name in METRICS
        ]
        lines.append(
            f"| {side} | " + " | ".join(f"{v:.4f}" for v in values) + " |"
        )

    lines += [
        "",
        f"改写状态：{summary['rewriteStates']}。",
        f"有效配对数：{summary['rewriteStateDenominator']}；"
        f"相同查询复用：{summary['sameQueryReusedPairs']}。",
        "",
        "## 耗时（成功配对，毫秒）",
        "",
        "| 环节 | 样本数 | Mean | P50 | P95 |",
        "|---|---|---|---|---|",
    ]
    for name, stats in summary["latencySuccessMs"].items():
        lines.append(
            f"| {name} | {stats['count']} | {stats['mean']} "
            f"| {stats['p50']} | {stats['p95']} |"
        )

    lines += [
        "",
        "## 逐题定位",
        "",
        "| 用例 | 执行 | 改写状态 | 原证据排名 | 新证据排名 | ΔRR |",
        "|---|---|---|---|---|---|",
    ]
    for row in rows:
        if row["status"] == "ERROR":
            lines.append(
                f"| {cell(row['caseId'])} | ERROR | — | — | — | — |"
            )
            continue

        old, new = (row["scores"][side] for side in SIDES)
        delta = (
            new["evidenceReciprocalRankAtK"]
            - old["evidenceReciprocalRankAtK"]
        )
        lines.append(
            f"| {cell(row['caseId'])} | SUCCESS | "
            f"{row['response']['rewrite']['trace']['status']} | "
            f"{old['firstEvidenceRank']} | {new['firstEvidenceRank']} | "
            f"{delta:+.4f} |"
        )

    lines += [
        "",
        "## 解释边界",
        "",
        "- 主表分母为全部计划题数；ERROR/未完成不是正常未命中。",
        "- comparisonReady=false 时，不据此宣布策略优劣。",
        "- rewritten 是包含回退和跳过的实际策略；APPLIED 子集另见 summary.json，不能代表全体收益。",
        "- None 排名表示本次正常返回的 Top5 没有匹配标注证据。",
        "- 评分要求来源、章节和证据文本匹配，不证明最终答案正确或引用充分。",
        "- rewrittenStages 为改写与检索之和；pairHttp 包含两侧实验工作，不是线上 Agent 耗时。",
        "- 同查询复用的两侧耗时来自同一次检索，不是独立性能样本。",
        "- 预热不计分；单轮小样本仍受缓存和服务波动影响，不能认定稳定收益。",
        "- 本地 Git/文件哈希不证明服务端部署身份；索引状态哈希不等于在线 Qdrant 全量校验。",
        "- 原查询、有效查询、回退原因、候选正文和错误信息保留在 details.jsonl。",
    ]
    return "\n".join(lines) + "\n"

def run(args):
    cases = baseline.load_cases(args.dataset)
    require(
        len({c["datasetVersion"] for c in cases}) == 1,
        "数据集版本必须一致",
    )
    require(
        all(
            c["topK"] == 5
            and len(c["query"].encode("utf-16-le")) // 2 <= 2000
            for c in cases
        ),
        "本轮要求 TopK=5 且查询不超过 2000 个 UTF-16 单位",
    )
    require(
        math.isfinite(args.timeout_seconds) and args.timeout_seconds > 0,
        "timeout 必须为正有限数",
    )

    # 在生成结果目录前记录工作区，避免本轮新目录污染初始 Git 状态。
    identity = baseline.local_identity()
    run_id = (
        datetime.now().strftime("%Y%m%d-%H%M%S")
        + "-m6-query-rewrite-pair-"
        + uuid.uuid4().hex[:8]
    )
    directory = args.results_root.resolve() / run_id
    directory.mkdir(parents=True, exist_ok=False)

    settings = baseline.ROOT / "src/main/resources/application.yml"
    manifest = {
        "runId": run_id,
        "createdAt": datetime.now().astimezone().isoformat(),
        "evaluatorVersion": VERSION,
        "datasetVersion": cases[0]["datasetVersion"],
        "datasetSha256": baseline.sha256(args.dataset),
        "plannedPairs": len(cases),
        "baseUrl": args.base_url,
        "endpoint": ENDPOINT,
        "topK": 5,
        "expectedCollection": args.expected_collection,
        "timeoutSeconds": args.timeout_seconds,
        "retrievalOrderPolicy": "alternating; same-query reuses one retrieval",
        "runnerWorkspace": identity,
        "runnerApplicationYmlSha256": (
            baseline.sha256(settings) if settings.is_file() else None
        ),
        "warmup": None,
        "observedConfiguration": None,
    }
    baseline.write_json(directory / "manifest.json", manifest)
    rows, frozen, warmup, interrupted = [], None, None, False

    def attempt(case, rewrite_first):
        nonlocal frozen
        row = {
            "caseId": case["id"],
            "query": case["query"],
            "expected": case["expected"],
            "rewriteFirst": rewrite_first,
            "requestedTopK": case["topK"],
        }
        started = time.perf_counter()
        try:
            pair = request_pair(
                args.base_url,
                case,
                rewrite_first,
                args.timeout_seconds,
            )
            row.update(
                response=pair,
                clientCostMs=(time.perf_counter() - started) * 1000,
            )

            # 预热冻结本轮配置；之后任何配置变化都停止本轮。
            if (
                frozen is not None
                and isinstance(pair, dict)
                and frozen != pair.get("configuration")
            ):
                raise ConfigurationDrift(
                    "本轮服务端配置或语料/索引指纹发生变化"
                )

            validate_pair(pair, case, args.expected_collection, rewrite_first)
            if frozen is None:
                frozen = pair["configuration"]

            row.update(status="SUCCESS", scores=score_pair(pair, case))
        except (OSError, ValueError, KeyError, TypeError) as error:
            # 失败仍保留真实观察耗时，不写成 0，也不伪造评分。
            row.setdefault(
                "clientCostMs",
                (time.perf_counter() - started) * 1000,
            )
            row.update(
                status="ERROR",
                scores=None,
                errorType=type(error).__name__,
                error=str(error),
            )
        return row

    try:
        warmup = attempt(cases[0], False)
        manifest["warmup"] = warmup
        baseline.write_json(directory / "manifest.json", manifest)
        print(f"Warmup pair: {warmup['status']} (excluded from metrics)")

        with (directory / "details.jsonl").open("w", encoding="utf-8") as output:
            # 预热失败就停止，先修复地址、配置或依赖，不发送整批请求。
            if warmup["status"] == "SUCCESS":
                for index, case in enumerate(cases):
                    # 交替检索执行顺序，减轻固定先后顺序带来的偏差。
                    row = attempt(case, bool(index % 2))
                    rows.append(row)
                    output.write(json.dumps(row, ensure_ascii=False) + "\n")
                    output.flush()
                    print(
                        f"[{index + 1}/{len(cases)}] "
                        f"{case['id']}: {row['status']}"
                    )
                    if row.get("errorType") == "ConfigurationDrift":
                        break
    except KeyboardInterrupt:
        interrupted = True
    finally:
        # 中断也写出已完成结果；未完成项不会被悄悄删除出分母。
        (directory / "details.jsonl").touch(exist_ok=True)
        warmup_ok = warmup is not None and warmup["status"] == "SUCCESS"
        summary = summarize(rows, len(cases), warmup_ok, interrupted)
        summary.update(runId=run_id, evaluatorVersion=VERSION)

        manifest.update(
            status=summary["status"],
            observedConfiguration=frozen,
            finishedAt=datetime.now().astimezone().isoformat(),
        )
        baseline.write_json(directory / "manifest.json", manifest)
        baseline.write_json(directory / "summary.json", summary)
        (directory / "report.md").write_text(
            report_text(summary, rows),
            encoding="utf-8",
        )

    return directory, summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8088")
    parser.add_argument(
        "--dataset",
        type=Path,
        default=baseline.ROOT / "evaluation/rag/datasets/retrieval-seed-v1.jsonl",
    )
    parser.add_argument(
        "--results-root",
        type=Path,
        default=baseline.ROOT / "evaluation/rag/results",
    )
    parser.add_argument(
        "--expected-collection",
        default="ai_agent_knowledge_managed_v1",
    )
    parser.add_argument("--timeout-seconds", type=float, default=60)
    args = parser.parse_args()

    directory, summary = run(args)
    print(
        f"Results: {directory}\n"
        f"Status: {summary['status']}; "
        f"comparisonReady={summary['comparisonReady']}"
    )
    return 0 if summary["comparisonReady"] else 2


if __name__ == "__main__":
    raise SystemExit(main())