package com.example.agent.client;

import com.example.agent.config.DeepSeekProperties;
import com.example.agent.dto.ToolDecision;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.validator.ToolDecisionValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DeepSeek 工具决策客户端。
 *
 * 负责：
 * 1. 构造 Agent 决策消息；
 * 2. 调用 DeepSeek；
 * 3. 解析 ToolDecision；
 * 4. 将工具执行结果追加到对话上下文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekDecisionClient {

    private final WebClient webClient;
    private final DeepSeekProperties deepSeekProperties;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final ToolDecisionValidator toolDecisionValidator;

    public List<Map<String, String>> createDecisionMessages(String userMessage) {
        List<Map<String, String>> messages = new ArrayList<>();

        messages.add(Map.of(
                "role", "system",
                "content", buildSystemPrompt()
        ));

        messages.add(Map.of(
                "role", "user",
                "content", userMessage
        ));

        return messages;
    }

    public ToolDecision decide(List<Map<String, String>> messages) {
        String content = callDeepSeek(messages);

        try {
            String json = cleanJson(content);
            ToolDecision decision = objectMapper.readValue(json, ToolDecision.class);

            return toolDecisionValidator.validateAndNormalize(decision);
        } catch (Exception e) {
            log.error("DeepSeek 决策解析失败，rawContent={}", content, e);
            throw new IllegalStateException("模型工具决策解析或校验失败", e);
        }
    }

    /**
     * 工具结果必须加入原有 messages，而不是重新创建一段孤立对话。
     * 这样模型才能同时看到原始问题和前面已经取得的工具证据。
     */
    public void appendToolResult(
            List<Map<String, String>> messages,
            String toolName,
            Map<String, Object> arguments,
            String toolResult
    ) {
        messages.add(Map.of(
                "role", "user",
                "content", """
                        后端工具已经执行完成。

                        工具名称：
                        %s

                        工具参数：
                        %s

                        工具返回证据：
                        %s

                        请结合原始用户问题和上述工具证据继续判断：
                        1. 如果证据已经足够，needTool 返回 false，并在 directAnswer 中生成最终答案。
                        2. 如果还需要其他工具，needTool 返回 true，并给出下一个工具及参数。
                        3. 不要重复调用相同工具和相同参数。
                        """.formatted(
                        toolName,
                        toJsonSafely(arguments),
                        toolResult
                )
        ));
    }

    private String buildSystemPrompt() {
        String toolsPrompt = toolRegistry.buildToolsPrompt();

        return """
                你是一个 Java 后端排障 Agent，同时负责判断是否需要调用后端工具。

                当前可用工具如下：

                %s

                调用规则：
                - 如果用户问题需要工具提供真实证据才能准确回答，则调用对应工具。
                - 如果用户只是询问概念问题，不需要调用工具，直接回答。
                - 如果用户想分析报错，但是没有提供具体报错内容，不要调用工具，直接提示用户补充日志。
                - toolName 必须严格使用已有工具名称，不要编造工具名。
                - arguments 必须严格符合工具参数格式。
                - 对话中可能包含已经执行完成的工具结果。
                - 如果已有工具证据足以回答，needTool 必须返回 false，并在 directAnswer 中给出最终答案。
                - 只有现有证据不足时才能继续调用其他工具。
                - 不得重复调用名称和参数都相同的工具。
                - directAnswer 必须以真实工具证据为依据，不得编造证据中不存在的信息。
                - 不得根据字段名猜测字段类型、长度、默认值和注释。
                - 无法确认业务设计时，不要直接生成 ALTER TABLE、UPDATE、DELETE 等 SQL。
                - 字段不存在时，应优先建议检查 Mapper XML、实体类和数据库版本。
                - 只有用户明确确认需要新增字段并提供字段定义后，才能生成 DDL。

                你只能返回 JSON，不要返回 Markdown、解释文字或代码块。

                如果需要工具，返回：
                {
                  "needTool": true,
                  "toolName": "工具名称",
                  "arguments": {},
                  "directAnswer": ""
                }

                如果不需要工具，返回：
                {
                  "needTool": false,
                  "toolName": "",
                  "arguments": {},
                  "directAnswer": "直接回答用户的问题"
                }
                """.formatted(toolsPrompt);
    }

    private String callDeepSeek(List<Map<String, String>> messages) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> requestBody = Map.of(
                "model", deepSeekProperties.getModel(),
                "messages", messages,
                "stream", false
        );

        log.info(
                "Agent 开始调用 DeepSeek，requestId={}, model={}",
                requestId,
                deepSeekProperties.getModel()
        );

        try {
            Map response = webClient.post()
                    .uri(deepSeekProperties.getBaseUrl() + "/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers ->
                            headers.setBearerAuth(deepSeekProperties.getApiKey())
                    )
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(deepSeekProperties.getTimeoutMs()))
                    .block();

            String content = parseAnswer(response);

            log.info(
                    "Agent 调用 DeepSeek 成功，requestId={}, contentLength={}",
                    requestId,
                    content == null ? 0 : content.length()
            );

            return content;
        } catch (Exception e) {
            log.error("Agent 调用 DeepSeek 失败，requestId={}", requestId, e);

            throw new RuntimeException(
                    "Agent 调用 DeepSeek 失败：" + e.getMessage(),
                    e
            );
        }
    }

    private String parseAnswer(Map response) {
        if (response == null) {
            return "AI 接口没有返回结果";
        }

        Object choicesObject = response.get("choices");

        if (!(choicesObject instanceof List choices) || choices.isEmpty()) {
            return "AI 返回 choices 格式异常：" + response;
        }

        Object firstChoiceObject = choices.get(0);

        if (!(firstChoiceObject instanceof Map firstChoice)) {
            return "AI 返回 choice 格式异常：" + response;
        }

        Object messageObject = firstChoice.get("message");

        if (!(messageObject instanceof Map message)) {
            return "AI 返回 message 格式异常：" + response;
        }

        Object content = message.get("content");

        return content == null
                ? "AI 返回 content 为空"
                : content.toString();
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

    private String toJsonSafely(Object object) {
        if (object == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            return object.toString();
        }
    }
}