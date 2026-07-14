package com.example.agent.service;

import com.example.agent.client.DeepSeekDecisionClient;
import com.example.agent.config.AgentProperties;
import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.AgentTraceStep;
import com.example.agent.dto.ToolDecision;
import com.example.agent.dto.ToolExecutionResult;
import com.example.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorTest {

    private final DeepSeekDecisionClient decisionClient = mock(DeepSeekDecisionClient.class);

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

    private final AgentProperties agentProperties = new AgentProperties();

    private final AgentOrchestrator agentOrchestrator =
            new AgentOrchestrator(
                    decisionClient,
                    toolRegistry,
                    new ObjectMapper(),
                    agentProperties
            );

    @Test
    void shouldStopWhenSameToolAndArgumentsRepeated() {
        ToolDecision firstDecision = buildToolDecision(
                "getTableSchema",
                Map.of("tableName", "agent_run_log")
        );

        ToolDecision repeatedDecision = buildToolDecision(
                "getTableSchema",
                Map.of("tableName", "agent_run_log")
        );

        ToolExecutionResult successResult = new ToolExecutionResult(
                "getTableSchema",
                true,
                "{\"tableName\":\"agent_run_log\"}",
                null,
                10L
        );

        when(decisionClient.createDecisionMessages("查询表结构")).thenReturn(new ArrayList<>());

        when(decisionClient.decide(anyList())).thenReturn(firstDecision, repeatedDecision);

        when(toolRegistry.executeWithResult(eq("getTableSchema"), anyMap())).thenReturn(successResult);

        AgentAskResponse response = agentOrchestrator.execute("查询表结构", "test-trace");

        assertEquals("Agent 检测到重复的工具调用，已停止继续执行。", response.getAnswer());

        assertEquals(4, response.getSteps().size());

        AgentTraceStep guardStep = response.getSteps().get(3);

        assertEquals("AGENT_GUARD", guardStep.getStepName());
        assertFalse(guardStep.getSuccess());
        assertEquals("Agent 检测到重复的工具调用，已停止继续执行。", guardStep.getErrorMessage());

        verify(toolRegistry, times(1)).executeWithResult(eq("getTableSchema"), anyMap());
    }

    @Test
    void shouldStopAfterMaximumToolCalls() {
        ToolDecision firstDecision = buildToolDecision(
                "getTableSchema",
                Map.of("tableName", "table_1")
        );

        ToolDecision secondDecision = buildToolDecision(
                "getTableSchema",
                Map.of("tableName", "table_2")
        );

        ToolDecision thirdDecision = buildToolDecision(
                "getTableSchema",
                Map.of("tableName", "table_3")
        );

        ToolDecision fourthDecision = buildToolDecision(
                "getTableSchema",
                Map.of("tableName", "table_4")
        );

        ToolExecutionResult successResult = new ToolExecutionResult(
                "getTableSchema",
                true,
                "{\"result\":\"success\"}",
                null,
                10L
        );

        when(decisionClient.createDecisionMessages("连续查询表结构")).thenReturn(new ArrayList<>());

        when(decisionClient.decide(anyList())).thenReturn(firstDecision, secondDecision, thirdDecision, fourthDecision);

        when(toolRegistry.executeWithResult(
                eq("getTableSchema"),
                anyMap()
        )).thenReturn(successResult);

        AgentAskResponse response = agentOrchestrator.execute("连续查询表结构", "max-call-trace");

        assertEquals("Agent 已达到最大工具调用次数，已停止继续执行。", response.getAnswer());

        assertEquals(8, response.getSteps().size());

        AgentTraceStep guardStep = response.getSteps().get(7);

        assertEquals("AGENT_GUARD", guardStep.getStepName());
        assertFalse(guardStep.getSuccess());
        assertEquals("Agent 已达到最大工具调用次数，已停止继续执行。", guardStep.getErrorMessage());

        verify(toolRegistry, times(3))
                .executeWithResult(
                        eq("getTableSchema"),
                        anyMap()
                );
    }

    private ToolDecision buildToolDecision(String toolName, Map<String, Object> arguments) {
        ToolDecision decision = new ToolDecision();

        decision.setNeedTool(true);
        decision.setToolName(toolName);
        decision.setArguments(arguments);
        decision.setDirectAnswer("");

        return decision;
    }
}