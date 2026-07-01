package com.example.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionResult {
    private String toolName;

    private Boolean success;

    private String result;

    private String errorMessage;

    private Long costMs;
}
