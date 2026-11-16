package com.example.agent.entity;

import com.example.agent.task.AgentTaskStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentTask {

	private String taskId;

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