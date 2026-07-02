package com.example.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentAskResponse {

    private String answer;

    private Boolean usedTool;

    private String toolName;

    private String toolResult;

    /**
     * 工具执行耗时，单位毫秒
     */
    private Long toolCostMs;

    /**
     * Agent 整体请求耗时，单位毫秒
     */
    private Long agentCostMs;

    /**
     * 本次 Agent 请求的追踪 ID
     */
    private String traceId;

    /**
     * AI 决策是否调用工具的耗时，单位毫秒
     */
    private Long decisionCostMs;

    /**
     * AI 根据工具结果总结回答的耗时，单位毫秒
     */
    private Long summaryCostMs;

    /**
     * Agent 执行步骤明细
     */
    private List<AgentTraceStep> steps;
}