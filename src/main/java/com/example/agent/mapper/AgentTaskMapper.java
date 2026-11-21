package com.example.agent.mapper;

import com.example.agent.entity.AgentTask;
import com.example.agent.task.AgentTaskStatus;
import org.apache.ibatis.annotations.Param;

public interface AgentTaskMapper {

	int insert(AgentTask task);

	AgentTask selectById(@Param("taskId") String taskId);

	AgentTask selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

	int markRunning(@Param("taskId") String taskId);

	int markRejected(@Param("taskId") String taskId);

	int finish(@Param("taskId") String taskId, @Param("status") AgentTaskStatus status,
			@Param("traceId") String traceId, @Param("errorCode") String errorCode,
			@Param("errorMessage") String errorMessage);

}