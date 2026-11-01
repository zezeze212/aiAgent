package com.example.agent.dto;

import com.example.agent.rag.KnowledgeChunk;

import java.util.List;
import java.util.Map;

/**
 * 检索基线结果。
 *
 * configuration 保存固定实验配置； diagnostics 保存请求级候选、排名与耗时，不参与配置冻结比较。
 */
public record RagBenchmarkResponse(String schemaVersion, String strategy, String resultUnit, String query,
		int requestedTopK, int effectiveTopK, double costMs, List<KnowledgeChunk> hits,
		Map<String, Object> configuration, Map<String, Object> diagnostics) {

	public RagBenchmarkResponse(String schemaVersion, String strategy, String resultUnit, String query,
			int requestedTopK, int effectiveTopK, double costMs, List<KnowledgeChunk> hits,
			Map<String, Object> configuration) {

		this(schemaVersion, strategy, resultUnit, query, requestedTopK, effectiveTopK, costMs, hits, configuration,
				Map.of());
	}
}