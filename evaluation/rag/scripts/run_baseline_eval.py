"""M6-0: compare the real Java rule/dense retrievers, without generation.

Reuses the M5 dataset contract and evidence matcher. Python 3.10+, stdlib only.
Metadata describes the running server configuration; local Git/code hashes are
labelled as runner-workspace identity, not proof of the server's deployed code.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import subprocess
import time
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from run_retrieval_eval import load_cases, find_first_relevant_rank

ROOT = Path(__file__).resolve().parents[3]
VERSION = "rag-baseline-evaluator-v3"
STRATEGIES = ("rule", "dense")
ENDPOINT = "/rag/benchmark/search"
METRICS = ("sourceHitAt1", "sourceHitWithinRawK", "evidenceAvailable", "evidenceHitAtK", "evidenceReciprocalRankAtK")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def build_url(base_url: str, strategy: str, case: dict[str, Any]) -> str:
    # Never send expected.source/section/evidence as retrieval hints.
    params = {"strategy": strategy, "query": case["query"], "topK": case["topK"]}
    return base_url.rstrip("/") + ENDPOINT + "?" + urlencode(params)


def request_search(base_url: str, strategy: str, case: dict[str, Any], timeout: float) -> dict[str, Any]:
    request = Request(build_url(base_url, strategy, case), headers={"Accept": "application/json"})
    with urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if not isinstance(payload, dict) or payload.get("code") != 200:
        code = payload.get("code") if isinstance(payload, dict) else None
        message = payload.get("message") if isinstance(payload, dict) else "invalid response"
        raise ValueError(f"Backend business error: code={code}, message={message}")
    data = payload.get("data")
    if not isinstance(data, dict):
        raise ValueError("Response data must be an object")
    return data


def finite_nonnegative(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0


def validate_response(data: dict[str, Any], strategy: str, case: dict[str, Any], expected_collection: str) -> None:
    unit = "document" if strategy == "rule" else "chunk"
    effective_k = 1 if strategy == "rule" else case["topK"]
    if (data.get("schemaVersion") != "rag-benchmark-response-v1" or data.get("strategy") != strategy
            or data.get("resultUnit") != unit or data.get("query") != case["query"].strip()
            or data.get("requestedTopK") != case["topK"] or data.get("effectiveTopK") != effective_k):
        raise ValueError("Response strategy/unit/query/TopK contract mismatch")
    if not finite_nonnegative(data.get("costMs")):
        raise ValueError("Missing/invalid costMs; it must not silently become 0 ms")
    hits = data.get("hits")
    if not isinstance(hits, list) or len(hits) > effective_k:
        raise ValueError("Invalid hits or more hits than effectiveTopK")
    ids = set()
    for hit in hits:
        if not isinstance(hit, dict) or any(not isinstance(hit.get(k), str) or not hit[k].strip()
                                           for k in ("chunkId", "source", "section", "content")):
            raise ValueError("Hit lacks chunkId/source/section/content")
        if hit["chunkId"] in ids:
            raise ValueError("Duplicate result ID would distort ranking")
        ids.add(hit["chunkId"])
    config = data.get("configuration")
    if not isinstance(config, dict):
        raise ValueError("Missing server configuration")
    if config.get("category") != "mysql" or config.get("visibility") != "public" or config.get("sourceFilter") is not None:
        raise ValueError("This seed baseline requires mysql/public and no answer-source filter")
    for name in ("knowledgeVersion", "corpusSha256"):
        if not isinstance(config.get(name), str) or not config[name]:
            raise ValueError(f"Missing server {name}")
    inventory = config.get("sourceInventory")
    if not isinstance(inventory, list) or not inventory or config.get("corpusDocumentCount") != len(inventory):
        raise ValueError("Missing corpus inventory")
    sources = [item.get("source") for item in inventory if isinstance(item, dict)]
    if len(sources) != len(inventory) or any(not isinstance(s, str) or not s for s in sources) or len(set(sources)) != len(sources):
        raise ValueError("Invalid corpus source identities")
    if case["expected"]["source"] not in sources or any(hit["source"] not in sources for hit in hits):
        raise ValueError("Target/returned source is outside the declared corpus")
    if strategy in ("dense", "hybrid"):
        if config.get("configuredCollection") != expected_collection:
            raise ValueError("Running server collection differs from --expected-collection")
        if config.get("similarityThreshold") != 0.0:
            raise ValueError("M6-0 frozen dense baseline requires threshold=0.0")
        if config.get("indexStatePresent") is not True or not config.get("indexStateSha256"):
            raise ValueError("Managed index state is missing; confirm the existing index state before comparison")
        if not config.get("configuredEmbeddingModel") or not config.get("configuredEmbeddingRevision"):
            raise ValueError("Missing configured embedding identity")
    if strategy == "hybrid":
        if (
            config.get("engine") != "rrf"
            or config.get("rrfRankConstant") != 60
            or config.get("rrfWeights") != [1, 1]
            or config.get("candidateTopK") != case["topK"]
            or config.get("outputTopK") != case["topK"]
            or config.get("tieBreak") != "chunkId-asc"
        ):
            raise ValueError("Unexpected hybrid baseline configuration")

        bm25_config = config.get("bm25Configuration")

        if (
            not isinstance(bm25_config, dict)
            or bm25_config.get("engine") != "lucene"
        ):
            raise ValueError("Missing BM25 branch configuration")

        for key in (
            "knowledgeVersion",
            "corpusSha256",
            "category",
            "visibility",
            "sourceFilter",
            "sourceInventory",
        ):
            if key not in bm25_config or bm25_config[key] != config[key]:
                raise ValueError("Hybrid branches corpus or scope mismatch")

        diagnostics = data.get("diagnostics")

        if not isinstance(diagnostics, dict):
            raise ValueError("Missing hybrid diagnostics")

        for key in ("denseHits", "bm25Hits", "ranking"):
            if not isinstance(diagnostics.get(key), list):
                raise ValueError(f"Missing hybrid {key}")

        if any(
            len(diagnostics[key]) > case["topK"]
            for key in ("denseHits", "bm25Hits")
        ):
            raise ValueError("Hybrid branch exceeds candidateTopK")

def score_hits(hits: list[dict[str, Any]], expected: dict[str, str], unit: str, raw_k: int) -> dict[str, Any]:
    candidates = hits[:raw_k]
    source = expected["source"]
    evidence_available = any(h["source"] == source and expected["evidence"] in h["content"] for h in candidates)
    rank = find_first_relevant_rank(candidates, expected) if unit == "chunk" else None
    return {
        "sourceHitAt1": bool(candidates and candidates[0]["source"] == source),
        "sourceHitWithinRawK": any(h["source"] == source for h in candidates),
        "evidenceAvailable": evidence_available,
        "evidenceHitAtK": (rank is not None) if unit == "chunk" else None,
        "evidenceReciprocalRankAtK": (1.0 / rank if rank else 0.0) if unit == "chunk" else None,
        "firstEvidenceRank": rank,
        "returnedCount": len(candidates),
        "distinctSourceCount": len({h["source"] for h in candidates}),
        "returnedCharsUtf16": sum(len(h["content"].encode("utf-16-le")) // 2 for h in candidates),
    }


def git_output(*args: str) -> str | None:
    try:
        return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=True, timeout=10).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None


def local_identity() -> dict[str, Any]:
    paths = [ROOT / "pom.xml"]
    for folder in ("src/main/java", "src/main/resources/docs/knowledge", "evaluation/rag/scripts"):
        paths.extend(p for p in (ROOT / folder).rglob("*") if p.is_file() and "__pycache__" not in p.parts)
    hashes = {p.relative_to(ROOT).as_posix(): sha256(p) for p in sorted(paths)}
    return {"gitHead": git_output("rev-parse", "HEAD"), "gitStatus": git_output("status", "--short"),
            "fileSha256": hashes, "meaning": "RUNNER_WORKSPACE_ONLY; not a server code identity assertion"}


def percentile(values: list[float], q: float) -> float | None:
    return round(sorted(values)[max(0, math.ceil(q * len(values)) - 1)], 3) if values else None


def latency(values: list[float]) -> dict[str, Any]:
    return {"count": len(values), "mean": round(sum(values) / len(values), 3) if values else None,
            "p50": percentile(values, .50), "p95": percentile(values, .95)}


def summarize(
    details: list[dict[str, Any]],
    case_count: int,
    strategies: tuple[str, ...] = STRATEGIES,
) -> dict[str, Any]:
    result = {}
    for strategy in strategies:
        rows = [d for d in details if d["strategy"] == strategy]
        ok = [d for d in rows if d["status"] == "SUCCESS"]
        errors = [d for d in rows if d["status"] == "ERROR"]
        metrics = {}
        for name in METRICS:
            if strategy == "rule" and name in ("evidenceHitAtK", "evidenceReciprocalRankAtK"):
                metrics[name] = None  # N/A, never zero or a fabricated document-ranking score.
            else:
                total = sum(d["scores"][name] for d in ok)
                metrics[name] = {"sum": total, "plannedDenominator": case_count,
                                 "allPlanned": total / case_count,
                                 "successfulOnly": total / len(ok) if ok else None}
        result[strategy] = {
            "planned": case_count, "attempted": len(rows), "succeeded": len(ok), "errors": len(errors),
            "notRun": case_count - len(rows), "metrics": metrics,
            "serverLatencySuccessMs": latency([d["response"]["costMs"] for d in ok]),
            "clientLatencySuccessMs": latency([d["clientCostMs"] for d in ok]),
            "clientLatencyErrorMs": latency([d["clientCostMs"] for d in errors]),
            "meanReturnedCharsUtf16": sum(d["scores"]["returnedCharsUtf16"] for d in ok) / len(ok) if ok else None,
            "errorCaseIds": [d["caseId"] for d in errors],
            "evidenceMissCaseIds": [d["caseId"] for d in ok if not d["scores"]["evidenceAvailable"]],
        }
    return result


def rate_cell(metric: dict[str, Any] | None) -> str:
    return "N/A" if metric is None else f"{metric['allPlanned']:.4f}"


def report_text(summary: dict[str, Any]) -> str:
    lines = ["# RAG 检索基线报告", "", f"运行状态：{summary['status']}。",
             f"所选策略运行完整且配置稳定：{summary['comparisonReady']}。仅检索，不含模型回答。", "",
             "| 策略 | 粒度 | 成功/计划 | 错误 | 未执行 | SourceHit@1 | EvidenceAvailable | EvidenceHit@5 | EvidenceMRR@5 |",
             "|---|---|---|---|---|---|---|---|---|"]
    for strategy, r in summary["strategies"].items():
        m = r["metrics"]
        cells = [rate_cell(m[n]) for n in ("sourceHitAt1", "evidenceAvailable", "evidenceHitAtK", "evidenceReciprocalRankAtK")]
        lines.append(f"| {strategy} | {'全文，最多1篇' if strategy == 'rule' else '片段，最多5块'} | "
                     f"{r['succeeded']}/{r['planned']} | {r['errors']} | {r['notRun']} | " + " | ".join(cells) + " |")
    lines += ["", "表中分母为全部计划查询；错误/未执行不会获得命中分，但在状态中单独保留，不当作正常 MISS。",
              "successfulOnly 指标及成功/失败延迟分别见 summary.json。含错误或未执行时，不据此宣布策略优劣。",
              "", "## 解释边界", "",
              "- rule 是全文返回，片段排名指标为 N/A。SourceHitWithinRawK 的原始 K/粒度不同，不作等预算胜负判断。",
              "- EvidenceAvailable 仅匹配来源与证据文本；不证明上下文选入或最终答案正确。",
              "- 15 个成功样本的 nearest-rank P95 是最大观测值；单轮不是稳定性能或容量结论。",
              "- 配置由服务端回显；本地索引状态哈希不是对在线 Qdrant 数据的完整核验。",
              "- 多策略运行时核对共同语料与范围；各策略配置在本次运行中冻结，配置漂移计为 ERROR。",
              "- server costMs 包围检索适配调用；clientCostMs 还包含元数据读取、HTTP 与响应读取。",
              "- 运行期间请勿改语料、重建索引或切换服务；本地工作区哈希不能证明服务器部署了同一代码。", "",
              "## 失败定位", ""]
    for strategy, r in summary["strategies"].items():
        lines.append(f"- {strategy} 错误：{r['errorCaseIds']}；成功请求中未提供目标证据：{r['evidenceMissCaseIds']}。")
    return "\n".join(lines) + "\n"


def run(args: argparse.Namespace) -> tuple[Path, dict[str, Any]]:
    strategies = tuple(getattr(args, "strategies", STRATEGIES))

    if (
        not strategies
        or len(set(strategies)) != len(strategies)
        or not set(strategies) <= {"rule", "dense", "bm25", "hybrid"}
    ):
        raise ValueError("Invalid or duplicate strategies")

    cases = load_cases(args.dataset)
    versions = {c["datasetVersion"] for c in cases}

    if len(versions) != 1:
        raise ValueError("Dataset versions must match")

    run_id = (
        datetime.now().strftime("%Y%m%d-%H%M%S")
        + "-m6-baseline-"
        + uuid.uuid4().hex[:8]
    )
    directory = args.results_root.resolve() / run_id
    directory.mkdir(parents=True, exist_ok=False)

    manifest = {
        "runId": run_id,
        "createdAt": datetime.now().astimezone().isoformat(),
        "evaluatorVersion": VERSION,
        "datasetVersion": next(iter(versions)),
        "datasetSha256": sha256(args.dataset),
        "baseUrl": args.base_url,
        "endpoint": ENDPOINT,
        "topK": 5,
        "plannedCasesPerStrategy": len(cases),
        "requestedStrategies": list(strategies),
        "strategyOrder": "alternating per case",
        "expectedCollection": args.expected_collection,
        "runnerWorkspace": local_identity(),
        "warmup": [],
        "observedConfigurations": {},
    }

    write_json(directory / "manifest.json", manifest)

    details: list[dict[str, Any]] = []
    frozen: dict[str, Any] = {}
    common: dict[str, Any] = {}
    interrupted = False

    def attempt(strategy: str, case: dict[str, Any]) -> dict[str, Any]:
        row: dict[str, Any] = {
            "caseId": case["id"],
            "strategy": strategy,
            "query": case["query"],
            "expected": case["expected"],
            "requestedTopK": case["topK"],
        }
        started = time.perf_counter()

        try:
            response = request_search(
                args.base_url, strategy, case, args.timeout_seconds
            )
            row["clientCostMs"] = (time.perf_counter() - started) * 1000
            row["response"] = response

            validate_response(
                response, strategy, case, args.expected_collection
            )

            config = response["configuration"]

            if strategy in frozen and frozen[strategy] != config:
                raise ValueError(
                    "Server configuration/index/corpus changed during this run"
                )

            shared = {
                key: config[key]
                for key in (
                    "knowledgeVersion",
                    "corpusSha256",
                    "category",
                    "visibility",
                    "sourceInventory",
                )
            }

            if common and common != shared:
                raise ValueError(
                    "Selected strategies corpus or scope mismatch"
                )

            common.update(shared)
            frozen[strategy] = config

            row["scores"] = score_hits(
                response["hits"],
                case["expected"],
                response["resultUnit"],
                response["effectiveTopK"],
            )
            row["status"] = "SUCCESS"

        except (HTTPError, URLError, TimeoutError, OSError, ValueError) as error:
            row.setdefault(
                "clientCostMs", (time.perf_counter() - started) * 1000
            )
            row.update(
                status="ERROR",
                errorType=type(error).__name__,
                error=str(error),
                scores=None,
            )

        return row

    try:
        for strategy in strategies:
            warmup = attempt(strategy, cases[0])
            manifest["warmup"].append(warmup)
            write_json(directory / "manifest.json", manifest)
            print(
                f"Warmup {strategy}: {warmup['status']} "
                "(excluded from metrics)"
            )

        with (directory / "details.jsonl").open(
            "w", encoding="utf-8"
        ) as output:
            for index, case in enumerate(cases):
                order = strategies if index % 2 == 0 else strategies[::-1]

                for strategy in order:
                    row = attempt(strategy, case)
                    details.append(row)
                    output.write(json.dumps(row, ensure_ascii=False) + "\n")
                    output.flush()

                    print(
                        f"[{index + 1}/{len(cases)}] "
                        f"{strategy} {case['id']}: {row['status']}"
                    )

    except KeyboardInterrupt:
        interrupted = True

    finally:
        stats = summarize(details, len(cases), strategies)

        complete = all(
            result["errors"] == 0 and result["notRun"] == 0
            for result in stats.values()
        )
        warmups_ok = (
            len(manifest["warmup"]) == len(strategies)
            and all(
                row["status"] == "SUCCESS"
                for row in manifest["warmup"]
            )
        )

        summary = {
            "runId": run_id,
            "evaluatorVersion": VERSION,
            "status": (
                "PARTIAL"
                if interrupted or any(
                    result["notRun"] for result in stats.values()
                )
                else "COMPLETED"
                if complete
                else "COMPLETED_WITH_ERRORS"
            ),
            "comparisonReady": complete and warmups_ok,
            "strategies": stats,
        }

        manifest["observedConfigurations"] = frozen
        manifest["finishedAt"] = datetime.now().astimezone().isoformat()
        manifest["status"] = summary["status"]

        write_json(directory / "manifest.json", manifest)
        write_json(directory / "summary.json", summary)
        (directory / "report.md").write_text(
            report_text(summary), encoding="utf-8"
        )

    return directory, summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8088")
    parser.add_argument(
        "--dataset",
        type=Path,
        default=ROOT / "evaluation/rag/datasets/retrieval-seed-v1.jsonl",
    )
    parser.add_argument(
        "--results-root",
        type=Path,
        default=ROOT / "evaluation/rag/results",
    )
    parser.add_argument(
        "--expected-collection",
        default="ai_agent_knowledge_managed_v1",
    )
    parser.add_argument("--timeout-seconds", type=float, default=30)
    parser.add_argument(
        "--strategies",
        nargs="+",
        choices=("rule", "dense", "bm25", "hybrid"),
        default=list(STRATEGIES),
    )

    args = parser.parse_args()

    if not math.isfinite(args.timeout_seconds) or args.timeout_seconds <= 0:
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
