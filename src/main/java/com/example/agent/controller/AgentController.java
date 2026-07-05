package com.example.agent.controller;

import com.example.agent.dto.*;
import com.example.agent.service.AgentLogService;
import com.example.agent.service.SimpleAgentService;
import com.example.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @GetMapping("/runs")
    public AgentRunPageResponse getRunPage(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) Integer success
    ) {
        return agentLogService.getRunPage(pageNum, pageSize, toolName, success);
    }

    @GetMapping("/runs/stats")
    public AgentRunStatsResponse getRunStats(
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) Integer success
    ) {
        return agentLogService.getRunStats(toolName, success);
    }

    @GetMapping("/runs/{traceId}")
    public AgentRunDetailResponse getRunDetail(@PathVariable String traceId) {
        return agentLogService.getRunDetail(traceId);
    }
}