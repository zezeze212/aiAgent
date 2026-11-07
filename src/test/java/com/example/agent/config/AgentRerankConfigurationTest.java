package com.example.agent.config;

import com.example.agent.rag.rerank.AgentKnowledgeReranker;
import com.example.agent.rag.rerank.SiliconFlowReranker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentRerankConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(AgentRerankConfiguration.class)
		.withBean(ObjectMapper.class, ObjectMapper::new);

	@Test
	void shouldStayDisabledByDefaultWithoutKey() {
		runner.withPropertyValues("agent.rag.rerank.api-key=").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(AgentKnowledgeReranker.class);
		});
	}

	@Test
	void shouldEnableWithoutBenchmarkAndWithoutPublishingAnotherClientBean() {
		runner
			.withPropertyValues("agent.rag.rerank.enabled=true", "agent.rag.rerank.api-key=test-key",
					"agent.rag.rerank.request-timeout-ms=3000", "agent.rag.benchmark.enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(AgentKnowledgeReranker.class);
				assertThat(context).doesNotHaveBean(SiliconFlowReranker.class);
			});
	}

	@Test
	void shouldCoexistWithAnExistingRerankerBean() {
		SiliconFlowReranker existingClient = mock(SiliconFlowReranker.class);

		runner.withBean(SiliconFlowReranker.class, () -> existingClient)
			.withPropertyValues("agent.rag.rerank.enabled=true", "agent.rag.rerank.api-key=test-key")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(AgentKnowledgeReranker.class);
				assertThat(context.getBean(SiliconFlowReranker.class)).isSameAs(existingClient);
			});
	}

	@Test
	void shouldFailStartupWhenExplicitlyEnabledWithoutKey() {
		runner.withPropertyValues("agent.rag.rerank.enabled=true", "agent.rag.rerank.api-key=").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasStackTraceContaining("缺少 SILICONFLOW_API_KEY");
		});
	}

}