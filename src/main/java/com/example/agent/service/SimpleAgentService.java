package com.example.agent.service;

import com.example.agent.dto.AgentAskResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Agent 对外服务入口。
 *
 * 只负责：
 * 1. 接收用户请求；
 * 2. 调用 Agent 编排器；
 * 3. 保存完整运行记录；
 * 4. 返回执行结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimpleAgentService {

    private final AgentOrchestrator agentOrchestrator;

    private final AgentLogService agentLogService;

    public AgentAskResponse ask(String userMessage) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);

        log.info("Agent 请求开始，traceId={}, userMessage={}", traceId, userMessage);

        AgentAskResponse response = agentOrchestrator.execute(userMessage, traceId);

        agentLogService.saveRunLog(userMessage, response);

        log.info("Agent 请求完成，traceId={}, usedTool={}, agentCostMs={}",
                traceId,
                response.getUsedTool(),
                response.getAgentCostMs());

        return response;
    }
}