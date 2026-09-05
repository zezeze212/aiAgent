package com.example.agent.dto;

import java.util.List;

/**
 * Vector RAG 问答结果。
 */
public record VectorRagAskResponse(String query, String answer, long retrievalCostMs, long generationCostMs,
		long totalCostMs, List<VectorRagCitation> citations) {
}