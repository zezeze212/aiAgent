package com.example.agent.integration;

import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.AgentTraceStep;
import com.example.agent.entity.AgentRunLog;
import com.example.agent.entity.AgentStepLog;
import com.example.agent.mapper.AgentRunLogMapper;
import com.example.agent.mapper.AgentStepLogMapper;
import com.example.agent.service.AgentLogService;
import com.example.agent.support.AgentJsonHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(classes = AgentLogServiceMySqlContainerTest.LogServiceTestApplication.class,
		properties = { "spring.main.web-application-type=none", "spring.flyway.enabled=true",
				"spring.flyway.locations=classpath:db/migration", "spring.flyway.clean-disabled=true",
				"mybatis.mapper-locations=classpath*:mapper/*.xml",
				"mybatis.configuration.map-underscore-to-camel-case=true" })
class AgentLogServiceMySqlContainerTest {

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
	private AgentLogService agentLogService;

	@Autowired
	private AgentRunLogMapper agentRunLogMapper;

	@Autowired
	private AgentStepLogMapper agentStepLogMapper;

	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * 只加载本测试需要的数据库、MyBatis、日志服务和 JSON 组件。
	 *
	 * 不加载 AgentOrchestrator、DeepSeekDecisionClient 等业务 Bean， 因此不需要 API Key，也不会调用外部模型。
	 */
	@SpringBootConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EnableTransactionManagement
	@MapperScan(basePackageClasses = { AgentRunLogMapper.class, AgentStepLogMapper.class })
	@Import({ AgentLogService.class, AgentJsonHelper.class })
	static class LogServiceTestApplication {

	}

	@Test
	void shouldPersistCompleteRunAndStepsToRealMySql() throws Exception {

		String traceId = newTraceId("service-success");
		String userMessage = "users 表中有哪些字段？";

		Map<String, Object> toolResult = Map.of("tableName", "users", "columns", List.of("id", "email"));

		AgentTraceStep decisionStep = new AgentTraceStep("AI_DECISION", "判断是否需要调用工具", true, 10L,
				"{\"message\":\"检查 users 表结构\"}", "{\"needTool\":true}", null);

		AgentTraceStep toolStep = new AgentTraceStep("TOOL_EXECUTION", "执行表结构查询工具", true, 25L,
				"{\"tableName\":\"users\"}", "{\"columns\":[\"id\",\"email\"]}", null);

		AgentAskResponse response = new AgentAskResponse();
		response.setTraceId(traceId);
		response.setAnswer("users 表包含 id 和 email 字段。");
		response.setUsedTool(true);
		response.setToolName("getTableSchema");
		response.setToolResult(toolResult);
		response.setDecisionCostMs(10L);
		response.setToolCostMs(25L);
		response.setSummaryCostMs(15L);
		response.setAgentCostMs(50L);
		response.setSteps(List.of(decisionStep, toolStep));

		agentLogService.saveRunLog(userMessage, response);

		AgentRunLog savedRun = agentRunLogMapper.selectByTraceId(traceId);

		assertThat(savedRun).isNotNull();
		assertThat(savedRun.getId()).isNotNull().isPositive();
		assertThat(savedRun.getTraceId()).isEqualTo(traceId);
		assertThat(savedRun.getUserMessage()).isEqualTo(userMessage);
		assertThat(savedRun.getAnswer()).isEqualTo("users 表包含 id 和 email 字段。");
		assertThat(savedRun.getUsedTool()).isEqualTo(1);
		assertThat(savedRun.getToolName()).isEqualTo("getTableSchema");
		assertThat(savedRun.getDecisionCostMs()).isEqualTo(10L);
		assertThat(savedRun.getToolCostMs()).isEqualTo(25L);
		assertThat(savedRun.getSummaryCostMs()).isEqualTo(15L);
		assertThat(savedRun.getAgentCostMs()).isEqualTo(50L);
		assertThat(savedRun.getSuccess()).isEqualTo(1);
		assertThat(savedRun.getErrorMessage()).isNull();
		assertThat(savedRun.getCreatedTime()).isNotNull();

		/*
		 * 不直接比较 JSON 字符串，避免属性顺序影响测试。 解析为 JSON 树后再比较内容。
		 */
		assertThat(objectMapper.readTree(savedRun.getToolResult())).isEqualTo(objectMapper.valueToTree(toolResult));

		List<AgentStepLog> savedSteps = agentStepLogMapper.selectByTraceId(traceId);

		assertThat(savedSteps).hasSize(2);

		assertThat(savedSteps).extracting(AgentStepLog::getStepOrder).containsExactly(1, 2);

		assertThat(savedSteps).extracting(AgentStepLog::getStepName).containsExactly("AI_DECISION", "TOOL_EXECUTION");

		AgentStepLog savedDecisionStep = savedSteps.get(0);

		assertThat(savedDecisionStep.getId()).isNotNull().isPositive();
		assertThat(savedDecisionStep.getTraceId()).isEqualTo(traceId);
		assertThat(savedDecisionStep.getDescription()).isEqualTo("判断是否需要调用工具");
		assertThat(savedDecisionStep.getSuccess()).isEqualTo(1);
		assertThat(savedDecisionStep.getCostMs()).isEqualTo(10L);
		assertThat(savedDecisionStep.getInputText()).isEqualTo("{\"message\":\"检查 users 表结构\"}");
		assertThat(savedDecisionStep.getOutputText()).isEqualTo("{\"needTool\":true}");
		assertThat(savedDecisionStep.getErrorMessage()).isNull();
		assertThat(savedDecisionStep.getCreatedTime()).isNotNull();

		AgentStepLog savedToolStep = savedSteps.get(1);

		assertThat(savedToolStep.getTraceId()).isEqualTo(traceId);
		assertThat(savedToolStep.getDescription()).isEqualTo("执行表结构查询工具");
		assertThat(savedToolStep.getSuccess()).isEqualTo(1);
		assertThat(savedToolStep.getCostMs()).isEqualTo(25L);
		assertThat(savedToolStep.getInputText()).isEqualTo("{\"tableName\":\"users\"}");
		assertThat(savedToolStep.getOutputText()).isEqualTo("{\"columns\":[\"id\",\"email\"]}");
		assertThat(savedToolStep.getCreatedTime()).isNotNull();
	}

	@Test
	void shouldRollbackRunAndStepsWhenOneStepInsertFails() {
		String traceId = newTraceId("service-rollback");
		String userMessage = "测试事务回滚";

		/*
		 * agent_step_log.step_name 是 NOT NULL。 主记录会先插入，然后该步骤插入失败。
		 *
		 * 如果 saveRunLog 上的 @Transactional 正常生效， 前面已经插入的主记录也会回滚。
		 */
		AgentTraceStep invalidStep = new AgentTraceStep(null, "故意构造非法步骤", true, 10L, "{}", "{}", null);

		AgentAskResponse response = new AgentAskResponse();
		response.setTraceId(traceId);
		response.setAnswer("该回答不应该留下半成品日志");
		response.setUsedTool(false);
		response.setAgentCostMs(10L);
		response.setSteps(List.of(invalidStep));

		assertThatThrownBy(() -> agentLogService.saveRunLog(userMessage, response)).isInstanceOf(RuntimeException.class)
			.hasRootCauseInstanceOf(SQLException.class);

		/*
		 * 如果没有事务，这里会查到已插入的 agent_run_log。 现在应当主表、步骤表均没有当前 traceId 的数据。
		 */
		assertThat(agentRunLogMapper.selectByTraceId(traceId)).isNull();

		assertThat(agentStepLogMapper.selectByTraceId(traceId)).isEmpty();
	}

	private String newTraceId(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

}