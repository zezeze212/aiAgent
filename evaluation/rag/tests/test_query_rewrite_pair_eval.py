"""离线验证评分与实验控制，不调用 Java 或真实模型。"""
import argparse
import copy
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import run_query_rewrite_pair_eval as evaluator

COLLECTION = "ai_agent_knowledge_managed_v1"
SOURCE = "docs/knowledge/sql/duplicate-entry.md"
CASE = {
    "schemaVersion": "rag-retrieval-case-v1",
    "datasetVersion": "fixture-v1",
    "id": "case-1",
    "query": "唯一键报错怎么查",
    "topK": 5,
    "expected": {
        "source": SOURCE,
        "section": "常见原因",
        "evidence": "接口重复提交",
    },
}


def fixture(first=False, state="APPLIED"):
    """构造目标证据由第 2 位升至第 1 位的合法成对响应。"""
    same = state != "APPLIED"
    query = CASE["query"] if same else "唯一键冲突原因排查"

    target = dict(
        chunkId="target",
        source=SOURCE,
        title="唯一键冲突",
        section="常见原因",
        content="接口重复提交，没有做幂等控制。",
        score=0.02,
    )
    other = dict(target, chunkId="other", content="其他内容", score=0.03)
    old = dict(
        query=CASE["query"],
        costMs=10.0,
        result=dict(
            retriever="hybrid",
            chunks=[other, target],
            matched=True,
        ),
    )
    new = copy.deepcopy(old) if same else dict(
        query=query,
        costMs=12.0,
        result=dict(
            retriever="hybrid",
            chunks=[
                dict(target, score=0.03),
                dict(other, score=0.02),
            ],
            matched=True,
        ),
    )

    return {
        "schemaVersion": "rag-query-rewrite-pair-v1",
        "originalQuery": CASE["query"],
        "topK": 5,
        "retrievalOrder": ["original"] if same else (
            ["rewritten", "original"] if first else ["original", "rewritten"]
        ),
        "sameQueryReused": same,
        "rewrite": {
            "query": query,
            "costMs": 5,
            "trace": {
                "status": state,
                "reason": "MODEL_UNAVAILABLE" if state == "FALLBACK" else None,
                "effectiveQuery": query,
                "model": "test-model",
                "promptVersion": "query-rewrite-v1",
            },
        },
        "original": old,
        "rewritten": new,
        "rewrittenStageCostMs": 5 + new["costMs"],
        "configuration": {
            "evaluationScope": "RETRIEVAL_BEFORE_RERANK_AND_CONTEXT",
            "rerankApplied": False,
            "contextSelectionApplied": False,
            "answerGenerationApplied": False,
            "rewrite": {
                "model": "test-model",
                "promptVersion": "query-rewrite-v1",
                "modelIdentity": "PROVIDER_ALIAS_ONLY",
                "readTimeoutMs": 5000,
                "maxInputChars": 2000,
                "maxQueryChars": 512,
                "maxResponseChars": 4096,
                "protectedTokenPattern": "fixture-pattern",
            },
            "retrieval": {
                "retriever": "hybrid",
                "retrieverImplementation":
                    "com.example.agent.rag.HybridKnowledgeRetriever",
                "category": "mysql",
                "visibility": "public",
                "sourceFilter": None,
                "candidateTopK": 5,
                "outputTopK": 5,
                "similarityThreshold": 0.0,
                "configuredCollection": COLLECTION,
                "configuredEmbeddingModel": "bge-m3",
                "configuredEmbeddingRevision": "fixture-v1",
                "knowledgeVersion": "fixture-v1",
                "indexStatePresent": True,
                "indexStateSha256": "a" * 64,
                "corpusSha256": "b" * 64,
                "indexVerification":
                    "LOCAL_STATE_ONLY_NOT_LIVE_QDRANT_VERIFICATION",
                "corpusDocumentCount": 1,
                "sourceInventory": [{
                    "source": SOURCE,
                    "title": "唯一键冲突",
                    "category": "mysql",
                    "visibility": "public",
                    "contentSha256": "c" * 64,
                }],
                "fusion": {
                    "engine": "rrf",
                    "rankConstant": 60,
                    "weights": [1, 1],
                    "tieBreak": "chunkId-asc",
                },
                "bm25": {
                    "engine": "lucene",
                    "luceneVersion": "9.12.3",
                    "analyzer": "SmartChineseAnalyzer",
                    "queryOperator": "SHOULD",
                    "k1": 1.2,
                    "b": 0.75,
                },
            },
        },
    }


class QueryRewritePairTests(unittest.TestCase):
    def test_request_never_contains_expected_answer(self):
        url = evaluator.build_url("http://unused", CASE, True)
        self.assertEqual(
            {"query", "topK", "rewriteFirst"},
            set(parse_qs(urlparse(url).query)),
        )
        self.assertNotIn("expected", url)

    def test_valid_orders_and_rank_improvement(self):
        for first in (False, True):
            pair = fixture(first)
            evaluator.validate_pair(pair, CASE, COLLECTION, first)
            scores = evaluator.score_pair(pair, CASE)
            self.assertEqual(2, scores["original"]["firstEvidenceRank"])
            self.assertEqual(1, scores["rewritten"]["firstEvidenceRank"])

    def test_invalid_response_cannot_be_scored_as_success(self):
        # 每次只破坏一个条件，确保错误不能混入有效评分。
        mutations = [
            lambda p: p["rewrite"].update(costMs=None),
            lambda p: p.update(rewrittenStageCostMs=999),
            lambda p: p.update(retrievalOrder=["rewritten", "original"]),
            lambda p: p.update(sameQueryReused=True),
            lambda p: p["rewritten"]["result"]["chunks"].append(
                p["rewritten"]["result"]["chunks"][0]
            ),
            lambda p: p["rewritten"]["result"]["chunks"][0].update(
                score=float("nan")
            ),
            lambda p: p["rewritten"]["result"]["chunks"][0].update(
                content="同 ID 的正文被修改"
            ),
            lambda p: p["configuration"]["retrieval"].update(
                configuredCollection="wrong"
            ),
            lambda p: p["configuration"].update(rerankApplied=True),
        ]
        for mutation in mutations:
            pair = fixture()
            mutation(pair)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                evaluator.validate_pair(pair, CASE, COLLECTION, False)

    def test_fallback_is_valid_policy_result_but_not_applied(self):
        pair = fixture(state="FALLBACK")
        evaluator.validate_pair(pair, CASE, COLLECTION, True)
        row = dict(
            caseId="case-1",
            status="SUCCESS",
            response=pair,
            scores=evaluator.score_pair(pair, CASE),
            clientCostMs=20,
        )
        summary = evaluator.summarize([row], 1, True)
        self.assertTrue(summary["comparisonReady"])
        self.assertEqual(1, summary["rewriteStates"]["FALLBACK"])
        self.assertEqual(0, summary["appliedSubset"]["count"])
        self.assertEqual(
            ["case-1"], summary["pairedMrrChanges"]["unchanged"]
        )

    def test_runner_excludes_warmup(self):
        summary, rows, calls = self.run_fixture([fixture(), fixture()])
        self.assertEqual(2, calls)
        self.assertEqual(1, len(rows))
        self.assertTrue(summary["comparisonReady"])
        self.assertEqual(
            1, summary["latencySuccessMs"]["rewriteCall"]["count"]
        )
        self.assertEqual(
            ["case-1"], summary["pairedMrrChanges"]["improved"]
        )

    def test_configuration_drift_is_error(self):
        changed = fixture()
        changed["configuration"]["rewrite"]["readTimeoutMs"] = 6000
        summary, rows, _ = self.run_fixture([fixture(), changed])
        self.assertFalse(summary["comparisonReady"])
        self.assertEqual("ConfigurationDrift", rows[0]["errorType"])
        self.assertIsNone(rows[0]["scores"])
        self.assertIsNone(
            summary["latencySuccessMs"]["rewriteCall"]["mean"]
        )

    def test_http_conflict_is_configuration_drift(self):
        error = HTTPError("http://unused", 409, "Conflict", None, None)
        with patch.object(evaluator, "urlopen", side_effect=error):
            with self.assertRaises(evaluator.ConfigurationDrift):
                evaluator.request_pair("http://unused", CASE, False, 60)

    def test_errors_and_unfinished_cases_stay_in_planned_denominator(self):
        pair = fixture()
        success = dict(
            caseId="case-1",
            status="SUCCESS",
            response=pair,
            scores=evaluator.score_pair(pair, CASE),
            clientCostMs=20,
        )
        failure = dict(
            caseId="case-2",
            status="ERROR",
            clientCostMs=30,
        )
        summary = evaluator.summarize([success, failure], 3, True)
        metric = summary["metrics"]["rewritten"]["evidenceReciprocalRankAtK"]
        self.assertAlmostEqual(1 / 3, metric["allPlanned"])
        self.assertEqual(1.0, metric["successfulOnly"])
        self.assertFalse(summary["comparisonReady"])
        self.assertEqual(1, summary["notCompletedPairs"])
        self.assertEqual(1, summary["errorPairs"])

    def test_failed_warmup_stops_batch(self):
        summary, rows, calls = self.run_fixture([
            OSError("模拟服务不可用")
        ])
        self.assertEqual("PRECHECK_FAILED", summary["status"])
        self.assertEqual([], rows)
        self.assertEqual(1, calls)
        self.assertEqual(1, summary["notCompletedPairs"])

    def test_interruption_still_saves_partial_summary(self):
        summary, rows, _ = self.run_fixture([
            fixture(), KeyboardInterrupt()
        ])
        self.assertEqual("PARTIAL", summary["status"])
        self.assertFalse(summary["comparisonReady"])
        self.assertEqual([], rows)
        self.assertEqual(1, summary["notCompletedPairs"])

    def run_fixture(self, responses):
        # 临时目录模拟完整运行，不污染项目真实评测结果。
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            dataset = root / "cases.jsonl"
            dataset.write_text(
                json.dumps(CASE, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
            args = argparse.Namespace(
                dataset=dataset,
                results_root=root / "results",
                base_url="http://unused",
                timeout_seconds=60,
                expected_collection=COLLECTION,
            )
            with patch.object(
                evaluator, "request_pair", side_effect=responses
            ) as request, patch.object(
                evaluator.baseline, "local_identity", return_value={}
            ), redirect_stdout(io.StringIO()):
                directory, summary = evaluator.run(args)

            self.assertTrue((directory / "manifest.json").is_file())
            self.assertTrue((directory / "report.md").is_file())
            rows = [
                json.loads(line)
                for line in (directory / "details.jsonl")
                .read_text(encoding="utf-8").splitlines()
            ]
            return summary, rows, request.call_count


if __name__ == "__main__":
    unittest.main()