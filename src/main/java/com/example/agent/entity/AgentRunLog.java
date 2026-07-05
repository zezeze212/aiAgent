package com.example.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentRunLog {

    private Long id;

    private String traceId;

    private String userMessage;

    private String answer;

    private Integer usedTool;

    private String toolName;

    private String toolResult;

    private Long decisionCostMs;

    private Long toolCostMs;

    private Long summaryCostMs;

    private Long agentCostMs;

    private Integer success;

    private String errorMessage;

    private LocalDateTime createdTime;

    /**
     * 回答摘要，列表页使用，不对应数据库字段
     */
    private String answerSummary;
}