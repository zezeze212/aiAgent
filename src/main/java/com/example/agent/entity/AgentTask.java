package com.example.agent.entity;

import com.example.agent.task.AgentTaskStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentTask {

	private String taskId;

	/**
	 * 内部提交去重信息，不作为任务查询响应字段输出。
	 */
	@JsonIgnore
	private String idempotencyKey;

	@JsonIgnore
	private String requestFingerprint;

	private String requestedModelId;

	private String modelId;

	private AgentTaskStatus status;

	private String traceId;

	private String errorCode;

	private String errorMessage;

	private LocalDateTime createdTime;

	private LocalDateTime startedTime;

	private LocalDateTime finishedTime;

}