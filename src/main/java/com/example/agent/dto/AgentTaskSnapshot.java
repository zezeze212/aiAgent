package com.example.agent.dto;

import com.example.agent.entity.AgentTask;
import com.example.agent.task.AgentTaskStatus;

import java.time.LocalDateTime;

/**
 * SSE 对外任务快照。 只包含展示状态所需的信息，不携带请求正文和幂等信息。
 */
public record AgentTaskSnapshot(String taskId, AgentTaskStatus status, String traceId, String errorCode,
		String errorMessage, LocalDateTime startedTime, LocalDateTime finishedTime, LocalDateTime deadlineAt) {

	public static AgentTaskSnapshot from(AgentTask task) {
		return new AgentTaskSnapshot(task.getTaskId(), task.getStatus(), task.getTraceId(), task.getErrorCode(),
				task.getErrorMessage(), task.getStartedTime(), task.getFinishedTime(), task.getDeadlineAt());
	}

	/**
	 * 沿用当前任务状态机的终态定义。
	 */
	public boolean terminal() {
		return switch (status) {
			case SUCCEEDED, FAILED, REJECTED, TIMED_OUT -> true;
			case QUEUED, RUNNING -> false;
		};
	}

}