package com.example.agent.dto;

import com.example.agent.entity.AgentRunLog;
import com.example.agent.entity.AgentStepLog;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunDetailResponse {

    private AgentRunLog run;

    private List<AgentTraceStepResponse> steps;
}