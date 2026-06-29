package com.example.agent.tool;

import com.example.agent.dto.ErrorAnalyzeResponse;
import com.example.agent.service.AiChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class AnalyzeJavaErrorTool implements AgentTool {

    private final AiChatService aiChatService;

    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "analyzeJavaError";
    }

    @Override
    public String description() {
        return "分析 Java 后端、Spring Boot、MyBatis、SQL、JSON、接口调用、权限、连接失败等相关报错。";
    }

    @Override
    public String parameterSchema() {
        return """
                {
                  "log": "用户提供的报错日志或错误描述"
                }
                """;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String logText = getStringArg(arguments, "log");

        if (logText == null || logText.isBlank()) {
            return "缺少报错日志参数 log，无法分析错误";
        }

        try {
            ErrorAnalyzeResponse response = aiChatService.analyzeError(logText);
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "分析 Java 报错失败：" + e.getMessage();
        }
    }

    private String getStringArg(Map<String, Object> arguments, String key) {
        if (arguments == null) {
            return null;
        }

        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }

        return value.toString();
    }
}