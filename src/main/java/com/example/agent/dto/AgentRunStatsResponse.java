package com.example.agent.dto;

import lombok.Data;

import java.util.List;

@Data
public class AgentRunStatsResponse {

    /**
     * 总调用次数
     */
    private Long total;

    /**
     * 成功次数
     */
    private Long successCount;

    /**
     * 失败次数
     */
    private Long failedCount;

    /**
     * 成功率，百分比
     */
    private Double successRate;

    /**
     * AI 决策平均耗时
     */
    private Double avgDecisionCostMs;

    /**
     * 工具平均耗时
     */
    private Double avgToolCostMs;

    /**
     * AI 总结平均耗时
     */
    private Double avgSummaryCostMs;

    /**
     * Agent 整体平均耗时
     */
    private Double avgAgentCostMs;

    /**
     * 按工具维度统计
     */
    private List<ToolStatsItem> toolStats;
}