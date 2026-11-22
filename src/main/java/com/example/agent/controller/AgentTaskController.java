package com.example.agent.controller;

import com.example.agent.common.Result;
import com.example.agent.dto.AgentAskRequest;
import com.example.agent.dto.AgentTaskSnapshot;
import com.example.agent.entity.AgentTask;
import com.example.agent.exception.BusinessException;
import com.example.agent.service.AgentTaskService;
import com.example.agent.service.AgentTaskSseService;
import com.example.agent.task.AgentTaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/agent/tasks")
@RequiredArgsConstructor
public class AgentTaskController {

	private final AgentTaskService taskService;

	private final AgentTaskSseService taskSseService;

	@PostMapping
	public ResponseEntity<Result<AgentTask>> submit(@RequestBody AgentAskRequest request,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

		AgentTask task = taskService.submit(request, idempotencyKey);

		return toTaskResponse(task);
	}

	@GetMapping("/{taskId}")
	public Result<AgentTask> getTask(@PathVariable String taskId) {
		return Result.success(taskService.getTask(taskId));
	}

	@GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<Flux<ServerSentEvent<AgentTaskSnapshot>>> events(@PathVariable("taskId") String taskId) {

		Flux<ServerSentEvent<AgentTaskSnapshot>> stream = taskSseService.stream(taskId);

		return ResponseEntity.ok()
			.contentType(MediaType.TEXT_EVENT_STREAM)
			.header("Cache-Control", "no-cache")
			.header("X-Accel-Buffering", "no")
			.body(stream);
	}

	@PostMapping("/{taskId}/retry")
	public ResponseEntity<Result<AgentTask>> retry(@PathVariable("taskId") String taskId) {

		AgentTask task = taskService.retry(taskId);
		return toTaskResponse(task);
	}

	private ResponseEntity<Result<AgentTask>> toTaskResponse(AgentTask task) {

		if (task.getStatus() == AgentTaskStatus.REJECTED) {
			return ResponseEntity.status(503).body(new Result<>(503, "任务执行器未接收该任务；重复请求不会重新投递", task));
		}

		if (task.getStatus() == AgentTaskStatus.SUCCEEDED || task.getStatus() == AgentTaskStatus.FAILED
				|| task.getStatus() == AgentTaskStatus.TIMED_OUT) {

			return ResponseEntity.ok(Result.success(task));
		}

		return ResponseEntity.status(202).body(new Result<>(202, "accepted", task));
	}

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<Result<Void>> handleTaskBusinessException(BusinessException e) {

		return ResponseEntity.status(e.getCode())
			.contentType(MediaType.APPLICATION_JSON)
			.body(Result.fail(e.getCode(), e.getMessage()));
	}

}