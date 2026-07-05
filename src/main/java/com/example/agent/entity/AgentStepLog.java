package com.example.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentStepLog {

    private Long id;

    private String traceId;

    private String stepName;

    private String description;

    private Integer success;

    private Long costMs;

    private String inputText;

    private String outputText;

    private String errorMessage;

    private Integer stepOrder;

    private LocalDateTime createdTime;
}