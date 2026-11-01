"""Offline scoring/runner checks. No Java service, model, or Qdrant required."""
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
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import run_baseline_eval as evaluator

EXPECTED = {"source": "docs/knowledge/sql/duplicate-entry.md", "section": "常见原因", "evidence": "逻辑删除占用唯一值"}
CASE = {"schemaVersion": "rag-retrieval-case-v1", "datasetVersion": "fixture-v1", "id": "case-1",
        "query": "逻辑删除后为什么重复键", "topK": 5, "expected": EXPECTED}


def hit(identifier="chunk-1", section="常见原因", content="逻辑删除占用唯一值", source=EXPECTED["source"]):
    return {"chunkId": identifier, "source": source, "section": section, "content": content, "score": None}


def response(strategy, case=CASE):
    if strategy == "hybrid":
        dense = response("dense", case)
        bm25 = response("bm25", case)

        value = copy.deepcopy(dense)
        value["strategy"] = "hybrid"
        value["costMs"] = 2.6
        value["hits"][0]["score"] = 2.0 / 61

        value["configuration"].update(
            engine="rrf",
            rrfRankConstant=60,
            rrfWeights=[1, 1],
            candidateTopK=5,
            outputTopK=5,
            tieBreak="chunkId-asc",
            bm25Configuration=bm25["configuration"],
        )

        value["diagnostics"] = {
            "denseHits": dense["hits"],
            "bm25Hits": bm25["hits"],
            "ranking": [
                {
                    "chunkId": "chunk-1",
                    "denseRank": 1,
                    "bm25Rank": 1,
                    "rrfScore": 2.0 / 61,
                }
            ],
            "denseCostMs": 1.25,
            "bm25CostMs": 1.25,
            "fusionCostMs": 0.1,
        }
        return value

    config = {
        "knowledgeVersion": "fixture-v1",
        "corpusSha256": "abc",
        "category": "mysql",
        "visibility": "public",
        "sourceFilter": None,
        "corpusDocumentCount": 1,
        "sourceInventory": [{"source": EXPECTED["source"]}],
    }

    if strategy == "dense":
        config.update(
            configuredCollection="ai_agent_knowledge_managed_v1",
            similarityThreshold=0.0,
            indexStatePresent=True,
            indexStateSha256="state-hash",
            configuredEmbeddingModel="bge-m3:latest",
            configuredEmbeddingRevision="local-v1",
        )

    if strategy == "bm25":
        config["engine"] = "lucene"

    return {
        "schemaVersion": "rag-benchmark-response-v1",
        "strategy": strategy,
        "resultUnit": "document" if strategy == "rule" else "chunk",
        "query": case["query"],
        "requestedTopK": 5,
        "effectiveTopK": 1 if strategy == "rule" else 5,
        "costMs": 1.25,
        "hits": [
            hit(section="全文" if strategy == "rule" else "常见原因")
        ],
        "configuration": config,
    }


class ScoringTests(unittest.TestCase):
    def test_source_match_without_evidence_is_not_evidence_hit(self):
        score = evaluator.score_hits([hit(section="典型报错", content="Duplicate entry")], EXPECTED, "chunk", 5)
        self.assertTrue(score["sourceHitAt1"])
        self.assertFalse(score["evidenceAvailable"])
        self.assertFalse(score["evidenceHitAtK"])
        self.assertEqual(0, score["evidenceReciprocalRankAtK"])

    def test_fourth_chunk_and_repeated_source_do_not_fake_rank(self):
        hits = [hit(str(i), content="其他内容") for i in range(3)] + [hit("target")]
        score = evaluator.score_hits(hits, EXPECTED, "chunk", 5)
        self.assertEqual(4, score["firstEvidenceRank"])
        self.assertEqual(.25, score["evidenceReciprocalRankAtK"])
        self.assertEqual(1, score["distinctSourceCount"])

    def test_full_document_evidence_does_not_acquire_chunk_mrr(self):
        score = evaluator.score_hits([hit(section="全文")], EXPECTED, "document", 1)
        self.assertTrue(score["evidenceAvailable"])
        self.assertIsNone(score["evidenceHitAtK"])
        self.assertIsNone(score["evidenceReciprocalRankAtK"])

    def test_rank_cutoff_and_wrong_section(self):
        hits = [hit(str(i), content="无关") for i in range(5)] + [hit("sixth")]
        self.assertFalse(evaluator.score_hits(hits, EXPECTED, "chunk", 5)["evidenceHitAtK"])
        score = evaluator.score_hits([hit(section="其他章节")], EXPECTED, "chunk", 5)
        self.assertTrue(score["evidenceAvailable"])
        self.assertFalse(score["evidenceHitAtK"])

    def test_empty_and_utf16_character_count(self):
        self.assertFalse(evaluator.score_hits([], EXPECTED, "chunk", 5)["sourceHitAt1"])
        self.assertEqual(2, evaluator.score_hits([hit(content="😀")], EXPECTED, "chunk", 5)["returnedCharsUtf16"])

    def test_url_does_not_leak_expected_source_or_evidence(self):
        url = evaluator.build_url("http://localhost:8088", "dense", CASE)
        self.assertEqual({"strategy": ["dense"], "query": [CASE["query"]], "topK": ["5"]}, parse_qs(urlsplit(url).query))

    def test_missing_latency_wrong_collection_and_duplicate_ids_are_errors(self):
        valid = response("dense")
        evaluator.validate_response(valid, "dense", CASE, "ai_agent_knowledge_managed_v1")
        variants = []
        value = copy.deepcopy(valid); value.pop("costMs"); variants.append(value)
        value = copy.deepcopy(valid); value["configuration"]["configuredCollection"] = "old"; variants.append(value)
        value = copy.deepcopy(valid); value["hits"] *= 2; variants.append(value)
        value = copy.deepcopy(valid); value["configuration"]["similarityThreshold"] = .6; variants.append(value)
        for value in variants:
            with self.subTest(value=value), self.assertRaises(ValueError):
                evaluator.validate_response(value, "dense", CASE, "ai_agent_knowledge_managed_v1")

    def test_errors_remain_in_planned_denominator_and_have_no_fake_server_latency(self):
        ok = {"caseId": "ok", "strategy": "dense", "status": "SUCCESS", "clientCostMs": 2,
              "response": response("dense"), "scores": evaluator.score_hits([hit()], EXPECTED, "chunk", 5)}
        err = {"caseId": "err", "strategy": "dense", "status": "ERROR", "clientCostMs": 30, "scores": None}
        result = evaluator.summarize([ok, err], 2)["dense"]
        self.assertEqual(.5, result["metrics"]["evidenceHitAtK"]["allPlanned"])
        self.assertEqual(1, result["metrics"]["evidenceHitAtK"]["successfulOnly"])
        self.assertEqual(1, result["serverLatencySuccessMs"]["count"])
        self.assertEqual(1, result["clientLatencyErrorMs"]["count"])
        self.assertEqual([], result["evidenceMissCaseIds"])

    def test_hybrid_checks_dense_scope_and_diagnostics(self):
        valid = response("hybrid")

        evaluator.validate_response(
            valid, "hybrid", CASE, "ai_agent_knowledge_managed_v1"
        )

        variants = []

        value = copy.deepcopy(valid)
        value.pop("diagnostics")
        variants.append(value)

        value = copy.deepcopy(valid)
        value["configuration"]["configuredCollection"] = "wrong"
        variants.append(value)

        value = copy.deepcopy(valid)
        value["configuration"]["bm25Configuration"]["corpusSha256"] = "changed"
        variants.append(value)

        value = copy.deepcopy(valid)
        value["diagnostics"].pop("bm25Hits")
        variants.append(value)

        for value in variants:
            with self.subTest(value=value), self.assertRaises(ValueError):
                evaluator.validate_response(
                    value, "hybrid", CASE, "ai_agent_knowledge_managed_v1"
                )

class RunnerTests(unittest.TestCase):
    def run_fixture(self, request, strategies=None):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        dataset = root / "cases.jsonl"
        dataset.write_text(json.dumps(CASE, ensure_ascii=False) + "\n", encoding="utf-8")
        args = argparse.Namespace(dataset=dataset, results_root=root / "results", base_url="http://localhost:8088",
                                  expected_collection="ai_agent_knowledge_managed_v1", timeout_seconds=1)
        if strategies is not None:
            args.strategies = strategies
        with patch.object(evaluator, "request_search", side_effect=request), patch.object(evaluator, "local_identity", return_value={}), redirect_stdout(io.StringIO()):
            path, summary = evaluator.run(args)
        return path, summary

    def test_success_writes_artifacts_and_excludes_warmup(self):
        path, summary = self.run_fixture(lambda base, strategy, case, timeout: response(strategy, case))
        self.assertTrue(summary["comparisonReady"])
        self.assertEqual(1, summary["strategies"]["dense"]["serverLatencySuccessMs"]["count"])
        self.assertIsNone(summary["strategies"]["rule"]["metrics"]["evidenceReciprocalRankAtK"])
        self.assertEqual(2, len((path / "details.jsonl").read_text(encoding="utf-8").splitlines()))
        self.assertIn("N/A", (path / "report.md").read_text(encoding="utf-8"))

    def test_mid_run_index_drift_is_error_not_new_baseline(self):
        count = 0
        def request(base, strategy, case, timeout):
            nonlocal count
            value = response(strategy, case)
            if strategy == "dense":
                count += 1
                value["configuration"]["indexStateSha256"] = str(count)
            return value
        path, summary = self.run_fixture(request)
        self.assertFalse(summary["comparisonReady"])
        self.assertEqual(1, summary["strategies"]["dense"]["errors"])
        self.assertIn("changed", (path / "details.jsonl").read_text(encoding="utf-8"))

    def test_cross_strategy_corpus_mismatch_is_error(self):
        def request(base, strategy, case, timeout):
            value = response(strategy, case)
            if strategy == "dense":
                value["configuration"]["corpusSha256"] = "different"
            return value
        _, summary = self.run_fixture(request)
        self.assertFalse(summary["comparisonReady"])
        self.assertEqual(1, summary["strategies"]["dense"]["errors"])

    def test_interruption_still_saves_partial_summary(self):
        count = 0
        def request(base, strategy, case, timeout):
            nonlocal count
            count += 1
            if count == 3:
                raise KeyboardInterrupt()
            return response(strategy, case)
        path, summary = self.run_fixture(request)
        self.assertEqual("PARTIAL", summary["status"])
        self.assertEqual(1, summary["strategies"]["rule"]["notRun"])
        self.assertTrue((path / "summary.json").exists())

    def test_bm25_only_excludes_unselected_strategies_and_warmup(self):
        seen = []

        def request(base, strategy, case, timeout):
            seen.append(strategy)
            return response(strategy, case)

        path, summary = self.run_fixture(
            request, strategies=("bm25",)
        )

        # 一次预热、一次正式请求，均只能执行 BM25。
        self.assertEqual(["bm25", "bm25"], seen)
        self.assertEqual({"bm25"}, set(summary["strategies"]))
        self.assertTrue(summary["comparisonReady"])

        result = summary["strategies"]["bm25"]

        # 预热不进入正式耗时统计。
        self.assertEqual(
            1, result["serverLatencySuccessMs"]["count"]
        )
        self.assertEqual(
            1,
            result["metrics"]["evidenceReciprocalRankAtK"]["allPlanned"],
        )

        manifest = json.loads(
            (path / "manifest.json").read_text(encoding="utf-8")
        )
        self.assertEqual(
            ["bm25"], manifest["requestedStrategies"]
        )

    def test_hybrid_only_preserves_diagnostics_and_excludes_warmup(self):
        seen = []

        def request(base, strategy, case, timeout):
            seen.append(strategy)
            return response(strategy, case)

        path, summary = self.run_fixture(
            request, strategies=("hybrid",)
        )

        self.assertEqual(["hybrid", "hybrid"], seen)
        self.assertEqual({"hybrid"}, set(summary["strategies"]))
        self.assertTrue(summary["comparisonReady"])

        self.assertEqual(
            1,
            summary["strategies"]["hybrid"]["serverLatencySuccessMs"]["count"],
        )

        rows = [
            json.loads(line)
            for line in (path / "details.jsonl")
            .read_text(encoding="utf-8")
            .splitlines()
        ]

        self.assertEqual(1, len(rows))
        self.assertIn(
            "bm25Hits", rows[0]["response"]["diagnostics"]
        )

if __name__ == "__main__":
    unittest.main()
