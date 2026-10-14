package com.example.agent.tool.springai;

import com.example.agent.dto.ToolExecutionResult;
import com.example.agent.rag.KnowledgeRetrievalResult;
import com.example.agent.rag.KnowledgeSearchResult;
import com.example.agent.support.AgentJsonHelper;
import com.example.agent.tool.ToolExecutionStatus;
import com.example.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringAiDiagnosticToolsTest {

	@Mock
	private ToolRegistry toolRegistry;

	private ObjectMapper objectMapper;

	private SpringAiDiagnosticTools diagnosticTools;

	@Mock
	private SpringAiKnowledgeSupport knowledgeSupport;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();

		AgentJsonHelper agentJsonHelper = new AgentJsonHelper(objectMapper);

		diagnosticTools = new SpringAiDiagnosticTools(toolRegistry, agentJsonHelper, objectMapper, knowledgeSupport);
	}

	@Test
	void shouldExposeThreeSpringAiTools() {
		ToolCallback[] callbacks = ToolCallbacks.from(diagnosticTools);

		Map<String, ToolCallback> callbackMap = Arrays.stream(callbacks)
			.collect(Collectors.toMap(callback -> callback.getToolDefinition().name(), callback -> callback));

		assertThat(callbackMap).containsOnlyKeys("getTableSchema", "analyzeStackTrace", "analyzeSqlErrorWithSchema");

		assertThat(callbackMap.get("getTableSchema").getToolDefinition().inputSchema()).contains("tableName");

		assertThat(callbackMap.get("analyzeStackTrace").getToolDefinition().inputSchema()).contains("stackTrace",
				"applicationPackagePrefix");

		assertThat(callbackMap.get("analyzeSqlErrorWithSchema").getToolDefinition().inputSchema()).contains("log",
				"tableName");
	}

	@Test
	void shouldExecuteTableSchemaThroughToolRegistry() throws Exception {
		stubNoPostToolKnowledge();
		Map<String, Object> arguments = Map.of("tableName", "agent_run_log");

		ToolExecutionResult registryResult = ToolExecutionResult.success("getTableSchema", """
				[
				  {
				    "columnName": "id",
				    "columnType": "bigint"
				  }
				]
				""", 12L);

		when(toolRegistry.executeWithResult("getTableSchema", arguments)).thenReturn(registryResult);

		SpringAiToolRunContext runContext = createRunContext("请查询 agent_run_log 表的真实结构。");

		String result = diagnosticTools.getTableSchema("agent_run_log", createToolContext(runContext));

		JsonNode resultJson = objectMapper.readTree(result);

		assertThat(resultJson.get("success").asBoolean()).isTrue();
		assertThat(resultJson.get("status").asText()).isEqualTo("SUCCESS");
		assertThat(resultJson.get("result").isArray()).isTrue();
		assertThat(resultJson.get("result").get(0).get("columnName").asText()).isEqualTo("id");

		verify(toolRegistry).executeWithResult("getTableSchema", arguments);
		verify(knowledgeSupport).retrievePostToolKnowledge(runContext, registryResult.getResult());

		verify(knowledgeSupport).appendKnowledgeToToolPayload(anyString(), any());
	}

	@Test
	void shouldExecuteStackTraceThroughToolRegistry() {
		stubNoPostToolKnowledge();
		String stackTrace = """
				java.lang.IllegalStateException: 创建任务失败
				    at com.example.agent.service.TaskService.create(TaskService.java:42)
				Caused by: java.lang.NullPointerException: userId is null
				    at com.example.agent.service.TaskService.validate(TaskService.java:57)
				""";

		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("stackTrace", stackTrace);
		arguments.put("applicationPackagePrefix", "com.example.agent");

		ToolExecutionResult registryResult = ToolExecutionResult.success("analyzeStackTrace",
				"{\"rootCauseType\":\"java.lang.NullPointerException\"}", 8L);

		when(toolRegistry.executeWithResult("analyzeStackTrace", arguments)).thenReturn(registryResult);

		SpringAiToolRunContext runContext = createRunContext("请分析以下完整 Java StackTrace：" + stackTrace);

		String result = diagnosticTools.analyzeStackTrace(stackTrace, "com.example.agent",
				createToolContext(runContext));

		assertThat(result).contains("\"toolName\":\"analyzeStackTrace\"", "\"success\":true", "\"status\":\"SUCCESS\"",
				"java.lang.NullPointerException");

		verify(toolRegistry).executeWithResult("analyzeStackTrace", arguments);
	}

	@Test
	void shouldExecuteSqlAnalysisThroughToolRegistry() {
		stubNoPostToolKnowledge();
		String errorLog = "Unknown column 'model_name' in 'field list'";

		Map<String, Object> arguments = Map.of("log", errorLog, "tableName", "agent_run_log");

		ToolExecutionResult registryResult = ToolExecutionResult.success("analyzeSqlErrorWithSchema",
				"{\"missingColumns\":[\"model_name\"]}", 18L);

		when(toolRegistry.executeWithResult("analyzeSqlErrorWithSchema", arguments)).thenReturn(registryResult);

		SpringAiToolRunContext runContext = createRunContext("请分析报错，相关表是 agent_run_log。" + errorLog);

		String result = diagnosticTools.analyzeSqlErrorWithSchema(errorLog, "agent_run_log",
				createToolContext(runContext));

		assertThat(result).contains("\"toolName\":\"analyzeSqlErrorWithSchema\"", "\"success\":true",
				"\"status\":\"SUCCESS\"", "model_name");

		verify(toolRegistry).executeWithResult("analyzeSqlErrorWithSchema", arguments);
	}

	@Test
	void shouldAbortWhenRegistryReturnsBusinessFailure() {
		Map<String, Object> arguments = Map.of("tableName", "agent_run_logs");

		ToolExecutionResult registryResult = ToolExecutionResult.businessFailure("getTableSchema", "TABLE_NOT_FOUND",
				"未查询到表结构，tableName=agent_run_logs。你是不是想查询：agent_run_log", "数据库中不存在目标表，tableName=agent_run_logs", 15L);

		when(toolRegistry.executeWithResult("getTableSchema", arguments)).thenReturn(registryResult);

		SpringAiToolRunContext runContext = createRunContext("请查询 agent_run_logs 表的真实结构。");

		assertThatThrownBy(() -> diagnosticTools.getTableSchema("agent_run_logs", createToolContext(runContext)))
			.isInstanceOf(SpringAiToolAbortException.class)
			.hasMessageContaining("未查询到表结构", "agent_run_logs", "agent_run_log");

		assertThat(runContext.hasAbortAnswer()).isTrue();

		assertThat(runContext.getAbortAnswer()).contains("agent_run_logs", "agent_run_log");

		assertThat(runContext.getLastToolExecutionResult()).isSameAs(registryResult);

		assertThat(runContext.getLastToolExecutionResult().getStatus()).isEqualTo(ToolExecutionStatus.BUSINESS_ERROR);

		assertThat(runContext.getLastToolExecutionResult().getErrorCode()).isEqualTo("TABLE_NOT_FOUND");

		assertThat(runContext.getToolCallCount()).isEqualTo(1);

		assertThat(runContext.getSteps()).anySatisfy(step -> {
			assertThat(step.getStepName()).isEqualTo("TOOL_EXECUTION");

			assertThat(step.getSuccess()).isFalse();

			assertThat(step.getToolStatus()).isEqualTo(ToolExecutionStatus.BUSINESS_ERROR);

			assertThat(step.getErrorCode()).isEqualTo("TABLE_NOT_FOUND");

			assertThat(step.getOutput()).contains("agent_run_logs");
		});

		verify(toolRegistry).executeWithResult("getTableSchema", arguments);
	}

	@Test
	void shouldRejectTableNameThatDoesNotComeFromOriginalQuestion() {
		SpringAiToolRunContext runContext = createRunContext("Unknown column 'model_name' in 'field list'，请结合真实表结构分析。");

		assertThatThrownBy(
				() -> diagnosticTools.analyzeSqlErrorWithSchema("Unknown column 'model_name' in 'field list'", "model",
						createToolContext(runContext)))
			.isInstanceOf(SpringAiToolAbortException.class)
			.hasMessageContaining("缺少可验证的表名", "请补充表名");

		assertThat(runContext.hasAbortAnswer()).isTrue();
		assertThat(runContext.getToolCallCount()).isZero();
		assertThat(runContext.getSteps()).singleElement().satisfies(step -> {
			assertThat(step.getStepName()).isEqualTo("AGENT_GUARD");
			assertThat(step.getDescription()).isEqualTo("表名证据保护");
			assertThat(step.getInput()).contains("analyzeSqlErrorWithSchema", "model");
		});
	}

	/**
	 * 只有工具成功执行时才会进入 POST_TOOL 知识检索。
	 *
	 * 不放在 @BeforeEach 中，避免只检查工具定义或工具执行失败的测试 出现 Mockito UnnecessaryStubbingException。
	 */
	private void stubNoPostToolKnowledge() {
		when(knowledgeSupport.retrievePostToolKnowledge(any(SpringAiToolRunContext.class), any()))
			.thenReturn(KnowledgeRetrievalResult.empty("rule"));

		when(knowledgeSupport.appendKnowledgeToToolPayload(anyString(), any()))
			.thenAnswer(invocation -> invocation.getArgument(0, String.class));
	}

	private SpringAiToolRunContext createRunContext(String userMessage) {
		return new SpringAiToolRunContext("test1234", userMessage, 120_000L, objectMapper,
				new AgentJsonHelper(objectMapper));
	}

	private ToolContext createToolContext(SpringAiToolRunContext runContext) {

		return new ToolContext(Map.of(SpringAiToolRunContext.CONTEXT_KEY, runContext));
	}

}