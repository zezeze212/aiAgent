package com.example.agent.controller;

import com.example.agent.common.Result;
import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import com.example.agent.exception.BusinessException;
import com.example.agent.service.SpringAiToolChatService;
import com.example.agent.support.AgentTraceSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/spring-ai")
@RequiredArgsConstructor
public class SpringAiChatController {

	private final SpringAiChatService springAiChatService;

	private final SpringAiToolChatService springAiToolChatService;

	private final AgentTraceSanitizer agentTraceSanitizer;

	@PostMapping("/chat")
	public Result<ChatResponse> chat(@RequestBody ChatRequest request) {

		validateRequest(request);

		String answer = springAiChatService.chat(request.getMessage());

		return Result.success(new ChatResponse(answer));
	}

	@PostMapping("/schema-chat")
	public Result<AgentAskResponse> schemaChat(@RequestBody ChatRequest request) {

		validateRequest(request);

		AgentAskResponse response = springAiToolChatService.chat(request.getMessage());

		/*
		 * 数据库已经保存脱敏后的 Trace。 这里治理的是 HTTP 对外输出。
		 */
		agentTraceSanitizer.sanitize(response.getSteps());

		return Result.success(response);
	}

	private void validateRequest(ChatRequest request) {
		if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {

			throw new BusinessException(400, "message 不能为空");
		}
	}

}