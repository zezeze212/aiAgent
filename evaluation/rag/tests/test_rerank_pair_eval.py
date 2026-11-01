"""成对评测的离线测试，不连接 Java 或模型服务。"""
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

sys.path.insert(
    0, str(Path(__file__).resolve().parents[1] / "scripts")
)
import run_rerank_pair_eval as evaluator
from test_baseline_eval import CASE, response


def fixture():
    old = response("hybrid")
    candidate = dict(old["hits"][0], score=None)
    return {
        "schemaVersion": "rag-rerank-pair-v1",
        "baseline": old,
        "candidates": [candidate],
        "rerank": {
            "ranking": [dict(candidate, score=0.9)],
            "costMs": 30.0,
            "requestId": "fixture",
        },
        "rerankerConfiguration": {
            "provider": "siliconflow",
            "model": "BAAI/bge-reranker-v2-m3",
            "documentInput": "KnowledgeChunk.content",
            "returnAllCandidates": True,
            "tieBreak": "chunkId-asc",
            "applicationRetries": 0,
            "modelRevisionVerification": "PROVIDER_ALIAS_ONLY",
            "maxChunksPerDoc": 1024,
            "overlapTokens": 80,
        },
    }


class PairTests(unittest.TestCase):
    def test_rejects_candidate_loss_content_change_and_invalid_score(self):
        valid = fixture()
        evaluator.validate_pair(
            valid, CASE, "ai_agent_knowledge_managed_v1"
        )
        variants = []

        value = copy.deepcopy(valid)
        value["candidates"] = []
        variants.append(value)

        value = copy.deepcopy(valid)
        value["rerank"]["ranking"] *= 2
        variants.append(value)

        value = copy.deepcopy(valid)
        value["rerank"]["ranking"][0]["content"] = "被修改"
        variants.append(value)

        value = copy.deepcopy(valid)
        value["rerank"]["ranking"][0]["score"] = float("nan")
        variants.append(value)

        value = copy.deepcopy(valid)
        value["baseline"]["hits"][0]["score"] = 0.5
        variants.append(value)

        for value in variants:
            with self.subTest(value=value), self.assertRaises(ValueError):
                evaluator.validate_pair(
                    value, CASE, "ai_agent_knowledge_managed_v1"
                )

    def test_target_outside_rrf_top5_can_be_promoted_from_complete_union(self):
        pair = fixture()
        template = pair["candidates"][0]
        chunks = [
            dict(template, chunkId=str(i), content="无关")
            for i in range(6)
        ]
        chunks[4]["content"] = CASE["expected"]["evidence"]

        dense = chunks[:5]
        bm25 = [chunks[i] for i in (5, 0, 1, 2, 3)]
        scores = {}
        for branch in (dense, bm25):
            for rank, hit in enumerate(branch, 1):
                key = hit["chunkId"]
                scores[key] = scores.get(key, 0) + 1 / (60 + rank)

        rrf = sorted(
            [dict(h, score=scores[h["chunkId"]]) for h in chunks],
            key=lambda h: (-h["score"], h["chunkId"]),
        )
        pair["baseline"]["diagnostics"].update(
            denseHits=dense, bm25Hits=bm25
        )
        pair["baseline"]["hits"] = rrf[:5]
        pair["candidates"] = chunks
        pair["rerank"]["ranking"] = [
            dict(chunks[i], score=1 - rank * 0.1)
            for rank, i in enumerate((4, 0, 1, 2, 3, 5))
        ]

        evaluator.validate_pair(
            pair, CASE, "ai_agent_knowledge_managed_v1"
        )
        result = evaluator.score_pair(pair, CASE)

        # 并集中有证据，不代表 RRF Top5 命中。
        self.assertFalse(result["hybrid"]["evidenceHitAtK"])
        self.assertEqual(1, result["rerank"]["firstEvidenceRank"])

    def test_runner_excludes_warmup_and_preserves_one_row_per_pair(self):
        summary, rows, calls = self.run_fixture(
            [fixture(), fixture()]
        )
        self.assertEqual(2, calls)  # 一次预热、一次正式请求。
        self.assertEqual(1, len(rows))
        self.assertTrue(summary["comparisonReady"])
        self.assertEqual(
            1,
            summary["latencySuccessMs"]["rerankIncrement"]["count"],
        )

    def test_configuration_drift_is_pair_error_without_fake_latency(self):
        changed = fixture()
        changed["rerankerConfiguration"]["extraDeploymentMarker"] = (
            "changed"
        )
        summary, rows, _ = self.run_fixture([fixture(), changed])

        self.assertFalse(summary["comparisonReady"])
        self.assertEqual(1, summary["errorPairs"])
        self.assertIsNone(rows[0]["scores"])
        self.assertEqual(
            0,
            summary["latencySuccessMs"]["rerankIncrement"]["count"],
        )

    def run_fixture(self, responses):
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
                expected_collection="ai_agent_knowledge_managed_v1",
            )
            with (
                patch.object(
                    evaluator, "request_pair", side_effect=responses
                ) as request,
                patch.object(
                    evaluator.baseline,
                    "local_identity",
                    return_value={},
                ),
                redirect_stdout(io.StringIO()),
            ):
                directory, summary = evaluator.run(args)

            rows = [
                json.loads(line)
                for line in (directory / "details.jsonl")
                .read_text(encoding="utf-8")
                .splitlines()
            ]
            return summary, rows, request.call_count


if __name__ == "__main__":
    unittest.main()