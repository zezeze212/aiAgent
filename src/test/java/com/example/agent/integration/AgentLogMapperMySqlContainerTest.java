package com.example.agent.integration;

import com.example.agent.dto.AgentRunStatsResponse;
import com.example.agent.dto.ToolStatsItem;
import com.example.agent.entity.AgentRunLog;
import com.example.agent.entity.AgentStepLog;
import com.example.agent.mapper.AgentRunLogMapper;
import com.example.agent.mapper.AgentStepLogMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Transactional
@SpringBootTest(classes = AgentLogMapperMySqlContainerTest.MapperTestApplication.class,
		properties = { "spring.main.web-application-type=none", "spring.flyway.enabled=true",
				"spring.flyway.locations=classpath:db/migration", "spring.flyway.clean-disabled=true",
				"mybatis.mapper-locations=classpath*:mapper/*.xml",
				"mybatis.configuration.map-underscore-to-camel-case=true" })
class AgentLogMapperMySqlContainerTest {

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
	private AgentRunLogMapper agentRunLogMapper;

	@Autowired
	private AgentStepLogMapper agentStepLogMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * 只启动数据库、Flyway、MyBatis 等基础设施。
	 *
	 * 不扫描正式项目中的 DeepSeekDecisionClient、AgentService 等业务 Bean， 因此本测试不需要 DeepSeek API
	 * Key，也不会调用外部模型。
	 */
	@SpringBootConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@MapperScan(basePackageClasses = { AgentRunLogMapper.class, AgentStepLogMapper.class })
	static class MapperTestApplication {

	}

	@Test
	void shouldInsertAndSelectRunLogWithGeneratedIdAndCreatedTime() {
		String traceId = newTraceId("run");

		AgentRunLog runLog = newRunLog(traceId, "为什么 SQL 查询失败？", "需要先检查字段是否存在。", "getTableSchema", 1, 320L);

		int affectedRows = agentRunLogMapper.insert(runLog);

		assertThat(affectedRows).isEqualTo(1);

		// useGeneratedKeys=true 应把 MySQL 自增主键写回实体。
		assertThat(runLog.getId()).isNotNull();
		assertThat(runLog.getId()).isPositive();

		AgentRunLog saved = agentRunLogMapper.selectByTraceId(traceId);

		assertThat(saved).isNotNull();
		assertThat(saved.getId()).isEqualTo(runLog.getId());
		assertThat(saved.getTraceId()).isEqualTo(traceId);
		assertThat(saved.getUserMessage()).isEqualTo("为什么 SQL 查询失败？");
		assertThat(saved.getAnswer()).isEqualTo("需要先检查字段是否存在。");
		assertThat(saved.getUsedTool()).isEqualTo(1);
		assertThat(saved.getToolName()).isEqualTo("getTableSchema");
		assertThat(saved.getToolResult()).isEqualTo("schema result");
		assertThat(saved.getDecisionCostMs()).isEqualTo(10L);
		assertThat(saved.getToolCostMs()).isEqualTo(20L);
		assertThat(saved.getSummaryCostMs()).isEqualTo(5L);
		assertThat(saved.getAgentCostMs()).isEqualTo(320L);
		assertThat(saved.getSuccess()).isEqualTo(1);
		assertThat(saved.getErrorMessage()).isNull();

		/*
		 * Mapper 的 INSERT 没有写 created_time， 因此查询结果不为空可以证明 MySQL 的 DEFAULT
		 * CURRENT_TIMESTAMP(3) 已经真正生效。
		 */
		assertThat(saved.getCreatedTime()).isNotNull();

		// answerSummary 不对应数据库字段，本次明细查询不应凭空赋值。
		assertThat(saved.getAnswerSummary()).isNull();
	}

	@Test
	void shouldInsertStepsAndReturnThemInStepOrder() {
		String traceId = newTraceId("step");

		// agent_step_log 有外键，必须先写入对应的主运行记录。
		insertRunLog(traceId, "getTableSchema", 1, 500L);

		// 故意先插入第 2 步，再插入第 1 步。
		AgentStepLog secondStep = newStepLog(traceId, "TOOL_CALL", "调用数据库表结构工具", 2, 1);

		AgentStepLog firstStep = newStepLog(traceId, "DECISION", "判断是否需要调用工具", 1, 1);

		assertThat(agentStepLogMapper.insert(secondStep)).isEqualTo(1);
		assertThat(agentStepLogMapper.insert(firstStep)).isEqualTo(1);

		assertThat(secondStep.getId()).isNotNull().isPositive();
		assertThat(firstStep.getId()).isNotNull().isPositive();

		List<AgentStepLog> steps = agentStepLogMapper.selectByTraceId(traceId);

		assertThat(steps).hasSize(2);

		// 验证 XML 中的 ORDER BY step_order ASC。
		assertThat(steps).extracting(AgentStepLog::getStepOrder).containsExactly(1, 2);

		assertThat(steps).extracting(AgentStepLog::getStepName).containsExactly("DECISION", "TOOL_CALL");

		assertThat(steps).allSatisfy(step -> {
			assertThat(step.getTraceId()).isEqualTo(traceId);
			assertThat(step.getCreatedTime()).isNotNull();
			assertThat(step.getSuccess()).isEqualTo(1);
			assertThat(step.getCostMs()).isEqualTo(15L);
		});

		AgentStepLog savedFirstStep = steps.get(0);

		assertThat(savedFirstStep.getDescription()).isEqualTo("判断是否需要调用工具");
		assertThat(savedFirstStep.getInputText()).isEqualTo("step input");
		assertThat(savedFirstStep.getOutputText()).isEqualTo("step output");
		assertThat(savedFirstStep.getErrorMessage()).isNull();
	}

	@Test
	void shouldCountFilterAndPageRunLogs() {
		String oldestTraceId = newTraceId("oldest");
		String secondTraceId = newTraceId("second");
		String thirdTraceId = newTraceId("third");
		String newestTraceId = newTraceId("newest");

		LocalDateTime oldestTime = LocalDateTime.of(2026, 8, 8, 10, 0, 0);
		LocalDateTime secondTime = LocalDateTime.of(2026, 8, 8, 11, 0, 0);
		LocalDateTime thirdTime = LocalDateTime.of(2026, 8, 8, 12, 0, 0);
		LocalDateTime newestTime = LocalDateTime.of(2026, 8, 8, 13, 0, 0);

		insertRunLog(oldestTraceId, "getTableSchema", 1, 100L);
		insertRunLog(secondTraceId, "getTableSchema", 0, 200L);
		insertRunLog(thirdTraceId, "analyzeSqlErrorWithSchema", 1, 300L);
		insertRunLog(newestTraceId, null, 1, 400L);

		/*
		 * 分页 SQL 按 created_time 倒序。 显式设置不同时间，避免四条记录创建过快导致时间相同， 从而让分页顺序具有确定性。
		 */
		updateCreatedTime(oldestTraceId, oldestTime);
		updateCreatedTime(secondTraceId, secondTime);
		updateCreatedTime(thirdTraceId, thirdTime);
		updateCreatedTime(newestTraceId, newestTime);

		assertThat(agentRunLogMapper.countByCondition(null, null, null, null)).isEqualTo(4L);

		assertThat(agentRunLogMapper.countByCondition("getTableSchema", null, null, null)).isEqualTo(2L);

		assertThat(agentRunLogMapper.countByCondition(null, 1, null, null)).isEqualTo(3L);

		// 验证起止时间使用 >= 和 <=，边界记录也会被统计。
		assertThat(agentRunLogMapper.countByCondition(null, null, secondTime, thirdTime)).isEqualTo(2L);

		List<AgentRunLog> firstPage = agentRunLogMapper.selectPageByCondition(0, 2, null, null, null, null);

		assertThat(firstPage).extracting(AgentRunLog::getTraceId).containsExactly(newestTraceId, thirdTraceId);

		List<AgentRunLog> secondPage = agentRunLogMapper.selectPageByCondition(2, 2, null, null, null, null);

		assertThat(secondPage).extracting(AgentRunLog::getTraceId).containsExactly(secondTraceId, oldestTraceId);

		List<AgentRunLog> successfulSchemaRuns = agentRunLogMapper.selectPageByCondition(0, 10, "getTableSchema", 1,
				null, null);

		assertThat(successfulSchemaRuns).extracting(AgentRunLog::getTraceId).containsExactly(oldestTraceId);
	}

	@Test
	void shouldCalculateOverallAndToolStats() {
		insertRunLog(newTraceId("schema-success"), "getTableSchema", 1, 100L);

		insertRunLog(newTraceId("schema-failed"), "getTableSchema", 0, 200L);

		insertRunLog(newTraceId("analyze-success"), "analyzeSqlErrorWithSchema", 1, 300L);

		insertRunLog(newTraceId("no-tool"), null, 1, 400L);

		AgentRunStatsResponse overallStats = agentRunLogMapper.selectOverallStats(null, null, null, null);

		assertThat(overallStats).isNotNull();
		assertThat(longProperty(overallStats, "total")).isEqualTo(4L);
		assertThat(longProperty(overallStats, "successCount")).isEqualTo(3L);
		assertThat(longProperty(overallStats, "failedCount")).isEqualTo(1L);

		AgentRunStatsResponse schemaStats = agentRunLogMapper.selectOverallStats("getTableSchema", null, null, null);

		assertThat(longProperty(schemaStats, "total")).isEqualTo(2L);
		assertThat(longProperty(schemaStats, "successCount")).isEqualTo(1L);
		assertThat(longProperty(schemaStats, "failedCount")).isEqualTo(1L);

		List<ToolStatsItem> toolStats = agentRunLogMapper.selectToolStats(null, null, null, null);

		assertThat(toolStats).hasSize(3);

		/*
		 * SQL 对没有调用工具的记录使用 COALESCE(tool_name, 'NO_TOOL')。 转成 Map 后断言，不依赖相同 runCount
		 * 分组之间的数据库排序。
		 */
		Map<String, Long> runCountByTool = toolStats.stream()
			.collect(
					Collectors.toMap(item -> stringProperty(item, "toolName"), item -> longProperty(item, "runCount")));

		assertThat(runCountByTool).containsEntry("getTableSchema", 2L)
			.containsEntry("analyzeSqlErrorWithSchema", 1L)
			.containsEntry("NO_TOOL", 1L);
	}

	private String newTraceId(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	private AgentRunLog insertRunLog(String traceId, String toolName, int success, long agentCostMs) {
		AgentRunLog runLog = newRunLog(traceId, "test user message", success == 1 ? "test answer" : null, toolName,
				success, agentCostMs);

		assertThat(agentRunLogMapper.insert(runLog)).isEqualTo(1);
		assertThat(runLog.getId()).isNotNull().isPositive();

		return runLog;
	}

	private AgentRunLog newRunLog(String traceId, String userMessage, String answer, String toolName, int success,
			long agentCostMs) {
		AgentRunLog runLog = new AgentRunLog();

		runLog.setTraceId(traceId);
		runLog.setUserMessage(userMessage);
		runLog.setAnswer(answer);
		runLog.setUsedTool(toolName == null ? 0 : 1);
		runLog.setToolName(toolName);
		runLog.setToolResult(toolName == null ? null : "schema result");
		runLog.setDecisionCostMs(10L);
		runLog.setToolCostMs(toolName == null ? null : 20L);
		runLog.setSummaryCostMs(5L);
		runLog.setAgentCostMs(agentCostMs);
		runLog.setSuccess(success);
		runLog.setErrorMessage(success == 1 ? null : "test failure");

		return runLog;
	}

	private AgentStepLog newStepLog(String traceId, String stepName, String description, int stepOrder, int success) {
		AgentStepLog stepLog = new AgentStepLog();

		stepLog.setTraceId(traceId);
		stepLog.setStepName(stepName);
		stepLog.setDescription(description);
		stepLog.setSuccess(success);
		stepLog.setCostMs(15L);
		stepLog.setInputText("step input");
		stepLog.setOutputText(success == 1 ? "step output" : null);
		stepLog.setErrorMessage(success == 1 ? null : "step failure");
		stepLog.setStepOrder(stepOrder);

		return stepLog;
	}

	private void updateCreatedTime(String traceId, LocalDateTime createdTime) {
		int affectedRows = jdbcTemplate.update("""
				UPDATE agent_run_log
				SET created_time = ?
				WHERE trace_id = ?
				""", Timestamp.valueOf(createdTime), traceId);

		assertThat(affectedRows).isEqualTo(1);
	}

	/**
	 * DTO 的完整代码未包含在上传内容中。
	 *
	 * 这里通过 Spring BeanWrapper 读取属性， 不需要猜测 DTO 字段具体使用 Long、Integer 还是 BigDecimal。
	 */
	private long longProperty(Object bean, String propertyName) {
		BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(bean);

		Object value = wrapper.getPropertyValue(propertyName);

		assertThat(value).as("属性 %s 应为数字", propertyName).isInstanceOf(Number.class);

		return ((Number) value).longValue();
	}

	private String stringProperty(Object bean, String propertyName) {
		BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(bean);

		Object value = wrapper.getPropertyValue(propertyName);

		assertThat(value).as("属性 %s 应为字符串", propertyName).isInstanceOf(String.class);

		return (String) value;
	}

}