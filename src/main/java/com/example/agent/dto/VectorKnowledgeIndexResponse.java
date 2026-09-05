package com.example.agent.dto;

/**
 * 知识入库结果。
 */
public record VectorKnowledgeIndexResponse(String knowledgeVersion, int sourceCount, int chunkCount, long costMs) {

}