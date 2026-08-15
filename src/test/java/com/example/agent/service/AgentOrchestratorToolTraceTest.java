package com.example.agent.service;

import com.example.agent.client.DeepSeekDecisionClient;
import com.example.agent.config.AgentProperties;
import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.AgentTraceStep;
import com.example.agent.dto.ToolDecision;
import com.example.agent.dto.ToolExecutionResult;
import com.example.agent.rag.KnowledgeSearchResult;
import com.example.agent.rag.SimpleRagRetriever;
import com.example.agent.support.AgentJsonHelper;
import com.example.agent.tool.ToolExecutionStatus;
import com.example.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentOrchestratorToolTraceTest {

	@Test
	void shouldWriteToolExecutionStatusIntoTrace() {
		DeepSeekDecisionClient decisionClient = mock(DeepSeekDecisionClient.class);

		ToolRegistry toolRegistry = mock(ToolRegistry.class);

		SimpleRagRetriever ragRetriever = mock(SimpleRagRetriever.class);

		AgentProperties agentProperties = new AgentProperties();

		ObjectMapper objectMapper = new ObjectMapper();

		AgentJsonHelper agentJsonHelper = new AgentJsonHelper(objectMapper);

		AgentOrchestrator orchestrator = new AgentOrchestrator(decisionClient, toolRegistry, ragRetriever,
				agentJsonHelper, objectMapper, agentProperties);

		String userMessage = "查询 users 表";

		ToolDecision decision = new ToolDecision();

		decision.setNeedTool(true);
		decision.setToolName("getTableSchema");

		decision.setArguments(Map.of("tableName", 123));

		decision.setDirectAnswer("");

		when(decisionClient.createDecisionMessages(userMessage)).thenReturn(new ArrayList<>());

		when(ragRetriever.retrieve(userMessage)).thenReturn(KnowledgeSearchResult.notFound());

		when(decisionClient.decide(anyList())).thenReturn(decision);

		ToolExecutionResult toolResult = ToolExecutionResult.failure("getTableSchema",
				ToolExecutionStatus.INVALID_ARGUMENT, "工具参数校验失败：参数类型错误", "参数类型错误", 3L);

		when(toolRegistry.executeWithResult("getTableSchema", Map.of("tableName", 123))).thenReturn(toolResult);

		AgentAskResponse response = orchestrator.execute(userMessage, "tool-trace-test");

		assertEquals("工具参数校验失败：参数类型错误", response.getAnswer());

		AgentTraceStep toolStep = response.getSteps()
			.stream()
			.filter(step -> "TOOL_EXECUTION".equals(step.getStepName()))
			.findFirst()
			.orElseThrow();

		assertFalse(toolStep.getSuccess());

		assertEquals(ToolExecutionStatus.INVALID_ARGUMENT, toolStep.getToolStatus());

		assertEquals("参数类型错误", toolStep.getErrorMessage());

		assertEquals(3L, toolStep.getCostMs());

		assertEquals(null, toolStep.getErrorCode());
	}

}