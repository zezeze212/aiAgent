package com.example.agent.service;

import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.AgentTraceStep;
import com.example.agent.entity.AgentRunLog;
import com.example.agent.mapper.AgentRunLogMapper;
import com.example.agent.mapper.AgentStepLogMapper;
import com.example.agent.support.AgentJsonHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import com.example.agent.entity.AgentStepLog;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;

class AgentLogServiceTest {

    private final AgentRunLogMapper runLogMapper = mock(AgentRunLogMapper.class);

    private final AgentStepLogMapper stepLogMapper = mock(AgentStepLogMapper.class);

    private final AgentJsonHelper agentJsonHelper = new AgentJsonHelper(new ObjectMapper());

    private final AgentLogService agentLogService = new AgentLogService(runLogMapper, stepLogMapper, agentJsonHelper);

    @Test
    void shouldSaveFailedRunWhenAnyStepFails() {
        AgentTraceStep failedStep = new AgentTraceStep(
                "TOOL_EXECUTION",
                "执行工具",
                false,
                10L,
                "{}",
                "工具执行失败",
                "参数错误"
        );

        AgentAskResponse response = new AgentAskResponse();
        response.setTraceId("test-trace");
        response.setSteps(List.of(failedStep));

        agentLogService.saveRunLog("测试问题", response);

        ArgumentCaptor<AgentRunLog> captor =
                ArgumentCaptor.forClass(AgentRunLog.class);

        verify(runLogMapper).insert(captor.capture());

        AgentRunLog savedRun = captor.getValue();

        assertEquals(0, savedRun.getSuccess());
        assertEquals("参数错误", savedRun.getErrorMessage());
    }

    @Test
    void shouldSaveSuccessfulRunWhenAllStepsSucceed() {
        AgentTraceStep decisionStep = new AgentTraceStep(
                "AI_DECISION",
                "判断是否需要工具",
                true,
                10L,
                "{}",
                null,
                null
        );

        AgentTraceStep toolStep = new AgentTraceStep(
                "TOOL_EXECUTION",
                "执行工具",
                true,
                10L,
                "{}",
                null,
                null
        );

        AgentAskResponse response = new AgentAskResponse();
        response.setTraceId("success-trace");
        response.setSteps(List.of(decisionStep, toolStep));

        agentLogService.saveRunLog("测试成功请求", response);

        ArgumentCaptor<AgentRunLog> captor = ArgumentCaptor.forClass(AgentRunLog.class);

        verify(runLogMapper).insert(captor.capture());
        verify(stepLogMapper, times(2)).insert(any(AgentStepLog.class));

        AgentRunLog savedRun = captor.getValue();

        assertEquals(1, savedRun.getSuccess());
        assertNull(savedRun.getErrorMessage());
    }
}