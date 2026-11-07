package com.example.agent.rag;

import com.example.agent.rag.vector.VectorKnowledgeRetriever;
import com.example.agent.rag.vector.VectorKnowledgeService;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;

/**
 * 正式 Agent 的串行 Hybrid 检索器。
 *
 * 两路分别召回 TopK，经过快照检查后使用现有 RRF 融合。 不在这里执行重排或上下文预算筛选。
 */
public final class HybridKnowledgeRetriever implements KnowledgeRetriever, Closeable {

	private final VectorKnowledgeRetriever dense;

	private final Bm25KnowledgeRetriever bm25;

	public HybridKnowledgeRetriever(VectorKnowledgeService vectorService, Bm25KnowledgeRetriever bm25) {

		Objects.requireNonNull(vectorService);
		this.bm25 = Objects.requireNonNull(bm25);

		VectorKnowledgeService.SearchPolicy policy = vectorService.searchPolicy();

		if (!Objects.equals(policy.category(), bm25.category())
				|| !Objects.equals(policy.visibility(), bm25.visibility())) {
			throw new IllegalStateException("Hybrid 两路检索范围不一致");
		}

		this.dense = new VectorKnowledgeRetriever(vectorService);
	}

	@Override
	public KnowledgeRetrievalResult retrieve(String query, int topK) {
		if (topK < 1 || topK > 20) {
			throw new IllegalArgumentException("topK 必须在 1～20 之间");
		}

		if (query == null || query.isBlank()) {
			return KnowledgeRetrievalResult.empty("hybrid");
		}

		String normalizedQuery = query.trim();

		/*
		 * 当前串行执行，每路 candidateTopK = 最终 topK。 任一路异常直接传播，由正式知识支持层记录检索失败。
		 */
		KnowledgeRetrievalResult denseResult = dense.retrieve(normalizedQuery, topK);

		// 检查全部 Dense 候选，不仅检查两路共同命中的片段。
		bm25.requireSameSnapshot(denseResult.chunks());

		KnowledgeRetrievalResult bm25Result = bm25.retrieve(normalizedQuery, topK);

		RrfKnowledgeFusion.FusionResult fusion = RrfKnowledgeFusion.fuse(denseResult.chunks(), bm25Result.chunks(),
				topK);

		return new KnowledgeRetrievalResult("hybrid", fusion.hits());
	}

	@Override
	public void close() throws IOException {
		// 本实例拥有内部 BM25 索引；Vector 服务由 Spring 管理。
		bm25.close();
	}

}