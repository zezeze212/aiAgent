package com.example.agent.tool;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 工具的统一机器可读契约。
 *
 * timeout、权限、风险和副作用在 M2-1 中先作为声明元数据； 当前真正强制执行的是参数契约。
 */
public record ToolDefinition(String name, String description, List<ToolParameterDefinition> parameters,
		String resultDescription, Long timeoutMs, boolean readOnly, ToolSideEffect sideEffect, ToolRiskLevel riskLevel,
		List<String> requiredPermissions) {

	public ToolDefinition {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("工具名称不能为空");
		}

		if (description == null || description.isBlank()) {
			throw new IllegalArgumentException("工具描述不能为空，toolName=" + name);
		}

		if (timeoutMs == null || timeoutMs <= 0) {
			throw new IllegalArgumentException("工具 timeoutMs 必须大于 0，toolName=" + name);
		}

		if (sideEffect == null) {
			throw new IllegalArgumentException("工具 sideEffect 不能为空，toolName=" + name);
		}

		if (riskLevel == null) {
			throw new IllegalArgumentException("工具 riskLevel 不能为空，toolName=" + name);
		}

		parameters = parameters == null ? List.of() : List.copyOf(parameters);
		requiredPermissions = requiredPermissions == null ? List.of() : List.copyOf(requiredPermissions);

		resultDescription = resultDescription == null ? "" : resultDescription;

		Set<String> parameterNames = new HashSet<>();

		for (ToolParameterDefinition parameter : parameters) {
			if (!parameterNames.add(parameter.name())) {
				throw new IllegalArgumentException("工具参数名称重复，toolName=" + name + "，parameter=" + parameter.name());
			}
		}
	}

	private ToolDefinition buildDefinition(String name, List<ToolParameterDefinition> parameters) {

		return buildDefinition(name, parameters, 1000L);
	}

	private ToolDefinition buildDefinition(String name, List<ToolParameterDefinition> parameters, Long timeoutMs) {

		return new ToolDefinition(name, "测试工具", parameters, "测试结果", timeoutMs, true, ToolSideEffect.NONE,
				ToolRiskLevel.LOW, List.of("TEST_READ"));
	}
}