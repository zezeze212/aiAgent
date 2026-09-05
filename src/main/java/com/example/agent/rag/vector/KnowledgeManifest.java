package com.example.agent.rag.vector;

import java.util.List;

/**
 * 一次知识索引使用的 Manifest。
 *
 * @param knowledgeVersion 知识版本
 * @param documents 本版本允许进入向量库的文档
 */
public record KnowledgeManifest(String knowledgeVersion, List<KnowledgeSource> documents) {

	public KnowledgeManifest {
		documents = documents == null ? List.of() : List.copyOf(documents);
	}

}