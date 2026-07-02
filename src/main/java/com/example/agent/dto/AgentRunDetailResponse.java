package com.example.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunDetailResponse {

    private Map<String, Object> run;

    private List<Map<String, Object>> steps;
}