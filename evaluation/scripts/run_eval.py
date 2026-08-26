"""Run ai-agent-demo evaluation cases against POST /agent/ask.

This stage is intentionally execution-only:

1. Validate the JSON Schema and JSONL dataset before sending requests.
2. Call the official /agent/ask endpoint sequentially.
3. Save a reproducible run manifest and one raw detail record per request.
4. Do not score model behavior yet. Scoring belongs to the next stage.

The script uses only Python's standard library for HTTP and file output. The
existing jsonschema dependency is reused for input validation.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from validate_dataset import (
    ValidationIssue,
    load_json_file,
    load_jsonl,
    validate_cases_with_schema,
    validate_cross_record_rules,
    validate_project_semantics,
    validate_schema_contract,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG_PATH = PROJECT_ROOT / "evaluation" / "configs" / "local-deepseek-v1.json"
DEFAULT_SCHEMA_PATH = PROJECT_ROOT / "evaluation" / "schemas" / "eval-case-v1.schema.json"
DEFAULT_DATASET_PATH = PROJECT_ROOT / "evaluation" / "datasets" / "eval-v1-seed.jsonl"
DEFAULT_RESULTS_ROOT = PROJECT_ROOT / "evaluation" / "results"
DEFAULT_KNOWLEDGE_ROOT = PROJECT_ROOT / "src" / "main" / "resources" / "docs" / "knowledge"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run Evaluation cases against ai-agent-demo /agent/ask.")

    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH, help="Evaluation run config path.")
    parser.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA_PATH, help="Evaluation case schema path.")
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET_PATH, help="Evaluation JSONL path.")
    parser.add_argument("--results-root", type=Path, default=DEFAULT_RESULTS_ROOT, help="Run output directory.")
    parser.add_argument(
        "--case-id",
        action="append",
        default=[],
        help="Run only the specified case ID. May be supplied more than once.",
    )
    parser.add_argument("--limit", type=int, default=None, help="Run only the first N selected cases.")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Validate and list the execution plan without calling the API.",
    )

    return parser.parse_args()


def validate_run_config(config: dict[str, Any]) -> None:
    required_keys = {
        "schemaVersion",
        "baseUrl",
        "endpoint",
        "requestTimeoutSeconds",
        "concurrency",
        "repetitions",
        "versions",
    }

    missing_keys = sorted(required_keys - config.keys())

    if missing_keys:
        raise ValueError(f"Run config is missing required fields: {missing_keys}")

    if config["schemaVersion"] != "eval-run-config-v1":
        raise ValueError(f"Unsupported run config schemaVersion: {config['schemaVersion']}")

    if not isinstance(config["baseUrl"], str) or not config["baseUrl"].strip():
        raise ValueError("baseUrl must be a non-empty string.")

    if not isinstance(config["endpoint"], str) or not config["endpoint"].strip():
        raise ValueError("endpoint must be a non-empty string.")

    if not isinstance(config["requestTimeoutSeconds"], int) or config["requestTimeoutSeconds"] <= 0:
        raise ValueError("requestTimeoutSeconds must be a positive integer.")

    if config["concurrency"] != 1:
        raise ValueError("M4-3 runner is sequential; concurrency must be 1.")

    if not isinstance(config["repetitions"], int) or config["repetitions"] <= 0:
        raise ValueError("repetitions must be a positive integer.")

    versions = config["versions"]

    if not isinstance(versions, dict):
        raise ValueError("versions must be a JSON object.")

    for key in ("evaluator", "prompt", "model", "knowledge"):
        if not isinstance(versions.get(key), str) or not versions[key].strip():
            raise ValueError(f"versions.{key} must be a non-empty string.")


def load_and_validate_cases(schema_path: Path, dataset_path: Path) -> list[dict[str, Any]]:
    schema = load_json_file(schema_path)
    cases, issues = load_jsonl(dataset_path)

    issues.extend(validate_schema_contract(schema))

    if not issues:
        issues.extend(validate_cases_with_schema(schema, cases))

    issues.extend(validate_cross_record_rules(cases))
    issues.extend(validate_project_semantics(cases))

    if issues:
        formatted_issues = "\n".join(f"- {issue.format()}" for issue in issues)
        raise ValueError(f"Evaluation dataset validation failed: issues={len(issues)}\n{formatted_issues}")

    normalized_cases: list[dict[str, Any]] = []

    for case in cases:
        normalized_case = dict(case)
        normalized_case.pop("__lineNumber", None)
        normalized_cases.append(normalized_case)

    return normalized_cases


def select_cases(
    cases: list[dict[str, Any]], selected_case_ids: list[str], limit: int | None
) -> list[dict[str, Any]]:
    if limit is not None and limit <= 0:
        raise ValueError("--limit must be greater than 0.")

    if selected_case_ids:
        requested_ids = set(selected_case_ids)
        existing_ids = {case["id"] for case in cases}
        missing_ids = sorted(requested_ids - existing_ids)

        if missing_ids:
            raise ValueError(f"Unknown --case-id values: {missing_ids}")

        selected_cases = [case for case in cases if case["id"] in requested_ids]
    else:
        selected_cases = list(cases)

    if limit is not None:
        selected_cases = selected_cases[:limit]

    if not selected_cases:
        raise ValueError("No Evaluation cases were selected.")

    return selected_cases


def build_endpoint_url(config: dict[str, Any]) -> str:
    return config["baseUrl"].rstrip("/") + "/" + config["endpoint"].lstrip("/")


def calculate_file_sha256(path: Path) -> str:
    digest = hashlib.sha256()

    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)

    return digest.hexdigest()


def calculate_directory_sha256(root: Path) -> str | None:
    if not root.is_dir():
        return None

    digest = hashlib.sha256()
    files = sorted(path for path in root.rglob("*") if path.is_file())

    for path in files:
        relative_path = path.relative_to(root).as_posix()
        digest.update(relative_path.encode("utf-8"))
        digest.update(b"\0")

        with path.open("rb") as file:
            for chunk in iter(lambda: file.read(1024 * 1024), b""):
                digest.update(chunk)

        digest.update(b"\0")

    return digest.hexdigest()


def read_git_head(project_root: Path) -> str | None:
    try:
        completed = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=project_root,
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        )
    except (FileNotFoundError, subprocess.SubprocessError):
        return None

    value = completed.stdout.strip()
    return value or None


def build_run_id(config: dict[str, Any], dataset_version: str) -> str:
    timestamp = datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    model = sanitize_path_component(config["versions"]["model"])
    dataset = sanitize_path_component(dataset_version)

    return f"{timestamp}-{model}-{dataset}"


def sanitize_path_component(value: str) -> str:
    normalized = re.sub(r"[^a-zA-Z0-9._-]+", "-", value.strip())
    normalized = normalized.strip("-._")
    return normalized or "unknown"


def utc_offset_timestamp() -> str:
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_suffix(path.suffix + ".tmp")
    temporary_path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary_path.replace(path)


def parse_response_body(response_text: str) -> tuple[bool, Any | None]:
    if not response_text:
        return False, None

    try:
        return True, json.loads(response_text)
    except json.JSONDecodeError:
        return False, None


def execute_request(endpoint_url: str, message: str, timeout_seconds: int) -> dict[str, Any]:
    request_body = json.dumps({"message": message}, ensure_ascii=False).encode("utf-8")

    request = Request(
        endpoint_url,
        data=request_body,
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json",
            "User-Agent": "ai-agent-demo-evaluation/1.0",
        },
        method="POST",
    )

    start_ns = time.perf_counter_ns()
    http_status: int | None = None
    response_text: str | None = None
    transport_success = False
    error_type: str | None = None
    error_message: str | None = None

    try:
        with urlopen(request, timeout=timeout_seconds) as response:
            http_status = response.status
            response_text = response.read().decode("utf-8", errors="replace")
            transport_success = 200 <= response.status < 300
    except HTTPError as exc:
        http_status = exc.code
        response_text = exc.read().decode("utf-8", errors="replace")
        error_type = exc.__class__.__name__
        error_message = str(exc)
    except (URLError, TimeoutError, OSError) as exc:
        error_type = exc.__class__.__name__
        error_message = str(exc)

    client_cost_ms = round((time.perf_counter_ns() - start_ns) / 1_000_000, 3)
    response_parsed, response_json = parse_response_body(response_text or "")
    execution_success = transport_success and response_parsed

    return {
        "executionSuccess": execution_success,
        "timing": {
            "clientCostMs": client_cost_ms,
        },
        "transport": {
            "success": transport_success,
            "httpStatus": http_status,
            "errorType": error_type,
            "errorMessage": error_message,
        },
        "response": {
            "parsed": response_parsed,
            "body": response_json,
            "rawText": None if response_parsed else response_text,
        },
    }


def build_manifest(
    run_id: str,
    config: dict[str, Any],
    config_path: Path,
    dataset_path: Path,
    dataset_version: str,
    selected_case_count: int,
) -> dict[str, Any]:
    repetitions = config["repetitions"]

    return {
        "schemaVersion": "eval-run-manifest-v1",
        "runId": run_id,
        "status": "RUNNING",
        "startedAt": utc_offset_timestamp(),
        "finishedAt": None,
        "target": {
            "baseUrl": config["baseUrl"],
            "endpoint": config["endpoint"],
            "requestTimeoutSeconds": config["requestTimeoutSeconds"],
        },
        "versions": {
            **config["versions"],
            "code": read_git_head(PROJECT_ROOT),
        },
        "inputs": {
            "configPath": str(config_path.resolve()),
            "configSha256": calculate_file_sha256(config_path),
            "datasetPath": str(dataset_path.resolve()),
            "datasetVersion": dataset_version,
            "datasetSha256": calculate_file_sha256(dataset_path),
            "knowledgePath": str(DEFAULT_KNOWLEDGE_ROOT.resolve()),
            "knowledgeSha256": calculate_directory_sha256(DEFAULT_KNOWLEDGE_ROOT),
        },
        "execution": {
            "concurrency": config["concurrency"],
            "repetitions": repetitions,
            "selectedCaseCount": selected_case_count,
            "plannedRequestCount": selected_case_count * repetitions,
        },
        "result": {
            "succeededRequestCount": 0,
            "failedRequestCount": 0,
        },
    }


def print_dry_run(config: dict[str, Any], selected_cases: list[dict[str, Any]]) -> None:
    print("M4-3 dry run passed; no HTTP requests were sent")
    print(f"endpoint={build_endpoint_url(config)}")
    print(f"requestTimeoutSeconds={config['requestTimeoutSeconds']}")
    print(f"repetitions={config['repetitions']}")
    print(f"selectedCases={len(selected_cases)}")

    for index, case in enumerate(selected_cases, start=1):
        need_tool = bool(case["expected"]["toolCalls"])
        print(f"  {index:02d}. {case['id']} category={case['category']} expectedNeedTool={str(need_tool).lower()}")


def run_evaluation(
    config: dict[str, Any],
    config_path: Path,
    dataset_path: Path,
    results_root: Path,
    selected_cases: list[dict[str, Any]],
) -> tuple[Path, int, int]:
    dataset_version = selected_cases[0]["datasetVersion"]
    run_id = build_run_id(config, dataset_version)
    run_directory = results_root / run_id
    run_directory.mkdir(parents=True, exist_ok=False)

    manifest_path = run_directory / "manifest.json"
    details_path = run_directory / "details.jsonl"
    manifest = build_manifest(run_id, config, config_path, dataset_path, dataset_version, len(selected_cases))
    write_json(manifest_path, manifest)

    endpoint_url = build_endpoint_url(config)
    timeout_seconds = config["requestTimeoutSeconds"]
    repetitions = config["repetitions"]
    sequence = 0
    succeeded_count = 0
    failed_count = 0

    print(f"M4-3 Evaluation run started: runId={run_id}")
    print(f"endpoint={endpoint_url}")
    print(f"plannedRequests={len(selected_cases) * repetitions}")

    with details_path.open("w", encoding="utf-8", newline="\n") as details_file:
        for repetition in range(1, repetitions + 1):
            for case in selected_cases:
                sequence += 1
                print(
                    f"[{sequence}/{len(selected_cases) * repetitions}] "
                    f"caseId={case['id']} repetition={repetition} ...",
                    flush=True,
                )

                execution_result = execute_request(endpoint_url, case["input"]["message"], timeout_seconds)

                detail = {
                    "schemaVersion": "eval-run-detail-v1",
                    "runId": run_id,
                    "sequence": sequence,
                    "caseId": case["id"],
                    "category": case["category"],
                    "tags": case["tags"],
                    "repetition": repetition,
                    "request": {
                        "message": case["input"]["message"],
                    },
                    **execution_result,
                }

                details_file.write(json.dumps(detail, ensure_ascii=False, separators=(",", ":")) + "\n")
                details_file.flush()

                if execution_result["executionSuccess"]:
                    succeeded_count += 1
                    http_status = execution_result["transport"]["httpStatus"]
                    client_cost_ms = execution_result["timing"]["clientCostMs"]
                    print(f"  completed httpStatus={http_status} clientCostMs={client_cost_ms}")
                else:
                    failed_count += 1
                    transport = execution_result["transport"]
                    print(
                        f"  failed httpStatus={transport['httpStatus']} "
                        f"errorType={transport['errorType']} error={transport['errorMessage']}",
                        file=sys.stderr,
                    )

    manifest["status"] = "COMPLETED" if failed_count == 0 else "COMPLETED_WITH_ERRORS"
    manifest["finishedAt"] = utc_offset_timestamp()
    manifest["result"] = {
        "succeededRequestCount": succeeded_count,
        "failedRequestCount": failed_count,
    }
    write_json(manifest_path, manifest)

    print("M4-3 Evaluation run finished")
    print(f"runDirectory={run_directory.resolve()}")
    print(f"succeededRequests={succeeded_count}")
    print(f"failedRequests={failed_count}")

    return run_directory, succeeded_count, failed_count


def main() -> int:
    args = parse_args()

    try:
        config = load_json_file(args.config)
        validate_run_config(config)
        cases = load_and_validate_cases(args.schema, args.dataset)
        selected_cases = select_cases(cases, args.case_id, args.limit)
    except (OSError, ValueError) as exc:
        print(f"M4-3 Evaluation setup failed:\n{exc}", file=sys.stderr)
        return 1

    if args.dry_run:
        print_dry_run(config, selected_cases)
        return 0

    try:
        _, _, failed_count = run_evaluation(
            config,
            args.config,
            args.dataset,
            args.results_root,
            selected_cases,
        )
    except (OSError, ValueError) as exc:
        print(f"M4-3 Evaluation execution failed:\n{exc}", file=sys.stderr)
        return 1

    return 0 if failed_count == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
