package com.example.agent.tool;

import com.example.agent.dto.ToolExecutionResult;
import com.example.agent.dto.ToolInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class ToolRegistryTest {

	private final ToolArgumentValidator toolArgumentValidator = new ToolArgumentValidator();

	@Test
	void shouldReturnExecutionErrorWhenToolThrowsException() {
		AgentTool tool = mock(AgentTool.class);

		when(tool.definition()).thenReturn(buildDefinition("failingTool", List.of()));

		when(tool.execute(anyMap())).thenThrow(new IllegalArgumentException("参数错误"));

		ToolRegistry registry = new ToolRegistry(List.of(tool), toolArgumentValidator);

		ToolExecutionResult result = registry.executeWithResult("failingTool", Map.of());

		assertFalse(result.getSuccess());

		assertEquals(ToolExecutionStatus.EXECUTION_ERROR, result.getStatus());

		assertEquals("参数错误", result.getErrorMessage());

		assertEquals("工具执行失败：参数错误", result.getResult());

		assertNull(result.getErrorCode());
	}

	@Test
	void shouldReturnToolNotFoundWhenToolDoesNotExist() {
		ToolRegistry registry = new ToolRegistry(List.of(), toolArgumentValidator);

		ToolExecutionResult result = registry.executeWithResult("missingTool", Map.of());

		assertFalse(result.getSuccess());

		assertEquals(ToolExecutionStatus.TOOL_NOT_FOUND, result.getStatus());

		assertEquals("未知工具：missingTool", result.getErrorMessage());

		assertEquals("未知工具：missingTool", result.getResult());

		assertNull(result.getErrorCode());
	}

	@Test
	void shouldReturnSuccessStatusWhenToolExecutesNormally() {
		AgentTool tool = mock(AgentTool.class);

		when(tool.definition()).thenReturn(buildDefinition("successTool", List.of()));

		when(tool.execute(anyMap())).thenReturn("执行成功");

		ToolRegistry registry = new ToolRegistry(List.of(tool), toolArgumentValidator);

		ToolExecutionResult result = registry.executeWithResult("successTool", Map.of());

		assertTrue(result.getSuccess());

		assertEquals(ToolExecutionStatus.SUCCESS, result.getStatus());

		assertEquals("successTool", result.getToolName());

		assertEquals("执行成功", result.getResult());

		assertNull(result.getErrorCode());
		assertNull(result.getErrorMessage());
	}

	@Test
	void shouldReturnInvalidArgumentBeforeCallingTool() {
		AgentTool tool = mock(AgentTool.class);

		ToolParameterDefinition tableName = ToolParameterDefinition.requiredString("tableName", 1, 64,
				"^[a-zA-Z0-9_]+$", true, "表名");

		when(tool.definition()).thenReturn(buildDefinition("schemaTool", List.of(tableName)));

		ToolRegistry registry = new ToolRegistry(List.of(tool), toolArgumentValidator);

		ToolExecutionResult result = registry.executeWithResult("schemaTool", Map.of("tableName", 123));

		assertFalse(result.getSuccess());

		assertEquals(ToolExecutionStatus.INVALID_ARGUMENT, result.getStatus());

		assertTrue(result.getErrorMessage().contains("参数类型错误：tableName"));

		assertTrue(result.getResult().startsWith("工具参数校验失败："));

		verify(tool, never()).execute(anyMap());
	}

	@Test
	void shouldPassNormalizedArgumentsToTool() {
		AgentTool tool = mock(AgentTool.class);

		ToolParameterDefinition tableName = ToolParameterDefinition.requiredString("tableName", 1, 64,
				"^[a-zA-Z0-9_]+$", true, "表名");

		when(tool.definition()).thenReturn(buildDefinition("schemaTool", List.of(tableName)));

		when(tool.execute(Map.of("tableName", "agent_run_log"))).thenReturn("执行成功");

		ToolRegistry registry = new ToolRegistry(List.of(tool), toolArgumentValidator);

		ToolExecutionResult result = registry.executeWithResult("schemaTool", Map.of("tableName", "  agent_run_log  "));

		assertTrue(result.getSuccess());

		assertEquals(ToolExecutionStatus.SUCCESS, result.getStatus());

		verify(tool).execute(Map.of("tableName", "agent_run_log"));
	}

	@Test
	void shouldRejectDuplicateToolNames() {
		AgentTool firstTool = mock(AgentTool.class);

		AgentTool secondTool = mock(AgentTool.class);

		ToolDefinition definition = buildDefinition("duplicateTool", List.of());

		when(firstTool.definition()).thenReturn(definition);

		when(secondTool.definition()).thenReturn(definition);

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> new ToolRegistry(List.of(firstTool, secondTool), toolArgumentValidator));

		assertEquals("工具名称重复：duplicateTool", exception.getMessage());
	}

	@Test
	void shouldExposeMachineReadableToolDefinition() {
		AgentTool tool = mock(AgentTool.class);

		ToolParameterDefinition tableName = ToolParameterDefinition.requiredString("tableName", 1, 64,
				"^[a-zA-Z0-9_]+$", true, "表名");

		ToolDefinition definition = buildDefinition("schemaTool", List.of(tableName));

		when(tool.definition()).thenReturn(definition);

		ToolRegistry registry = new ToolRegistry(List.of(tool), toolArgumentValidator);

		List<ToolInfo> tools = registry.listTools();

		assertEquals(1, tools.size());

		assertEquals("schemaTool", tools.get(0).getName());

		assertEquals(1, tools.get(0).getParameters().size());

		assertEquals("tableName", tools.get(0).getParameters().get(0).name());

		assertEquals(ToolParameterType.STRING, tools.get(0).getParameters().get(0).type());
	}

	private ToolDefinition buildDefinition(String name, List<ToolParameterDefinition> parameters) {

		return new ToolDefinition(name, "测试工具", parameters, "测试结果", 1000L, true, ToolSideEffect.NONE, ToolRiskLevel.LOW,
				List.of("TEST_READ"));
	}

}