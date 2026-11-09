package com.example.agent.dto;

/**
 * 本次正式 Agent 回答链的模型选择与调用信息。
 *
 * reportedModel、providerResponseId 来自最终 ChatResponse， 不使用配置值补造缺失的服务端信息。
 */
public record ModelExecutionInfo(String requestedModelId, String modelId, String provider, String configuredModel,
		String reportedModel, String providerResponseId, String callStatus) {
}