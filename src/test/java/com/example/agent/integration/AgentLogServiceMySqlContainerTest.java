package com.example.agent.integration;

import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.AgentTraceStep;
import com.example.agent.entity.AgentRunLog;
import com.example.agent.entity.AgentStepLog;
import com.example.agent.entity.AgentTask;
import com.example.agent.mapper.AgentRunLogMapper;
import com.example.agent.mapper.AgentStepLogMapper;
import com.example.agent.mapper.AgentTaskMapper;
import com.example.agent.service.AgentLogService;
import com.example.agent.support.AgentJsonHelper;
import com.example.agent.support.TraceContentSanitizer;
import com.example.agent.task.AgentTaskStatus;
import com.example.agent.task.TaskRequestFingerprint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

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
	private AgentTaskMapper agentTaskMapper;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate taskDeadlineJdbc;

	/**
	 * 只加载本测试需要的数据库、MyBatis、日志服务和 JSON 组件。
	 *
	 * 不加载 AgentOrchestrator、DeepSeekDecisionClient 等业务 Bean， 因此不需要 API Key，也不会调用外部模型。
	 */
	@SpringBootConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EnableTransactionManagement
	@MapperScan(basePackageClasses = { AgentRunLogMapper.class, AgentStepLogMapper.class })
	@Import({ AgentLogService.class, AgentJsonHelper.class, TraceContentSanitizer.class })
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

	@Test
	void shouldAllowOnlyOneConcurrentInsertForSameIdempotencyKey() throws Exception {
		String key = "concurrent-" + UUID.randomUUID();
		String fingerprint = TaskRequestFingerprint.calculate("解释 Java 接口", null);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		Callable<Boolean> attempt = () -> {
			AgentTask task = new AgentTask();
			task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
			task.setIdempotencyKey(key);
			task.setRequestFingerprint(fingerprint);
			task.setModelId("deepseek-main");
			task.setStatus(AgentTaskStatus.QUEUED);
			task.setTimeoutSeconds(120);

			ready.countDown();

			if (!start.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("等待并发测试启动超时");
			}

			try {
				return agentTaskMapper.insert(task) == 1;
			}
			catch (DuplicateKeyException e) {
				return false;
			}
		};

		try {
			Future<Boolean> first = pool.submit(attempt);
			Future<Boolean> second = pool.submit(attempt);

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			boolean firstInserted = first.get(10, TimeUnit.SECONDS);
			boolean secondInserted = second.get(10, TimeUnit.SECONDS);

			// 必须恰好一个成功、另一个因唯一约束被拒绝。
			assertThat(firstInserted).isNotEqualTo(secondInserted);

			AgentTask saved = agentTaskMapper.selectByIdempotencyKey(key);
			assertThat(saved).isNotNull();
			assertThat(saved.getRequestFingerprint()).isEqualTo(fingerprint);
			assertThat(saved.getStatus()).isEqualTo(AgentTaskStatus.QUEUED);
		}
		finally {
			start.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	void shouldExpireQueuedTaskWithoutStartingIt() {
		AgentTask task = insertDeadlineTestTask();
		String taskId = task.getTaskId();

		moveDeadlineIntoPast(taskId);

		assertThat(agentTaskMapper.markRunning(taskId)).isZero();
		assertThat(agentTaskMapper.expireDueTask(taskId)).isEqualTo(1);

		AgentTask expired = agentTaskMapper.selectById(taskId);

		assertThat(expired.getStatus()).isEqualTo(AgentTaskStatus.TIMED_OUT);
		assertThat(expired.getErrorCode()).isEqualTo("TASK_DEADLINE_EXCEEDED");
		assertThat(expired.getStartedTime()).isNull();
		assertThat(expired.getTraceId()).isNull();
		assertThat(expired.getFinishedTime()).isNotNull();

		// 从未开始执行的任务，不接受迟到 Trace。
		assertThat(agentTaskMapper.attachLateTrace(taskId, "late-trace")).isZero();

		assertThat(agentTaskMapper.expireDueTask(taskId)).isZero();
	}

	@Test
	void shouldRejectLateCompletionBeforeSweepRuns() {
		AgentTask task = insertDeadlineTestTask();
		String taskId = task.getTaskId();

		assertThat(agentTaskMapper.markRunning(taskId)).isEqualTo(1);
		moveDeadlineIntoPast(taskId);

		// 扫描尚未执行，数据库中仍是 RUNNING。
		// finish 必须自行检查截止时间。
		assertThat(agentTaskMapper.finish(taskId, AgentTaskStatus.SUCCEEDED, "late-success", null, null)).isZero();

		assertThat(agentTaskMapper.expireDueTask(taskId)).isEqualTo(1);

		LocalDateTime finishedTime = agentTaskMapper.selectById(taskId).getFinishedTime();

		assertThat(agentTaskMapper.attachLateTrace(taskId, "late-success")).isEqualTo(1);

		// 不能将超时终态改成失败，也不能替换已有 Trace。
		assertThat(agentTaskMapper.finish(taskId, AgentTaskStatus.FAILED, "another-trace", "LATE_ERROR", "迟到异常"))
			.isZero();

		assertThat(agentTaskMapper.attachLateTrace(taskId, "another-trace")).isZero();

		AgentTask saved = agentTaskMapper.selectById(taskId);

		assertThat(saved.getStatus()).isEqualTo(AgentTaskStatus.TIMED_OUT);
		assertThat(saved.getTraceId()).isEqualTo("late-success");
		assertThat(saved.getErrorCode()).isEqualTo("TASK_DEADLINE_EXCEEDED");
		assertThat(saved.getFinishedTime()).isEqualTo(finishedTime);
	}

	@Test
	void shouldKeepTimeoutWhenSweepWinsBeforeCompletion() {
		AgentTask task = insertDeadlineTestTask();
		String taskId = task.getTaskId();

		assertThat(agentTaskMapper.markRunning(taskId)).isEqualTo(1);
		moveDeadlineIntoPast(taskId);

		assertThat(agentTaskMapper.expireDueBatch()).isGreaterThanOrEqualTo(1);

		AgentTask expired = agentTaskMapper.selectById(taskId);
		LocalDateTime finishedTime = expired.getFinishedTime();

		assertThat(expired.getStatus()).isEqualTo(AgentTaskStatus.TIMED_OUT);

		assertThat(agentTaskMapper.finish(taskId, AgentTaskStatus.SUCCEEDED, "after-sweep", null, null)).isZero();

		assertThat(agentTaskMapper.attachLateTrace(taskId, "after-sweep")).isEqualTo(1);

		AgentTask saved = agentTaskMapper.selectById(taskId);

		assertThat(saved.getStatus()).isEqualTo(AgentTaskStatus.TIMED_OUT);
		assertThat(saved.getTraceId()).isEqualTo("after-sweep");
		assertThat(saved.getFinishedTime()).isEqualTo(finishedTime);
	}

	@Test
	void shouldKeepCompletedTaskAndSupportLegacyNullDeadline() {
		AgentTask task = insertDeadlineTestTask();
		String taskId = task.getTaskId();

		assertThat(agentTaskMapper.markRunning(taskId)).isEqualTo(1);
		assertThat(agentTaskMapper.finish(taskId, AgentTaskStatus.SUCCEEDED, "normal-trace", null, null)).isEqualTo(1);

		LocalDateTime finishedTime = agentTaskMapper.selectById(taskId).getFinishedTime();

		moveDeadlineIntoPast(taskId);
		agentTaskMapper.expireDueBatch();

		AgentTask completed = agentTaskMapper.selectById(taskId);

		assertThat(completed.getStatus()).isEqualTo(AgentTaskStatus.SUCCEEDED);
		assertThat(completed.getTraceId()).isEqualTo("normal-trace");
		assertThat(completed.getFinishedTime()).isEqualTo(finishedTime);

		// 模拟迁移前的旧记录：没有 deadline_at。
		AgentTask legacy = insertDeadlineTestTask();

		taskDeadlineJdbc.update("""
				UPDATE agent_task
				SET deadline_at = NULL
				WHERE task_id = ?
				""", legacy.getTaskId());

		assertThat(agentTaskMapper.expireDueTask(legacy.getTaskId())).isZero();
		assertThat(agentTaskMapper.markRunning(legacy.getTaskId())).isEqualTo(1);
		assertThat(agentTaskMapper.finish(legacy.getTaskId(), AgentTaskStatus.SUCCEEDED, "legacy-trace", null, null))
			.isEqualTo(1);
	}

	@Test
	void shouldRecoverUnfinishedTasksAccordingToDeadline() {
		AgentTask queued = insertDeadlineTestTask();

		AgentTask running = insertDeadlineTestTask();
		assertThat(agentTaskMapper.markRunning(running.getTaskId())).isEqualTo(1);

		AgentTask expiredQueued = insertDeadlineTestTask();
		moveDeadlineIntoPast(expiredQueued.getTaskId());

		AgentTask expiredRunning = insertDeadlineTestTask();
		assertThat(agentTaskMapper.markRunning(expiredRunning.getTaskId())).isEqualTo(1);
		moveDeadlineIntoPast(expiredRunning.getTaskId());

		AgentTask legacy = insertDeadlineTestTask();
		taskDeadlineJdbc.update("""
				UPDATE agent_task
				SET deadline_at = NULL
				WHERE task_id = ?
				""", legacy.getTaskId());

		/*
		 * 给其中一个任务设置幂等信息，验证恢复不会破坏原来的关联。 此处指纹只是数据库测试数据，不用于真实请求重放。
		 */
		String key = "startup-" + UUID.randomUUID();
		String fingerprint = "a".repeat(64);

		taskDeadlineJdbc.update("""
				UPDATE agent_task
				SET idempotency_key = ?,
				    request_fingerprint = ?
				WHERE task_id = ?
				""", key, fingerprint, queued.getTaskId());

		int updated = agentTaskMapper.recoverUnfinishedOnStartup();

		// 同一测试容器可能还有其他用例留下的活动记录，不依赖全库精确数量。
		assertThat(updated).isGreaterThanOrEqualTo(5);

		for (AgentTask task : List.of(queued, running, legacy)) {
			AgentTask saved = agentTaskMapper.selectById(task.getTaskId());

			assertThat(saved.getStatus()).isEqualTo(AgentTaskStatus.FAILED);
			assertThat(saved.getErrorCode()).isEqualTo("TASK_PROCESS_RESTARTED");
			assertThat(saved.getFinishedTime()).isNotNull();
		}

		for (AgentTask task : List.of(expiredQueued, expiredRunning)) {
			AgentTask saved = agentTaskMapper.selectById(task.getTaskId());

			assertThat(saved.getStatus()).isEqualTo(AgentTaskStatus.TIMED_OUT);
			assertThat(saved.getErrorCode()).isEqualTo("TASK_DEADLINE_EXCEEDED");
			assertThat(saved.getFinishedTime()).isNotNull();
		}

		AgentTask recoveredQueued = agentTaskMapper.selectById(queued.getTaskId());

		assertThat(recoveredQueued.getStartedTime()).isNull();
		assertThat(recoveredQueued.getTraceId()).isNull();
		assertThat(recoveredQueued.getDeadlineAt()).isEqualTo(queued.getDeadlineAt());

		assertThat(agentTaskMapper.selectById(running.getTaskId()).getStartedTime()).isNotNull();

		AgentTask reused = agentTaskMapper.selectByIdempotencyKey(key);

		assertThat(reused.getTaskId()).isEqualTo(queued.getTaskId());
		assertThat(reused.getRequestFingerprint()).isEqualTo(fingerprint);
		assertThat(reused.getStatus()).isEqualTo(AgentTaskStatus.FAILED);

		// 已完成恢复，再执行一次不应重复更新终态。
		assertThat(agentTaskMapper.recoverUnfinishedOnStartup()).isZero();

		assertThat(agentTaskMapper.selectById(queued.getTaskId()).getFinishedTime())
			.isEqualTo(recoveredQueued.getFinishedTime());
	}

	@Test
	void shouldPreserveAllTerminalTasksDuringStartupRecovery() {
		AgentTask succeeded = insertDeadlineTestTask();

		assertThat(agentTaskMapper.markRunning(succeeded.getTaskId())).isEqualTo(1);
		assertThat(agentTaskMapper.finish(succeeded.getTaskId(), AgentTaskStatus.SUCCEEDED, "recovery-success-trace",
				null, null))
			.isEqualTo(1);

		AgentTask failed = insertDeadlineTestTask();

		assertThat(agentTaskMapper.markRunning(failed.getTaskId())).isEqualTo(1);
		assertThat(agentTaskMapper.finish(failed.getTaskId(), AgentTaskStatus.FAILED, "recovery-failed-trace",
				"AGENT_EXECUTION_FAILED", "已有执行失败"))
			.isEqualTo(1);

		AgentTask rejected = insertDeadlineTestTask();

		assertThat(agentTaskMapper.markRejected(rejected.getTaskId())).isEqualTo(1);

		AgentTask timedOut = insertDeadlineTestTask();

		assertThat(agentTaskMapper.markRunning(timedOut.getTaskId())).isEqualTo(1);
		moveDeadlineIntoPast(timedOut.getTaskId());

		assertThat(agentTaskMapper.expireDueTask(timedOut.getTaskId())).isEqualTo(1);
		assertThat(agentTaskMapper.attachLateTrace(timedOut.getTaskId(), "recovery-late-trace")).isEqualTo(1);

		List<AgentTask> beforeRecovery = List
			.of(succeeded.getTaskId(), failed.getTaskId(), rejected.getTaskId(), timedOut.getTaskId())
			.stream()
			.map(agentTaskMapper::selectById)
			.toList();

		agentTaskMapper.recoverUnfinishedOnStartup();

		for (AgentTask before : beforeRecovery) {
			AgentTask after = agentTaskMapper.selectById(before.getTaskId());

			// 包括状态、错误信息、Trace 和所有时间字段，均应保持不变。
			assertThat(after).usingRecursiveComparison().isEqualTo(before);
		}
	}

	@Test
	void shouldPersistRequestAndAllowOnlyOneRetryChild() {
		AgentTask source = new AgentTask();
		source.setTaskId(UUID.randomUUID().toString().replace("-", ""));
		source.setModelId("deepseek-main");
		source.setStatus(AgentTaskStatus.QUEUED);
		source.setTimeoutSeconds(120);
		source.setRequestMessage("第一行问题\n第二行补充信息");

		assertThat(agentTaskMapper.insert(source)).isEqualTo(1);
		assertThat(agentTaskMapper.markRejected(source.getTaskId()))
				.isEqualTo(1);

		AgentTask sourceBefore =
				agentTaskMapper.selectById(source.getTaskId());

		assertThat(sourceBefore.getRequestMessage())
				.isEqualTo("第一行问题\n第二行补充信息");

		AgentTask first = new AgentTask();
		first.setTaskId(UUID.randomUUID().toString().replace("-", ""));
		first.setRetryOfTaskId(source.getTaskId());
		first.setRequestMessage(sourceBefore.getRequestMessage());
		first.setRequestedModelId("deepseek-main");
		first.setModelId("deepseek-main");
		first.setStatus(AgentTaskStatus.QUEUED);
		first.setTimeoutSeconds(120);

		assertThat(agentTaskMapper.insert(first)).isEqualTo(1);

		AgentTask second = new AgentTask();
		second.setTaskId(UUID.randomUUID().toString().replace("-", ""));
		second.setRetryOfTaskId(source.getTaskId());
		second.setRequestMessage(sourceBefore.getRequestMessage());
		second.setRequestedModelId("deepseek-main");
		second.setModelId("deepseek-main");
		second.setStatus(AgentTaskStatus.QUEUED);
		second.setTimeoutSeconds(120);

		// 不依赖 Service 检查，数据库本身必须拒绝第二个子任务。
		assertThatThrownBy(() -> agentTaskMapper.insert(second))
				.isInstanceOf(DuplicateKeyException.class);

		AgentTask saved =
				agentTaskMapper.selectByRetryOfTaskId(source.getTaskId());

		assertThat(saved.getTaskId()).isEqualTo(first.getTaskId());
		assertThat(saved.getRequestMessage())
				.isEqualTo(sourceBefore.getRequestMessage());
		assertThat(saved.getDeadlineAt())
				.isEqualTo(saved.getCreatedTime().plusSeconds(120));

		// 创建子任务不能覆盖原任务状态、错误或时间。
		assertThat(agentTaskMapper.selectById(source.getTaskId()))
				.usingRecursiveComparison()
				.isEqualTo(sourceBefore);
	}

	private AgentTask insertDeadlineTestTask() {
		AgentTask task = new AgentTask();
		task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
		task.setModelId("deepseek-main");
		task.setStatus(AgentTaskStatus.QUEUED);
		task.setTimeoutSeconds(120);

		assertThat(agentTaskMapper.insert(task)).isEqualTo(1);

		AgentTask saved = agentTaskMapper.selectById(task.getTaskId());

		assertThat(saved.getDeadlineAt()).isEqualTo(saved.getCreatedTime().plusSeconds(120));

		return saved;
	}

	private void moveDeadlineIntoPast(String taskId) {
		/*
		 * 只调整测试容器中的时间字段，让用例确定性地到期。 不依赖 Thread.sleep，也不等待真实的 120 秒。
		 */
		int updated = taskDeadlineJdbc.update("""
				UPDATE agent_task
				SET deadline_at =
				    TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(3))
				WHERE task_id = ?
				""", taskId);

		assertThat(updated).isEqualTo(1);
	}

	private String newTraceId(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

}