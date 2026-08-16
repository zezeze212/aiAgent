package com.example.agent.integration;

import com.example.agent.config.AgentProperties;
import com.example.agent.config.ToolExecutionConfig;
import com.example.agent.sql.SqlErrorEvidenceExtractor;
import com.example.agent.tool.*;
import com.example.agent.dto.ToolExecutionResult;
import com.example.agent.tool.ToolBusinessException;
import com.example.agent.tool.ToolExecutionStatus;
import com.example.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(classes = DatabaseToolMySqlContainerTest.DatabaseToolTestApplication.class,
		properties = { "spring.main.web-application-type=none", "spring.flyway.enabled=true",
				"spring.flyway.locations=classpath:db/migration" })
class DatabaseToolMySqlContainerTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.11"))
		.withDatabaseName("ai_agent")
		.withUsername("ai_agent")
		.withPassword("test_password")
		.withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
				"--default-time-zone=+08:00");

	@DynamicPropertySource
	static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
	}

	@Autowired
	private GetTableSchemaTool getTableSchemaTool;

	@Autowired
	private AnalyzeSqlErrorWithSchemaTool analyzeSqlErrorWithSchemaTool;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ToolRegistry toolRegistry;

	/**
	 * 只加载数据库、Flyway、JdbcTemplate、JSON 组件和两个数据库工具。
	 *
	 * 不加载 Agent 编排器和模型客户端，因此： 1. 不需要 DeepSeek API Key； 2. 不会调用外部模型； 3. 测试结果只反映数据库工具本身的行为。
	 */
	@SpringBootConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@Import({ GetTableSchemaTool.class, AnalyzeSqlErrorWithSchemaTool.class, SqlErrorEvidenceExtractor.class,
			ToolRegistry.class, ToolArgumentValidator.class, ToolExecutionRunner.class, ToolExecutionConfig.class,
			ToolAccessPolicy.class, AgentProperties.class })
	static class DatabaseToolTestApplication {

	}

	@Bean
	AgentProperties agentProperties() {
		AgentProperties properties = new AgentProperties();

		/*
		 * 本测试验证数据库工具本身以及 Registry 集成， 因此显式开放数据库元数据只读权限。
		 */
		properties.getToolGovernance().setAllowedPermissions(List.of("DATABASE_METADATA_READ"));

		properties.getToolGovernance().setMaxRiskLevel(ToolRiskLevel.LOW);

		properties.getToolGovernance().setAllowSideEffectTools(false);

		return properties;
	}

	@Test
	void shouldQueryRealTableSchemaFromInformationSchema() throws Exception {

		String result = getTableSchemaTool.execute(Map.of("tableName", "agent_run_log"));

		JsonNode columns = objectMapper.readTree(result);

		assertThat(columns.isArray()).isTrue();
		assertThat(columns.size()).isGreaterThanOrEqualTo(14);

		JsonNode idColumn = columns.get(0);

		assertThat(idColumn.path("columnName").asText()).isEqualTo("id");

		assertThat(idColumn.path("columnType").asText()).contains("bigint");

		assertThat(idColumn.path("nullable").asText()).isEqualTo("NO");

		assertThat(idColumn.path("ordinalPosition").asInt()).isEqualTo(1);

		assertThat(result).contains("\"trace_id\"")
			.contains("\"user_message\"")
			.contains("\"answer\"")
			.contains("\"used_tool\"")
			.contains("\"agent_cost_ms\"")
			.contains("\"created_time\"");
	}

	@Test
	void shouldReturnColumnsInDatabaseOrder() throws Exception {

		String result = getTableSchemaTool.execute(Map.of("tableName", "agent_step_log"));

		JsonNode columns = objectMapper.readTree(result);

		assertThat(columns.isArray()).isTrue();
		assertThat(columns.size()).isGreaterThanOrEqualTo(11);

		assertThat(columns.get(0).path("columnName").asText()).isEqualTo("id");

		assertThat(columns.get(1).path("columnName").asText()).isEqualTo("trace_id");

		assertThat(columns.get(2).path("columnName").asText()).isEqualTo("step_name");

		assertThat(columns.get(3).path("columnName").asText()).isEqualTo("description");

		assertThat(columns.get(9).path("columnName").asText()).isEqualTo("output_text");
	}

	@Test
	void shouldReportTableNotFoundAsBusinessFailure() {
		/*
		 * 直接调用具体 Tool 时，它只负责表达确定性的业务事实， 不负责包装 ToolExecutionResult。
		 */
		assertThatThrownBy(() -> getTableSchemaTool.execute(Map.of("tableName", "agent_run_lgo")))
			.isInstanceOf(ToolBusinessException.class)
			.satisfies(exception -> {
				ToolBusinessException businessException = (ToolBusinessException) exception;

				assertThat(businessException.getErrorCode()).isEqualTo("TABLE_NOT_FOUND");

				assertThat(businessException.getResult()).contains("未查询到表结构")
					.contains("agent_run_lgo")
					.contains("agent_run_log");
			});
	}

	@Test
	void shouldRejectUnsafeTableNameBeforeQueryingDatabase() {
		assertThatThrownBy(
				() -> getTableSchemaTool.execute(Map.of("tableName", "agent_run_log; DROP TABLE agent_run_log")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("表名格式不合法")
			.hasMessageContaining("只允许字母、数字和下划线");
	}

	@Test
	void shouldAnalyzeUnknownColumnAgainstRealTableSchema() throws Exception {

		String sqlError = "java.sql.SQLSyntaxErrorException: " + "Unknown column 'r.missing_field' "
				+ "in 'field list'";

		String result = analyzeSqlErrorWithSchemaTool.execute(Map.of("log", sqlError, "tableName", "agent_run_log"));

		JsonNode analysis = objectMapper.readTree(result);

		assertThat(analysis.path("errorEvidence").path("errorType").asText()).isEqualTo("UNKNOWN_COLUMN");

		assertThat(analysis.path("tableName").asText()).isEqualTo("agent_run_log");

		assertThat(analysis.path("referencedColumns").get(0).asText()).isEqualTo("missing_field");

		assertThat(analysis.path("missingColumns").get(0).asText()).isEqualTo("missing_field");

		assertThat(analysis.path("existingColumns").toString()).contains("\"id\"")
			.contains("\"trace_id\"")
			.contains("\"user_message\"");

		assertThat(analysis.path("diagnosis").asText()).contains("agent_run_log")
			.contains("missing_field")
			.contains("不存在的字段");

		assertThat(analysis.path("suggestedActions").isArray()).isTrue();

		assertThat(analysis.path("suggestedActions").size()).isGreaterThanOrEqualTo(4);

		assertThat(analysis.path("tableSchema").isArray()).isTrue();

		assertThat(analysis.path("tableSchema").size()).isGreaterThanOrEqualTo(14);
	}

	@Test
	void shouldReportMissingTableDuringSqlErrorAnalysisAsBusinessFailure() {
		String sqlError = "Unknown column 'missing_field' in 'field list'";

		assertThatThrownBy(() -> analyzeSqlErrorWithSchemaTool
			.execute(Map.of("log", sqlError, "tableName", "table_that_does_not_exist")))
			.isInstanceOf(ToolBusinessException.class)
			.satisfies(exception -> {
				ToolBusinessException businessException = (ToolBusinessException) exception;

				assertThat(businessException.getErrorCode()).isEqualTo("TABLE_NOT_FOUND");

				assertThat(businessException.getResult()).contains("未查询到表结构")
					.contains("无法结合表结构分析")
					.contains("table_that_does_not_exist");
			});
	}

	@Test
	void shouldRejectUnsafeTableNameDuringSqlErrorAnalysis() {
		String sqlError = "Unknown column 'missing_field' in 'field list'";

		assertThatThrownBy(() -> analyzeSqlErrorWithSchemaTool
			.execute(Map.of("log", sqlError, "tableName", "agent_run_log where 1=1")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("表名格式不合法")
			.hasMessageContaining("只允许字母、数字和下划线");
	}

	@Test
	void shouldConvertSchemaTableNotFoundToBusinessErrorThroughRegistry() {
		ToolExecutionResult result = toolRegistry.executeWithResult("getTableSchema",
				Map.of("tableName", "agent_run_lgo"));

		assertThat(result.getSuccess()).isFalse();

		assertThat(result.getStatus()).isEqualTo(ToolExecutionStatus.BUSINESS_ERROR);

		assertThat(result.getErrorCode()).isEqualTo("TABLE_NOT_FOUND");

		assertThat(result.getResult()).contains("未查询到表结构").contains("agent_run_log");
	}

	@Test
	void shouldConvertAnalysisTableNotFoundToBusinessErrorThroughRegistry() {
		String sqlError = "Unknown column 'missing_field' in 'field list'";

		ToolExecutionResult result = toolRegistry.executeWithResult("analyzeSqlErrorWithSchema",
				Map.of("log", sqlError, "tableName", "table_that_does_not_exist"));

		assertThat(result.getSuccess()).isFalse();

		assertThat(result.getStatus()).isEqualTo(ToolExecutionStatus.BUSINESS_ERROR);

		assertThat(result.getErrorCode()).isEqualTo("TABLE_NOT_FOUND");

		assertThat(result.getResult()).contains("未查询到表结构").contains("无法结合表结构分析").contains("table_that_does_not_exist");
	}

}