package com.example.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentTraceStepResponse {

    private Integer stepOrder;

    private String stepName;

    private String description;

    private Boolean success;

    private Long costMs;

    private String input;

    private String output;

    private Object inputView;

    private Object outputView;

    private String errorMessage;
}