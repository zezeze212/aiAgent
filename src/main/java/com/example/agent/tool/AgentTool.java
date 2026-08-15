package com.example.agent.tool;

import java.util.Map;

public interface AgentTool {

	/**
	 * 工具的统一机器可读契约。
	 */
	ToolDefinition definition();

	/**
	 * 工具名称。
	 *
	 * 名称直接来自 ToolDefinition，避免工具名称存在两份定义。
	 */
	default String name() {
		return definition().name();
	}

	/**
	 * 工具描述。
	 *
	 * 描述直接来自 ToolDefinition，避免描述信息重复维护。
	 */
	default String description() {
		return definition().description();
	}

	/**
	 * 执行工具。
	 *
	 * 正常情况下 arguments 已经经过 ToolRegistry 的统一参数校验。
	 */
	String execute(Map<String, Object> arguments);

}