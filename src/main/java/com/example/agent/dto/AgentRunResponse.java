package com.example.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunResponse {

    private Long id;

    private String traceId;

    private String userMessage;

    private String answer;

    private String answerSummary;

    private Boolean usedTool;

    private String toolName;

    private String toolResult;

    private Object toolResultView;

    private Long decisionCostMs;

    private Long toolCostMs;

    private Long summaryCostMs;

    private Long agentCostMs;

    private Boolean success;

    private String errorMessage;

    private LocalDateTime createdTime;
}