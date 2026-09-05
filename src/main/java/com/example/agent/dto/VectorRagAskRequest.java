package com.example.agent.dto;

/**
 * Vector RAG 问答请求。
 */
public record VectorRagAskRequest(String query, Integer topK) {
}