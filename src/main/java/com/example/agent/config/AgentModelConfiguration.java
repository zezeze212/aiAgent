package com.example.agent.config;

import com.example.agent.model.AgentModelRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class AgentModelConfiguration {

	@Bean
	public AgentModelRegistry agentModelRegistry(ChatClient.Builder chatClientBuilder,
			@Value("${agent.models.default-id:deepseek-main}") String defaultModelId,
			@Value("${agent.models.deepseek.enabled:true}") boolean enabled,
			@Value("${spring.ai.openai.chat.options.model}") String providerModel) {

		/*
		 * 沿用现有自动装配客户端。 Agent 的统一系统提示词在请求入口设置，避免不同模型各维护一份提示词。
		 */
		ChatClient deepSeekClient = chatClientBuilder.build();

		AgentModelRegistry.Entry deepSeek = new AgentModelRegistry.Entry("deepseek-main", "deepseek", providerModel,
				enabled, true, deepSeekClient);

		// 当前只有一个真实服务；AutoDL 接入后再增加真实注册项。
		return new AgentModelRegistry(defaultModelId, List.of(deepSeek));
	}

}