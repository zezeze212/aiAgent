package com.example.agent.service;

import com.example.agent.client.DeepSeekDecisionClient;
import com.example.agent.config.AgentProperties;
import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.AgentTraceStep;
import com.example.agent.dto.ToolDecision;
import com.example.agent.dto.ToolExecutionResult;
import com.example.agent.support.AgentJsonHelper;
import com.example.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Agent 核心编排器。
 *
 * 负责执行“AI 决策 -> 工具执行 -> 结果回填 -> 再次决策”的多步循环，
 * 同时提供最大调用次数、重复工具调用等运行保护。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentOrchestrator {

    /**
     * 防止模型持续要求调用工具，造成无限循环和不必要的 API 消耗。
     */
    private static final int MAX_TOOL_CALLS = 3;

    private final DeepSeekDecisionClient decisionClient;

    private final ToolRegistry toolRegistry;

    private final AgentJsonHelper agentJsonHelper;

    private final ObjectMapper objectMapper;

    private final AgentProperties agentProperties;

    public AgentAskResponse execute(String userMessage, String traceId) {
        // 保存一次 Agent 请求在循环过程中的可变状态。
        AgentRunContext context = createContext(userMessage, traceId);

        while (true) {
            // 检测是否超时
            GuardViolation timeoutViolation = checkExecutionTimeout(context);

            if (timeoutViolation != null) {
                return finishWithGuardFailure(context, timeoutViolation);
            }

            // 获取AI决策结果和耗时、耗时保护
            TimedDecision timedDecision = decideWithTiming(context.messages);
            if (!timedDecision.success()) {
                return finishWithDecisionFailure(context, timedDecision);
            }

            ToolDecision decision = timedDecision.decision();

            // 打印决策日志
            logDecision(traceId, decision, timedDecision.costMs());

            // 不需要使用工具直接返回
            if (Boolean.FALSE.equals(decision.getNeedTool())) {
                return finishWithDirectAnswer(context, decision, timedDecision.costMs());
            }

            // 总计决策耗时
            context.totalDecisionCostMs += timedDecision.costMs();

            // 添加决策步骤记录
            addDecisionStep(context, decision, timedDecision.costMs());

            // 检测是否超时
            timeoutViolation = checkExecutionTimeout(context);

            if (timeoutViolation != null) {
                return finishWithGuardFailure(context, timeoutViolation);
            }

            // 检测是否超过最大调用工具次数、以及是否使用相同参数调用相同工具
            GuardViolation violation = checkToolCallGuard(context, decision);

            // 超过最大调用工具次数、或者使用相同参数调用相同工具、执行AGENT_GUARD步骤记录并返回
            if (violation != null) {
                return finishWithGuardFailure(context, violation);
            }

            // 继续调用工具
            ToolExecutionResult toolResult = executeTool(context, decision);

            // 工具执行是否成功
            if (!Boolean.TRUE.equals(toolResult.getSuccess())) {
                return finishWithToolFailure(context, toolResult);
            }

            /*
             * 工具成功后，将真实证据放回原对话。
             * 下一轮 AI 可以选择直接回答，也可以调用另一个工具。
             */
            decisionClient.appendToolResult(
                    context.messages,
                    context.lastToolName,
                    decision.getArguments(),
                    context.lastToolResult
            );
        }
    }

    private AgentAskResponse finishWithDecisionFailure(AgentRunContext context, TimedDecision timedDecision) {
        String answer = "Agent 决策失败：" + timedDecision.errorMessage();

        context.totalDecisionCostMs += timedDecision.costMs();

        context.steps.add(new AgentTraceStep(
                "AI_DECISION",
                "AI 决策失败",
                false,
                timedDecision.costMs(),
                context.userMessage,
                null,
                answer
        ));

        return buildResponse(context, answer, 0L);
    }

    private AgentRunContext createContext(String userMessage, String traceId) {
        List<Map<String, String>> messages = decisionClient.createDecisionMessages(userMessage);
        return new AgentRunContext(userMessage, traceId, messages);
    }

    private TimedDecision decideWithTiming(List<Map<String, String>> messages) {
        long startTime = System.currentTimeMillis();

        try {
            ToolDecision decision = decisionClient.decide(messages);
            long costMs = System.currentTimeMillis() - startTime;
            return new TimedDecision(decision, costMs, true, null);
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startTime;
            return new TimedDecision(null, costMs, false, e.getMessage());
        }
    }

    /**
     * needTool=false 有两种含义：
     * 1. 从未执行工具：普通直接回答；
     * 2. 已执行工具：根据工具证据生成最终总结。
     */
    private AgentAskResponse finishWithDirectAnswer(AgentRunContext context, ToolDecision decision, long currentDecisionCostMs) {
        Long summaryCostMs = null;

        if (context.usedTool) {
            summaryCostMs = currentDecisionCostMs;
            addSummaryStep(context, decision.getDirectAnswer(), currentDecisionCostMs);
        } else {
            context.totalDecisionCostMs += currentDecisionCostMs;
            addDecisionStep(context, decision, currentDecisionCostMs);
        }

        // 检测是否超时
        GuardViolation timeoutViolation = checkExecutionTimeout(context);

        if (timeoutViolation != null) {
            return finishWithGuardFailure(context, timeoutViolation);
        }

        return buildResponse(
                context,
                decision.getDirectAnswer(),
                summaryCostMs
        );
    }

    private ToolExecutionResult executeTool(AgentRunContext context, ToolDecision decision) {
        String toolName = decision.getToolName();
        Map<String, Object> arguments = decision.getArguments();

        ToolExecutionResult result = toolRegistry.executeWithResult(toolName, arguments);

        context.toolCallCount++;
        context.usedTool = true;
        context.totalToolCostMs += result.getCostMs();
        context.lastToolName = toolName;
        context.lastToolResult = result.getResult();

        context.steps.add(new AgentTraceStep(
                "TOOL_EXECUTION",
                "执行工具：" + toolName,
                result.getSuccess(),
                result.getCostMs(),
                toJsonSafely(arguments),
                result.getResult(),
                result.getErrorMessage()
        ));

        return result;
    }

    /**
     * 工具失败后不再调用 AI 总结，避免模型掩盖真实错误。
     */
    private AgentAskResponse finishWithToolFailure(AgentRunContext context, ToolExecutionResult toolResult) {
        log.warn(
                "Agent 工具执行失败，traceId={}, toolName={}, error={}",
                context.traceId,
                context.lastToolName,
                toolResult.getErrorMessage()
        );

        return buildResponse(
                context,
                toolResult.getResult(),
                0L
        );
    }

    /**
     * 检查当前 Agent 请求是否超过允许的总执行时间。
     */
    private GuardViolation checkExecutionTimeout(AgentRunContext context) {
        // 计算当前时间与 agentStartTime 的差值
        long maxExecutionTimeMs = agentProperties.getMaxExecutionTimeMs();
        long elapsedTimeMs = System.currentTimeMillis() - context.agentStartTime;

        // 没有超时返回 null
        if (elapsedTimeMs < maxExecutionTimeMs) {
            return null;
        }

        // 超时后返回 GuardViolation
        return new GuardViolation(
                "总执行时间保护",
                "elapsedTimeMs=" + elapsedTimeMs,
                "Agent 执行时间已超过 "
                        + agentProperties.getMaxExecutionTimeMs()
                        + " 毫秒，已停止继续执行。"
        );
    }

    private GuardViolation checkToolCallGuard(AgentRunContext context, ToolDecision decision) {
        if (context.toolCallCount >= MAX_TOOL_CALLS) {
            return new GuardViolation(
                    "最大工具调用次数保护",
                    toJsonSafely(decision),
                    "Agent 已达到最大工具调用次数，已停止继续执行。"
            );
        }

        String signature = buildToolCallSignature(decision.getToolName(), decision.getArguments());

        /*
         * Set.add() 返回 false，表示相同工具和相同参数已经调用过。
         */
        if (!context.executedToolCalls.add(signature)) {
            return new GuardViolation(
                    "重复工具调用保护",
                    signature,
                    "Agent 检测到重复的工具调用，已停止继续执行。"
            );
        }

        return null;
    }

    private AgentAskResponse finishWithGuardFailure(AgentRunContext context, GuardViolation violation) {
        context.steps.add(new AgentTraceStep(
                "AGENT_GUARD",
                violation.description(),
                false,
                0L,
                violation.input(),
                violation.errorMessage(),
                violation.errorMessage()
        ));

        log.warn(
                "Agent 运行保护触发，traceId={}, reason={}",
                context.traceId,
                violation.description()
        );

        return buildResponse(context, violation.errorMessage(), 0L);
    }

    private void addDecisionStep(AgentRunContext context, ToolDecision decision, long costMs) {
        String input = context.toolCallCount == 0
                ? context.userMessage
                : context.lastToolResult;

        String description = Boolean.TRUE.equals(decision.getNeedTool())
                ? "AI 判断并选择后端工具"
                : "AI 判断无需调用工具";

        context.steps.add(new AgentTraceStep(
                "AI_DECISION",
                description,
                true,
                costMs,
                input,
                toJsonSafely(decision),
                null
        ));
    }

    private void addSummaryStep(
            AgentRunContext context,
            String finalAnswer,
            long costMs
    ) {
        context.steps.add(new AgentTraceStep(
                "AI_SUMMARY",
                "AI 根据已有工具证据生成最终回答",
                true,
                costMs,
                context.lastToolResult,
                finalAnswer,
                null
        ));
    }

    /**
     * 当前响应字段只保存最后一次工具名称和结果；
     * 完整的多步调用过程由 steps 保存。
     */
    private AgentAskResponse buildResponse(AgentRunContext context, String finalAnswer, Long summaryCostMs) {
        long agentCostMs = System.currentTimeMillis() - context.agentStartTime;
        Long toolCostMs = context.usedTool ? context.totalToolCostMs : null;

        return new AgentAskResponse(
                finalAnswer,
                context.usedTool,
                context.lastToolName,
                agentJsonHelper.parseJsonIfPossible(context.lastToolResult),
                toolCostMs,
                agentCostMs,
                context.traceId,
                context.totalDecisionCostMs,
                summaryCostMs,
                buildResponseSteps(context.steps)
        );
    }



    private List<AgentTraceStep> buildResponseSteps(List<AgentTraceStep> steps) {
        if (steps == null) {
            return null;
        }

        for (AgentTraceStep step : steps) {
            step.setInputView(agentJsonHelper.parseJsonIfPossible(step.getInput()));
            step.setOutputView(agentJsonHelper.parseJsonIfPossible(step.getOutput()));
        }

        return steps;
    }

    private String buildToolCallSignature(String toolName, Map<String, Object> arguments) {
        String normalizedToolName = toolName == null ? "" : toolName.trim();
        Map<String, Object> normalizedArguments = arguments == null ? Map.of() : new TreeMap<>(arguments);

        return normalizedToolName + "|" + toJsonSafely(normalizedArguments);
    }

    private String toJsonSafely(Object object) {
        if (object == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            return object.toString();
        }
    }

    private void logDecision(String traceId, ToolDecision decision, long decisionCostMs) {
        log.info(
                "Agent 工具决策完成，traceId={}, needTool={}, toolName={}, decisionCostMs={}",
                traceId,
                decision.getNeedTool(),
                decision.getToolName(),
                decisionCostMs
        );
    }

    private record TimedDecision(ToolDecision decision, long costMs, boolean success, String errorMessage) {
    }

    private record GuardViolation(String description, String input, String errorMessage) {
    }

    /**
     * 保存一次 Agent 请求在循环过程中的可变状态。
     *
     * 这些字段只在单个请求内部使用，不会在多个请求之间共享，
     * 因此不会产生 Spring 单例 Bean 的线程安全问题。
     */
    private static final class AgentRunContext {

        private final String userMessage;
        private final String traceId;
        private final long agentStartTime;
        private final List<Map<String, String>> messages;
        private final List<AgentTraceStep> steps = new ArrayList<>();
        private final Set<String> executedToolCalls = new HashSet<>();

        private long totalDecisionCostMs;
        private long totalToolCostMs;
        private int toolCallCount;

        private boolean usedTool;
        private String lastToolName;
        private String lastToolResult;

        private AgentRunContext(
                String userMessage,
                String traceId,
                List<Map<String, String>> messages
        ) {
            this.userMessage = userMessage;
            this.traceId = traceId;
            this.messages = messages;
            this.agentStartTime = System.currentTimeMillis();
        }
    }
}