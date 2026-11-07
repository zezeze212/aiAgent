package com.example.agent.config;

import com.example.agent.rag.rewrite.AgentQueryRewriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agent.rag.query-rewrite", name = "enabled", havingValue = "true")
public class AgentQueryRewriteConfiguration {

	@Bean
	public AgentQueryRewriter agentQueryRewriter(ObjectMapper objectMapper,
			@Value("${spring.ai.openai.api-key}") String apiKey, @Value("${spring.ai.openai.base-url}") String baseUrl,
			@Value("${spring.ai.openai.chat.options.model}") String modelName,
			@Value("${agent.rag.query-rewrite.read-timeout-ms:5000}") long readTimeoutMs) {

		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalArgumentException("查询改写缺少模型 API Key");
		}

		if (modelName == null || modelName.isBlank()) {
			throw new IllegalArgumentException("查询改写缺少模型名称");
		}

		if (readTimeoutMs <= 0) {
			throw new IllegalArgumentException("agent.rag.query-rewrite.read-timeout-ms 必须大于 0");
		}

		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

		OpenAiApi api = OpenAiApi.builder()
			.baseUrl(baseUrl)
			.apiKey(apiKey)
			.restClientBuilder(RestClient.builder().requestFactory(requestFactory))
			.build();

		OpenAiChatOptions options = OpenAiChatOptions.builder()
			.model(modelName)
			// 模型输出上限与应用层的字符校验分别生效。
			.maxTokens(384)
			// 沿用本项目现有 DeepSeek 非思考模式配置。
			.extraBody(Map.of("thinking", Map.of("type", "disabled")))
			.internalToolExecutionEnabled(false)
			.build();

		OpenAiChatModel rewriteModel = OpenAiChatModel.builder()
			.openAiApi(api)
			.defaultOptions(options)
			// 一次初始尝试，不做额外重试，避免改写拖长主请求。
			.retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
			.build();

		/*
		 * 只把业务包装对象注册为 Bean。 不额外发布 ChatModel Bean，避免影响现有正式对话模型的自动装配。
		 */
		return new AgentQueryRewriter(rewriteModel, objectMapper, modelName);
	}

}