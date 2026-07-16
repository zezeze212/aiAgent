package com.example.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentTraceStep {

    /**
     * 步骤名称，比如 AI_DECISION、TOOL_EXECUTION、AI_SUMMARY
     */
    private String stepName;

    /**
     * 步骤描述
     */
    private String description;

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 步骤耗时，单位毫秒
     */
    private Long costMs;

    /**
     * 步骤输入，先用字符串保存，后面可以考虑 JSON 化或落库
     */
    private String input;

    /**
     * 步骤输出，先用字符串保存
     */
    private String output;

    /**
     * 步骤输入
     */
    private Object inputView;

    /**
     * 步骤输出
     */
    private Object outputView;

    /**
     * 失败原因
     */
    private String errorMessage;

    public AgentTraceStep(
            String stepName,
            String description,
            Boolean success,
            Long costMs,
            String input,
            String output,
            String errorMessage
    ) {
        this.stepName = stepName;
        this.description = description;
        this.success = success;
        this.costMs = costMs;
        this.input = input;
        this.output = output;
        this.errorMessage = errorMessage;
    }
}