package com.example.agent.dto;

/**
 * 提供给模型的 Chunk 级引用。
 *
 * number 对应回答中的 [1]、[2] 等编号。
 */
public record VectorRagCitation(int number, String chunkId, Double score, String source, String title, String section) {
}