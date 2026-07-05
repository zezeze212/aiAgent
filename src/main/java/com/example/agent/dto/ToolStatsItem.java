package com.example.agent.dto;

import lombok.Data;

@Data
public class ToolStatsItem {

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 调用次数
     */
    private Long runCount;

    /**
     * 平均 Agent 总耗时
     */
    private Double avgAgentCostMs;

    /**
     * 平均工具耗时
     */
    private Double avgToolCostMs;

    /**
     * 最大 Agent 总耗时
     */
    private Long maxAgentCostMs;
}