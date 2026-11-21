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
public class AgentTaskService {

	private final AgentTaskMapper taskMapper;

	private final AgentRunLogMapper runLogMapper;

	private final AgentModelRegistry modelRegistry;

	private final SpringAiToolChatService chatService;

	private final TaskExecutor taskExecutor;

	/**
	 * 本项目幂等键规则：8～128 位 ASCII 字符，区分大小写。 推荐客户端直接使用 UUID，同一次提交的重发复用原键。
	 */
	private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}");

	public AgentTaskService(AgentTaskMapper taskMapper, AgentRunLogMapper runLogMapper,
			AgentModelRegistry modelRegistry, SpringAiToolChatService chatService,
			@Qualifier("agentTaskExecutor") TaskExecutor taskExecutor) {
		this.taskMapper = taskMapper;
		this.runLogMapper = runLogMapper;
		this.modelRegistry = modelRegistry;
		this.chatService = chatService;
		this.taskExecutor = taskExecutor;
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
				// 返回已有任务不需要再次验证模型当前是否可用。
				return reuseExistingTask(existing, fingerprint);
			}
		}

		// 只有确实需要创建新任务时，才检查模型可用性。
		AgentModelRegistry.Entry selected = modelRegistry.resolve(requestedModelId);

		String taskId = UUID.randomUUID().toString().replace("-", "");

		AgentTask task = new AgentTask();
		task.setTaskId(taskId);
		task.setIdempotencyKey(key);
		task.setRequestFingerprint(fingerprint);
		task.setRequestedModelId(requestedModelId);
		task.setModelId(selected.modelId());
		task.setStatus(AgentTaskStatus.QUEUED);

		try {
			if (taskMapper.insert(task) != 1) {
				throw new IllegalStateException("任务创建失败");
			}
		}
		catch (DuplicateKeyException e) {
			/*
			 * 两个并发请求可能都在首次查询时没有找到任务。 唯一约束只允许一个插入成功，失败者读取获胜者的记录。
			 */
			if (key != null) {
				AgentTask existing = taskMapper.selectByIdempotencyKey(key);

				if (existing != null) {
					return reuseExistingTask(existing, fingerprint);
				}
			}

			// 没有找到对应幂等记录，不能把其他唯一键冲突伪装成成功重放。
			throw e;
		}

		/*
		 * 只有执行到这里的请求才真正创建了新任务。 普通重发和并发冲突分支均已返回，不会再次投递。
		 */
		try {
			taskExecutor.execute(() -> executeTask(taskId, message, requestedModelId));
		}
		catch (TaskRejectedException e) {
			if (taskMapper.markRejected(taskId) != 1) {
				throw new IllegalStateException("任务拒绝状态保存失败", e);
			}

			log.warn("Agent 异步任务被拒绝，taskId={}", taskId);
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
			// 只有成功完成 QUEUED -> RUNNING 的执行者才可以调用 Agent。
			if (taskMapper.markRunning(taskId) != 1) {
				log.info("任务未获得执行资格，跳过执行，taskId={}", taskId);
				return;
			}

			log.info("Agent 异步任务开始，taskId={}, thread={}", taskId, Thread.currentThread().getName());

			// 完整复用模型选择、RAG、工具治理、引用审计和历史保存。
			AgentAskResponse response = chatService.chat(message, requestedModelId);

			if (response == null || response.getTraceId() == null || response.getTraceId().isBlank()) {
				finish(taskId, AgentTaskStatus.FAILED, null, "AGENT_RESULT_MISSING", "Agent 未返回可关联的执行记录");
				return;
			}

			traceId = response.getTraceId();

			/*
			 * chat() 可能返回失败说明，也可能捕获了日志保存异常。 必须读取已经持久化的执行状态，不能根据 answer 是否非空判断。
			 */
			AgentRunLog run = runLogMapper.selectByTraceId(traceId);

			if (run == null) {
				finish(taskId, AgentTaskStatus.FAILED, traceId, "RUN_LOG_MISSING", "Agent 已返回，但未找到对应历史记录");
				return;
			}

			if (!Integer.valueOf(1).equals(run.getSuccess())) {
				finish(taskId, AgentTaskStatus.FAILED, traceId, "AGENT_EXECUTION_FAILED", "Agent 执行未通过，请查看关联 Trace");
				return;
			}

			finish(taskId, AgentTaskStatus.SUCCEEDED, traceId, null, null);
		}
		catch (Exception e) {
			log.error("Agent 异步任务执行异常，taskId={}, traceId={}", taskId, traceId, e);

			try {
				// 不直接把供应商或数据库异常原文返回给客户端。
				finish(taskId, AgentTaskStatus.FAILED, traceId, "TASK_EXECUTION_ERROR", "任务执行异常，请查看服务端日志");
			}
			catch (Exception persistenceError) {
				/*
				 * 数据库不可用时，无法保证失败状态也能保存。 明确记录异常，后续由恢复机制处理遗留任务，不无限重试。
				 */
				log.error("任务失败状态保存异常，taskId={}", taskId, persistenceError);
			}
		}
	}

	private void finish(String taskId, AgentTaskStatus status, String traceId, String errorCode, String errorMessage) {
		int updated = taskMapper.finish(taskId, status, traceId, errorCode, errorMessage);

		if (updated != 1) {
			// 条件更新不允许覆盖已经结束或不处于 RUNNING 的任务。
			log.warn("任务完成状态未更新，taskId={}, targetStatus={}", taskId, status);
		}
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