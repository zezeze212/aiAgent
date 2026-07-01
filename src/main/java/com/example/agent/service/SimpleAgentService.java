package com.example.agent.service;

import com.example.agent.config.DeepSeekProperties;
import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.ToolDecision;
import com.example.agent.dto.ToolExecutionResult;
import com.example.agent.tool.ToolRegistry;
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
public class SimpleAgentService {

    private final WebClient webClient;

    private final DeepSeekProperties deepSeekProperties;

    private final ObjectMapper objectMapper;

    private final ToolRegistry toolRegistry;

    public AgentAskResponse ask(String userMessage) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        long agentStartTime = System.currentTimeMillis();

        log.info("Agent 请求开始，traceId={}, userMessage={}", traceId, userMessage);

        long decisionStartTime = System.currentTimeMillis();
        ToolDecision decision = decideTool(userMessage);
        long decisionCostMs = System.currentTimeMillis() - decisionStartTime;

        log.info("Agent 工具决策完成，traceId={}, needTool={}, toolName={}, decisionCostMs={}",
                traceId,
                decision.getNeedTool(),
                decision.getToolName(),
                decisionCostMs);

        if (Boolean.FALSE.equals(decision.getNeedTool())) {
            long agentCostMs = System.currentTimeMillis() - agentStartTime;

            log.info("Agent 请求完成，traceId={}, usedTool=false, agentCostMs={}",
                    traceId,
                    agentCostMs);

            return new AgentAskResponse(
                    decision.getDirectAnswer(),
                    false,
                    null,
                    null,
                    null,
                    agentCostMs,
                    traceId,
                    decisionCostMs,
                    null
            );
        }

        String toolName = decision.getToolName();

        ToolExecutionResult toolExecutionResult = toolRegistry.executeWithResult(
                toolName,
                decision.getArguments()
        );

        String toolResult = toolExecutionResult.getResult();

        long summaryStartTime = System.currentTimeMillis();
        String finalAnswer = summarizeWithToolResult(userMessage, toolName, toolResult);
        long summaryCostMs = System.currentTimeMillis() - summaryStartTime;

        long agentCostMs = System.currentTimeMillis() - agentStartTime;

        log.info("Agent 请求完成，traceId={}, usedTool=true, toolName={}, decisionCostMs={}, toolCostMs={}, summaryCostMs={}, agentCostMs={}",
                traceId,
                toolName,
                decisionCostMs,
                toolExecutionResult.getCostMs(),
                summaryCostMs,
                agentCostMs);

        return new AgentAskResponse(
                finalAnswer,
                true,
                toolName,
                toolResult,
                toolExecutionResult.getCostMs(),
                agentCostMs,
                traceId,
                decisionCostMs,
                summaryCostMs
        );
    }

    private ToolDecision decideTool(String userMessage) {
        String toolsPrompt = toolRegistry.buildToolsPrompt();

        String content = callDeepSeek(List.of(
                Map.of(
                        "role", "system",
                        "content", """
                            你是一个工具调用决策助手。
                            你需要判断用户问题是否需要调用后端工具。
                            
                            当前可用工具如下：
                            
                            %s
                            
                            调用规则：
                            - 如果用户问题需要某个工具才能准确回答，则调用对应工具。
                            - 如果用户只是问概念问题，不需要调用工具，直接回答。
                            - 如果用户想分析报错，但是没有提供具体报错内容，不要调用工具，直接提示用户补充日志。
                            - toolName 必须严格使用工具名称，不要自己编造工具名。
                            - arguments 必须严格符合工具参数格式。
                            
                            你只能返回 JSON，不要返回 Markdown，不要返回解释文字，不要使用 ```json 包裹。
                            
                            如果需要工具，JSON 格式必须是：
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
                            """.formatted(toolsPrompt)
                ),
                Map.of(
                        "role", "user",
                        "content", userMessage
                )
        ));

        try {
            String json = cleanJson(content);
            return objectMapper.readValue(json, ToolDecision.class);
        } catch (Exception e) {
            log.error("工具决策 JSON 解析失败，AI原始返回={}", content, e);

            ToolDecision fallback = new ToolDecision();
            fallback.setNeedTool(false);
            fallback.setDirectAnswer("我暂时无法判断是否需要调用工具，AI 原始返回为：" + content);
            return fallback;
        }
    }


    private String summarizeWithToolResult(String userMessage, String toolName, String toolResult) {
        return callDeepSeek(List.of(
                Map.of(
                        "role", "system",
                        "content", """
                                你是一个 Java 后端 Agent 助手。
                                用户提出了一个问题，后端工具已经执行完成。
                                请根据工具执行结果，用自然语言回答用户。
                                回答要简洁清楚。
                                """
                ),
                Map.of(
                        "role", "user",
                        "content", """
                                用户问题：
                                %s
                                
                                调用的工具：
                                %s
                                
                                工具返回结果：
                                %s
                                """.formatted(userMessage, toolName, toolResult)
                )
        ));
    }

    private String callDeepSeek(List<Map<String, String>> messages) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> requestBody = Map.of(
                "model", deepSeekProperties.getModel(),
                "messages", messages,
                "stream", false
        );

        log.info("Agent 开始调用 DeepSeek，requestId={}, model={}", requestId, deepSeekProperties.getModel());

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

            log.info("Agent 调用 DeepSeek 成功，requestId={}, contentLength={}",
                    requestId,
                    content == null ? 0 : content.length());

            return content;
        } catch (Exception e) {
            log.error("Agent 调用 DeepSeek 失败，requestId={}", requestId, e);
            throw new RuntimeException("Agent 调用 DeepSeek 失败：" + e.getMessage(), e);
        }
    }

    private String parseAnswer(Map response) {
        if (response == null) {
            return "AI 接口没有返回结果";
        }

        Object choicesObj = response.get("choices");
        if (!(choicesObj instanceof List choices)) {
            return "AI 返回格式异常：" + response;
        }

        if (choices.isEmpty()) {
            return "AI 返回 choices 为空：" + response;
        }

        Object firstChoiceObj = choices.get(0);
        if (!(firstChoiceObj instanceof Map firstChoice)) {
            return "AI 返回 choice 格式异常：" + response;
        }

        Object messageObj = firstChoice.get("message");
        if (!(messageObj instanceof Map message)) {
            return "AI 返回 message 格式异常：" + response;
        }

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