"""Generate a readable Markdown report from M4-4 scoring artifacts.

This script is presentation-only. It does not call the service, rerun scoring,
or modify the immutable raw manifest/details files of a completed run.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Iterable


METRIC_LABELS = {
    "executionSuccessRate": "请求执行成功率",
    "httpStatusAccuracy": "HTTP 状态正确率",
    "bodyCodeAccuracy": "响应 Body Code 正确率",
    "needToolAccuracy": "needTool accuracy",
    "toolNameAccuracy": "toolName accuracy",
    "argumentCompleteness": "参数完整率",
    "argumentAccuracy": "参数正确率",
    "toolStatusAccuracy": "Tool 状态正确率",
    "toolErrorCodeAccuracy": "Tool 错误码正确率",
    "toolEvidenceAccuracy": "Tool Evidence 正确率",
    "ragAccuracy": "RAG accuracy",
    "chatSuccessAccuracy": "Chat 状态正确率",
    "answerEvidenceCoverage": "Answer Evidence 覆盖率",
    "requiredBehaviorCoverage": "要求行为覆盖率",
    "businessErrorHandlingRate": "Business Error 处理率",
    "clarificationHandlingRate": "澄清处理率",
}

METRIC_ORDER = list(METRIC_LABELS)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate report.md for one scored Evaluation run.")
    parser.add_argument(
        "--run-dir",
        type=Path,
        required=True,
        help="Directory containing manifest.json, summary.json, and scored-details.jsonl.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="Optional report path. Default: <run-dir>/report.md",
    )
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


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        raise ValueError(f"File does not exist: {path}")

    values: list[dict[str, Any]] = []

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
                raise ValueError(f"Each JSONL line must be an object: line={line_number}")

            values.append(value)

    if not values:
        raise ValueError(f"No records found: {path}")

    return values


def validate_inputs(
    manifest: dict[str, Any], summary: dict[str, Any], scored_details: list[dict[str, Any]]
) -> None:
    if manifest.get("schemaVersion") != "eval-run-manifest-v1":
        raise ValueError(f"Unsupported manifest schemaVersion: {manifest.get('schemaVersion')}")

    if summary.get("schemaVersion") != "eval-summary-v1":
        raise ValueError(f"Unsupported summary schemaVersion: {summary.get('schemaVersion')}")

    if manifest.get("status") != "COMPLETED":
        raise ValueError(f"Only a COMPLETED run can be reported: status={manifest.get('status')}")

    run_id = manifest.get("runId")

    if summary.get("runId") != run_id:
        raise ValueError(f"summary.runId does not match manifest.runId: {summary.get('runId')} != {run_id}")

    if summary.get("requestCount") != len(scored_details):
        raise ValueError(
            "summary.requestCount does not match scored-details.jsonl: "
            f"summary={summary.get('requestCount')}, details={len(scored_details)}"
        )

    sequences: set[int] = set()

    for index, record in enumerate(scored_details, start=1):
        if record.get("schemaVersion") != "eval-scored-detail-v1":
            raise ValueError(
                f"Unsupported scored detail schemaVersion at line {index}: {record.get('schemaVersion')}"
            )

        if record.get("runId") != run_id:
            raise ValueError(f"Scored detail runId mismatch at line {index}: {record.get('runId')}")

        sequence = record.get("sequence")

        if not isinstance(sequence, int) or sequence <= 0 or sequence in sequences:
            raise ValueError(f"Invalid or duplicate scored detail sequence at line {index}: {sequence}")

        sequences.add(sequence)


def markdown_text(value: Any) -> str:
    if value is None:
        return "N/A"
    return str(value).replace("|", "\\|").replace("\r", " ").replace("\n", " ")


def code_text(value: Any) -> str:
    if value is None:
        return "`N/A`"
    text = markdown_text(value).replace("`", "'")
    return f"`{text}`"


def percentage(value: Any) -> str:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return "N/A"
    return f"{value:.2f}%"


def rate_text(metric: dict[str, Any]) -> str:
    return f"{metric.get('passed', 0)} / {metric.get('total', 0)} / {percentage(metric.get('percentage'))}"


def failed_cases_text(metric: dict[str, Any]) -> str:
    failed_case_ids = metric.get("failedCaseIds") or []
    return "—" if not failed_case_ids else ", ".join(code_text(case_id) for case_id in failed_case_ids)


def compact_json(value: Any, limit: int = 180) -> str:
    text = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    text = text.replace("\r", " ").replace("\n", " ")

    if len(text) > limit:
        text = text[: limit - 1] + "…"

    return text


def collect_failed_checks(value: Any, path: str = "") -> list[tuple[str, Any, Any]]:
    failures: list[tuple[str, Any, Any]] = []

    if isinstance(value, dict):
        if "passed" in value and value.get("passed") is False:
            failures.append((value.get("label") or path, value.get("expected"), value.get("actual")))
        else:
            for key, child in value.items():
                child_path = f"{path}.{key}" if path else key
                failures.extend(collect_failed_checks(child, child_path))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            failures.extend(collect_failed_checks(child, f"{path}[{index}]"))

    return failures


def all_checks_pass(checks: Iterable[dict[str, Any]]) -> bool:
    return all(check.get("passed") is True for check in checks)


def answer_checks_pass(record: dict[str, Any]) -> bool:
    checks = record["checks"]
    answer_checks = [
        *(checks.get("answerEvidence") or []),
        *(checks.get("requiredBehaviors") or []),
        *(checks.get("forbiddenClaims") or []),
    ]
    return all_checks_pass(answer_checks)


def rag_checks_pass(record: dict[str, Any]) -> bool:
    rag = record["checks"]["rag"]
    return rag["preDecision"].get("passed") is True and rag["postTool"].get("passed") is True


def tool_names(record: dict[str, Any]) -> str:
    names = [call.get("name") or "UNKNOWN" for call in record["actual"].get("toolCalls") or []]
    return "—" if not names else " → ".join(code_text(name) for name in names)


def status_text(passed: bool) -> str:
    return "PASS" if passed else "FAIL"


def build_summary_points(summary: dict[str, Any]) -> list[str]:
    metrics = summary["metrics"]
    points: list[str] = []

    execution = metrics["executionSuccessRate"]
    points.append(f"请求采集：{rate_text(execution)}。")

    need_tool = metrics["needToolAccuracy"]
    if need_tool.get("failedCaseIds"):
        points.append(
            f"工具调用边界：{rate_text(need_tool)}；偏差用例为 {failed_cases_text(need_tool)}。"
        )
    else:
        points.append(f"工具调用边界：{rate_text(need_tool)}，本轮未发现偏差。")

    tool_evidence = metrics["toolEvidenceAccuracy"]
    points.append(f"确定性工具证据：{rate_text(tool_evidence)}。")

    clarification = metrics["clarificationHandlingRate"]
    if clarification.get("failedCaseIds"):
        points.append(
            f"信息不足时的澄清处理：{rate_text(clarification)}；需检查 {failed_cases_text(clarification)}。"
        )
    else:
        points.append(f"信息不足时的澄清处理：{rate_text(clarification)}。")

    proxy = metrics["unsupportedAnswerProxyRate"]
    points.append(
        "无依据回答率（规则代理指标）："
        f"{proxy.get('matchedCases', 0)} / {proxy.get('applicableCases', 0)} / "
        f"{percentage(proxy.get('percentage'))}；该指标只覆盖已配置正则。"
    )
    return points


def render_report(
    manifest: dict[str, Any], summary: dict[str, Any], scored_details: list[dict[str, Any]]
) -> str:
    metrics = summary["metrics"]
    versions = manifest.get("versions") or {}
    inputs = manifest.get("inputs") or {}
    execution = manifest.get("execution") or {}
    result = manifest.get("result") or {}
    counts = summary.get("counts") or {}
    latency = summary.get("latency") or {}
    lines: list[str] = []

    lines.append("# AI Agent Evaluation 报告")
    lines.append("")
    lines.append("> 本报告由固定规则从 Trace 和最终回答中生成。各指标独立展示，不计算加权总分。")
    lines.append("")
    lines.append("## 一、运行信息")
    lines.append("")
    lines.append("| 项目 | 值 |")
    lines.append("|---|---|")
    lines.append(f"| Run ID | {code_text(manifest.get('runId'))} |")
    lines.append(f"| 状态 | {code_text(manifest.get('status'))} |")
    lines.append(f"| 开始时间 | {code_text(manifest.get('startedAt'))} |")
    lines.append(f"| 结束时间 | {code_text(manifest.get('finishedAt'))} |")
    lines.append(f"| Dataset | {code_text(inputs.get('datasetVersion'))} |")
    lines.append(f"| Dataset SHA-256 | {code_text(inputs.get('datasetSha256'))} |")
    lines.append(f"| Model | {code_text(versions.get('model'))} |")
    lines.append(f"| Prompt | {code_text(versions.get('prompt'))} |")
    lines.append(f"| Knowledge | {code_text(versions.get('knowledge'))} |")
    lines.append(f"| Code Commit | {code_text(versions.get('code'))} |")
    lines.append(f"| 并发数 | {code_text(execution.get('concurrency'))} |")
    lines.append(f"| 成功请求 | {result.get('succeededRequestCount', 0)} |")
    lines.append(f"| 失败请求 | {result.get('failedRequestCount', 0)} |")
    lines.append("")
    lines.append("## 二、结论摘要")
    lines.append("")

    for point in build_summary_points(summary):
        lines.append(f"- {point}")

    lines.append("")
    lines.append("## 三、规则指标")
    lines.append("")
    lines.append("| 指标 | 分子 / 分母 / 百分比 | 未通过用例 |")
    lines.append("|---|---:|---|")

    for metric_name in METRIC_ORDER:
        metric = metrics[metric_name]
        lines.append(
            f"| {METRIC_LABELS[metric_name]} | {rate_text(metric)} | {failed_cases_text(metric)} |"
        )

    proxy = metrics["unsupportedAnswerProxyRate"]
    matched_ids = proxy.get("matchedCaseIds") or []
    matched_text = "—" if not matched_ids else ", ".join(code_text(case_id) for case_id in matched_ids)
    lines.append(
        "| 无依据回答率（规则代理指标，越低越好） | "
        f"{proxy.get('matchedCases', 0)} / {proxy.get('applicableCases', 0)} / "
        f"{percentage(proxy.get('percentage'))} | {matched_text} |"
    )
    lines.append("")
    lines.append("指标口径：")
    lines.append("")
    lines.append("- `toolName accuracy` 只以期望调用 Tool 的用例为分母；额外调用由 `needTool accuracy` 反映。")
    lines.append("- 参数和 Tool Result 中的每个期望值都是一条原子断言，因此报告同时保留分子和分母。")
    lines.append("- RAG 每个请求包含 PRE_DECISION、POST_TOOL 两个阶段断言；状态和来源同时正确才通过。")
    lines.append("- 无依据回答率仅表示命中预先定义的禁用正则，不等价于完整 groundedness Judge。")
    lines.append("")
    lines.append("## 四、调用次数与延迟")
    lines.append("")
    model_calls = counts.get("modelCalls") or {}
    lines.append(f"- Tool 调用次数：{counts.get('toolCalls', 0)}")
    lines.append(
        f"- 模型调用次数：{model_calls.get('display', 'N/A')}。"
        f"{model_calls.get('reason', '')}"
    )
    lines.append("")
    lines.append("| 延迟口径 | 样本数 | Min | Mean | P50 | P95 | Max |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|")

    for key, label in (("serverAgentCost", "服务端 Agent"), ("clientWallTime", "客户端 Wall Time")):
        value = latency.get(key) or {}
        lines.append(
            f"| {label} | {value.get('count', 0)} | {value.get('minMs', 'N/A')} ms | "
            f"{value.get('meanMs', 'N/A')} ms | {value.get('p50Ms', 'N/A')} ms | "
            f"{value.get('p95Ms', 'N/A')} ms | {value.get('maxMs', 'N/A')} ms |"
        )

    lines.append("")
    lines.append("P50/P95 使用 nearest-rank。当前仅14条种子样例，只用于验证统计链路，不作为稳定性能结论。")
    lines.append("")
    lines.append("## 五、逐题结果")
    lines.append("")
    lines.append("| # | Case ID | 类别 | 期望结果 | 实际 Tool | Tool 边界 | RAG | Answer | 契约 | 服务端 / 客户端 |")
    lines.append("|---:|---|---|---|---|---|---|---|---|---:|")

    ordered_records = sorted(scored_details, key=lambda record: record["sequence"])

    for record in ordered_records:
        actual = record["actual"]
        checks = record["checks"]
        lines.append(
            f"| {record['sequence']} | {code_text(record['caseId'])} | {record['category']} | "
            f"{record['expectedOutcome']} | {tool_names(record)} | "
            f"{status_text(checks['needTool']['passed'])} | {status_text(rag_checks_pass(record))} | "
            f"{status_text(answer_checks_pass(record))} | "
            f"{status_text(record['derived']['fullContractPassed'])} | "
            f"{actual.get('agentCostMs', 'N/A')} / {actual.get('clientCostMs', 'N/A')} ms |"
        )

    failed_records = [record for record in ordered_records if not record["derived"]["fullContractPassed"]]
    lines.append("")
    lines.append("## 六、失败明细")
    lines.append("")

    if not failed_records:
        lines.append("本轮没有发现未通过固定契约的用例。")
    else:
        for record in failed_records:
            lines.append(f"### {record['caseId']}")
            lines.append("")

            for label, expected, actual in collect_failed_checks(record["checks"]):
                lines.append(
                    f"- `{markdown_text(label)}`：expected=`{markdown_text(compact_json(expected))}`；"
                    f"actual=`{markdown_text(compact_json(actual))}`。"
                )

            lines.append("")

    lines.append("## 七、限制与后续用途")
    lines.append("")
    lines.append("- 这是种子集校准报告，不是项目最终效果报告。")
    lines.append("- 规则断言只能检查已定义的 Tool、RAG、证据词组和禁用表达，不能覆盖全部自然语言事实。")
    lines.append("- 当前 Trace 没有提供真实模型调用次数，因此必须显示 `N/A`。")
    lines.append("- 种子集稳定后应扩充到不少于50条，再冻结 Spring AI 当前基线。")
    lines.append("- 后续 M5/M6/M7 应使用冻结数据集重复运行，分别比较向量 RAG、检索策略和模型差异。")
    lines.append("")
    return "\n".join(lines)


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_suffix(path.suffix + ".tmp")
    temporary_path.write_text(content, encoding="utf-8", newline="\n")
    temporary_path.replace(path)


def main() -> int:
    args = parse_args()
    run_directory = args.run_dir.resolve()
    output_path = args.output.resolve() if args.output else run_directory / "report.md"

    try:
        manifest = load_json_object(run_directory / "manifest.json")
        summary = load_json_object(run_directory / "summary.json")
        scored_details = load_jsonl(run_directory / "scored-details.jsonl")
        validate_inputs(manifest, summary, scored_details)
        report = render_report(manifest, summary, scored_details)
        write_text(output_path, report)
    except (OSError, TypeError, ValueError) as exc:
        print(f"M4-5 report generation failed: {exc}")
        return 1

    print("M4-5 Evaluation report generated")
    print(f"runDirectory={run_directory}")
    print(f"records={len(scored_details)}")
    print(f"report={output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
