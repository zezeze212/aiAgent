package com.example.agent.tool.springai;

import com.example.agent.rag.SimpleRagRetriever;
import com.example.agent.stacktrace.StackTraceParser;
import com.example.agent.sql.SqlErrorEvidenceExtractor;
import com.example.agent.support.AgentJsonHelper;
import com.example.agent.tool.AgentTool;
import com.example.agent.tool.AnalyzeSqlErrorWithSchemaTool;
import com.example.agent.tool.AnalyzeStackTraceTool;
import com.example.agent.tool.GetTableSchemaTool;
import com.example.agent.tool.ToolDefinition;
import com.example.agent.tool.ToolParameterDefinition;
import com.example.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SpringAiToolContractParityTest {

	@Test
	void springAiToolSchemasShouldMatchLegacyToolDefinitions() throws Exception {

		ObjectMapper objectMapper = new ObjectMapper();
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

		/*
		 * 这里只读取旧工具的 ToolDefinition， 不会真正查询数据库或执行工具。
		 */
		List<AgentTool> legacyTools = List.of(new GetTableSchemaTool(jdbcTemplate, objectMapper),
				new AnalyzeStackTraceTool(new StackTraceParser(), objectMapper),
				new AnalyzeSqlErrorWithSchemaTool(jdbcTemplate, objectMapper, new SqlErrorEvidenceExtractor()));

		Map<String, ToolDefinition> legacyDefinitionMap = legacyTools.stream()
			.map(AgentTool::definition)
			.collect(Collectors.toMap(ToolDefinition::name, definition -> definition));

		SpringAiDiagnosticTools springAiTools = new SpringAiDiagnosticTools(
				mock(ToolRegistry.class),
				new AgentJsonHelper(objectMapper),
				objectMapper,
				mock(SpringAiKnowledgeSupport.class));

		Map<String, ToolCallback> springAiCallbackMap = Arrays.stream(ToolCallbacks.from(springAiTools))
			.collect(Collectors.toMap(callback -> callback.getToolDefinition().name(), callback -> callback));

		/*
		 * 第一层：工具名称必须完全对等。
		 */
		assertThat(springAiCallbackMap.keySet()).isEqualTo(legacyDefinitionMap.keySet());

		for (Map.Entry<String, ToolDefinition> entry : legacyDefinitionMap.entrySet()) {

			String toolName = entry.getKey();
			ToolDefinition legacyDefinition = entry.getValue();

			ToolCallback springAiCallback = springAiCallbackMap.get(toolName);

			assertThat(springAiCallback).as("Spring AI 缺少工具：" + toolName).isNotNull();

			assertThat(springAiCallback.getToolDefinition().description()).as("工具描述不能为空：" + toolName).isNotBlank();

			assertInputSchemaParity(objectMapper, legacyDefinition, springAiCallback);
		}
	}

	private void assertInputSchemaParity(ObjectMapper objectMapper, ToolDefinition legacyDefinition,
			ToolCallback springAiCallback) throws Exception {

		JsonNode schema = objectMapper.readTree(springAiCallback.getToolDefinition().inputSchema());

		JsonNode properties = schema.path("properties");

		Set<String> springAiParameterNames = new HashSet<>();

		properties.fieldNames().forEachRemaining(springAiParameterNames::add);

		Set<String> legacyParameterNames = legacyDefinition.parameters()
			.stream()
			.map(ToolParameterDefinition::name)
			.collect(Collectors.toSet());

		/*
		 * 参数名称不能多、不能少。 ToolContext 属于本地上下文，不应该进入模型 Schema。
		 */
		assertThat(springAiParameterNames).as("工具参数不一致：" + legacyDefinition.name())
			.isEqualTo(legacyParameterNames)
			.doesNotContain("toolContext");

		Set<String> springAiRequiredParameters = readRequiredParameters(schema);

		Set<String> legacyRequiredParameters = legacyDefinition.parameters()
			.stream()
			.filter(ToolParameterDefinition::required)
			.map(ToolParameterDefinition::name)
			.collect(Collectors.toSet());

		/*
		 * 必填和可选语义也必须保持一致。
		 */
		assertThat(springAiRequiredParameters).as("工具必填参数不一致：" + legacyDefinition.name())
			.isEqualTo(legacyRequiredParameters);
	}

	private Set<String> readRequiredParameters(JsonNode schema) {

		Set<String> requiredParameters = new HashSet<>();

		JsonNode requiredNode = schema.path("required");

		if (requiredNode.isArray()) {
			requiredNode.forEach(node -> requiredParameters.add(node.asText()));
		}

		return requiredParameters;
	}

}