package com.example.agent.tool;

import com.example.agent.config.AgentProperties;
import com.example.agent.dto.ToolExecutionResult;
import com.example.agent.dto.ToolInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class ToolRegistryTest {

	private final ToolArgumentValidator toolArgumentValidator = new ToolArgumentValidator();

	private ThreadPoolTaskExecutor executor;

	private ToolExecutionRunner toolExecutionRunner;

	private ToolAccessPolicy toolAccessPolicy;

	@BeforeEach
	void setUp() {
		executor = new ThreadPoolTaskExecutor();

		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(8);
		executor.setThreadNamePrefix("tool-registry-test-");

		/*
		 * 单元测试没有 Spring 容器负责初始化 Executor， 因此这里手动 initialize。
		 */
		executor.initialize();

		toolExecutionRunner = new ToolExecutionRunner(executor);

		/*
		 * 当前大多数 ToolRegistry 测试使用 TEST_READ 权限。 这样测试关注的是 Registry 对应行为，而不是被访问策略提前拒绝。
		 */
		AgentProperties properties = new AgentProperties();

		properties.getToolGovernance().setAllowedPermissions(List.of("TEST_READ"));

		properties.getToolGovernance().setMaxRiskLevel(ToolRiskLevel.LOW);

		properties.getToolGovernance().setAllowSideEffectTools(false);

		toolAccessPolicy = new ToolAccessPolicy(properties);
	}

	@AfterEach
	void tearDown() {
		/*
		 * 避免测试结束后残留工具线程。
		 */
		executor.shutdown();
	}

	@Test
	void shouldReturnExecutionErrorWhenToolThrowsException() {
		AgentTool tool = mock(AgentTool.class);

		when(tool.definition()).thenReturn(buildDefinition("failingTool", List.of()));

		when(tool.execute(anyMap())).thenThrow(new IllegalStateException("数据库连接异常"));

		ToolRegistry registry = buildRegistry(List.of(tool));

		ToolExecutionResult result = registry.executeWithResult("failingTool", Map.of());

		assertFalse(result.getSuccess());

		assertEquals(ToolExecutionStatus.EXECUTION_ERROR, result.getStatus());

		assertEquals("数据库连接异常", result.getErrorMessage());

		assertEquals("工具执行失败：数据库连接异常", result.getResult());

		assertNull(result.getErrorCode());
	}

	@Test
	void shouldReturnToolNotFoundWhenToolDoesNotExist() {
		ToolRegistry registry = buildRegistry(List.of());

		ToolExecutionResult result = registry.executeWithResult("missingTool", Map.of());

		assertFalse(result.getSuccess());

		assertEquals(ToolExecutionStatus.TOOL_NOT_FOUND, result.getStatus());

		assertEquals("未知工具：missingTool", result.getErrorMessage());

		assertNull(result.getErrorCode());
	}

	@Test
	void shouldReturnSuccessStatusWhenToolExecutesNormally() {
		AgentTool tool = mock(AgentTool.class);

		when(tool.definition()).thenReturn(buildDefinition("successTool", List.of()));

		when(tool.execute(anyMap())).thenReturn("执行成功");

		ToolRegistry registry = buildRegistry(List.of(tool));

		ToolExecutionResult result = registry.executeWithResult("successTool", Map.of());

		assertTrue(result.getSuccess());

		assertEquals(ToolExecutionStatus.SUCCESS, result.getStatus());

		assertEquals("执行成功", result.getResult());

		assertNull(result.getErrorCode());
		assertNull(result.getErrorMessage());
	}

	@Test
	void shouldReturnPermissionDeniedBeforeCallingTool() {
		AgentTool tool = mock(AgentTool.class);

		/*
		 * 当前测试环境只允许 TEST_READ， 这里故意声明一个未开放的 HTTP_EXTERNAL_READ。
		 */
		ToolDefinition definition = new ToolDefinition("httpTool", "测试 HTTP 工具", List.of(), "HTTP 结果", 1000L, true,
				ToolSideEffect.NONE, ToolRiskLevel.LOW, List.of("HTTP_EXTERNAL_READ"));

		when(tool.definition()).thenReturn(definition);

		ToolRegistry registry = buildRegistry(List.of(tool));

		ToolExecutionResult result = registry.executeWithResult("httpTool", Map.of());

		assertFalse(result.getSuccess());

		assertEquals(ToolExecutionStatus.PERMISSION_DENIED, result.getStatus());

		assertTrue(result.getErrorMessage().contains("HTTP_EXTERNAL_READ"));

		/*
		 * 权限拒绝必须发生在真正执行工具之前。
		 */
		verify(tool, never()).execute(anyMap());
	}

	@Test
	void shouldReturnInvalidArgumentBeforeCallingTool() {
		AgentTool tool = mock(AgentTool.class);

		ToolParameterDefinition tableName = ToolParameterDefinition.requiredString("tableName", 1, 64,
				"^[a-zA-Z0-9_]+$", true, "表名");

		when(tool.definition()).thenReturn(buildDefinition("schemaTool", List.of(tableName)));

		ToolRegistry registry = buildRegistry(List.of(tool));

		ToolExecutionResult result = registry.executeWithResult("schemaTool", Map.of("tableName", 123));

		assertFalse(result.getSuccess());

		assertEquals(ToolExecutionStatus.INVALID_ARGUMENT, result.getStatus());

		assertTrue(result.getErrorMessage().contains("参数类型错误：tableName"));

		verify(tool, never()).execute(anyMap());
	}

	@Test
	void shouldReturnBusinessErrorWhenToolReportsBusinessFailure() {
		AgentTool tool = mock(AgentTool.class);

		when(tool.definition()).thenReturn(buildDefinition("schemaTool", List.of()));

		when(tool.execute(anyMap())).thenThrow(new ToolBusinessException("TABLE_NOT_FOUND", "未查询到表结构", "数据库中不存在目标表"));

		ToolRegistry registry = buildRegistry(List.of(tool));

		ToolExecutionResult result = registry.executeWithResult("schemaTool", Map.of());

		assertFalse(result.getSuccess());

		assertEquals(ToolExecutionStatus.BUSINESS_ERROR, result.getStatus());

		assertEquals("TABLE_NOT_FOUND", result.getErrorCode());

		assertEquals("未查询到表结构", result.getResult());
	}

	@Test
	void shouldPassNormalizedArgumentsToTool() {
		AgentTool tool = mock(AgentTool.class);

		ToolParameterDefinition tableName = ToolParameterDefinition.requiredString("tableName", 1, 64,
				"^[a-zA-Z0-9_]+$", true, "表名");

		when(tool.definition()).thenReturn(buildDefinition("schemaTool", List.of(tableName)));

		when(tool.execute(Map.of("tableName", "agent_run_log"))).thenReturn("执行成功");

		ToolRegistry registry = buildRegistry(List.of(tool));

		ToolExecutionResult result = registry.executeWithResult("schemaTool", Map.of("tableName", "  agent_run_log  "));

		assertTrue(result.getSuccess());

		verify(tool).execute(Map.of("tableName", "agent_run_log"));
	}

	@Test
	void shouldReturnTimeoutWhenToolExecutionExceedsDefinitionTimeout() {
		AgentTool tool = mock(AgentTool.class);

		when(tool.definition()).thenReturn(buildDefinition("slowTool", List.of(), 50L));

		when(tool.execute(anyMap())).thenAnswer(invocation -> {
			/*
			 * sleep 明显超过 50ms timeout。 cancel(true) 会中断 sleep，因此不会真的等待 5 秒。
			 */
			Thread.sleep(5000);
			return "不应该返回这个结果";
		});

		ToolRegistry registry = buildRegistry(List.of(tool));

		ToolExecutionResult result = registry.executeWithResult("slowTool", Map.of());

		assertFalse(result.getSuccess());

		assertEquals(ToolExecutionStatus.TIMEOUT, result.getStatus());

		assertNull(result.getErrorCode());

		assertTrue(result.getResult().contains("工具执行超时"));

		assertTrue(result.getErrorMessage().contains("timeoutMs=50"));
	}

	@Test
	void shouldRejectDuplicateToolNames() {
		AgentTool firstTool = mock(AgentTool.class);

		AgentTool secondTool = mock(AgentTool.class);

		ToolDefinition definition = buildDefinition("duplicateTool", List.of());

		when(firstTool.definition()).thenReturn(definition);

		when(secondTool.definition()).thenReturn(definition);

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> buildRegistry(List.of(firstTool, secondTool)));

		assertEquals("工具名称重复：duplicateTool", exception.getMessage());
	}

	@Test
	void shouldExposeMachineReadableToolDefinition() {
		AgentTool tool = mock(AgentTool.class);

		ToolParameterDefinition tableName = ToolParameterDefinition.requiredString("tableName", 1, 64,
				"^[a-zA-Z0-9_]+$", true, "表名");

		ToolDefinition definition = buildDefinition("schemaTool", List.of(tableName));

		when(tool.definition()).thenReturn(definition);

		ToolRegistry registry = buildRegistry(List.of(tool));

		List<ToolInfo> tools = registry.listTools();

		assertEquals(1, tools.size());

		assertEquals("schemaTool", tools.get(0).getName());

		assertEquals(1, tools.get(0).getParameters().size());

		assertEquals("tableName", tools.get(0).getParameters().get(0).name());

		assertEquals(ToolParameterType.STRING, tools.get(0).getParameters().get(0).type());
	}

	private ToolRegistry buildRegistry(List<AgentTool> tools) {

		return new ToolRegistry(tools, toolArgumentValidator, toolExecutionRunner, toolAccessPolicy);
	}

	/**
	 * 普通测试默认使用 1000ms timeout。
	 */
	private ToolDefinition buildDefinition(String name, List<ToolParameterDefinition> parameters) {

		return buildDefinition(name, parameters, 1000L);
	}

	/**
	 * timeout 测试允许单独指定等待时间。
	 */
	private ToolDefinition buildDefinition(String name, List<ToolParameterDefinition> parameters, Long timeoutMs) {

		return new ToolDefinition(name, "测试工具", parameters, "测试结果", timeoutMs, true, ToolSideEffect.NONE,
				ToolRiskLevel.LOW, List.of("TEST_READ"));
	}

}