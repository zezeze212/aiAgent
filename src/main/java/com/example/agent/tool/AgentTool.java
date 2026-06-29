package com.example.agent.tool;

import java.util.Map;

public interface AgentTool {

    /**
     * 工具名称，必须和 AI 返回的 toolName 一致
     */
    String name();

    /**
     * 工具描述，告诉 AI 这个工具能干什么
     */
    String description();

    /**
     * 参数格式，告诉 AI arguments 应该怎么传
     */
    String parameterSchema();

    /**
     * 执行工具
     */
    String execute(Map<String, Object> arguments);
}