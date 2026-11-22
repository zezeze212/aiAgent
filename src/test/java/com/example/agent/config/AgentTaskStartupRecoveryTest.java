package com.example.agent.config;

import com.example.agent.mapper.AgentTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentTaskStartupRecoveryTest {

	@Test
	void shouldRunRecoveryDuringContextInitialization() {
		AgentTaskMapper mapper = mock(AgentTaskMapper.class);
		when(mapper.recoverUnfinishedOnStartup()).thenReturn(2);

		contextRunner(mapper).withPropertyValues("agent.task.startup-recovery-enabled=true").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(AgentTaskStartupRecovery.class);

			// 验证 Spring 初始化实际触发了恢复，而非手动调用方法。
			verify(mapper).recoverUnfinishedOnStartup();
		});
	}

	@Test
	void shouldFailContextWhenRecoveryFails() {
		AgentTaskMapper mapper = mock(AgentTaskMapper.class);

		when(mapper.recoverUnfinishedOnStartup()).thenThrow(new IllegalStateException("recovery database unavailable"));

		contextRunner(mapper).withPropertyValues("agent.task.startup-recovery-enabled=true").run(context -> {
			assertThat(context).hasFailed();

			assertThat(context.getStartupFailure()).hasRootCauseMessage("recovery database unavailable");
		});
	}

	@Test
	void shouldKeepBeanButSkipDatabaseWhenRecoveryIsDisabled() {
		AgentTaskMapper mapper = mock(AgentTaskMapper.class);

		contextRunner(mapper).withPropertyValues("agent.task.startup-recovery-enabled=false").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(AgentTaskStartupRecovery.class);

			verifyNoInteractions(mapper);
		});
	}

	private ApplicationContextRunner contextRunner(AgentTaskMapper mapper) {

		return new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class)
			.withBean(AgentTaskMapper.class, () -> mapper);
	}

	@Configuration(proxyBeanMethods = false)
	@Import(AgentTaskStartupRecovery.class)
	static class TestConfiguration {

	}

}