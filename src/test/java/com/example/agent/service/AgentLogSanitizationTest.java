package com.example.agent.service;

import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.AgentTraceStep;
import com.example.agent.entity.AgentRunLog;
import com.example.agent.entity.AgentStepLog;
import com.example.agent.mapper.AgentRunLogMapper;
import com.example.agent.mapper.AgentStepLogMapper;
import com.example.agent.support.AgentJsonHelper;
import com.example.agent.support.TraceContentSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentLogSanitizationTest {

	@Test
	void shouldSanitizeRunAndStepBeforePersistence() {
		AgentRunLogMapper runLogMapper = mock(AgentRunLogMapper.class);

		AgentStepLogMapper stepLogMapper = mock(AgentStepLogMapper.class);

		AgentJsonHelper agentJsonHelper = new AgentJsonHelper(new ObjectMapper());

		AgentLogService service = new AgentLogService(runLogMapper, stepLogMapper, agentJsonHelper,
				new TraceContentSanitizer());

		AgentTraceStep step = new AgentTraceStep("TOOL_EXECUTION", "执行测试工具", true, 5L, "password=step-password",
				"{\"token\":\"step-token\"}", null);

		AgentAskResponse response = new AgentAskResponse();

		response.setTraceId("sanitize-test");

		/*
		 * 即时业务 Response 保持原值， AgentLogService 只治理数据库副本。
		 */
		response.setAnswer("answer apiKey=answer-secret");

		response.setUsedTool(true);

		response.setToolName("testTool");

		response.setToolResult(Map.of("password", "tool-result-password"));

		response.setAgentCostMs(20L);

		response.setToolCostMs(5L);

		response.setSteps(List.of(step));

		String userMessage = "请分析问题 password=user-password";

		service.saveRunLog(userMessage, response);

		ArgumentCaptor<AgentRunLog> runCaptor = ArgumentCaptor.forClass(AgentRunLog.class);

		verify(runLogMapper).insert(runCaptor.capture());

		AgentRunLog savedRun = runCaptor.getValue();

		assertThat(savedRun.getUserMessage()).contains("password=***").doesNotContain("user-password");

		assertThat(savedRun.getAnswer()).contains("apiKey=***").doesNotContain("answer-secret");

		assertThat(savedRun.getToolResult()).contains("\"password\":\"***\"").doesNotContain("tool-result-password");

		ArgumentCaptor<AgentStepLog> stepCaptor = ArgumentCaptor.forClass(AgentStepLog.class);

		verify(stepLogMapper).insert(stepCaptor.capture());

		AgentStepLog savedStep = stepCaptor.getValue();

		assertThat(savedStep.getInputText()).contains("password=***").doesNotContain("step-password");

		assertThat(savedStep.getOutputText()).contains("\"token\":\"***\"").doesNotContain("step-token");

		/*
		 * 持久化治理不能反向修改即时业务回答。
		 */
		assertThat(response.getAnswer()).isEqualTo("answer apiKey=answer-secret");
	}

}