package com.example.agent.controller;

import com.example.agent.dto.AgentAskRequest;
import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.AgentRunDetailResponse;
import com.example.agent.dto.ToolInfo;
import com.example.agent.service.AgentLogService;
import com.example.agent.service.SimpleAgentService;
import com.example.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {

    private final SimpleAgentService simpleAgentService;

    private final ToolRegistry toolRegistry;

    private final AgentLogService agentLogService;

    @PostMapping("/ask")
    public AgentAskResponse ask(@RequestBody AgentAskRequest request) {
        return simpleAgentService.ask(request.getMessage());
    }

    @GetMapping("/tools")
    public List<ToolInfo> listTools() {
        return toolRegistry.listTools();
    }

    @GetMapping("/runs/{traceId}")
    public AgentRunDetailResponse getRunDetail(@PathVariable String traceId) {
        return agentLogService.getRunDetail(traceId);
    }
}