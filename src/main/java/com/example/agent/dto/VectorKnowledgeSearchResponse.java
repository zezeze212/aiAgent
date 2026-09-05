package com.example.agent.dto;

import java.util.List;

/**
 * 一次向量检索结果。
 */
public record VectorKnowledgeSearchResponse(String query, int topK, long costMs, List<VectorKnowledgeSearchHit> hits) {

}