package com.example.agent.dto;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ErrorAnalyzeResponse {

    private String errorType;

    private String possibleReason;

    private String suggestion;

    private String nextStep;
}