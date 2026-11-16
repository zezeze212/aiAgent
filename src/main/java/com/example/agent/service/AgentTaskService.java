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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
public class AgentTaskService {

	private final AgentTaskMapper taskMapper;

	private final AgentRunLogMapper runLogMapper;

	private final AgentModelRegistry modelRegistry;

	private final SpringAiToolChatService chatService;

	private final TaskExecutor taskExecutor;

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
	 * 不允许带着未提交的外围事务投递任务。 Mapper insert 返回时已提交，工作线程才能立即读取和更新任务。
	 */
	@Transactional(propagation = Propagation.NEVER)
	public AgentTask submit(AgentAskRequest request) {
		if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
			throw new BusinessException(400, "message 不能为空");
		}

		// 限制排队请求的单条大小；这是本项目异步入口策略，不是模型窗口限制。
		if (request.getMessage().length() > 50_000) {
			throw new BusinessException(400, "message 不能超过 50000 个字符");
		}

		String message = request.getMessage();
		String requestedModelId = normalizeModelId(request.getModelId());

		// 无效、禁用模型在创建任务之前拒绝，避免占用队列。
		AgentModelRegistry.Entry selected = modelRegistry.resolve(requestedModelId);

		String taskId = UUID.randomUUID().toString().replace("-", "");

		AgentTask task = new AgentTask();
		task.setTaskId(taskId);
		task.setRequestedModelId(requestedModelId);
		task.setModelId(selected.modelId());
		task.setStatus(AgentTaskStatus.QUEUED);

		if (taskMapper.insert(task) != 1) {
			throw new IllegalStateException("任务创建失败");
		}

		try {
			// 捕获不可变字符串，不把可变请求 DTO 交给异步线程。
			taskExecutor.execute(() -> executeTask(taskId, message, requestedModelId));
		}
		catch (TaskRejectedException e) {
			if (taskMapper.markRejected(taskId) != 1) {
				throw new IllegalStateException("任务拒绝状态保存失败", e);
			}

			log.warn("Agent 异步任务被拒绝，taskId={}", taskId);
		}

		// 工作线程可能已经启动，返回查询时实际可见的状态。
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

}