package com.example.agent.service;

import com.example.agent.support.AgentJsonHelper;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.tool.springai.SpringAiDiagnosticTools;
import com.example.agent.tool.springai.SpringAiKnowledgeSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SpringAiBoundaryPolicyTest {

	@Test
	void systemPromptShouldRequireTableNameEvidenceBeforeSchemaTools() {
		assertThat(SpringAiToolChatService.SYSTEM_PROMPT)
			.contains("不得猜测、补全或选择默认表名", "无法确定 tableName 时", "请用户补充表名或包含目标表的完整 SQL")
			.contains("不得把 Tool 描述中的示例、本地知识或历史经验当成用户已经提供的参数");
	}

	@Test
	void tableNameToolSchemasShouldRejectGuessedOrExampleValues() throws Exception {
		ObjectMapper objectMapper = new ObjectMapper();
		SpringAiDiagnosticTools tools = new SpringAiDiagnosticTools(mock(ToolRegistry.class),
				mock(AgentJsonHelper.class), objectMapper, mock(SpringAiKnowledgeSupport.class));

		Map<String, ToolCallback> callbacks = Arrays.stream(ToolCallbacks.from(tools))
			.collect(Collectors.toMap(callback -> callback.getToolDefinition().name(), callback -> callback));

		assertTableNamePolicy(objectMapper, callbacks.get("getTableSchema"));
		assertTableNamePolicy(objectMapper, callbacks.get("analyzeSqlErrorWithSchema"));
	}

	private void assertTableNamePolicy(ObjectMapper objectMapper, ToolCallback callback) throws Exception {
		assertThat(callback.getToolDefinition().description()).contains("不得猜测");

		JsonNode inputSchema = objectMapper.readTree(callback.getToolDefinition().inputSchema());
		String tableNameDescription = inputSchema.path("properties").path("tableName").path("description").asText();

		assertThat(tableNameDescription).contains("必须来自用户", "不得猜测").doesNotContain("agent_run_log");
	}

}
