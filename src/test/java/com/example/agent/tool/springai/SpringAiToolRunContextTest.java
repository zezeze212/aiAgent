package com.example.agent.tool.springai;

import com.example.agent.dto.ToolExecutionResult;
import com.example.agent.support.AgentJsonHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiToolRunContextTest {

	private ObjectMapper objectMapper;

	private AgentJsonHelper agentJsonHelper;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		agentJsonHelper = new AgentJsonHelper(objectMapper);
	}

	@Test
	void shouldRejectRepeatedToolCall() {
		SpringAiToolRunContext context = createContext(120_000L);

		Map<String, Object> arguments = Map.of("tableName", "agent_run_log");

		context.executeGovernedTool("getTableSchema", arguments,
				() -> ToolExecutionResult.success("getTableSchema", "[]", 10L));

		assertThatThrownBy(() -> context.executeGovernedTool("getTableSchema", arguments,
				() -> ToolExecutionResult.success("getTableSchema", "[]", 10L)))
			.isInstanceOf(SpringAiToolAbortException.class)
			.hasMessageContaining("重复");

		assertThat(context.getToolCallCount()).isEqualTo(1);

		assertThat(context.getSteps()).anySatisfy(step -> {
			assertThat(step.getStepName()).isEqualTo("AGENT_GUARD");

			assertThat(step.getDescription()).isEqualTo("重复工具调用保护");
		});
	}

	@Test
	void shouldRejectFourthToolCall() {
		SpringAiToolRunContext context = createContext(120_000L);

		for (int i = 1; i <= 3; i++) {
			String tableName = "table_" + i;

			context.executeGovernedTool("getTableSchema", Map.of("tableName", tableName),
					() -> ToolExecutionResult.success("getTableSchema", "[]", 5L));
		}

		assertThatThrownBy(() -> context.executeGovernedTool("getTableSchema", Map.of("tableName", "table_4"),
				() -> ToolExecutionResult.success("getTableSchema", "[]", 5L)))
			.isInstanceOf(SpringAiToolAbortException.class)
			.hasMessageContaining("最大工具调用次数");

		assertThat(context.getToolCallCount()).isEqualTo(3);
	}

	@Test
	void shouldAbortWhenTotalExecutionTimeExceeded() {
		SpringAiToolRunContext context = createContext(1L);

		/*
		 * 避免依赖 Thread.sleep： 循环等待到毫秒时间发生变化即可。
		 */
		long start = System.currentTimeMillis();

		while (System.currentTimeMillis() == start) {
			Thread.onSpinWait();
		}

		assertThatThrownBy(() -> context.executeGovernedTool("getTableSchema", Map.of("tableName", "agent_run_log"),
				() -> ToolExecutionResult.success("getTableSchema", "[]", 5L)))
			.isInstanceOf(SpringAiToolAbortException.class)
			.hasMessageContaining("执行时间");

		assertThat(context.getToolCallCount()).isZero();
	}

	private SpringAiToolRunContext createContext(long maxExecutionTimeMs) {

		return new SpringAiToolRunContext("test1234", "测试问题", maxExecutionTimeMs, objectMapper, agentJsonHelper);
	}

}