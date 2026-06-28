package com.example.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentAskResponse {

    private String answer;

    private Boolean usedTool;

    private String toolName;

    private String toolResult;
}