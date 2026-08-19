package com.example.agent.controller;

import com.example.agent.common.Result;
import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import com.example.agent.exception.BusinessException;
import com.example.agent.service.SpringAiChatService;
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

    @PostMapping("/chat")
    public Result<ChatResponse> chat(@RequestBody ChatRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            throw new BusinessException(400, "message 不能为空");
        }

        String answer = springAiChatService.chat(request.getMessage());
        return Result.success(new ChatResponse(answer));
    }

}