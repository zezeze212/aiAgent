package com.example.agent.service;

import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.AgentRunDetailResponse;
import com.example.agent.dto.AgentTraceStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLogService {

    private final JdbcTemplate jdbcTemplate;

    public void saveRunLog(String userMessage, AgentAskResponse response) {
        if (response == null) {
            return;
        }

        saveAgentRun(userMessage, response);
        saveAgentSteps(response.getTraceId(), response.getSteps());
    }

    private void saveAgentRun(String userMessage, AgentAskResponse response) {
        String sql = """
                INSERT INTO agent_run_log (
                    trace_id,
                    user_message,
                    answer,
                    used_tool,
                    tool_name,
                    tool_result,
                    decision_cost_ms,
                    tool_cost_ms,
                    summary_cost_ms,
                    agent_cost_ms,
                    success,
                    error_message
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        jdbcTemplate.update(
                sql,
                response.getTraceId(),
                userMessage,
                response.getAnswer(),
                Boolean.TRUE.equals(response.getUsedTool()) ? 1 : 0,
                response.getToolName(),
                response.getToolResult(),
                response.getDecisionCostMs(),
                response.getToolCostMs(),
                response.getSummaryCostMs(),
                response.getAgentCostMs(),
                1,
                null
        );

        log.info("Agent主记录保存成功，traceId={}", response.getTraceId());
    }

    private void saveAgentSteps(String traceId, List<AgentTraceStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }

        String sql = """
                INSERT INTO agent_step_log (
                    trace_id,
                    step_name,
                    description,
                    success,
                    cost_ms,
                    input_text,
                    output_text,
                    error_message,
                    step_order
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        for (int i = 0; i < steps.size(); i++) {
            AgentTraceStep step = steps.get(i);

            jdbcTemplate.update(
                    sql,
                    traceId,
                    step.getStepName(),
                    step.getDescription(),
                    Boolean.TRUE.equals(step.getSuccess()) ? 1 : 0,
                    step.getCostMs(),
                    step.getInput(),
                    step.getOutput(),
                    step.getErrorMessage(),
                    i + 1
            );
        }

        log.info("Agent步骤记录保存成功，traceId={}, stepCount={}", traceId, steps.size());
    }

    public AgentRunDetailResponse getRunDetail(String traceId) {
        String runSql = """
            SELECT
                id,
                trace_id,
                user_message,
                answer,
                used_tool,
                tool_name,
                tool_result,
                decision_cost_ms,
                tool_cost_ms,
                summary_cost_ms,
                agent_cost_ms,
                success,
                error_message,
                created_time
            FROM agent_run_log
            WHERE trace_id = ?
            """;

        List<Map<String, Object>> runs = jdbcTemplate.queryForList(runSql, traceId);

        if (runs.isEmpty()) {
            return new AgentRunDetailResponse(null, List.of());
        }

        String stepSql = """
            SELECT
                id,
                trace_id,
                step_name,
                description,
                success,
                cost_ms,
                input_text,
                output_text,
                error_message,
                step_order,
                created_time
            FROM agent_step_log
            WHERE trace_id = ?
            ORDER BY step_order ASC
            """;

        List<Map<String, Object>> steps = jdbcTemplate.queryForList(stepSql, traceId);

        return new AgentRunDetailResponse(runs.get(0), steps);
    }
}