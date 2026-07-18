package com.example.agent.service;

import com.example.agent.dto.*;
import com.example.agent.entity.AgentRunLog;
import com.example.agent.entity.AgentStepLog;
import com.example.agent.mapper.AgentRunLogMapper;
import com.example.agent.mapper.AgentStepLogMapper;
import com.example.agent.support.AgentJsonHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLogService {

    private final AgentRunLogMapper agentRunLogMapper;

    private final AgentStepLogMapper agentStepLogMapper;

    private final AgentJsonHelper agentJsonHelper;

    public void saveRunLog(String userMessage, AgentAskResponse response) {
        if (response == null) {
            return;
        }

        saveAgentRun(userMessage, response);
        saveAgentSteps(response.getTraceId(), response.getSteps());
    }

    private void saveAgentRun(String userMessage, AgentAskResponse response) {
        AgentRunLog runLog = new AgentRunLog();

        runLog.setTraceId(response.getTraceId());
        runLog.setUserMessage(userMessage);
        runLog.setAnswer(response.getAnswer());
        runLog.setUsedTool(Boolean.TRUE.equals(response.getUsedTool()) ? 1 : 0);
        runLog.setToolName(response.getToolName());
        runLog.setToolResult(agentJsonHelper.serializeForStorage(response.getToolResult()));
        runLog.setDecisionCostMs(response.getDecisionCostMs());
        runLog.setToolCostMs(response.getToolCostMs());
        runLog.setSummaryCostMs(response.getSummaryCostMs());
        runLog.setAgentCostMs(response.getAgentCostMs());

        int runSuccess = 1;
        String runErrorMessage = null;
        List<AgentTraceStep> steps = response.getSteps();

        if (steps != null) {
            for (AgentTraceStep step : steps) {
                if (!Boolean.TRUE.equals(step.getSuccess())) {
                    runSuccess = 0;
                    runErrorMessage = step.getErrorMessage();
                    break;
                }
            }
        }
        runLog.setSuccess(runSuccess);
        runLog.setErrorMessage(runErrorMessage);

        agentRunLogMapper.insert(runLog);

        log.info("Agent主记录保存成功，traceId={}, id={}", response.getTraceId(), runLog.getId());
    }

    private void saveAgentSteps(String traceId, List<AgentTraceStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }

        for (int i = 0; i < steps.size(); i++) {
            AgentTraceStep step = steps.get(i);

            AgentStepLog stepLog = new AgentStepLog();
            stepLog.setTraceId(traceId);
            stepLog.setStepName(step.getStepName());
            stepLog.setDescription(step.getDescription());
            stepLog.setSuccess(Boolean.TRUE.equals(step.getSuccess()) ? 1 : 0);
            stepLog.setCostMs(step.getCostMs());
            stepLog.setInputText(step.getInput());
            stepLog.setOutputText(step.getOutput());
            stepLog.setErrorMessage(step.getErrorMessage());
            stepLog.setStepOrder(i + 1);

            agentStepLogMapper.insert(stepLog);
        }

        log.info("Agent步骤记录保存成功，traceId={}, stepCount={}", traceId, steps.size());
    }

    public AgentRunPageResponse getRunPage(
            Integer pageNum,
            Integer pageSize,
            String toolName,
            Integer success,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        if (pageNum == null || pageNum < 1) {
            pageNum = 1;
        }

        if (pageSize == null || pageSize < 1) {
            pageSize = 10;
        }

        if (pageSize > 100) {
            pageSize = 100;
        }

        if (toolName != null && toolName.isBlank()) {
            toolName = null;
        }

        int offset = (pageNum - 1) * pageSize;

        Long total = agentRunLogMapper.countByCondition(
                toolName,
                success,
                startTime,
                endTime
        );

        List<AgentRunLog> runLogs = agentRunLogMapper.selectPageByCondition(
                offset,
                pageSize,
                toolName,
                success,
                startTime,
                endTime
        );


        return new AgentRunPageResponse(
                pageNum,
                pageSize,
                total == null ? 0L : total,
                buildRunListItemResponses(runLogs)
        );
    }

    private List<AgentRunListItemResponse> buildRunListItemResponses(List<AgentRunLog> runLogs) {
        if (runLogs == null || runLogs.isEmpty()) {
            return List.of();
        }

        List<AgentRunListItemResponse> responses = new ArrayList<>();

        for (AgentRunLog runLog : runLogs) {
            responses.add(buildRunListItemResponse(runLog));
        }

        return responses;
    }

    private AgentRunListItemResponse buildRunListItemResponse(AgentRunLog runLog) {
        return new AgentRunListItemResponse(
                runLog.getId(),
                runLog.getTraceId(),
                buildSummary(runLog.getUserMessage(), 120),
                buildSummary(runLog.getAnswer(), 120),
                toBoolean(runLog.getUsedTool()),
                runLog.getToolName(),
                runLog.getDecisionCostMs(),
                runLog.getToolCostMs(),
                runLog.getSummaryCostMs(),
                runLog.getAgentCostMs(),
                toBoolean(runLog.getSuccess()),
                runLog.getErrorMessage(),
                runLog.getCreatedTime()
        );
    }


    private String buildSummary(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String cleanText = text
                .replace("\r", "")
                .replace("\n", " ")
                .replace("\\n", " ")
                .trim();

        if (cleanText.length() <= maxLength) {
            return cleanText;
        }

        return cleanText.substring(0, maxLength) + "...";
    }

    public AgentRunDetailResponse getRunDetail(String traceId) {
        AgentRunLog run = agentRunLogMapper.selectByTraceId(traceId);

        if (run == null) {
            return new AgentRunDetailResponse(null, List.of());
        }

        List<AgentStepLog> steps = agentStepLogMapper.selectByTraceId(traceId);

        return new AgentRunDetailResponse(buildRunResponse(run), buildStepResponses(steps));
    }

    private List<AgentTraceStepResponse> buildStepResponses(List<AgentStepLog> steps) {
        if (steps == null) {
            return null;
        }

        List<AgentTraceStepResponse> responses = new ArrayList<>();

        for (AgentStepLog step : steps) {
            responses.add(buildStepResponse(step));
        }

        return responses;
    }

    private AgentRunResponse buildRunResponse(AgentRunLog run) {
        if (run == null) {
            return null;
        }

        return new AgentRunResponse(
                run.getId(),
                run.getTraceId(),
                run.getUserMessage(),
                run.getAnswer(),
                buildSummary(run.getAnswer(), 120),
                toBoolean(run.getUsedTool()),
                run.getToolName(),
                run.getToolResult(),
                agentJsonHelper.parseJsonIfPossible(run.getToolResult()),
                run.getDecisionCostMs(),
                run.getToolCostMs(),
                run.getSummaryCostMs(),
                run.getAgentCostMs(),
                toBoolean(run.getSuccess()),
                run.getErrorMessage(),
                run.getCreatedTime()
        );
    }

    private Boolean toBoolean(Integer value) {
        if (value == null) {
            return null;
        }

        return Integer.valueOf(1).equals(value);
    }

    private AgentTraceStepResponse buildStepResponse(AgentStepLog step) {
        return new AgentTraceStepResponse(
                step.getStepOrder(),
                step.getStepName(),
                step.getDescription(),
                Integer.valueOf(1).equals(step.getSuccess()),
                step.getCostMs(),
                step.getInputText(),
                step.getOutputText(),
                agentJsonHelper.parseJsonIfPossible(step.getInputText()),
                agentJsonHelper.parseJsonIfPossible(step.getOutputText()),
                step.getErrorMessage()
        );
    }




    public AgentRunStatsResponse getRunStats(
            String toolName,
            Integer success,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        if (toolName != null && toolName.isBlank()) {
            toolName = null;
        }

        AgentRunStatsResponse stats = agentRunLogMapper.selectOverallStats(
                toolName,
                success,
                startTime,
                endTime
        );

        if (stats == null) {
            stats = new AgentRunStatsResponse();
            stats.setTotal(0L);
            stats.setSuccessCount(0L);
            stats.setFailedCount(0L);
            stats.setSuccessRate(0.0);
            stats.setToolStats(List.of());
            return stats;
        }

        Long total = stats.getTotal() == null ? 0L : stats.getTotal();
        Long successCount = stats.getSuccessCount() == null ? 0L : stats.getSuccessCount();

        if (total == 0) {
            stats.setSuccessRate(0.0);
        } else {
            double successRate = successCount * 100.0 / total;
            stats.setSuccessRate(Math.round(successRate * 100.0) / 100.0);
        }

        stats.setToolStats(agentRunLogMapper.selectToolStats(
                toolName,
                success,
                startTime,
                endTime
        ));

        return stats;
    }
}