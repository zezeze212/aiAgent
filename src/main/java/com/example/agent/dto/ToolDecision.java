package com.example.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolDecision {

    private Boolean needTool;

    private String toolName;

    private Map<String, Object> arguments;

    private String directAnswer;
}