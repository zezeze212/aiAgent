package com.example.agent.validator;

import com.example.agent.dto.ToolDecision;
import io.micrometer.common.util.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Objects;

@Component
public class ToolDecisionValidator {

    public ToolDecision validateAndNormalize(ToolDecision decision) {
        if (decision == null) {
            throw new IllegalArgumentException("工具决策不能为空");
        }

        if (decision.getNeedTool() == null) {
            throw new IllegalArgumentException("needTool不能为空");
        }

        if (decision.getArguments() == null) {
            decision.setArguments(new LinkedHashMap<>());
        }

        if (Boolean.TRUE.equals(decision.getNeedTool())) {
            if (isBlank(decision.getToolName())) {
                throw new IllegalArgumentException("toolName不能为空");
            }

            decision.setToolName(decision.getToolName().trim());
            decision.setDirectAnswer("");
        } else {
            if (isBlank(decision.getDirectAnswer())) {
                throw new IllegalArgumentException("directAnswer不能为空");
            }

            decision.setToolName("");
        }

        return decision;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}