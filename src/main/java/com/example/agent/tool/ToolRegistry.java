package com.example.agent.tool;

import com.example.agent.dto.ToolInfo;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    }

    public String execute(String toolName, Map<String, Object> arguments) {
        AgentTool tool = toolMap.get(toolName);

        if (tool == null) {
            return "未知工具：" + toolName;
        }

        return tool.execute(arguments);
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