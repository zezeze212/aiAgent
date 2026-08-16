package com.example.agent.tool;

import com.example.agent.config.AgentProperties;
import com.example.agent.dto.ToolInfo;
import com.example.agent.sql.SqlErrorEvidenceExtractor;
import com.example.agent.stacktrace.StackTraceParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 验证 Spring 能自动收集全部 AgentTool， 并由 ToolRegistry 统一完成注册。
 *
 * 这里不访问真实 MySQL，也不调用 DeepSeek。 目标只是验证“新增 Tool 后无需修改 Registry”这一框架能力。
 */
@SpringJUnitConfig(ToolRegistrationSpringTest.TestConfig.class)
class ToolRegistrationSpringTest {

	@jakarta.annotation.Resource
	private ToolRegistry toolRegistry;

	@jakarta.annotation.Resource(name = "toolExecutionExecutor")
	private ThreadPoolTaskExecutor executor;

	@Test
	void shouldAutomaticallyRegisterAllAgentTools() {
		List<ToolInfo> tools = toolRegistry.listTools();

		assertThat(tools).hasSize(3);

		assertThat(tools).extracting(ToolInfo::getName)
			.containsExactlyInAnyOrder("getTableSchema", "analyzeSqlErrorWithSchema", "analyzeStackTrace");
	}

	@Test
	void shouldExposeAnalyzeStackTraceContractThroughRegistry() {
		ToolInfo stackTraceTool = toolRegistry.listTools()
			.stream()
			.filter(tool -> "analyzeStackTrace".equals(tool.getName()))
			.findFirst()
			.orElseThrow();

		/*
		 * 这里验证 Registry 对外暴露的是机器可读 ToolDefinition， 而不是为了新工具再单独维护一份描述。
		 */
		assertThat(stackTraceTool.getParameters()).extracting(ToolParameterDefinition::name)
			.containsExactly("stackTrace", "applicationPackagePrefix");

		assertThat(stackTraceTool.getReadOnly()).isTrue();

		assertThat(stackTraceTool.getSideEffect()).isEqualTo(ToolSideEffect.NONE);

		assertThat(stackTraceTool.getRiskLevel()).isEqualTo(ToolRiskLevel.LOW);

		assertThat(stackTraceTool.getTimeoutMs()).isEqualTo(1000L);

		assertThat(stackTraceTool.getRequiredPermissions()).containsExactly("LOCAL_DIAGNOSTIC_ANALYZE");
	}

	@AfterEach
	void tearDown() {
		/*
		 * 测试上下文中的线程池由本测试创建， 测试完成后主动关闭，避免残留工具线程。
		 */
		executor.shutdown();
	}

	@Configuration
	static class TestConfig {

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}

		@Bean
		JdbcTemplate jdbcTemplate() {
			/*
			 * 本测试只验证 Tool 注册，不执行数据库工具， 因此 JdbcTemplate 使用 Mock 即可。
			 */
			return mock(JdbcTemplate.class);
		}

		@Bean
		SqlErrorEvidenceExtractor sqlErrorEvidenceExtractor() {
			return new SqlErrorEvidenceExtractor();
		}

		@Bean
		StackTraceParser stackTraceParser() {
			return new StackTraceParser();
		}

		@Bean
		AgentProperties agentProperties() {
			AgentProperties properties = new AgentProperties();

			/*
			 * 模拟当前 application.yml 的工具权限。
			 */
			properties.getToolGovernance()
				.setAllowedPermissions(List.of("DATABASE_METADATA_READ", "LOCAL_DIAGNOSTIC_ANALYZE"));

			properties.getToolGovernance().setMaxRiskLevel(ToolRiskLevel.LOW);

			properties.getToolGovernance().setAllowSideEffectTools(false);

			return properties;
		}

		@Bean(name = "toolExecutionExecutor")
		ThreadPoolTaskExecutor toolExecutionExecutor() {
			ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

			executor.setCorePoolSize(2);
			executor.setMaxPoolSize(2);
			executor.setQueueCapacity(8);
			executor.setThreadNamePrefix("tool-registration-test-");

			executor.initialize();

			return executor;
		}

		@Bean
		ToolExecutionRunner toolExecutionRunner(ThreadPoolTaskExecutor toolExecutionExecutor) {

			return new ToolExecutionRunner(toolExecutionExecutor);
		}

		@Bean
		ToolArgumentValidator toolArgumentValidator() {
			return new ToolArgumentValidator();
		}

		@Bean
		ToolAccessPolicy toolAccessPolicy(AgentProperties agentProperties) {

			return new ToolAccessPolicy(agentProperties);
		}

		@Bean
		GetTableSchemaTool getTableSchemaTool(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {

			return new GetTableSchemaTool(jdbcTemplate, objectMapper);
		}

		@Bean
		AnalyzeSqlErrorWithSchemaTool analyzeSqlErrorWithSchemaTool(JdbcTemplate jdbcTemplate,
				ObjectMapper objectMapper, SqlErrorEvidenceExtractor sqlErrorEvidenceExtractor) {

			return new AnalyzeSqlErrorWithSchemaTool(jdbcTemplate, objectMapper, sqlErrorEvidenceExtractor);
		}

		@Bean
		AnalyzeStackTraceTool analyzeStackTraceTool(StackTraceParser stackTraceParser, ObjectMapper objectMapper) {

			return new AnalyzeStackTraceTool(stackTraceParser, objectMapper);
		}

		@Bean
		ToolRegistry toolRegistry(List<AgentTool> tools, ToolArgumentValidator toolArgumentValidator,
				ToolExecutionRunner toolExecutionRunner, ToolAccessPolicy toolAccessPolicy) {

			/*
			 * 这里故意接收 List<AgentTool>。
			 *
			 * Spring 会自动把上面的三个 AgentTool Bean 全部注入进来，这正是生产环境 ToolRegistry 自动发现工具的工作方式。
			 */
			return new ToolRegistry(tools, toolArgumentValidator, toolExecutionRunner, toolAccessPolicy);
		}

	}

}