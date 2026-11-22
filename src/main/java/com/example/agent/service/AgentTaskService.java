package com.example.agent.service;

import com.example.agent.dto.AgentAskRequest;
import com.example.agent.dto.AgentAskResponse;
import com.example.agent.entity.AgentRunLog;
import com.example.agent.entity.AgentTask;
import com.example.agent.exception.BusinessException;
import com.example.agent.mapper.AgentRunLogMapper;
import com.example.agent.mapper.AgentTaskMapper;
import com.example.agent.model.AgentModelRegistry;
import com.example.agent.task.AgentTaskStatus;
import com.example.agent.task.TaskRequestFingerprint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@DependsOn("agentTaskStartupRecovery")
public class AgentTaskService {

	private final AgentTaskMapper taskMapper;

	private final AgentRunLogMapper runLogMapper;

	private final AgentModelRegistry modelRegistry;

	private final SpringAiToolChatService chatService;

	private final TaskExecutor taskExecutor;

	private final int timeoutSeconds;

	/**
	 * 本项目幂等键规则：8～128 位 ASCII 字符，区分大小写。 推荐客户端直接使用 UUID，同一次提交的重发复用原键。
	 */
	private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}");

	public AgentTaskService(AgentTaskMapper taskMapper, AgentRunLogMapper runLogMapper,
			AgentModelRegistry modelRegistry, SpringAiToolChatService chatService,
			@Qualifier("agentTaskExecutor") TaskExecutor taskExecutor,
			@Value("${agent.task.timeout-seconds:120}") int timeoutSeconds) {

		if (timeoutSeconds <= 0) {
			throw new IllegalArgumentException("agent.task.timeout-seconds 必须大于 0");
		}

		this.taskMapper = taskMapper;
		this.runLogMapper = runLogMapper;
		this.modelRegistry = modelRegistry;
		this.chatService = chatService;
		this.taskExecutor = taskExecutor;
		this.timeoutSeconds = timeoutSeconds;
	}

	/**
	 * 保留原有内部调用方式：未提供幂等键时，每次创建新任务。
	 */
	@Transactional(propagation = Propagation.NEVER)
	public AgentTask submit(AgentAskRequest request) {
		return submit(request, null);
	}

	/**
	 * 任务创建必须先提交，再投递线程池。 继续禁止在外围事务中调用，避免并发请求读取未提交的数据。
	 */
	@Transactional(propagation = Propagation.NEVER)
	public AgentTask submit(AgentAskRequest request, String idempotencyKey) {

		validateRequest(request);

		String key = validateIdempotencyKey(idempotencyKey);
		String message = request.getMessage();
		String requestedModelId = normalizeModelId(request.getModelId());

		String fingerprint = key == null ? null : TaskRequestFingerprint.calculate(message, requestedModelId);

		if (key != null) {
			AgentTask existing = taskMapper.selectByIdempotencyKey(key);

			if (existing != null) {
				// 包括 TIMED_OUT 在内，相同幂等键都复用原任务。
				// 不重新分配预算，不再次投递。
				return reuseExistingTask(existing, fingerprint);
			}
		}

		AgentModelRegistry.Entry selected = modelRegistry.resolve(requestedModelId);

		String taskId = UUID.randomUUID().toString().replace("-", "");

		AgentTask task = new AgentTask();
		task.setTaskId(taskId);
		task.setIdempotencyKey(key);
		task.setRequestFingerprint(fingerprint);
		task.setRequestedModelId(requestedModelId);
		task.setModelId(selected.modelId());
		task.setStatus(AgentTaskStatus.QUEUED);

		// INSERT 使用数据库时间，将预算转换为持久化的 deadlineAt。
		task.setTimeoutSeconds(timeoutSeconds);

		try {
			if (taskMapper.insert(task) != 1) {
				throw new IllegalStateException("任务创建失败");
			}
		}
		catch (DuplicateKeyException e) {
			if (key != null) {
				AgentTask existing = taskMapper.selectByIdempotencyKey(key);

				if (existing != null) {
					return reuseExistingTask(existing, fingerprint);
				}
			}

			throw e;
		}

		try {
			taskExecutor.execute(() -> executeTask(taskId, message, requestedModelId));
		}
		catch (TaskRejectedException e) {
			int updated = taskMapper.markRejected(taskId);

			if (updated == 1) {
				log.warn("Agent 异步任务被拒绝，taskId={}", taskId);
			}
			else {
				/*
				 * 拒绝处理也可能与到期扫描发生竞争。 如果任务已经超时，应保留 TIMED_OUT， 不能把正常的状态竞争误报成保存失败。
				 */
				taskMapper.expireDueTask(taskId);

				AgentTask current = getTask(taskId);
				if (current.getStatus() != AgentTaskStatus.TIMED_OUT) {
					throw new IllegalStateException("任务拒绝状态保存失败", e);
				}
			}
		}

		return getTask(taskId);
	}

	public AgentTask getTask(String taskId) {
		AgentTask task = taskMapper.selectById(taskId);

		if (task == null) {
			throw new BusinessException(404, "TASK_NOT_FOUND：任务不存在");
		}

		return task;
	}

	private void executeTask(String taskId, String message, String requestedModelId) {

		String traceId = null;

		try {
			if (taskMapper.markRunning(taskId) != 1) {
				/*
				 * 可能是任务已经结束，也可能是排队期间已经到期。 对后者补一次状态收敛，然后跳过 Agent 调用。
				 */
				taskMapper.expireDueTask(taskId);

				log.info("任务未获得执行资格，跳过执行，taskId={}", taskId);
				return;
			}

			log.info("Agent 异步任务开始，taskId={}, thread={}", taskId, Thread.currentThread().getName());

			AgentAskResponse response = chatService.chat(message, requestedModelId);

			if (response == null || response.getTraceId() == null || response.getTraceId().isBlank()) {

				finish(taskId, AgentTaskStatus.FAILED, null, "AGENT_RESULT_MISSING", "Agent 未返回可关联的执行记录");
				return;
			}

			traceId = response.getTraceId();

			AgentRunLog run = runLogMapper.selectByTraceId(traceId);

			if (run == null) {
				finish(taskId, AgentTaskStatus.FAILED, traceId, "RUN_LOG_MISSING", "Agent 已返回，但未找到对应历史记录");
				return;
			}

			/*
			 * 沿用现有判定口径： chat 返回字符串不代表执行成功， 仍需核对已经持久化的 Run 结果。
			 */
			if (!Integer.valueOf(1).equals(run.getSuccess())) {
				finish(taskId, AgentTaskStatus.FAILED, traceId, "AGENT_EXECUTION_FAILED", "Agent 执行未通过，请查看关联 Trace");
				return;
			}

			finish(taskId, AgentTaskStatus.SUCCEEDED, traceId, null, null);

		}
		catch (Exception e) {
			log.error("Agent 异步任务执行异常，taskId={}, traceId={}", taskId, traceId, e);

			try {
				finish(taskId, AgentTaskStatus.FAILED, traceId, "TASK_EXECUTION_ERROR", "任务执行异常，请查看服务端日志");
			}
			catch (Exception persistenceException) {
				log.error("任务失败状态保存异常，taskId={}", taskId, persistenceException);
			}
		}
	}

	private void finish(String taskId, AgentTaskStatus status, String traceId, String errorCode, String errorMessage) {

		int updated = taskMapper.finish(taskId, status, traceId, errorCode, errorMessage);

		if (updated == 1) {
			return;
		}

		/*
		 * 完成 SQL 已经检查截止时间。 即使扫描还未运行，过期结果也不能写成成功或失败。
		 */
		taskMapper.expireDueTask(taskId);

		if (traceId != null && !traceId.isBlank()) {
			int attached = taskMapper.attachLateTrace(taskId, traceId);

			if (attached == 1) {
				log.info("超时任务已关联迟到结果，taskId={}, traceId={}", taskId, traceId);
				return;
			}
		}

		// 可能已处于终态，或者没有可补充的 Trace，不进行重复执行。
		log.debug("任务完成状态未更新，taskId={}, targetStatus={}, traceId={}", taskId, status, traceId);
	}

	private String normalizeModelId(String modelId) {
		return modelId == null || modelId.isBlank() ? null : modelId.trim();
	}

	private void validateRequest(AgentAskRequest request) {
		if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
			throw new BusinessException(400, "message 不能为空");
		}

		if (request.getMessage().length() > 50_000) {
			throw new BusinessException(400, "message 不能超过 50000 个字符");
		}
	}

	private String validateIdempotencyKey(String key) {
		if (key == null) {
			return null;
		}

		/*
		 * 请求头未提供和提供了非法值是两种情况。 不把空白键静默当成“未提供”，否则客户端可能误以为已启用幂等。
		 */
		if (!IDEMPOTENCY_KEY_PATTERN.matcher(key).matches()) {
			throw new BusinessException(400, "Idempotency-Key 必须为 8～128 位字母、数字或 . _ : -，且首位为字母或数字");
		}

		return key;
	}

	private AgentTask reuseExistingTask(AgentTask existing, String fingerprint) {
		if (!fingerprint.equals(existing.getRequestFingerprint())) {
			throw new BusinessException(409, "IDEMPOTENCY_KEY_CONFLICT：同一幂等键不能用于不同的请求内容");
		}

		// 不区分当前状态，不自动重跑 FAILED 或 REJECTED 任务。
		return existing;
	}

}