package com.example.agent.rag;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 统一检索结果。
 *
 * chunks 保留检索器返回的顺序，不在这里重新排序或拼接正文。 返回候选不代表这些候选已经足以支持答案。
 */
public record KnowledgeRetrievalResult(String retriever, List<KnowledgeChunk> chunks) {

	public KnowledgeRetrievalResult {
		chunks = chunks == null ? List.of() : List.copyOf(chunks);
	}

	/**
	 * matched 仅表示返回了候选片段，不表示答案可靠。 显式序列化该字段，方便后续 Trace 查看和评测读取。
	 */
	@JsonProperty("matched")
	public boolean matched() {
		return !chunks.isEmpty();
	}

	public static KnowledgeRetrievalResult empty(String retriever) {
		return new KnowledgeRetrievalResult(retriever, List.of());
	}
}