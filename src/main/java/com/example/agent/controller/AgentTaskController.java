package com.example.agent.controller;

import com.example.agent.common.Result;
import com.example.agent.dto.AgentAskRequest;
import com.example.agent.entity.AgentTask;
import com.example.agent.exception.BusinessException;
import com.example.agent.service.AgentTaskService;
import com.example.agent.task.AgentTaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/agent/tasks")
@RequiredArgsConstructor
public class AgentTaskController {

	private final AgentTaskService taskService;

	@PostMapping
	public ResponseEntity<Result<AgentTask>> submit(
			@RequestBody AgentAskRequest request,
			@RequestHeader(value = "Idempotency-Key", required = false)
			String idempotencyKey) {

		AgentTask task = taskService.submit(request, idempotencyKey);

		if (task.getStatus() == AgentTaskStatus.REJECTED) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
					.body(new Result<>(503,
							"任务执行器未接收该任务；相同幂等键不会重新投递", task));
		}

		if (task.getStatus() == AgentTaskStatus.SUCCEEDED
				|| task.getStatus() == AgentTaskStatus.FAILED) {
			// 返回已有终态任务，是否执行成功由 data.status 表达。
			return ResponseEntity.ok(Result.success(task));
		}

		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(new Result<>(202, "accepted", task));
	}

	@GetMapping("/{taskId}")
	public Result<AgentTask> getTask(@PathVariable String taskId) {
		return Result.success(taskService.getTask(taskId));
	}

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<Result<Void>> handleTaskBusinessException(
			BusinessException e) {
		return ResponseEntity.status(e.getCode())
				.body(Result.fail(e.getCode(), e.getMessage()));
	}

}