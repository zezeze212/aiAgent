package com.example.agent.controller;

import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import com.example.agent.dto.ErrorAnalyzeRequest;
import com.example.agent.dto.ErrorAnalyzeResponse;
import com.example.agent.service.AiChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String answer = aiChatService.chat(request.getMessage());
        return new ChatResponse(answer);
    }

    @PostMapping("/analyze-error")
    public ErrorAnalyzeResponse analyzeError(@RequestBody ErrorAnalyzeRequest request) {
        return aiChatService.analyzeError(request.getLog());
    }

    @PostMapping("/analyze-error/raw")
    public ChatResponse analyzeErrorRaw(@RequestBody ErrorAnalyzeRequest request) {
        String rawContent = aiChatService.analyzeErrorRaw(request.getLog());
        return new ChatResponse(rawContent);
    }
}