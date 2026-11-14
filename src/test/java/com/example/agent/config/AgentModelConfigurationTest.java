package com.example.agent.config;

import com.example.agent.exception.BusinessException;
import com.example.agent.model.AgentModelRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentModelConfigurationTest {

	private final ChatClient deepSeek = mock(ChatClient.class);

	private ApplicationContextRunner runner() {
		ChatClient.Builder builder = mock(ChatClient.Builder.class);
		when(builder.build()).thenReturn(deepSeek);

		return new ApplicationContextRunner().withUserConfiguration(AgentModelConfiguration.class)
			.withBean(ChatClient.Builder.class, () -> builder)
			.withBean(ToolCallingManager.class, () -> ToolCallingManager.builder().build())
			.withPropertyValues("spring.ai.openai.chat.options.model=test-deepseek");
	}

	private ApplicationContextRunner enabledRunner() {
		return runner().withPropertyValues("agent.models.autodl.enabled=true",
				"agent.models.autodl.api-key=autodl-test-key");
	}

	@Test
	void shouldKeepDeepSeekAvailableWhenAutodlIsDisabled() {
		runner().run(context -> {
			assertThat(context).hasNotFailed();

			AgentModelRegistry registry = context.getBean(AgentModelRegistry.class);

			assertThat(registry.resolve(null).client()).isSameAs(deepSeek);

			assertThatThrownBy(() -> registry.resolve("autodl-qwen")).isInstanceOfSatisfying(BusinessException.class,
					error -> assertThat(error.getCode()).isEqualTo(503));
		});
	}

	@Test
	void shouldRegisterIndependentClientWithoutPublishingChatModel() {
		enabledRunner().run(context -> {
			assertThat(context).hasNotFailed();

			AgentModelRegistry registry = context.getBean(AgentModelRegistry.class);

			AgentModelRegistry.Entry qwen = registry.resolve("autodl-qwen");

			assertThat(qwen.provider()).isEqualTo("autodl-vllm");
			assertThat(qwen.providerModel()).isEqualTo("qwen3.8-27b-fp8-agent");
			assertThat(qwen.client()).isNotNull().isNotSameAs(deepSeek);

			// 新增注册项不自动改变默认模型。
			assertThat(registry.resolve(null).client()).isSameAs(deepSeek);

			// 模型和 API 都是注册项内部对象。
			assertThat(context).doesNotHaveBean(ChatModel.class);
			assertThat(context).doesNotHaveBean(OpenAiApi.class);
		});
	}

	@Test
	void shouldRejectMissingKeyWhenEnabled() {
		runner().withPropertyValues("agent.models.autodl.enabled=true", "agent.models.autodl.api-key=").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasStackTraceContaining("启用 AutoDL 时必须配置");
		});
	}

	@Test
	void shouldRejectBaseUrlContainingApiPath() {
		enabledRunner().withPropertyValues("agent.models.autodl.base-url=http://127.0.0.1:18000/v1").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("AutoDL base-url 只填写根地址");
		});
	}

	@Test
	void shouldRejectInvalidReadTimeout() {
		enabledRunner().withPropertyValues("agent.models.autodl.read-timeout-ms=0").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("AutoDL read-timeout-ms 必须大于 0");
		});
	}

	@Test
	void shouldSendQwenRequestToItsOwnEndpoint() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		AtomicReference<JsonNode> receivedBody = new AtomicReference<>();
		AtomicReference<String> authorization = new AtomicReference<>();
		AtomicReference<String> method = new AtomicReference<>();
		AtomicInteger requestCount = new AtomicInteger();

		/*
		 * 本机临时 HTTP 服务只检查客户端协议。 不访问 AutoDL，也不把它当作真实模型验证。
		 */
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

		server.createContext("/v1/chat/completions", exchange -> {
			try {
				requestCount.incrementAndGet();
				method.set(exchange.getRequestMethod());
				authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
				receivedBody.set(mapper.readTree(exchange.getRequestBody()));

				byte[] response = """
						{
						  "id": "qwen-response-test",
						  "object": "chat.completion",
						  "created": 1,
						  "model": "qwen3.8-27b-fp8-agent",
						  "choices": [{
						    "index": 0,
						    "message": {
						      "role": "assistant",
						      "content": "测试回答"
						    },
						    "finish_reason": "stop"
						  }],
						  "usage": {
						    "prompt_tokens": 5,
						    "completion_tokens": 4,
						    "total_tokens": 9
						  }
						}
						""".getBytes(StandardCharsets.UTF_8);

				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, response.length);
				exchange.getResponseBody().write(response);
			}
			finally {
				exchange.close();
			}
		});

		server.start();

		try {
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

			enabledRunner().withPropertyValues("agent.models.autodl.base-url=" + baseUrl).run(context -> {
				assertThat(context).hasNotFailed();

				AgentModelRegistry registry = context.getBean(AgentModelRegistry.class);

				ChatResponse response = registry.resolve("autodl-qwen")
					.client()
					.prompt()
					.user("解释 resultMap")
					.call()
					.chatResponse();

				assertThat(response).isNotNull();
				assertThat(response.getResult().getOutput().getText()).isEqualTo("测试回答");
				assertThat(response.getMetadata().getModel()).isEqualTo("qwen3.8-27b-fp8-agent");
				assertThat(response.getMetadata().getId()).isEqualTo("qwen-response-test");
			});

			assertThat(requestCount.get()).isEqualTo(1);
			assertThat(method.get()).isEqualTo("POST");
			assertThat(authorization.get()).isEqualTo("Bearer autodl-test-key");

			JsonNode body = receivedBody.get();
			assertThat(body).isNotNull();
			assertThat(body.path("model").asText()).isEqualTo("qwen3.8-27b-fp8-agent");
			assertThat(body.path("max_tokens").asInt()).isEqualTo(1024);
			assertThat(body.path("chat_template_kwargs").path("enable_thinking").isBoolean()).isTrue();
			assertThat(body.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isFalse();

			// DeepSeek 的扩展参数不能混入 Qwen 请求。
			assertThat(body.has("thinking")).isFalse();
			verifyNoInteractions(deepSeek);
		}
		finally {
			server.stop(0);
		}
	}

}