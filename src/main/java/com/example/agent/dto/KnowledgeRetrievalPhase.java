package com.example.agent.dto;

/**
 * 知识检索在 Agent 编排流程中所处的阶段。
 */
public enum KnowledgeRetrievalPhase {

	/**
	 * 首次 AI 决策之前，根据用户原始问题检索知识。
	 */
	PRE_DECISION,

	/**
	 * 工具执行成功之后，结合用户问题和工具结果补充检索。
	 */
	POST_TOOL

}