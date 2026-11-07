package com.example.agent.config;

import com.example.agent.rag.rerank.AgentKnowledgeReranker;
import com.example.agent.rag.rerank.SiliconFlowReranker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 正式 Agent 重排的独立装配。
 *
 * 不依赖 Benchmark 是否启用，也不复用 Benchmark 的超时配置。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agent.rag.rerank", name = "enabled", havingValue = "true")
public class AgentRerankConfiguration {

	@Bean
	public AgentKnowledgeReranker agentKnowledgeReranker(ObjectMapper objectMapper,
			@Value("${agent.rag.rerank.api-key:${SILICONFLOW_API_KEY:}}") String apiKey,
			@Value("${agent.rag.rerank.request-timeout-ms:3000}") long requestTimeoutMs) {

		if (requestTimeoutMs <= 0) {
			throw new IllegalArgumentException("agent.rag.rerank.request-timeout-ms 必须大于 0");
		}

		/*
		 * 复用适配器代码，但创建正式链路自己的实例。 不额外注册 SiliconFlowReranker Bean，避免与 Benchmark 的同类型 Bean
		 * 冲突。
		 */
		SiliconFlowReranker client = new SiliconFlowReranker(objectMapper, apiKey, Duration.ofMillis(requestTimeoutMs));

		return new AgentKnowledgeReranker(client);
	}

}