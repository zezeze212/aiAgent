package com.example.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolArgumentValidatorTest {

	private final ToolArgumentValidator validator = new ToolArgumentValidator();

	private final ToolDefinition definition = new ToolDefinition("testTool", "测试工具",
			List.of(ToolParameterDefinition.requiredString("tableName", 1, 64, "^[a-zA-Z0-9_]+$", true, "表名")), "测试结果",
			1000L, true, ToolSideEffect.NONE, ToolRiskLevel.LOW, List.of("TEST_READ"));

	@Test
	void shouldValidateAndNormalizeValidArguments() {
		Map<String, Object> result = validator.validateAndNormalize(definition,
				Map.of("tableName", "  agent_run_log  "));

		assertEquals("agent_run_log", result.get("tableName"));
	}

	@Test
	void shouldRejectMissingRequiredArgument() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> validator.validateAndNormalize(definition, Map.of()));

		assertEquals("缺少必填参数：tableName", exception.getMessage());
	}

	@Test
	void shouldRejectWrongArgumentType() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> validator.validateAndNormalize(definition, Map.of("tableName", 123)));

		assertTrue(exception.getMessage().contains("参数类型错误：tableName"));

		assertTrue(exception.getMessage().contains("expected=STRING"));
	}

	@Test
	void shouldRejectUndefinedArgument() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> validator
			.validateAndNormalize(definition, Map.of("tableName", "agent_run_log", "extra", "unexpected")));

		assertEquals("存在未定义参数：extra", exception.getMessage());
	}

	@Test
	void shouldRejectArgumentThatExceedsMaxLength() {
		String tableName = "a".repeat(65);

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> validator.validateAndNormalize(definition, Map.of("tableName", tableName)));

		assertEquals("参数长度不能超过 64：tableName", exception.getMessage());
	}

	@Test
	void shouldRejectArgumentThatDoesNotMatchPattern() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> validator.validateAndNormalize(definition, Map.of("tableName", "agent_run_log where 1=1")));

		assertEquals("参数格式不合法：tableName", exception.getMessage());
	}

}