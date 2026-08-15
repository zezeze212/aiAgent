package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 根据 ToolDefinition 对模型提供的 arguments 做统一运行时校验。
 *
 * 校验成功后返回一份新的参数 Map； 非法参数最终将在进入具体 AgentTool.execute() 之前被拒绝。
 */
@Component
public class ToolArgumentValidator {

	public Map<String, Object> validateAndNormalize(ToolDefinition definition, Map<String, Object> arguments) {

		if (definition == null) {
			throw new IllegalArgumentException("工具定义不能为空");
		}

		Map<String, Object> sourceArguments = arguments == null ? Map.of() : arguments;

		validateUndefinedArguments(definition, sourceArguments);

		Map<String, Object> normalizedArguments = new LinkedHashMap<>();

		for (ToolParameterDefinition parameter : definition.parameters()) {
			Object value = sourceArguments.get(parameter.name());

			if (value == null) {
				if (parameter.required()) {
					throw new IllegalArgumentException("缺少必填参数：" + parameter.name());
				}

				continue;
			}

			Object normalizedValue = validateParameter(parameter, value);

			normalizedArguments.put(parameter.name(), normalizedValue);
		}

		return normalizedArguments;
	}

	/**
	 * 检查模型是否偷偷传入契约之外的参数。
	 */
	private void validateUndefinedArguments(ToolDefinition definition, Map<String, Object> arguments) {

		Set<String> definedNames = definition.parameters()
			.stream()
			.map(ToolParameterDefinition::name)
			.collect(Collectors.toSet());

		for (String argumentName : arguments.keySet()) {
			if (argumentName == null || !definedNames.contains(argumentName)) {

				throw new IllegalArgumentException("存在未定义参数：" + argumentName);
			}
		}
	}

	private Object validateParameter(ToolParameterDefinition parameter, Object value) {

		return switch (parameter.type()) {
			case STRING -> validateString(parameter, value);
			case INTEGER -> validateInteger(parameter, value);
			case BOOLEAN -> validateBoolean(parameter, value);
		};
	}

	private String validateString(ToolParameterDefinition parameter, Object value) {

		if (!(value instanceof String stringValue)) {
			throw typeMismatch(parameter, value);
		}

		String normalizedValue = parameter.trim() ? stringValue.trim() : stringValue;

		if (parameter.required() && normalizedValue.isBlank()) {
			throw new IllegalArgumentException("必填参数不能为空：" + parameter.name());
		}

		if (parameter.minLength() != null && normalizedValue.length() < parameter.minLength()) {

			throw new IllegalArgumentException("参数长度不能小于 " + parameter.minLength() + "：" + parameter.name());
		}

		if (parameter.maxLength() != null && normalizedValue.length() > parameter.maxLength()) {

			throw new IllegalArgumentException("参数长度不能超过 " + parameter.maxLength() + "：" + parameter.name());
		}

		if (parameter.pattern() != null && !parameter.pattern().isBlank()
				&& !normalizedValue.matches(parameter.pattern())) {

			throw new IllegalArgumentException("参数格式不合法：" + parameter.name());
		}

		if (!parameter.allowedValues().isEmpty() && !parameter.allowedValues().contains(normalizedValue)) {

			throw new IllegalArgumentException(
					"参数值不在允许范围内：" + parameter.name() + "，allowedValues=" + parameter.allowedValues());
		}

		return normalizedValue;
	}

	private Object validateInteger(ToolParameterDefinition parameter, Object value) {

		if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
				|| value instanceof BigInteger) {

			return value;
		}

		throw typeMismatch(parameter, value);
	}

	private Boolean validateBoolean(ToolParameterDefinition parameter, Object value) {

		if (value instanceof Boolean booleanValue) {
			return booleanValue;
		}

		throw typeMismatch(parameter, value);
	}

	private IllegalArgumentException typeMismatch(ToolParameterDefinition parameter, Object value) {

		String actualType = value == null ? "null" : value.getClass().getSimpleName();

		return new IllegalArgumentException(
				"参数类型错误：" + parameter.name() + "，expected=" + parameter.type() + "，actual=" + actualType);
	}

}