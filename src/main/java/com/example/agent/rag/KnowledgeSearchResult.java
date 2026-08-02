package com.example.agent.rag;

/**
 * 本地知识检索结果。
 *
 * @param matched 是否命中知识
 * @param source 知识来源
 * @param content 检索到的知识内容
 */
public record KnowledgeSearchResult(boolean matched, String source, String content) {

	public static KnowledgeSearchResult found(String source, String content) {
		return new KnowledgeSearchResult(true, source, content);
	}

	public static KnowledgeSearchResult notFound() {
		return new KnowledgeSearchResult(false, null, null);
	}

}