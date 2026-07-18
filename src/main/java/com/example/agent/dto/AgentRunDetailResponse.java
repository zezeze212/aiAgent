package com.example.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunDetailResponse {

    private AgentRunResponse run;

    private List<AgentTraceStepResponse> steps;
}