package com.example.agent.support;

import com.example.agent.dto.AgentTraceStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTraceSanitizerTest {

	private final AgentJsonHelper agentJsonHelper = new AgentJsonHelper(new ObjectMapper());

	private final AgentTraceSanitizer sanitizer = new AgentTraceSanitizer(new TraceContentSanitizer(), agentJsonHelper);

	@Test
	void shouldSanitizeTraceTextAndRebuildViews() {
		AgentTraceStep step = new AgentTraceStep("TOOL_EXECUTION", "测试 Trace 脱敏", true, 10L, """
				{"username":"admin","password":"db-password"}
				""", """
				{"token":"secret-token","result":"success"}
				""", null);

		sanitizer.sanitize(List.of(step));

		assertThat(step.getInput()).contains("\"password\":\"***\"").doesNotContain("db-password");

		assertThat(step.getOutput()).contains("\"token\":\"***\"").doesNotContain("secret-token");

		/*
		 * 不仅字符串需要脱敏， JSON View 同样不能保留原始敏感数据。
		 */
		assertThat(agentJsonHelper.serializeForStorage(step.getInputView())).contains("\"password\":\"***\"")
			.doesNotContain("db-password");

		assertThat(agentJsonHelper.serializeForStorage(step.getOutputView())).contains("\"token\":\"***\"")
			.doesNotContain("secret-token");
	}

}