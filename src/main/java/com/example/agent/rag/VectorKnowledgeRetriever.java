package com.example.agent.rag.vector;

import com.example.agent.dto.VectorKnowledgeSearchResponse;
import com.example.agent.rag.KnowledgeChunk;
import com.example.agent.rag.KnowledgeRetrievalResult;
import com.example.agent.rag.KnowledgeRetriever;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 向量检索适配器。
 *
 * 复用现有 VectorKnowledgeService， 不重复实现 Embedding 和 Qdrant 查询。
 */
@RequiredArgsConstructor
public class VectorKnowledgeRetriever implements KnowledgeRetriever {

	private final VectorKnowledgeService vectorKnowledgeService;

	@Override
	public KnowledgeRetrievalResult retrieve(String query, int topK) {
		if (topK < 1 || topK > 20) {
			throw new IllegalArgumentException("topK 必须在 1～20 之间");
		}

		if (query == null || query.isBlank()) {
			return KnowledgeRetrievalResult.empty("vector");
		}

		VectorKnowledgeSearchResponse response = vectorKnowledgeService.search(query.trim(), topK);

		List<KnowledgeChunk> chunks = response.hits()
			.stream()
			.map(hit -> new KnowledgeChunk(hit.id(), hit.source(), hit.title(), hit.section(), hit.score(),
					hit.content()))
			.toList();

		return new KnowledgeRetrievalResult("vector", chunks);
	}

}