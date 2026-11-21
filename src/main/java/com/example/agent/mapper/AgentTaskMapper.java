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

	/**
	 * 将指定的到期任务收敛为 TIMED_OUT。 已经结束或尚未到期时返回 0。
	 */
	int expireDueTask(@Param("taskId") String taskId);

	/**
	 * 每轮最多处理 100 条到期任务，避免一次更新无限扩大。
	 */
	int expireDueBatch();

	/**
	 * 给已经运行过的超时任务补充迟到的 Trace。 不改变终态、错误信息和结束时间，也不覆盖已有 Trace。
	 */
	int attachLateTrace(@Param("taskId") String taskId, @Param("traceId") String traceId);

}