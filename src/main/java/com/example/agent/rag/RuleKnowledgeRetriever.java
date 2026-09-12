package com.example.agent.rag;

import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 规则检索适配器。
 *
 * 复用原有关键词匹配与文档读取逻辑， 不改变旧 SimpleRagRetriever 的接口和行为。
 */
@RequiredArgsConstructor
public class RuleKnowledgeRetriever implements KnowledgeRetriever {

	private final SimpleRagRetriever simpleRagRetriever;

	@Override
	public KnowledgeRetrievalResult retrieve(String query, int topK) {
		if (topK < 1 || topK > 20) {
			throw new IllegalArgumentException("topK 必须在 1～20 之间");
		}

		if (query == null || query.isBlank()) {
			return KnowledgeRetrievalResult.empty("rule");
		}

		KnowledgeSearchResult result = simpleRagRetriever.retrieve(query);

		if (!result.matched()) {
			return KnowledgeRetrievalResult.empty("rule");
		}

		/*
		 * 规则检索返回完整文档，因此以来源路径构造文档级标识。 这个 ID 不冒充 Qdrant 中实际存在的 Chunk ID。
		 */
		KnowledgeChunk chunk = new KnowledgeChunk("rule:" + result.source(), result.source(), null, "全文", null,
				result.content());

		return new KnowledgeRetrievalResult("rule", List.of(chunk));
	}

}