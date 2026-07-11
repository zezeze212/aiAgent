package com.example.agent.service;

import com.example.agent.config.DeepSeekProperties;
import com.example.agent.dto.ErrorAnalyzeResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final WebClient webClient;

    private final DeepSeekProperties deepSeekProperties;

    private final ObjectMapper objectMapper;

    public String chat(String userMessage) {
        return callDeepSeek(List.of(
                Map.of(
                        "role", "system",
                        "content", "你是一个帮助 Java 后端学习 AI Agent 开发的助手，回答要清晰、具体、分步骤。"
                ),
                Map.of(
                        "role", "user",
                        "content", userMessage
                )
        ));
    }

    public ErrorAnalyzeResponse analyzeError(String logText) {
        String content = analyzeErrorRaw(logText);

        try {
            String json = cleanJson(content);
            return objectMapper.readValue(json, ErrorAnalyzeResponse.class);
        } catch (Exception e) {
            log.error("AI 结构化输出解析失败，原始返回={}", content, e);

            ErrorAnalyzeResponse fallback = new ErrorAnalyzeResponse();
            fallback.setErrorType("PARSE_ERROR");
            fallback.setPossibleReason("AI 返回内容不是标准 JSON：" + content);
            fallback.setSuggestion("检查 system prompt 是否明确要求只返回 JSON");
            fallback.setNextStep("调用 /ai/analyze-error/raw 查看 AI 原始返回内容");
            return fallback;
        }
    }

    public String analyzeErrorRaw(String logText) {
        return callDeepSeek(buildErrorAnalyzeMessages(logText));
    }

    private List<Map<String, String>> buildErrorAnalyzeMessages(String logText) {
        return List.of(
                Map.of(
                        "role", "system",
                        "content", """
                                你是一个 Java 后端错误排查助手。
                                你只能返回 JSON，不要返回 Markdown，不要返回解释文字，不要使用 ```json 包裹。
                                
                                JSON 格式必须是：
                                {
                                  "errorType": "错误类型",
                                  "possibleReason": "可能原因",
                                  "suggestion": "修改建议",
                                  "nextStep": "下一步排查动作"
                                }
                                """
                ),
                Map.of(
                        "role", "user",
                        "content", "请分析下面这段报错日志：\n" + logText
                )
        );
    }


    private String callDeepSeek(List<Map<String, String>> messages) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> requestBody = Map.of(
                "model", deepSeekProperties.getModel(),
                "messages", messages,
                "stream", false
        );

        log.info("开始调用 DeepSeek，requestId={}, model={}", requestId, deepSeekProperties.getModel());

        try {
            Map response = webClient.post()
                    .uri(deepSeekProperties.getBaseUrl() + "/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(deepSeekProperties.getApiKey()))
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            String content = parseAnswer(response);

            log.info("DeepSeek 调用成功，requestId={}, contentLength={}",
                    requestId,
                    content == null ? 0 : content.length());

            return content;
        } catch (Exception e) {
            log.error("DeepSeek 调用失败，requestId={}", requestId, e);
            throw new RuntimeException("调用 DeepSeek 失败：" + e.getMessage(), e);
        }
    }

    private String parseAnswer(Map response) {
        if (response == null) {
            return "AI 接口没有返回结果";
        }

        Object choicesObj = response.get("choices");
        if (!(choicesObj instanceof List)) {
            return "AI 返回格式异常：" + response;
        }

        List choices = (List) choicesObj;
        if (choices.isEmpty()) {
            return "AI 返回 choices 为空：" + response;
        }

        Object firstChoiceObj = choices.get(0);
        if (!(firstChoiceObj instanceof Map)) {
            return "AI 返回 choice 格式异常：" + response;
        }

        Map firstChoice = (Map) firstChoiceObj;
        Object messageObj = firstChoice.get("message");
        if (!(messageObj instanceof Map)) {
            return "AI 返回 message 格式异常：" + response;
        }

        Map message = (Map) messageObj;
        Object content = message.get("content");

        if (content == null) {
            return "AI 返回 content 为空";
        }

        return content.toString();
    }

    private String cleanJson(String content) {
        if (content == null) {
            return "";
        }

        return content
                .replace("```json", "")
                .replace("```", "")
                .trim();
    }
}