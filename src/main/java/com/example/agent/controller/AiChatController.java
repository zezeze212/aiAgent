package com.example.agent.controller;

import com.example.agent.common.Result;
import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import com.example.agent.dto.ErrorAnalyzeRequest;
import com.example.agent.dto.ErrorAnalyzeResponse;
import com.example.agent.exception.BusinessException;
import com.example.agent.service.AiChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;

    @PostMapping("/chat")
    public Result<ChatResponse> chat(@RequestBody ChatRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            throw new BusinessException(400, "message 不能为空");
        }

        String answer = aiChatService.chat(request.getMessage());
        return Result.success(new ChatResponse(answer));
    }

    @PostMapping("/analyze-error")
    public Result<ErrorAnalyzeResponse> analyzeError(@RequestBody ErrorAnalyzeRequest request) {
        if (request == null || request.getLog() == null || request.getLog().trim().isEmpty()) {
            throw new BusinessException(400, "log 不能为空");
        }

        ErrorAnalyzeResponse response = aiChatService.analyzeError(request.getLog());
        return Result.success(response);
    }

    @PostMapping("/analyze-error/raw")
    public Result<ChatResponse> analyzeErrorRaw(@RequestBody ErrorAnalyzeRequest request) {
        if (request == null || request.getLog() == null || request.getLog().trim().isEmpty()) {
            throw new BusinessException(400, "log 不能为空");
        }

        String rawContent = aiChatService.analyzeErrorRaw(request.getLog());
        return Result.success(new ChatResponse(rawContent));
    }
}