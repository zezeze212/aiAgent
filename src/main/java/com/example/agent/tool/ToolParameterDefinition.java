package com.example.agent.tool;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 单个工具参数的机器可读定义。
 */
public record ToolParameterDefinition(String name, ToolParameterType type, boolean required, Integer minLength,
		Integer maxLength, String pattern, List<String> allowedValues, boolean trim, String description) {

	public ToolParameterDefinition {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("工具参数名称不能为空");
		}

		if (type == null) {
			throw new IllegalArgumentException("工具参数类型不能为空，parameter=" + name);
		}

		if (minLength != null && minLength < 0) {
			throw new IllegalArgumentException("minLength 不能小于 0，parameter=" + name);
		}

		if (maxLength != null && maxLength < 0) {
			throw new IllegalArgumentException("maxLength 不能小于 0，parameter=" + name);
		}

		if (minLength != null && maxLength != null && minLength > maxLength) {
			throw new IllegalArgumentException("minLength 不能大于 maxLength，parameter=" + name);
		}

		if (pattern != null && !pattern.isBlank()) {
			try {
				Pattern.compile(pattern);
			}
			catch (PatternSyntaxException e) {
				throw new IllegalArgumentException("工具参数正则表达式不合法，parameter=" + name, e);
			}
		}

		allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
		description = description == null ? "" : description;
	}

	public static ToolParameterDefinition requiredString(String name, Integer minLength, Integer maxLength,
			String pattern, boolean trim, String description) {

		return new ToolParameterDefinition(name, ToolParameterType.STRING, true, minLength, maxLength, pattern,
				List.of(), trim, description);
	}

}