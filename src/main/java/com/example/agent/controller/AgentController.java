package com.example.agent.controller;

import com.example.agent.dto.AgentAskRequest;
import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.ToolInfo;
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

    @PostMapping("/ask")
    public AgentAskResponse ask(@RequestBody AgentAskRequest request) {
        return simpleAgentService.ask(request.getMessage());
    }

    @GetMapping("/tools")
    public List<ToolInfo> listTools() {
        return toolRegistry.listTools();
    }
}