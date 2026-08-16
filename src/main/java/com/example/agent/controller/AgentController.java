package com.example.agent.controller;

import com.example.agent.common.Result;
import com.example.agent.dto.*;
import com.example.agent.exception.BusinessException;
import com.example.agent.service.AgentLogService;
import com.example.agent.service.SimpleAgentService;
import com.example.agent.support.AgentTraceSanitizer;
import com.example.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {

	private final SimpleAgentService simpleAgentService;

	private final ToolRegistry toolRegistry;

	private final AgentLogService agentLogService;

	private final AgentTraceSanitizer agentTraceSanitizer;

	@PostMapping("/ask")
	public Result<AgentAskResponse> ask(@RequestBody AgentAskRequest request) {

		if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {

			throw new BusinessException(400, "message 不能为空");
		}

		/*
		 * Agent 已经完成真实计算和数据库保存。
		 *
		 * 这里位于 HTTP 输出边界，只治理对外返回的 Trace， 不修改最终 answer，也不会影响此前的模型/Tool 执行。
		 */
		AgentAskResponse response = simpleAgentService.ask(request.getMessage());

		agentTraceSanitizer.sanitize(response.getSteps());

		return Result.success(response);
	}

	@GetMapping("/tools")
	public Result<List<ToolInfo>> listTools() {
		return Result.success(toolRegistry.listTools());
	}

	@GetMapping("/runs")
	public Result<AgentRunPageResponse> getRunPage(@RequestParam(defaultValue = "1") Integer pageNum,
			@RequestParam(defaultValue = "10") Integer pageSize, @RequestParam(required = false) String toolName,
			@RequestParam(required = false) Integer success,
			@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
			@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
		if (pageNum == null || pageNum <= 0) {
			throw new BusinessException(400, "pageNum 必须大于 0");
		}

		if (pageSize == null || pageSize <= 0) {
			throw new BusinessException(400, "pageSize 必须大于 0");
		}

		if (pageSize > 100) {
			throw new BusinessException(400, "pageSize 不能超过 100");
		}

		if (success != null && success != 0 && success != 1) {
			throw new BusinessException(400, "success 只能是 0 或 1");
		}

		if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
			throw new BusinessException(400, "startTime 不能晚于 endTime");
		}

		AgentRunPageResponse response = agentLogService.getRunPage(pageNum, pageSize, toolName, success, startTime,
				endTime);

		return Result.success(response);
	}

	@GetMapping("/runs/stats")
	public Result<AgentRunStatsResponse> getRunStats(@RequestParam(required = false) String toolName,
			@RequestParam(required = false) Integer success,
			@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
			@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
		if (success != null && success != 0 && success != 1) {
			throw new BusinessException(400, "success 只能是 0 或 1");
		}

		if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
			throw new BusinessException(400, "startTime 不能晚于 endTime");
		}

		AgentRunStatsResponse response = agentLogService.getRunStats(toolName, success, startTime, endTime);

		return Result.success(response);
	}

	@GetMapping("/runs/{traceId}")
	public Result<AgentRunDetailResponse> getRunDetail(@PathVariable String traceId) {
		if (traceId == null || traceId.trim().isEmpty()) {
			throw new BusinessException(400, "traceId 不能为空");
		}

		AgentRunDetailResponse response = agentLogService.getRunDetail(traceId);
		return Result.success(response);
	}

}