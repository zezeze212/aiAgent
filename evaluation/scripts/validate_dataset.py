"""Validate an ai-agent-demo evaluation JSONL dataset.

This script performs two layers of validation:

1. JSON Schema validation for every JSONL record.
2. Cross-record and project-specific semantic validation that JSON Schema
   cannot express clearly, such as duplicate case IDs and outcome consistency.

It does not start the Java application and does not call the model.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SCHEMA_PATH = PROJECT_ROOT / "evaluation" / "schemas" / "eval-case-v1.schema.json"
DEFAULT_DATASET_PATH = PROJECT_ROOT / "evaluation" / "datasets" / "eval-v1-seed.jsonl"


@dataclass(frozen=True)
class ValidationIssue:
    """One validation problem with an optional case and JSON path."""

    line_number: int | None
    case_id: str | None
    path: str
    message: str

    def format(self) -> str:
        location_parts: list[str] = []

        if self.line_number is not None:
            location_parts.append(f"line={self.line_number}")

        if self.case_id:
            location_parts.append(f"id={self.case_id}")

        location = ", ".join(location_parts) if location_parts else "dataset"

        return f"[{location}] path={self.path}: {self.message}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate Evaluation JSONL data against eval-case-v1 schema.")

    parser.add_argument(
        "--schema",
        type=Path,
        default=DEFAULT_SCHEMA_PATH,
        help=f"JSON Schema path. Default: {DEFAULT_SCHEMA_PATH}",
    )

    parser.add_argument(
        "--dataset",
        type=Path,
        default=DEFAULT_DATASET_PATH,
        help=f"JSONL dataset path. Default: {DEFAULT_DATASET_PATH}",
    )

    return parser.parse_args()


def load_json_file(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise ValueError(f"File does not exist: {path}")

    try:
        with path.open("r", encoding="utf-8-sig") as file:
            value = json.load(file)
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON file: {path}, line={exc.lineno}, column={exc.colno}, error={exc.msg}") from exc

    if not isinstance(value, dict):
        raise ValueError(f"JSON root must be an object: {path}")

    return value


def load_jsonl(path: Path) -> tuple[list[dict[str, Any]], list[ValidationIssue]]:
    cases: list[dict[str, Any]] = []
    issues: list[ValidationIssue] = []

    if not path.is_file():
        return cases, [ValidationIssue(None, None, "$", f"File does not exist: {path}")]

    with path.open("r", encoding="utf-8-sig") as file:
        for line_number, raw_line in enumerate(file, start=1):
            line = raw_line.strip()

            if not line:
                issues.append(ValidationIssue(line_number, None, "$", "Blank lines are not allowed in JSONL."))
                continue

            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                issues.append(
                    ValidationIssue(
                        line_number,
                        None,
                        "$",
                        f"Invalid JSON, column={exc.colno}, error={exc.msg}",
                    )
                )
                continue

            if not isinstance(value, dict):
                issues.append(ValidationIssue(line_number, None, "$", "Each JSONL line must be a JSON object."))
                continue

            # The line number is evaluator metadata. It is not written back into the dataset.
            value["__lineNumber"] = line_number
            cases.append(value)

    return cases, issues


def build_json_path(parts: list[Any]) -> str:
    if not parts:
        return "$"

    path = "$"

    for part in parts:
        if isinstance(part, int):
            path += f"[{part}]"
        else:
            path += f".{part}"

    return path


def validate_schema_contract(schema: dict[str, Any]) -> list[ValidationIssue]:
    try:
        Draft202012Validator.check_schema(schema)
    except SchemaError as exc:
        return [
            ValidationIssue(
                None,
                None,
                build_json_path(list(exc.path)),
                f"The JSON Schema itself is invalid: {exc.message}",
            )
        ]

    return []


def validate_cases_with_schema(
    schema: dict[str, Any], cases: list[dict[str, Any]]
) -> list[ValidationIssue]:
    validator = Draft202012Validator(schema)
    issues: list[ValidationIssue] = []

    for case in cases:
        line_number = case.pop("__lineNumber")
        case_id = case.get("id") if isinstance(case.get("id"), str) else None

        errors = sorted(
            validator.iter_errors(case),
            key=lambda error: tuple(str(part) for part in error.absolute_path),
        )

        for error in errors:
            issues.append(
                ValidationIssue(
                    line_number,
                    case_id,
                    build_json_path(list(error.absolute_path)),
                    error.message,
                )
            )

        case["__lineNumber"] = line_number

    return issues


def validate_cross_record_rules(cases: list[dict[str, Any]]) -> list[ValidationIssue]:
    issues: list[ValidationIssue] = []
    first_line_by_id: dict[str, int] = {}
    dataset_versions: set[str] = set()

    for case in cases:
        line_number = case["__lineNumber"]
        case_id = case.get("id")
        dataset_version = case.get("datasetVersion")

        if isinstance(case_id, str):
            if case_id in first_line_by_id:
                issues.append(
                    ValidationIssue(
                        line_number,
                        case_id,
                        "$.id",
                        f"Duplicate case ID; first appeared on line {first_line_by_id[case_id]}.",
                    )
                )
            else:
                first_line_by_id[case_id] = line_number

        if isinstance(dataset_version, str):
            dataset_versions.add(dataset_version)

    if len(dataset_versions) > 1:
        issues.append(
            ValidationIssue(
                None,
                None,
                "$.datasetVersion",
                f"One JSONL file must use one datasetVersion, actual={sorted(dataset_versions)}",
            )
        )

    return issues


def validate_project_semantics(cases: list[dict[str, Any]]) -> list[ValidationIssue]:
    issues: list[ValidationIssue] = []

    for case in cases:
        line_number = case["__lineNumber"]
        case_id = case.get("id")
        expected = case.get("expected")

        # Schema errors are reported separately. Skip semantic checks when the
        # object does not have the minimum shape needed by this function.
        if not isinstance(expected, dict):
            continue

        outcome = expected.get("outcome")
        tool_calls = expected.get("toolCalls")
        rag = expected.get("rag")
        chat = expected.get("chat")

        if not isinstance(tool_calls, list) or not isinstance(rag, dict) or not isinstance(chat, dict):
            continue

        post_tool = rag.get("postTool")
        post_tool_state = post_tool.get("state") if isinstance(post_tool, dict) else None
        chat_success = chat.get("success")

        if not tool_calls and post_tool_state != "NOT_RUN":
            issues.append(
                ValidationIssue(
                    line_number,
                    case_id,
                    "$.expected.rag.postTool.state",
                    "A no-tool case must use NOT_RUN for POST_TOOL RAG.",
                )
            )

        if outcome == "CLARIFICATION":
            if tool_calls:
                issues.append(
                    ValidationIssue(
                        line_number,
                        case_id,
                        "$.expected.toolCalls",
                        "A CLARIFICATION seed case must not expect tool execution.",
                    )
                )

            if chat_success is not True:
                issues.append(
                    ValidationIssue(
                        line_number,
                        case_id,
                        "$.expected.chat.success",
                        "A normal clarification response must expect chat success=true.",
                    )
                )

        if outcome == "BUSINESS_ERROR":
            if not tool_calls:
                issues.append(
                    ValidationIssue(
                        line_number,
                        case_id,
                        "$.expected.toolCalls",
                        "A BUSINESS_ERROR case must expect at least one tool call.",
                    )
                )
            else:
                last_tool_call = tool_calls[-1]

                if last_tool_call.get("status") != "BUSINESS_ERROR":
                    issues.append(
                        ValidationIssue(
                            line_number,
                            case_id,
                            "$.expected.toolCalls[-1].status",
                            "A BUSINESS_ERROR case must end with tool status BUSINESS_ERROR.",
                        )
                    )

                if not last_tool_call.get("errorCode"):
                    issues.append(
                        ValidationIssue(
                            line_number,
                            case_id,
                            "$.expected.toolCalls[-1].errorCode",
                            "A BUSINESS_ERROR case must define a non-empty errorCode.",
                        )
                    )

            if chat_success is not False:
                issues.append(
                    ValidationIssue(
                        line_number,
                        case_id,
                        "$.expected.chat.success",
                        "A governed tool business failure must expect chat success=false.",
                    )
                )

            if post_tool_state != "NOT_RUN":
                issues.append(
                    ValidationIssue(
                        line_number,
                        case_id,
                        "$.expected.rag.postTool.state",
                        "POST_TOOL RAG must not run after a governed tool business failure.",
                    )
                )

        for index, tool_call in enumerate(tool_calls):
            if not isinstance(tool_call, dict):
                continue

            status = tool_call.get("status")
            error_code = tool_call.get("errorCode")

            if status == "SUCCESS" and error_code is not None:
                issues.append(
                    ValidationIssue(
                        line_number,
                        case_id,
                        f"$.expected.toolCalls[{index}].errorCode",
                        "A successful tool call must use errorCode=null.",
                    )
                )

            if status == "BUSINESS_ERROR" and not error_code:
                issues.append(
                    ValidationIssue(
                        line_number,
                        case_id,
                        f"$.expected.toolCalls[{index}].errorCode",
                        "A BUSINESS_ERROR tool call must define a non-empty errorCode.",
                    )
                )

    return issues


def print_summary(schema_path: Path, dataset_path: Path, cases: list[dict[str, Any]]) -> None:
    dataset_versions = sorted({case["datasetVersion"] for case in cases})
    category_counts = Counter(case["category"] for case in cases)
    outcome_counts = Counter(case["expected"]["outcome"] for case in cases)
    need_tool_counts = Counter(bool(case["expected"]["toolCalls"]) for case in cases)
    pre_rag_counts = Counter(case["expected"]["rag"]["preDecision"]["state"] for case in cases)

    print("M4-2 Evaluation dataset validation passed")
    print(f"schema={schema_path}")
    print(f"dataset={dataset_path}")
    print(f"cases={len(cases)}")
    print(f"datasetVersion={dataset_versions[0] if len(dataset_versions) == 1 else dataset_versions}")
    print(f"needTool=true:{need_tool_counts[True]}, false:{need_tool_counts[False]}")
    print("categories:")

    for name in sorted(category_counts):
        print(f"  - {name}: {category_counts[name]}")

    print("outcomes:")

    for name in sorted(outcome_counts):
        print(f"  - {name}: {outcome_counts[name]}")

    print("preDecisionRag:")

    for name in sorted(pre_rag_counts):
        print(f"  - {name}: {pre_rag_counts[name]}")


def main() -> int:
    args = parse_args()

    try:
        schema = load_json_file(args.schema)
    except ValueError as exc:
        print(f"M4-2 Evaluation dataset validation failed\n- {exc}", file=sys.stderr)
        return 1

    cases, issues = load_jsonl(args.dataset)

    issues.extend(validate_schema_contract(schema))

    if not issues:
        issues.extend(validate_cases_with_schema(schema, cases))

    issues.extend(validate_cross_record_rules(cases))
    issues.extend(validate_project_semantics(cases))

    if issues:
        print(f"M4-2 Evaluation dataset validation failed: issues={len(issues)}", file=sys.stderr)

        for issue in issues:
            print(f"- {issue.format()}", file=sys.stderr)

        return 1

    print_summary(args.schema.resolve(), args.dataset.resolve(), cases)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
