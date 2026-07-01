package com.example.agent.tool;

import com.example.agent.dto.ToolExecutionResult;
import com.example.agent.dto.ToolInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ToolRegistry {

    private final Map<String, AgentTool> toolMap = new HashMap<>();

    public ToolRegistry(List<AgentTool> tools) {
        for (AgentTool tool : tools) {
            if (toolMap.containsKey(tool.name())) {
                throw new IllegalArgumentException("工具名称重复：" + tool.name());
            }
            toolMap.put(tool.name(), tool);
        }

        log.info("Agent 工具注册完成，工具数量={}, 工具列表={}", toolMap.size(), toolMap.keySet());
    }

    public String execute(String toolName, Map<String, Object> arguments) {
        return executeWithResult(toolName, arguments).getResult();
    }

    public ToolExecutionResult executeWithResult(String toolName, Map<String, Object> arguments) {
        long startTime = System.currentTimeMillis();

        log.info("开始执行 Agent 工具，toolName={}, arguments={}", toolName, arguments);

        AgentTool tool = toolMap.get(toolName);

        if (tool == null) {
            long costMs = System.currentTimeMillis() - startTime;
            String errorMessage = "未知工具：" + toolName;

            log.warn("Agent 工具执行失败，toolName={}, costMs={}, error={}", toolName, costMs, errorMessage);

            return new ToolExecutionResult(
                    toolName,
                    false,
                    errorMessage,
                    errorMessage,
                    costMs
            );
        }

        try {
            String result = tool.execute(arguments);
            long costMs = System.currentTimeMillis() - startTime;

            log.info("Agent 工具执行成功，toolName={}, costMs={}, resultLength={}",
                    toolName,
                    costMs,
                    result == null ? 0 : result.length());

            return new ToolExecutionResult(
                    toolName,
                    true,
                    result,
                    null,
                    costMs
            );
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startTime;
            String errorMessage = e.getMessage();

            log.error("Agent 工具执行异常，toolName={}, costMs={}", toolName, costMs, e);

            return new ToolExecutionResult(
                    toolName,
                    false,
                    "工具执行失败：" + errorMessage,
                    errorMessage,
                    costMs
            );
        }
    }

    public String buildToolsPrompt() {
        StringBuilder builder = new StringBuilder();

        for (AgentTool tool : toolMap.values()) {
            builder.append("工具名称：").append(tool.name()).append("\n");
            builder.append("工具描述：").append(tool.description()).append("\n");
            builder.append("参数格式：").append(tool.parameterSchema()).append("\n");
            builder.append("\n");
        }

        return builder.toString();
    }

    public List<ToolInfo> listTools() {
        return toolMap.values()
                .stream()
                .map(tool -> new ToolInfo(
                        tool.name(),
                        tool.description(),
                        tool.parameterSchema()
                ))
                .toList();
    }
}