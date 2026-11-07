package com.example.agent.config;

import com.example.agent.rag.rewrite.AgentQueryRewriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AgentQueryRewriteConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(AgentQueryRewriteConfiguration.class)
		.withBean(ObjectMapper.class, ObjectMapper::new);

	@Test
	void shouldStayDisabledWithoutModelProperties() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(AgentQueryRewriter.class);
		});
	}

	@Test
	void shouldEnableWithoutPublishingAnotherChatModelBean() {
		enabledRunner("test-key").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(AgentQueryRewriter.class);

			// 模型是包装对象内部的实例，不参与主对话模型的 Bean 选择。
			assertThat(context).doesNotHaveBean(ChatModel.class);
		});
	}

	@Test
	void shouldRejectMissingKeyWhenEnabled() {
		enabledRunner("").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasStackTraceContaining("查询改写缺少模型 API Key");
		});
	}

	private ApplicationContextRunner enabledRunner(String key) {
		// 仅用于装配测试，不会发送 HTTP 请求。
		return runner.withPropertyValues("agent.rag.query-rewrite.enabled=true", "spring.ai.openai.api-key=" + key,
				"spring.ai.openai.base-url=http://localhost:9999", "spring.ai.openai.chat.options.model=test-model",
				"agent.rag.query-rewrite.read-timeout-ms=5000");
	}

}