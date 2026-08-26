"""Score one completed ai-agent-demo Evaluation run with deterministic rules.

The scorer never calls the Java service or the model. It joins the fixed
dataset expectations with M4-3 raw details, extracts facts from the returned
Trace, evaluates atomic assertions, and writes:

* scored-details.jsonl: one explainable score record per request;
* summary.json: metric numerators, denominators, percentages, and latency.

No weighted total score is produced. The unsupported-answer metric is only a
rule proxy based on explicitly configured forbidden regular expressions.
"""

from __future__ import annotations

import argparse
import json
import math
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

from run_eval import calculate_file_sha256, load_and_validate_cases


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SCHEMA_PATH = PROJECT_ROOT / "evaluation" / "schemas" / "eval-case-v1.schema.json"
DEFAULT_DATASET_PATH = PROJECT_ROOT / "evaluation" / "datasets" / "eval-v1-seed.jsonl"


@dataclass(frozen=True)
class AtomicCheck:
    """One deterministic assertion and the evidence used to judge it."""

    label: str
    passed: bool
    expected: Any
    actual: Any

    def to_dict(self) -> dict[str, Any]:
        return {
            "label": self.label,
            "passed": self.passed,
            "expected": self.expected,
            "actual": self.actual,
        }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Score one completed ai-agent-demo Evaluation run.")
    parser.add_argument("--run-dir", type=Path, required=True, help="Directory containing manifest.json and details.jsonl.")
    parser.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA_PATH, help="Evaluation case schema path.")
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET_PATH, help="Evaluation JSONL path.")
    return parser.parse_args()


def load_json_object(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise ValueError(f"File does not exist: {path}")

    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as exc:
        raise ValueError(
            f"Invalid JSON file: {path}, line={exc.lineno}, column={exc.colno}, error={exc.msg}"
        ) from exc

    if not isinstance(value, dict):
        raise ValueError(f"JSON root must be an object: {path}")

    return value


def load_detail_records(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        raise ValueError(f"File does not exist: {path}")

    records: list[dict[str, Any]] = []

    with path.open("r", encoding="utf-8-sig") as file:
        for line_number, raw_line in enumerate(file, start=1):
            line = raw_line.strip()

            if not line:
                raise ValueError(f"Blank line is not allowed: {path}, line={line_number}")

            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(
                    f"Invalid JSONL: {path}, line={line_number}, column={exc.colno}, error={exc.msg}"
                ) from exc

            if not isinstance(value, dict):
                raise ValueError(f"Each details.jsonl line must be an object: line={line_number}")

            records.append(value)

    if not records:
        raise ValueError(f"No detail records found: {path}")

    return records


def validate_run_inputs(
    manifest: dict[str, Any],
    details: list[dict[str, Any]],
    dataset_path: Path,
    cases_by_id: dict[str, dict[str, Any]],
) -> None:
    if manifest.get("schemaVersion") != "eval-run-manifest-v1":
        raise ValueError(f"Unsupported manifest schemaVersion: {manifest.get('schemaVersion')}")

    if manifest.get("status") != "COMPLETED":
        raise ValueError(f"Only a COMPLETED run can be scored: status={manifest.get('status')}")

    run_id = manifest.get("runId")

    if not isinstance(run_id, str) or not run_id:
        raise ValueError("manifest.runId must be a non-empty string.")

    expected_dataset_sha256 = ((manifest.get("inputs") or {}).get("datasetSha256"))
    actual_dataset_sha256 = calculate_file_sha256(dataset_path)

    if expected_dataset_sha256 != actual_dataset_sha256:
        raise ValueError(
            "Dataset SHA-256 does not match the run manifest. "
            f"manifest={expected_dataset_sha256}, current={actual_dataset_sha256}. "
            "Score the run with the exact dataset version that produced it."
        )

    seen_keys: set[tuple[str, int]] = set()
    seen_sequences: set[int] = set()

    for index, detail in enumerate(details, start=1):
        if detail.get("schemaVersion") != "eval-run-detail-v1":
            raise ValueError(f"Unsupported detail schemaVersion at line {index}: {detail.get('schemaVersion')}")

        if detail.get("runId") != run_id:
            raise ValueError(f"detail.runId mismatch at line {index}: {detail.get('runId')} != {run_id}")

        case_id = detail.get("caseId")
        repetition = detail.get("repetition")
        sequence = detail.get("sequence")

        if case_id not in cases_by_id:
            raise ValueError(f"Unknown caseId in details.jsonl at line {index}: {case_id}")

        if detail.get("category") != cases_by_id[case_id].get("category"):
            raise ValueError(f"Category mismatch for caseId={case_id}")

        if not isinstance(repetition, int) or repetition <= 0:
            raise ValueError(f"Invalid repetition for caseId={case_id}: {repetition}")

        key = (case_id, repetition)

        if key in seen_keys:
            raise ValueError(f"Duplicate detail record: caseId={case_id}, repetition={repetition}")

        seen_keys.add(key)

        if not isinstance(sequence, int) or sequence <= 0 or sequence in seen_sequences:
            raise ValueError(f"Invalid or duplicate sequence at line {index}: {sequence}")

        seen_sequences.add(sequence)

    result = manifest.get("result") or {}
    recorded_total = result.get("succeededRequestCount", 0) + result.get("failedRequestCount", 0)

    if recorded_total != len(details):
        raise ValueError(
            "Manifest result count does not match details.jsonl: "
            f"manifest={recorded_total}, details={len(details)}"
        )


def json_value(value: Any) -> Any:
    """Return an already structured value or parse a JSON-looking string."""
    if not isinstance(value, str):
        return value

    stripped = value.strip()

    if not stripped:
        return value

    try:
        return json.loads(stripped)
    except json.JSONDecodeError:
        return value


def extract_tool_name(step: dict[str, Any]) -> str | None:
    structured_name = step.get("toolName")

    if isinstance(structured_name, str) and structured_name.strip():
        return structured_name.strip()

    description = step.get("description")

    if not isinstance(description, str):
        return None

    match = re.search(r"执行工具[：:]\s*([^\s，,]+)", description)
    return match.group(1) if match else None


def extract_tool_calls(data: dict[str, Any]) -> list[dict[str, Any]]:
    calls: list[dict[str, Any]] = []

    for step in data.get("steps") or []:
        if not isinstance(step, dict) or step.get("stepName") != "TOOL_EXECUTION":
            continue

        arguments = step.get("inputView")

        if arguments is None:
            arguments = json_value(step.get("input"))

        result = step.get("outputView")

        if result is None and step.get("output") is not None:
            result = json_value(step.get("output"))

        calls.append(
            {
                "name": extract_tool_name(step),
                "arguments": arguments,
                "status": step.get("toolStatus"),
                "errorCode": step.get("errorCode"),
                "success": step.get("success"),
                "result": result,
                "costMs": step.get("costMs"),
            }
        )

    return calls


def rag_state(step: dict[str, Any]) -> tuple[str, str | None]:
    description = step.get("description") if isinstance(step.get("description"), str) else ""
    output = step.get("outputView")

    if output is None:
        output = json_value(step.get("output"))

    source = output.get("source") if isinstance(output, dict) else None

    if "跳过补充检索" in description or "PRE_DECISION 已命中" in description:
        return "SKIPPED_PRE_MATCH", None

    if isinstance(output, dict) and output.get("matched") is True:
        return "MATCHED", source

    if isinstance(output, dict) and output.get("matched") is False:
        return "NOT_MATCHED", source

    if "检索到相关本地知识" in description and "未检索到" not in description:
        return "MATCHED", source

    if "未检索到相关本地知识" in description:
        return "NOT_MATCHED", source

    return "UNKNOWN", source


def extract_rag(data: dict[str, Any]) -> dict[str, dict[str, Any]]:
    actual = {
        "preDecision": {"state": "NOT_RUN", "source": None},
        "postTool": {"state": "NOT_RUN", "source": None},
    }

    for step in data.get("steps") or []:
        if not isinstance(step, dict) or step.get("stepName") != "KNOWLEDGE_RETRIEVAL":
            continue

        description = step.get("description") if isinstance(step.get("description"), str) else ""
        state, source = rag_state(step)

        if description.startswith("PRE_DECISION"):
            actual["preDecision"] = {"state": state, "source": source}
        elif description.startswith("POST_TOOL"):
            actual["postTool"] = {"state": state, "source": source}

    return actual


def extract_chat(data: dict[str, Any]) -> dict[str, Any]:
    chat_steps = [
        step
        for step in data.get("steps") or []
        if isinstance(step, dict) and step.get("stepName") == "SPRING_AI_CHAT"
    ]

    if not chat_steps:
        return {"found": False, "success": None}

    return {"found": True, "success": chat_steps[-1].get("success")}


def extract_actual(detail: dict[str, Any]) -> dict[str, Any]:
    response = detail.get("response") or {}
    body = response.get("body") if isinstance(response, dict) else None
    body = body if isinstance(body, dict) else {}
    data = body.get("data") if isinstance(body.get("data"), dict) else {}
    tool_calls = extract_tool_calls(data)

    return {
        "executionSuccess": detail.get("executionSuccess") is True,
        "httpStatus": ((detail.get("transport") or {}).get("httpStatus")),
        "bodyCode": body.get("code"),
        "answer": data.get("answer") if isinstance(data.get("answer"), str) else "",
        "needTool": bool(tool_calls),
        "toolCalls": tool_calls,
        "rag": extract_rag(data),
        "chat": extract_chat(data),
        "toolCallCount": len(tool_calls),
        "modelCallCount": None,
        "agentCostMs": data.get("agentCostMs"),
        "clientCostMs": ((detail.get("timing") or {}).get("clientCostMs")),
    }


def is_present(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, dict, tuple, set)):
        return bool(value)
    return True


def contains_value(actual: Any, expected: Any) -> bool:
    if isinstance(actual, str):
        return str(expected) in actual
    if isinstance(actual, (list, tuple, set)):
        return expected in actual
    return actual == expected


def resolve_json_path(value: Any, path: str) -> Any:
    """Resolve the small JSONPath subset used by eval-case-v1.

    Supported syntax: $, $.field, $.field.nested, $[*], and $[*].field.
    """
    if not isinstance(path, str) or not path.startswith("$"):
        raise ValueError(f"Unsupported JSONPath: {path}")

    current: list[Any] = [value]
    cursor = 1

    while cursor < len(path):
        if path.startswith("[*]", cursor):
            expanded: list[Any] = []

            for item in current:
                if isinstance(item, list):
                    expanded.extend(item)

            current = expanded
            cursor += 3
            continue

        if path[cursor] == ".":
            match = re.match(r"\.([A-Za-z_][A-Za-z0-9_]*)", path[cursor:])

            if not match:
                raise ValueError(f"Unsupported JSONPath: {path}")

            key = match.group(1)
            current = [item[key] for item in current if isinstance(item, dict) and key in item]
            cursor += len(match.group(0))
            continue

        raise ValueError(f"Unsupported JSONPath: {path}")

    if not current:
        return None
    if len(current) == 1:
        return current[0]
    return current


def check_group(answer: str, group: list[str], label: str) -> AtomicCheck:
    normalized_answer = answer.casefold()
    matched = [candidate for candidate in group if candidate.casefold() in normalized_answer]
    return AtomicCheck(label, bool(matched), group, matched)


def evaluate_tool_assertions(
    expected_calls: list[dict[str, Any]], actual_calls: list[dict[str, Any]]
) -> dict[str, Any]:
    required_key_checks: list[AtomicCheck] = []
    argument_checks: list[AtomicCheck] = []
    status_checks: list[AtomicCheck] = []
    error_code_checks: list[AtomicCheck] = []
    result_checks: list[AtomicCheck] = []

    for index, expected_call in enumerate(expected_calls):
        actual_call = actual_calls[index] if index < len(actual_calls) else {}
        arguments = actual_call.get("arguments") if isinstance(actual_call.get("arguments"), dict) else {}
        expected_arguments = expected_call.get("arguments") or {}

        for key in expected_arguments.get("requiredKeys") or []:
            actual_value = arguments.get(key)
            required_key_checks.append(
                AtomicCheck(f"toolCalls[{index}].arguments.requiredKeys.{key}", is_present(actual_value), "non-empty", actual_value)
            )

        for key, expected_value in (expected_arguments.get("equals") or {}).items():
            actual_value = arguments.get(key)
            argument_checks.append(
                AtomicCheck(f"toolCalls[{index}].arguments.equals.{key}", actual_value == expected_value, expected_value, actual_value)
            )

        for key, expected_values in (expected_arguments.get("contains") or {}).items():
            actual_value = arguments.get(key)

            for expected_value in expected_values:
                argument_checks.append(
                    AtomicCheck(
                        f"toolCalls[{index}].arguments.contains.{key}.{expected_value}",
                        contains_value(actual_value, expected_value),
                        expected_value,
                        actual_value,
                    )
                )

        actual_status = actual_call.get("status")
        status_checks.append(
            AtomicCheck(f"toolCalls[{index}].status", actual_status == expected_call.get("status"), expected_call.get("status"), actual_status)
        )

        actual_error_code = actual_call.get("errorCode")
        error_code_checks.append(
            AtomicCheck(
                f"toolCalls[{index}].errorCode",
                actual_error_code == expected_call.get("errorCode"),
                expected_call.get("errorCode"),
                actual_error_code,
            )
        )

        result = actual_call.get("result")
        result_assertions = expected_call.get("resultAssertions") or {}

        for path, expected_value in (result_assertions.get("jsonPathEquals") or {}).items():
            actual_value = resolve_json_path(result, path)
            result_checks.append(AtomicCheck(f"toolCalls[{index}].result{path}", actual_value == expected_value, expected_value, actual_value))

        for path, expected_values in (result_assertions.get("jsonPathContains") or {}).items():
            actual_value = resolve_json_path(result, path)

            for expected_value in expected_values:
                result_checks.append(
                    AtomicCheck(
                        f"toolCalls[{index}].result{path}.contains.{expected_value}",
                        contains_value(actual_value, expected_value),
                        expected_value,
                        actual_value,
                    )
                )

        result_text = result if isinstance(result, str) else json.dumps(result, ensure_ascii=False, sort_keys=True)

        for expected_text in result_assertions.get("textContains") or []:
            result_checks.append(
                AtomicCheck(
                    f"toolCalls[{index}].result.textContains.{expected_text}",
                    expected_text in result_text,
                    expected_text,
                    result_text,
                )
            )

    return {
        "requiredKeys": [check.to_dict() for check in required_key_checks],
        "arguments": [check.to_dict() for check in argument_checks],
        "statuses": [check.to_dict() for check in status_checks],
        "errorCodes": [check.to_dict() for check in error_code_checks],
        "resultAssertions": [check.to_dict() for check in result_checks],
    }


def all_pass(checks: Iterable[dict[str, Any]]) -> bool:
    return all(check.get("passed") is True for check in checks)


def score_detail(case: dict[str, Any], detail: dict[str, Any]) -> dict[str, Any]:
    expected = case["expected"]
    actual = extract_actual(detail)
    expected_calls = expected["toolCalls"]
    actual_calls = actual["toolCalls"]

    execution_check = AtomicCheck("executionSuccess", actual["executionSuccess"], True, actual["executionSuccess"])
    http_check = AtomicCheck("http.status", actual["httpStatus"] == expected["http"]["status"], expected["http"]["status"], actual["httpStatus"])
    body_code_check = AtomicCheck("http.bodyCode", actual["bodyCode"] == expected["http"]["bodyCode"], expected["http"]["bodyCode"], actual["bodyCode"])
    need_tool_check = AtomicCheck("needTool", actual["needTool"] == bool(expected_calls), bool(expected_calls), actual["needTool"])

    expected_names = [call["name"] for call in expected_calls]
    actual_names = [call.get("name") for call in actual_calls]
    tool_sequence_check = AtomicCheck("toolCallSequence", actual_names == expected_names, expected_names, actual_names)
    tool_assertions = evaluate_tool_assertions(expected_calls, actual_calls)

    rag_checks: dict[str, dict[str, Any]] = {}

    for phase in ("preDecision", "postTool"):
        expected_phase = expected["rag"][phase]
        actual_phase = actual["rag"][phase]
        phase_check = AtomicCheck(
            f"rag.{phase}",
            actual_phase == expected_phase,
            expected_phase,
            actual_phase,
        )
        rag_checks[phase] = phase_check.to_dict()

    chat_check = AtomicCheck(
        "chat.success",
        actual["chat"]["found"] and actual["chat"]["success"] == expected["chat"]["success"],
        expected["chat"]["success"],
        actual["chat"],
    )

    answer = actual["answer"]
    answer_expectations = expected["answer"]
    evidence_checks = [
        check_group(answer, group, f"answer.evidenceFactGroups[{index}]")
        for index, group in enumerate(answer_expectations.get("evidenceFactGroups") or [])
    ]
    behavior_checks = [
        check_group(answer, group, f"answer.requiredBehaviorGroups[{index}]")
        for index, group in enumerate(answer_expectations.get("requiredBehaviorGroups") or [])
    ]

    forbidden_checks: list[AtomicCheck] = []

    for index, pattern in enumerate(answer_expectations.get("forbiddenClaimPatterns") or []):
        match = re.search(pattern, answer, flags=re.IGNORECASE | re.DOTALL)
        forbidden_checks.append(
            AtomicCheck(
                f"answer.forbiddenClaimPatterns[{index}]",
                match is None,
                "pattern must not match",
                match.group(0) if match else None,
            )
        )

    simple_checks = {
        "execution": execution_check.to_dict(),
        "httpStatus": http_check.to_dict(),
        "bodyCode": body_code_check.to_dict(),
        "needTool": need_tool_check.to_dict(),
        "toolSequence": tool_sequence_check.to_dict(),
        "rag": rag_checks,
        "chatSuccess": chat_check.to_dict(),
        "answerEvidence": [check.to_dict() for check in evidence_checks],
        "requiredBehaviors": [check.to_dict() for check in behavior_checks],
        "forbiddenClaims": [check.to_dict() for check in forbidden_checks],
        **tool_assertions,
    }

    flat_contract_checks: list[dict[str, Any]] = [
        simple_checks["execution"],
        simple_checks["httpStatus"],
        simple_checks["bodyCode"],
        simple_checks["needTool"],
        simple_checks["toolSequence"],
        simple_checks["rag"]["preDecision"],
        simple_checks["rag"]["postTool"],
        simple_checks["chatSuccess"],
        *simple_checks["requiredKeys"],
        *simple_checks["arguments"],
        *simple_checks["statuses"],
        *simple_checks["errorCodes"],
        *simple_checks["resultAssertions"],
        *simple_checks["answerEvidence"],
        *simple_checks["requiredBehaviors"],
        *simple_checks["forbiddenClaims"],
    ]

    business_error_handled: bool | None = None

    if expected["outcome"] == "BUSINESS_ERROR":
        business_error_handled = all_pass(
            [
                simple_checks["execution"],
                simple_checks["httpStatus"],
                simple_checks["bodyCode"],
                simple_checks["toolSequence"],
                simple_checks["rag"]["postTool"],
                simple_checks["chatSuccess"],
                *simple_checks["statuses"],
                *simple_checks["errorCodes"],
                *simple_checks["resultAssertions"],
                *simple_checks["answerEvidence"],
                *simple_checks["forbiddenClaims"],
            ]
        )

    clarification_handled: bool | None = None

    if expected["outcome"] == "CLARIFICATION":
        clarification_handled = all_pass(
            [
                simple_checks["execution"],
                simple_checks["httpStatus"],
                simple_checks["bodyCode"],
                simple_checks["needTool"],
                simple_checks["toolSequence"],
                simple_checks["rag"]["postTool"],
                simple_checks["chatSuccess"],
                *simple_checks["requiredBehaviors"],
                *simple_checks["forbiddenClaims"],
            ]
        )

    return {
        "schemaVersion": "eval-scored-detail-v1",
        "runId": detail["runId"],
        "sequence": detail["sequence"],
        "caseId": detail["caseId"],
        "category": detail["category"],
        "repetition": detail["repetition"],
        "expectedOutcome": expected["outcome"],
        "actual": actual,
        "checks": simple_checks,
        "derived": {
            "fullContractPassed": all_pass(flat_contract_checks),
            "businessErrorHandled": business_error_handled,
            "clarificationHandled": clarification_handled,
            "forbiddenClaimMatched": any(not check.passed for check in forbidden_checks),
        },
    }


def rate(checks: list[dict[str, Any]]) -> dict[str, Any]:
    passed = sum(1 for check in checks if check.get("passed") is True)
    total = len(checks)
    percentage = round(passed * 100 / total, 2) if total else None
    failed_case_ids = sorted({check["caseId"] for check in checks if check.get("passed") is not True})
    return {
        "passed": passed,
        "total": total,
        "percentage": percentage,
        "failedCaseIds": failed_case_ids,
    }


def metric_checks(scored: list[dict[str, Any]], selector: Any) -> list[dict[str, Any]]:
    checks: list[dict[str, Any]] = []

    for record in scored:
        selected = selector(record)

        if selected is None:
            continue

        selected_items = selected if isinstance(selected, list) else [selected]

        for item in selected_items:
            checks.append({**item, "caseId": record["caseId"]})

    return checks


def nearest_rank(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(percentile * len(ordered)))
    return ordered[rank - 1]


def latency_summary(values: list[Any]) -> dict[str, Any]:
    numeric_values = [float(value) for value in values if isinstance(value, (int, float)) and not isinstance(value, bool)]

    if not numeric_values:
        return {"count": 0, "minMs": None, "meanMs": None, "p50Ms": None, "p95Ms": None, "maxMs": None}

    return {
        "count": len(numeric_values),
        "minMs": round(min(numeric_values), 3),
        "meanMs": round(sum(numeric_values) / len(numeric_values), 3),
        "p50Ms": round(nearest_rank(numeric_values, 0.50) or 0, 3),
        "p95Ms": round(nearest_rank(numeric_values, 0.95) or 0, 3),
        "maxMs": round(max(numeric_values), 3),
        "percentileMethod": "nearest-rank",
    }


def build_summary(manifest: dict[str, Any], scored: list[dict[str, Any]]) -> dict[str, Any]:
    execution_checks = metric_checks(scored, lambda record: record["checks"]["execution"])
    http_checks = metric_checks(scored, lambda record: record["checks"]["httpStatus"])
    body_code_checks = metric_checks(scored, lambda record: record["checks"]["bodyCode"])
    need_tool_checks = metric_checks(scored, lambda record: record["checks"]["needTool"])
    tool_name_checks = metric_checks(
        scored,
        lambda record: record["checks"]["toolSequence"] if record["checks"]["toolSequence"]["expected"] else None,
    )
    required_key_checks = metric_checks(scored, lambda record: record["checks"]["requiredKeys"])
    argument_checks = metric_checks(scored, lambda record: record["checks"]["arguments"])
    status_checks = metric_checks(scored, lambda record: record["checks"]["statuses"])
    error_code_checks = metric_checks(scored, lambda record: record["checks"]["errorCodes"])
    result_checks = metric_checks(scored, lambda record: record["checks"]["resultAssertions"])
    rag_checks = metric_checks(
        scored,
        lambda record: [record["checks"]["rag"]["preDecision"], record["checks"]["rag"]["postTool"]],
    )
    chat_checks = metric_checks(scored, lambda record: record["checks"]["chatSuccess"])
    evidence_checks = metric_checks(scored, lambda record: record["checks"]["answerEvidence"])
    behavior_checks = metric_checks(scored, lambda record: record["checks"]["requiredBehaviors"])

    business_error_checks = [
        {
            "passed": record["derived"]["businessErrorHandled"],
            "caseId": record["caseId"],
        }
        for record in scored
        if record["derived"]["businessErrorHandled"] is not None
    ]
    clarification_checks = [
        {
            "passed": record["derived"]["clarificationHandled"],
            "caseId": record["caseId"],
        }
        for record in scored
        if record["derived"]["clarificationHandled"] is not None
    ]
    forbidden_applicable = [record for record in scored if record["checks"]["forbiddenClaims"]]
    forbidden_hits = [record for record in forbidden_applicable if record["derived"]["forbiddenClaimMatched"]]

    return {
        "schemaVersion": "eval-summary-v1",
        "runId": manifest["runId"],
        "datasetVersion": ((manifest.get("inputs") or {}).get("datasetVersion")),
        "versions": manifest.get("versions"),
        "requestCount": len(scored),
        "metrics": {
            "executionSuccessRate": rate(execution_checks),
            "httpStatusAccuracy": rate(http_checks),
            "bodyCodeAccuracy": rate(body_code_checks),
            "needToolAccuracy": rate(need_tool_checks),
            "toolNameAccuracy": rate(tool_name_checks),
            "argumentCompleteness": rate(required_key_checks),
            "argumentAccuracy": rate(argument_checks),
            "toolStatusAccuracy": rate(status_checks),
            "toolErrorCodeAccuracy": rate(error_code_checks),
            "toolEvidenceAccuracy": rate(result_checks),
            "ragAccuracy": rate(rag_checks),
            "chatSuccessAccuracy": rate(chat_checks),
            "answerEvidenceCoverage": rate(evidence_checks),
            "requiredBehaviorCoverage": rate(behavior_checks),
            "businessErrorHandlingRate": rate(business_error_checks),
            "clarificationHandlingRate": rate(clarification_checks),
            "unsupportedAnswerProxyRate": {
                "matchedCases": len(forbidden_hits),
                "applicableCases": len(forbidden_applicable),
                "percentage": round(len(forbidden_hits) * 100 / len(forbidden_applicable), 2) if forbidden_applicable else None,
                "matchedCaseIds": sorted(record["caseId"] for record in forbidden_hits),
                "direction": "lower_is_better",
                "scope": "Only configured forbiddenClaimPatterns; not a complete groundedness judge.",
            },
        },
        "counts": {
            "toolCalls": sum(record["actual"]["toolCallCount"] for record in scored),
            "modelCalls": {
                "value": None,
                "display": "N/A",
                "reason": "The current Java Trace does not expose real model invocation count.",
            },
        },
        "latency": {
            "serverAgentCost": latency_summary([record["actual"]["agentCostMs"] for record in scored]),
            "clientWallTime": latency_summary([record["actual"]["clientCostMs"] for record in scored]),
            "note": "A 14-case seed run validates collection and statistics only; it is not a stable performance conclusion.",
        },
        "notes": [
            "Each metric is reported independently; no weighted total score is generated.",
            "toolNameAccuracy is evaluated only for cases that expect at least one tool call.",
            "argument contains and Tool result contains values are counted as atomic assertions.",
            "ragAccuracy counts PRE_DECISION and POST_TOOL as two phase-level assertions per request; state and source must both match.",
        ],
    }


def write_json(path: Path, value: dict[str, Any]) -> None:
    temporary_path = path.with_suffix(path.suffix + ".tmp")
    temporary_path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary_path.replace(path)


def write_jsonl(path: Path, values: list[dict[str, Any]]) -> None:
    temporary_path = path.with_suffix(path.suffix + ".tmp")

    with temporary_path.open("w", encoding="utf-8", newline="\n") as file:
        for value in values:
            file.write(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n")

    temporary_path.replace(path)


def format_rate(value: dict[str, Any]) -> str:
    percentage = "N/A" if value["percentage"] is None else f"{value['percentage']:.2f}%"
    return f"{value['passed']}/{value['total']} ({percentage})"


def main() -> int:
    args = parse_args()
    run_directory = args.run_dir.resolve()
    manifest_path = run_directory / "manifest.json"
    details_path = run_directory / "details.jsonl"

    try:
        cases = load_and_validate_cases(args.schema.resolve(), args.dataset.resolve())
        cases_by_id = {case["id"]: case for case in cases}
        manifest = load_json_object(manifest_path)
        details = load_detail_records(details_path)
        validate_run_inputs(manifest, details, args.dataset.resolve(), cases_by_id)
        scored = [score_detail(cases_by_id[detail["caseId"]], detail) for detail in details]
        summary = build_summary(manifest, scored)
    except (OSError, TypeError, ValueError) as exc:
        print(f"M4-4 scoring failed: {exc}")
        return 1

    scored_details_path = run_directory / "scored-details.jsonl"
    summary_path = run_directory / "summary.json"
    write_jsonl(scored_details_path, scored)
    write_json(summary_path, summary)

    metrics = summary["metrics"]
    proxy = metrics["unsupportedAnswerProxyRate"]
    proxy_percentage = "N/A" if proxy["percentage"] is None else f"{proxy['percentage']:.2f}%"

    print("M4-4 Evaluation scoring finished")
    print(f"runDirectory={run_directory}")
    print(f"records={len(scored)}")
    print(f"needToolAccuracy={format_rate(metrics['needToolAccuracy'])}")
    print(f"toolNameAccuracy={format_rate(metrics['toolNameAccuracy'])}")
    print(f"argumentCompleteness={format_rate(metrics['argumentCompleteness'])}")
    print(f"argumentAccuracy={format_rate(metrics['argumentAccuracy'])}")
    print(f"toolEvidenceAccuracy={format_rate(metrics['toolEvidenceAccuracy'])}")
    print(f"ragAccuracy={format_rate(metrics['ragAccuracy'])}")
    print(f"answerEvidenceCoverage={format_rate(metrics['answerEvidenceCoverage'])}")
    print(
        "unsupportedAnswerProxyRate="
        f"{proxy['matchedCases']}/{proxy['applicableCases']} ({proxy_percentage})"
    )
    print(f"businessErrorHandlingRate={format_rate(metrics['businessErrorHandlingRate'])}")
    print(f"clarificationHandlingRate={format_rate(metrics['clarificationHandlingRate'])}")
    print(f"scoredDetails={scored_details_path}")
    print(f"summary={summary_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
