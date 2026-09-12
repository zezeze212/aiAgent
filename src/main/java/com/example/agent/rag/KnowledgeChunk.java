package com.example.agent.rag;

/**
 * 正式 Agent 使用的统一知识片段。
 *
 * 规则检索可以把一篇完整文档表示为一个片段； 向量检索则保留实际返回的 Chunk。
 *
 * @param chunkId 片段标识，用于请求内去重和来源追溯
 * @param source 来源文档路径
 * @param title 文档标题，规则适配暂不提取
 * @param section 所属章节
 * @param score 向量相似度；规则检索没有相似度，使用 null
 * @param content 知识正文
 */
public record KnowledgeChunk(String chunkId, String source, String title, String section, Double score,
		String content) {
}