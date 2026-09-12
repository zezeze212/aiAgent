package com.example.agent.rag;

/**
 * 正式 Agent 的统一知识检索入口。
 *
 * 实现类负责查找知识并转换结果； 调用方负责检索时机、上下文注入、去重和 Trace。
 */
public interface KnowledgeRetriever {

	/**
	 * 检索相关知识。
	 * @param query 检索文本，可以是用户问题，也可以是用户问题与工具结果的组合
	 * @param topK 最多返回多少条候选；规则检索仍最多返回一篇
	 */
	KnowledgeRetrievalResult retrieve(String query, int topK);

}