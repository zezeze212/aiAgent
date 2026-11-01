"""M6-3B：一次召回、同一候选并集、两种排序；只评检索，不生成答案。"""
from __future__ import annotations

import argparse
import json
import math
import time
import uuid
from datetime import datetime
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import run_baseline_eval as baseline

VERSION = "rag-rerank-pair-evaluator-v1"
ENDPOINT = "/rag/benchmark/rerank-pair"
STRATEGIES = ("hybrid", "rerank")
IDENTITY_FIELDS = ("chunkId", "source", "title", "section", "content")


def request_pair(base_url, case, timeout):
    # 标准答案仅用于本地评分，不传给召回或重排服务。
    params = urlencode({"query": case["query"], "topK": case["topK"]})
    request = Request(base_url.rstrip("/") + ENDPOINT + "?" + params,
                      headers={"Accept": "application/json"})
    with urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if not isinstance(payload, dict) or payload.get("code") != 200:
        raise ValueError(
            f"Pair request failed: "
            f"{payload.get('code') if isinstance(payload, dict) else 'invalid envelope'}"
        )
    if not isinstance(payload.get("data"), dict):
        raise ValueError("Missing pair response data")
    return payload["data"]


def identity(hit):
    return tuple(hit.get(key) for key in IDENTITY_FIELDS)


def indexed_hits(hits):
    if not isinstance(hits, list):
        raise ValueError("Candidate list is missing")
    result = {}
    for hit in hits:
        if not isinstance(hit, dict) or any(
            not isinstance(hit.get(key), str) or not hit[key].strip()
            for key in ("chunkId", "source", "section", "content")
        ):
            raise ValueError("Invalid candidate identity/content")
        if hit["chunkId"] in result:
            raise ValueError("Duplicate candidate ID")
        result[hit["chunkId"]] = hit
    return result


def finite_score(value):
    # 分数不是耗时；不假定所有模型分数一定非负或落在 [0, 1]。
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(value)
    )


def validate_pair(pair, case, collection):
    if pair.get("schemaVersion") != "rag-rerank-pair-v1":
        raise ValueError("Unexpected pair schema")
    old = pair["baseline"]
    baseline.validate_response(old, "hybrid", case, collection)
    diag = old["diagnostics"]
    allowed = {
        item["source"]
        for item in old["configuration"]["sourceInventory"]
    }

    # 从原始分支独立重建并集与 RRF 分数，不只相信候选总数相等。
    union, rrf_scores = {}, {}
    for branch in ("denseHits", "bm25Hits"):
        indexed_hits(diag[branch])
        for rank, hit in enumerate(diag[branch], 1):
            key = hit["chunkId"]
            if hit["source"] not in allowed:
                raise ValueError("Candidate outside declared corpus")
            if key in union and identity(union[key]) != identity(hit):
                raise ValueError("Same ID has different content/metadata")
            union[key] = hit
            rrf_scores[key] = (
                rrf_scores.get(key, 0.0) + 1.0 / (60 + rank)
            )

    candidates = pair["candidates"]
    candidate_map = indexed_hits(candidates)
    if list(candidate_map) != sorted(union):
        raise ValueError(
            "Candidates must be the complete union in chunkId order"
        )
    for key, hit in candidate_map.items():
        if (
            identity(hit) != identity(union[key])
            or hit.get("score") is not None
        ):
            raise ValueError(
                "Candidate content changed or old score leaked"
            )

    expected_rrf = sorted(
        union, key=lambda key: (-rrf_scores[key], key)
    )[:case["topK"]]
    if [h["chunkId"] for h in old["hits"]] != expected_rrf:
        raise ValueError("Baseline is not the expected RRF TopK")
    for hit in old["hits"]:
        key = hit["chunkId"]
        if (
            identity(hit) != identity(union[key])
            or not finite_score(hit.get("score"))
            or not math.isclose(
                hit["score"], rrf_scores[key],
                rel_tol=1e-9, abs_tol=1e-12
            )
        ):
            raise ValueError("Baseline content or RRF score mismatch")

    rerank = pair["rerank"]
    ranking = rerank["ranking"]
    reranked = indexed_hits(ranking)
    if set(reranked) != set(union):
        raise ValueError("Reranker lost or introduced candidates")
    for key, hit in reranked.items():
        if (
            identity(hit) != identity(union[key])
            or not finite_score(hit.get("score"))
        ):
            raise ValueError(
                "Reranker changed content or returned invalid score"
            )
    if ranking != sorted(
        ranking, key=lambda hit: (-hit["score"], hit["chunkId"])
    ):
        raise ValueError("Rerank order/tie-break mismatch")

    # 逐项检查计时，防止缺失耗时被默认成 0，或者更改统计范围。
    times = [
        diag[key]
        for key in ("denseCostMs", "bm25CostMs", "fusionCostMs")
    ]
    times.append(rerank["costMs"])
    if not all(baseline.finite_nonnegative(value) for value in times):
        raise ValueError("Invalid stage latency")
    if not math.isclose(
        old["costMs"], sum(times[:3]),
        rel_tol=1e-9, abs_tol=1e-6
    ):
        raise ValueError("Hybrid timing scope mismatch")

    config = pair["rerankerConfiguration"]
    required = {
        "provider": "siliconflow",
        "model": "BAAI/bge-reranker-v2-m3",
        "documentInput": "KnowledgeChunk.content",
        "returnAllCandidates": True,
        "tieBreak": "chunkId-asc",
        "applicationRetries": 0,
        "modelRevisionVerification": "PROVIDER_ALIAS_ONLY",
        "maxChunksPerDoc": 1024,
        "overlapTokens": 80,
    }
    if not isinstance(config, dict) or any(
        config.get(k) != v for k, v in required.items()
    ):
        raise ValueError("Unexpected reranker configuration")


def score_pair(pair, case):
    # 质量指标只看各自最终 TopK；完整并集仅用于候选覆盖诊断。
    return {
        "hybrid": baseline.score_hits(
            pair["baseline"]["hits"],
            case["expected"], "chunk", case["topK"]
        ),
        "rerank": baseline.score_hits(
            pair["rerank"]["ranking"][:case["topK"]],
            case["expected"], "chunk", case["topK"]
        ),
    }


def summarize(rows, planned, warmup_ok, interrupted=False):
    ok = [row for row in rows if row["status"] == "SUCCESS"]
    errors = [row for row in rows if row["status"] == "ERROR"]
    not_run = planned - len(rows)
    metrics = {}
    for strategy in STRATEGIES:
        metrics[strategy] = {}
        for name in baseline.METRICS:
            total = sum(row["scores"][strategy][name] for row in ok)
            metrics[strategy][name] = {
                "sum": total,
                "plannedDenominator": planned,
                "allPlanned": total / planned,
                "successfulOnly": total / len(ok) if ok else None,
            }

    deltas = [
        row["scores"]["rerank"]["evidenceReciprocalRankAtK"]
        - row["scores"]["hybrid"]["evidenceReciprocalRankAtK"]
        for row in ok
    ]
    return {
        "status": (
            "PARTIAL" if interrupted or not_run
            else "COMPLETED_WITH_ERRORS" if errors
            else "COMPLETED"
        ),
        "comparisonReady": (
            warmup_ok and not interrupted and len(ok) == planned
        ),
        "plannedPairs": planned,
        "succeededPairs": len(ok),
        "errorPairs": len(errors),
        "notRunPairs": not_run,
        "errorCaseIds": [row["caseId"] for row in errors],
        "metrics": metrics,
        "pairedMrrChanges": {
            "improved": sum(d > 0 for d in deltas),
            "worsened": sum(d < 0 for d in deltas),
            "unchanged": sum(d == 0 for d in deltas),
        },
        "latencySuccessMs": {
            "hybridStages": baseline.latency([
                r["response"]["baseline"]["costMs"] for r in ok
            ]),
            "rerankIncrement": baseline.latency([
                r["response"]["rerank"]["costMs"] for r in ok
            ]),
            "pairHttp": baseline.latency([
                r["clientCostMs"] for r in ok
            ]),
        },
        "latencyErrorMs": baseline.latency([
            r["clientCostMs"] for r in errors
        ]),
    }


def report_text(summary, rows):
    lines = [
        "# RRF / Reranker 固定候选评测", "",
        f"状态：{summary['status']}；"
        f"comparisonReady={summary['comparisonReady']}。", "",
        "| 策略 | SourceHit@1 | EvidenceHit@5 | EvidenceMRR@5 |",
        "|---|---|---|---|",
    ]
    for strategy in STRATEGIES:
        values = [
            summary["metrics"][strategy][key]["allPlanned"]
            for key in (
                "sourceHitAt1", "evidenceHitAtK",
                "evidenceReciprocalRankAtK"
            )
        ]
        lines.append(
            f"| {strategy} | "
            + " | ".join(f"{v:.4f}" for v in values) + " |"
        )
    lines += [
        "",
        "分母为全部计划查询；失败保留为 ERROR，不视为正常未命中。",
        "comparisonReady=false 时，不据此宣布排序方案优劣。",
        "",
        "耗时统计见 summary.json：hybridStages 为原检索阶段之和；",
        "rerankIncrement 为新增重排适配耗时；"
        "pairHttp 为一次完整成对请求耗时。",
        "pairHttp 不能拆成两个独立策略的 HTTP 耗时。"
        "单轮 15 题不证明稳定性能或泛化收益。",
        "",
        "| 用例 | 候选数 | 并集有目标证据 | RRF 证据排名 | Rerank 证据排名 |",
        "|---|---|---|---|---|",
    ]
    for row in rows:
        if row["status"] == "SUCCESS":
            ranks = [
                row["scores"][s]["firstEvidenceRank"]
                for s in STRATEGIES
            ]
            lines.append(
                f"| {row['caseId']} | "
                f"{len(row['response']['candidates'])} | "
                f"{row['candidateEvidenceHit']} | "
                f"{ranks[0]} | {ranks[1]} |"
            )
        else:
            lines.append(
                f"| {row['caseId']} | ERROR | — | — | — |"
            )
    return "\n".join(lines) + "\n"


def run(args):
    cases = baseline.load_cases(args.dataset)
    versions = {case["datasetVersion"] for case in cases}
    if len(versions) != 1 or any(
        case["topK"] != 5 for case in cases
    ):
        raise ValueError(
            "This experiment requires one dataset version and TopK=5"
        )

    run_id = (
        datetime.now().strftime("%Y%m%d-%H%M%S")
        + "-m6-rerank-pair-" + uuid.uuid4().hex[:8]
    )
    directory = args.results_root.resolve() / run_id
    directory.mkdir(parents=True, exist_ok=False)
    manifest = {
        "runId": run_id,
        "evaluatorVersion": VERSION,
        "createdAt": datetime.now().astimezone().isoformat(),
        "datasetVersion": next(iter(versions)),
        "datasetSha256": baseline.sha256(args.dataset),
        "baseUrl": args.base_url,
        "endpoint": ENDPOINT,
        "plannedPairs": len(cases),
        "topK": 5,
        "expectedCollection": args.expected_collection,
        "candidatePolicy":
            "same-request-dense5-bm25-5-union-chunkId-asc",
        "runnerWorkspace": baseline.local_identity(),
    }
    frozen = None
    rows, warmup, interrupted = [], None, False
    baseline.write_json(directory / "manifest.json", manifest)

    def attempt(case):
        nonlocal frozen
        row = {
            "caseId": case["id"],
            "query": case["query"],
            "expected": case["expected"],
        }
        started = time.perf_counter()
        try:
            pair = request_pair(
                args.base_url, case, args.timeout_seconds
            )
            row.update(
                response=pair,
                clientCostMs=(time.perf_counter() - started) * 1000
            )
            validate_pair(pair, case, args.expected_collection)

            config = {
                "hybrid": pair["baseline"]["configuration"],
                "reranker": pair["rerankerConfiguration"],
            }
            if frozen is not None and config != frozen:
                raise ValueError(
                    "Server configuration changed during this run"
                )
            frozen = config

            row["scores"] = score_pair(pair, case)
            row["candidateEvidenceHit"] = baseline.score_hits(
                pair["candidates"],
                case["expected"],
                "chunk",
                len(pair["candidates"]),
            )["evidenceHitAtK"]
            row["status"] = "SUCCESS"

        except (OSError, ValueError, KeyError, TypeError) as error:
            # 整对失败，不保留一侧成功制造不同样本集合的比较。
            row.setdefault(
                "clientCostMs",
                (time.perf_counter() - started) * 1000
            )
            row.update(
                status="ERROR",
                errorType=type(error).__name__,
                error=str(error),
                scores=None,
            )
        return row

    try:
        warmup = attempt(cases[0])
        manifest["warmup"] = warmup
        baseline.write_json(directory / "manifest.json", manifest)
        print(
            f"Warmup pair: {warmup['status']} "
            "(excluded from metrics)"
        )

        with (directory / "details.jsonl").open(
            "w", encoding="utf-8"
        ) as output:
            for index, case in enumerate(cases, 1):
                row = attempt(case)
                rows.append(row)
                output.write(
                    json.dumps(row, ensure_ascii=False) + "\n"
                )
                output.flush()
                print(
                    f"[{index}/{len(cases)}] "
                    f"{case['id']}: {row['status']}"
                )

    except KeyboardInterrupt:
        interrupted = True

    finally:
        summary = summarize(
            rows,
            len(cases),
            warmup is not None and warmup["status"] == "SUCCESS",
            interrupted,
        )
        summary.update(runId=run_id, evaluatorVersion=VERSION)
        manifest.update(
            observedConfigurations=frozen,
            status=summary["status"],
            finishedAt=datetime.now().astimezone().isoformat(),
        )
        baseline.write_json(directory / "manifest.json", manifest)
        baseline.write_json(directory / "summary.json", summary)
        (directory / "report.md").write_text(
            report_text(summary, rows), encoding="utf-8"
        )

    return directory, summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8088")
    parser.add_argument(
        "--dataset", type=Path,
        default=baseline.ROOT
                / "evaluation/rag/datasets/retrieval-seed-v1.jsonl",
    )
    parser.add_argument(
        "--results-root", type=Path,
        default=baseline.ROOT / "evaluation/rag/results",
    )
    parser.add_argument(
        "--expected-collection",
        default="ai_agent_knowledge_managed_v1",
    )
    parser.add_argument("--timeout-seconds", type=float, default=60)
    args = parser.parse_args()
    if (
        not math.isfinite(args.timeout_seconds)
        or args.timeout_seconds <= 0
    ):
        parser.error("timeout must be positive and finite")

    directory, summary = run(args)
    print(
        f"Results: {directory}\n"
        f"Status: {summary['status']}; "
        f"comparisonReady={summary['comparisonReady']}"
    )
    return 0 if summary["comparisonReady"] else 2


if __name__ == "__main__":
    raise SystemExit(main())