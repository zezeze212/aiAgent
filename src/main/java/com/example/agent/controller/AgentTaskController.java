package com.example.agent.controller;

import com.example.agent.common.Result;
import com.example.agent.dto.AgentAskRequest;
import com.example.agent.entity.AgentTask;
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
	public ResponseEntity<Result<AgentTask>> submit(@RequestBody AgentAskRequest request) {
		AgentTask task = taskService.submit(request);

		if (task.getStatus() == AgentTaskStatus.REJECTED) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(new Result<>(503, "任务执行器暂时无法接收请求，请稍后重试", task));
		}

		// 202 表示提交已接受，不代表后台执行成功。
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Result<>(202, "accepted", task));
	}

	@GetMapping("/{taskId}")
	public Result<AgentTask> getTask(@PathVariable String taskId) {
		return Result.success(taskService.getTask(taskId));
	}

}