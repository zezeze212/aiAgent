package com.example.agent.integration;

import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.AgentTraceStep;
import com.example.agent.entity.AgentStepLog;
import com.example.agent.mapper.AgentRunLogMapper;
import com.example.agent.mapper.AgentStepLogMapper;
import com.example.agent.service.AgentLogService;
import com.example.agent.support.AgentJsonHelper;
import com.example.agent.support.TraceContentSanitizer;
import com.example.agent.tool.ToolExecutionStatus;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = ToolTracePersistenceMySqlContainerTest.TestApplication.class,
		properties = { "spring.main.web-application-type=none", "spring.flyway.enabled=true",
				"spring.flyway.locations=classpath:db/migration", "spring.flyway.clean-disabled=true",
				"mybatis.mapper-locations=classpath*:mapper/*.xml",
				"mybatis.configuration.map-underscore-to-camel-case=true" })
class ToolTracePersistenceMySqlContainerTest {

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

	@SpringBootConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EnableTransactionManagement
	@MapperScan(basePackageClasses = { AgentRunLogMapper.class, AgentStepLogMapper.class })
	@Import({ AgentLogService.class, AgentJsonHelper.class, TraceContentSanitizer.class })
	static class TestApplication {

	}

	@Autowired
	private AgentLogService agentLogService;

	@Autowired
	private AgentStepLogMapper agentStepLogMapper;

	@Test
	void shouldPersistToolStatusAndErrorCode() {
		String traceId = "tool-status-" + UUID.randomUUID();

		AgentTraceStep toolStep = new AgentTraceStep("TOOL_EXECUTION", "执行工具：getTableSchema", false, 8L,
				"{\"tableName\":\"missing_table\"}", "未查询到表结构", ToolExecutionStatus.BUSINESS_ERROR, "TABLE_NOT_FOUND",
				"数据库中不存在目标表");

		AgentAskResponse response = new AgentAskResponse();

		response.setTraceId(traceId);

		response.setAnswer("未查询到表结构");

		response.setUsedTool(true);

		response.setToolName("getTableSchema");

		response.setToolResult("未查询到表结构");

		response.setToolCostMs(8L);

		response.setAgentCostMs(10L);

		response.setSteps(List.of(toolStep));

		agentLogService.saveRunLog("查询不存在的表", response);

		List<AgentStepLog> savedSteps = agentStepLogMapper.selectByTraceId(traceId);

		assertThat(savedSteps).hasSize(1);

		AgentStepLog savedStep = savedSteps.get(0);

		assertThat(savedStep.getStepName()).isEqualTo("TOOL_EXECUTION");

		assertThat(savedStep.getToolStatus()).isEqualTo("BUSINESS_ERROR");

		assertThat(savedStep.getErrorCode()).isEqualTo("TABLE_NOT_FOUND");

		assertThat(savedStep.getErrorMessage()).isEqualTo("数据库中不存在目标表");

		assertThat(savedStep.getCostMs()).isEqualTo(8L);
	}

}