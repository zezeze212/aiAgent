package com.example.agent.config;

import com.example.agent.model.AgentModelRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AutodlModelProperties.class)
public class AgentModelConfiguration {

	@Bean
	public AgentModelRegistry agentModelRegistry(ChatClient.Builder chatClientBuilder,
			ToolCallingManager toolCallingManager, AutodlModelProperties autodl,
			@Value("${agent.models.default-id:deepseek-main}") String defaultModelId,
			@Value("${agent.models.deepseek.enabled:true}") boolean deepSeekEnabled,
			@Value("${spring.ai.openai.chat.options.model}") String deepSeekModel) {

		// 保留现有自动装配的 DeepSeek 客户端。
		ChatClient deepSeekClient = chatClientBuilder.build();

		AgentModelRegistry.Entry deepSeek = new AgentModelRegistry.Entry("deepseek-main", "deepseek", deepSeekModel,
				deepSeekEnabled, true, deepSeekClient);

		/*
		 * 关闭时不要求 API Key，也不构造 AutoDL 客户端。 保留注册项，使显式选择返回 MODEL_UNAVAILABLE，而不是未知模型。
		 */
		ChatClient qwenClient = autodl.isEnabled() ? buildAutodlClient(autodl, toolCallingManager) : null;

		AgentModelRegistry.Entry qwen = new AgentModelRegistry.Entry("autodl-qwen", "autodl-vllm", autodl.getModel(),
				autodl.isEnabled(), true, qwenClient);

		return new AgentModelRegistry(defaultModelId, List.of(deepSeek, qwen));
	}

	private ChatClient buildAutodlClient(AutodlModelProperties properties, ToolCallingManager toolCallingManager) {

		validateAutodlConfiguration(properties);

		HttpClient httpClient = HttpClient.newBuilder()
			/*
			 * 当前通过明文 HTTP + SSH 隧道访问 vLLM。 明确使用 HTTP/1.1，避免 h2c 升级请求触发 部分
			 * Uvicorn/httptools 组合的请求体解析问题。
			 */
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
			.build();

		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));

		OpenAiApi api = OpenAiApi.builder()
			.baseUrl(properties.getBaseUrl())
			.apiKey(properties.getApiKey())
			.restClientBuilder(RestClient.builder().requestFactory(requestFactory))
			.build();

		OpenAiChatOptions options = OpenAiChatOptions.builder()
			.model(properties.getModel())
			.temperature(0.7)
			.topP(0.8)
			.maxTokens(properties.getMaxTokens())
			.extraBody(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)))
			.internalToolExecutionEnabled(true)
			.build();

		OpenAiChatModel qwenModel = OpenAiChatModel.builder()
			.openAiApi(api)
			.defaultOptions(options)
			// 继续复用现有工具执行与异常处理策略。
			.toolCallingManager(toolCallingManager)
			.retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
			.build();

		return ChatClient.builder(qwenModel).build();
	}

	private void validateAutodlConfiguration(AutodlModelProperties properties) {

		Assert.hasText(properties.getApiKey(), "启用 AutoDL 时必须配置 agent.models.autodl.api-key");
		Assert.hasText(properties.getModel(), "AutoDL API 模型名不能为空");
		Assert.hasText(properties.getBaseUrl(), "AutoDL base-url 不能为空");

		URI uri = URI.create(properties.getBaseUrl());

		Assert.isTrue(("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
				&& uri.getHost() != null, "AutoDL base-url 必须是有效的 HTTP/HTTPS 地址");

		// 本单元固定采用根地址，防止误配为 /v1 或完整接口路径。
		Assert.isTrue(uri.getRawPath() == null || uri.getRawPath().isEmpty(), "AutoDL base-url 只填写根地址，不带路径或末尾斜杠");

		Assert.isTrue(uri.getRawQuery() == null && uri.getRawFragment() == null && uri.getUserInfo() == null,
				"AutoDL base-url 不应包含查询参数、片段或认证信息");

		Assert.isTrue(properties.getConnectTimeoutMs() > 0, "AutoDL connect-timeout-ms 必须大于 0");
		Assert.isTrue(properties.getReadTimeoutMs() > 0, "AutoDL read-timeout-ms 必须大于 0");
		Assert.isTrue(properties.getMaxTokens() > 0, "AutoDL max-tokens 必须大于 0");
	}

}