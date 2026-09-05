"""Run the M5 dense retrieval seed evaluation against GET /rag/search.

This script evaluates only the Retriever:

1. Load and validate the frozen JSONL dataset.
2. Send one warm-up request, excluded from measured latency.
3. Call /rag/search sequentially for every case.
4. Match results by source, section and evidence text.
5. Calculate Recall@5, MRR and retrieval latency.
6. Save manifest.json, details.jsonl and summary.json.

It intentionally does not call /rag/ask because DeepSeek generation is not
part of the retrieval baseline.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import subprocess
import time
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


# 当前脚本位于 evaluation/rag/scripts，因此向上三级是项目根目录。
PROJECT_ROOT = Path(__file__).resolve().parents[3]

DEFAULT_DATASET_PATH = (
    PROJECT_ROOT
    / "evaluation"
    / "rag"
    / "datasets"
    / "retrieval-seed-v1.jsonl"
)

DEFAULT_RESULTS_ROOT = (
    PROJECT_ROOT
    / "evaluation"
    / "rag"
    / "results"
)


def parse_args() -> argparse.Namespace:
    """定义脚本支持的命令行参数。"""

    parser = argparse.ArgumentParser(
        description="Run the M5 dense retrieval seed evaluation."
    )

    parser.add_argument(
        "--base-url",
        default="http://localhost:8088",
        help="ai-agent-demo base URL.",
    )
    parser.add_argument(
        "--endpoint",
        default="/rag/search",
        help="Vector retrieval endpoint.",
    )
    parser.add_argument(
        "--dataset",
        type=Path,
        default=DEFAULT_DATASET_PATH,
        help="Retrieval JSONL dataset path.",
    )
    parser.add_argument(
        "--results-root",
        type=Path,
        default=DEFAULT_RESULTS_ROOT,
        help="Directory used to save evaluation results.",
    )
    parser.add_argument(
        "--timeout-seconds",
        type=int,
        default=30,
        help="Timeout for each HTTP request.",
    )
    parser.add_argument(
        "--embedding-model",
        default="bge-m3:latest",
        help="Embedding model version recorded in the manifest.",
    )
    parser.add_argument(
        "--collection",
        default="ai_agent_knowledge_v1",
        help="Qdrant collection recorded in the manifest.",
    )
    parser.add_argument(
        "--knowledge-version",
        default="m5-seed-v1",
        help="Indexed knowledge version recorded in the manifest.",
    )

    return parser.parse_args()


def load_cases(dataset_path: Path) -> list[dict[str, Any]]:
    """读取 JSONL，并执行最小数据契约校验。"""

    if not dataset_path.is_file():
        raise ValueError(f"Dataset does not exist: {dataset_path}")

    cases: list[dict[str, Any]] = []
    seen_ids: set[str] = set()

    with dataset_path.open("r", encoding="utf-8") as file:
        for line_number, raw_line in enumerate(file, start=1):
            line = raw_line.strip()

            if not line:
                continue

            try:
                case = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(
                    f"Invalid JSON at line {line_number}: {error}"
                ) from error

            validate_case(case, line_number)

            case_id = case["id"]

            if case_id in seen_ids:
                raise ValueError(f"Duplicate case id: {case_id}")

            seen_ids.add(case_id)
            cases.append(case)

    if not cases:
        raise ValueError("The retrieval dataset is empty.")

    return cases


def validate_case(case: dict[str, Any], line_number: int) -> None:
    """校验每条检索题是否包含运行和评分需要的字段。"""

    if case.get("schemaVersion") != "rag-retrieval-case-v1":
        raise ValueError(
            f"Unsupported schemaVersion at line {line_number}: "
            f"{case.get('schemaVersion')}"
        )

    for field_name in ("datasetVersion", "id", "query"):
        value = case.get(field_name)

        if not isinstance(value, str) or not value.strip():
            raise ValueError(
                f"{field_name} must be a non-empty string "
                f"at line {line_number}"
            )

    top_k = case.get("topK")

    if top_k != 5:
        raise ValueError(
            f"M5-1D seed baseline requires topK=5 at line {line_number}"
        )

    expected = case.get("expected")

    if not isinstance(expected, dict):
        raise ValueError(
            f"expected must be an object at line {line_number}"
        )

    for field_name in ("source", "section", "evidence"):
        value = expected.get(field_name)

        if not isinstance(value, str) or not value.strip():
            raise ValueError(
                f"expected.{field_name} must be a non-empty string "
                f"at line {line_number}"
            )


def build_search_url(
    base_url: str,
    endpoint: str,
    query: str,
    top_k: int,
) -> str:
    """构造包含中文 query 和 topK 的检索地址。"""

    base = base_url.rstrip("/")
    path = "/" + endpoint.lstrip("/")
    parameters = urlencode({"query": query, "topK": top_k})

    return f"{base}{path}?{parameters}"


def call_search(
    base_url: str,
    endpoint: str,
    query: str,
    top_k: int,
    timeout_seconds: int,
) -> tuple[dict[str, Any], float]:
    """调用 /rag/search，并同时记录客户端观察到的完整 HTTP 耗时。"""

    url = build_search_url(base_url, endpoint, query, top_k)
    request = Request(url, method="GET", headers={"Accept": "application/json"})

    start_time = time.perf_counter()

    try:
        with urlopen(request, timeout=timeout_seconds) as response:
            response_body = response.read().decode("utf-8")
    except HTTPError as error:
        error_body = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(
            f"HTTP {error.code} when calling {url}: {error_body}"
        ) from error
    except URLError as error:
        raise RuntimeError(
            f"Unable to call {url}: {error.reason}"
        ) from error

    client_cost_ms = round(
        (time.perf_counter() - start_time) * 1000,
        3,
    )

    try:
        payload = json.loads(response_body)
    except json.JSONDecodeError as error:
        raise RuntimeError(
            f"Endpoint returned invalid JSON: {response_body}"
        ) from error

    if payload.get("code") != 200:
        raise RuntimeError(
            f"Endpoint returned business failure: {payload}"
        )

    data = payload.get("data")

    if not isinstance(data, dict):
        raise RuntimeError(
            f"Response data must be an object: {payload}"
        )

    if not isinstance(data.get("hits"), list):
        raise RuntimeError(
            f"Response data.hits must be a list: {payload}"
        )

    return data, client_cost_ms


def find_first_relevant_rank(
    hits: list[dict[str, Any]],
    expected: dict[str, str],
) -> int | None:
    """返回第一个满足 source、section、evidence 的结果排名。"""

    for rank, hit in enumerate(hits, start=1):
        source_matches = hit.get("source") == expected["source"]
        section_matches = hit.get("section") == expected["section"]

        content = hit.get("content")
        evidence_matches = (
            isinstance(content, str)
            and expected["evidence"] in content
        )

        if source_matches and section_matches and evidence_matches:
            return rank

    return None


def nearest_rank_percentile(
    values: list[float],
    percentile: float,
) -> float:
    """使用 nearest-rank 方法计算小样本 P50/P95。"""

    if not values:
        return 0.0

    ordered_values = sorted(values)
    rank = math.ceil(percentile * len(ordered_values))
    index = max(0, rank - 1)

    return round(ordered_values[index], 3)


def calculate_sha256(path: Path) -> str:
    """计算数据集 Hash，保证评测结果能够对应到准确数据版本。"""

    digest = hashlib.sha256()

    with path.open("rb") as file:
        for block in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(block)

    return digest.hexdigest()


def read_git_head() -> str | None:
    """记录本次评测对应的 Git commit；未安装 Git 时返回 None。"""

    try:
        completed = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=PROJECT_ROOT,
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        )
    except (FileNotFoundError, subprocess.SubprocessError):
        return None

    git_head = completed.stdout.strip()
    return git_head or None


def write_json(path: Path, value: dict[str, Any]) -> None:
    """以可读格式保存 JSON。"""

    with path.open("w", encoding="utf-8", newline="\n") as file:
        json.dump(value, file, ensure_ascii=False, indent=2)
        file.write("\n")


def write_jsonl(path: Path, values: list[dict[str, Any]]) -> None:
    """保存逐题检索与评分明细。"""

    with path.open("w", encoding="utf-8", newline="\n") as file:
        for value in values:
            file.write(
                json.dumps(value, ensure_ascii=False)
            )
            file.write("\n")


def main() -> None:
    args = parse_args()

    if args.timeout_seconds <= 0:
        raise ValueError("--timeout-seconds must be greater than 0.")

    dataset_path = args.dataset.resolve()
    cases = load_cases(dataset_path)

    dataset_versions = {
        case["datasetVersion"]
        for case in cases
    }

    if len(dataset_versions) != 1:
        raise ValueError(
            "All cases must use the same datasetVersion."
        )

    dataset_version = next(iter(dataset_versions))

    print(f"Loaded cases: {len(cases)}")
    print(f"Dataset version: {dataset_version}")

    # 首次请求可能包含 Ollama 加载 BGE-M3 的冷启动耗时。
    # 因此先执行一次预热，并将预热耗时单独记录，不纳入 P50/P95。
    warmup_case = cases[0]

    print(f"Warm-up query: {warmup_case['id']}")

    warmup_data, warmup_client_cost_ms = call_search(
        args.base_url,
        args.endpoint,
        warmup_case["query"],
        warmup_case["topK"],
        args.timeout_seconds,
    )

    details: list[dict[str, Any]] = []
    server_latencies: list[float] = []
    client_latencies: list[float] = []
    reciprocal_ranks: list[float] = []

    for index, case in enumerate(cases, start=1):
        data, client_cost_ms = call_search(
            args.base_url,
            args.endpoint,
            case["query"],
            case["topK"],
            args.timeout_seconds,
        )

        hits = data["hits"]
        first_relevant_rank = find_first_relevant_rank(
            hits,
            case["expected"],
        )

        recalled_at_5 = first_relevant_rank is not None
        reciprocal_rank = (
            1.0 / first_relevant_rank
            if first_relevant_rank is not None
            else 0.0
        )

        server_cost_ms = float(data.get("costMs", 0))

        server_latencies.append(server_cost_ms)
        client_latencies.append(client_cost_ms)
        reciprocal_ranks.append(reciprocal_rank)

        detail = {
            "id": case["id"],
            "query": case["query"],
            "expected": case["expected"],
            "topK": case["topK"],
            "recalledAt5": recalled_at_5,
            "firstRelevantRank": first_relevant_rank,
            "reciprocalRank": round(reciprocal_rank, 6),
            "serverCostMs": server_cost_ms,
            "clientCostMs": client_cost_ms,
            "hits": hits,
        }

        details.append(detail)

        status = (
            f"rank={first_relevant_rank}"
            if first_relevant_rank is not None
            else "MISS"
        )

        print(
            f"[{index:02d}/{len(cases):02d}] "
            f"{case['id']}: {status}, "
            f"serverCostMs={server_cost_ms}"
        )

    recalled_count = sum(
        1 for detail in details if detail["recalledAt5"]
    )

    recall_at_5 = recalled_count / len(details)
    mrr = sum(reciprocal_ranks) / len(reciprocal_ranks)

    failed_case_ids = [
        detail["id"]
        for detail in details
        if not detail["recalledAt5"]
    ]

    run_id = (
        datetime.now().strftime("%Y%m%d-%H%M%S")
        + "-"
        + dataset_version
    )

    result_directory = args.results_root.resolve() / run_id
    result_directory.mkdir(parents=True, exist_ok=False)

    manifest = {
        "runId": run_id,
        "createdAt": datetime.now().astimezone().isoformat(),
        "gitHead": read_git_head(),
        "dataset": str(dataset_path.relative_to(PROJECT_ROOT)),
        "datasetSha256": calculate_sha256(dataset_path),
        "datasetVersion": dataset_version,
        "baseUrl": args.base_url,
        "endpoint": args.endpoint,
        "embeddingModel": args.embedding_model,
        "qdrantCollection": args.collection,
        "knowledgeVersion": args.knowledge_version,
        "caseCount": len(cases),
        "topK": 5,
        "warmup": {
            "caseId": warmup_case["id"],
            "serverCostMs": warmup_data.get("costMs"),
            "clientCostMs": warmup_client_cost_ms,
            "excludedFromLatencyMetrics": True,
        },
    }

    summary = {
        "runId": run_id,
        "datasetVersion": dataset_version,
        "caseCount": len(details),
        "recalledCount": recalled_count,
        "missedCount": len(details) - recalled_count,
        "recallAt5": round(recall_at_5, 6),
        "mrr": round(mrr, 6),
        "serverLatencyMs": {
            "average": round(
                sum(server_latencies) / len(server_latencies),
                3,
            ),
            "p50": nearest_rank_percentile(
                server_latencies,
                0.50,
            ),
            "p95": nearest_rank_percentile(
                server_latencies,
                0.95,
            ),
        },
        "clientLatencyMs": {
            "average": round(
                sum(client_latencies) / len(client_latencies),
                3,
            ),
            "p50": nearest_rank_percentile(
                client_latencies,
                0.50,
            ),
            "p95": nearest_rank_percentile(
                client_latencies,
                0.95,
            ),
        },
        "failedCaseIds": failed_case_ids,
    }

    write_json(result_directory / "manifest.json", manifest)
    write_jsonl(result_directory / "details.jsonl", details)
    write_json(result_directory / "summary.json", summary)

    print()
    print("Retrieval evaluation completed.")
    print(f"Recall@5: {recall_at_5:.2%}")
    print(f"MRR: {mrr:.6f}")
    print(
        "Server latency: "
        f"P50={summary['serverLatencyMs']['p50']} ms, "
        f"P95={summary['serverLatencyMs']['p95']} ms"
    )
    print(f"Failed cases: {failed_case_ids}")
    print(f"Results: {result_directory}")


if __name__ == "__main__":
    main()