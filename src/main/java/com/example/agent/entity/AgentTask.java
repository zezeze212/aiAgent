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

	/**
	 * 任务整体截止时间，由数据库在创建任务时计算。 历史任务可能为空。
	 */
	private LocalDateTime deadlineAt;

	/**
	 * 创建任务时使用的服务端预算。 只作为 INSERT 参数，不单独持久化，也不向接口返回。
	 */
	@JsonIgnore
	private int timeoutSeconds;

}