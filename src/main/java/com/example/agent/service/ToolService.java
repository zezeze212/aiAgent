package com.example.agent.service;

import com.example.agent.dto.ErrorAnalyzeResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class ToolService {

    private final AiChatService aiChatService;

    private final ObjectMapper objectMapper;

    /**
     * 工具1：获取当前北京时间
     */
    public String getCurrentTime() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 工具2：分析 Java 后端报错日志
     */
    public String analyzeJavaError(String logText) {
        try {
            ErrorAnalyzeResponse response = aiChatService.analyzeError(logText);
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "分析 Java 报错失败：" + e.getMessage();
        }
    }
}