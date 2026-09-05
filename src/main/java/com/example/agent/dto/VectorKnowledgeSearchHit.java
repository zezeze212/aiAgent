package com.example.agent.dto;

/**
 * 一条向量检索命中结果。
 */
public record VectorKnowledgeSearchHit(String id, Double score, String source, String title, String section,
		String content) {

}