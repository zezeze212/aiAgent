package com.example.agent.service;

import com.example.agent.dto.AgentAskResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Agent 对外服务入口。
 *
 * 负责： 1. 生成 traceId； 2. 调用 Agent 编排器； 3. 尝试保存完整运行记录； 4. 返回 Agent 执行结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimpleAgentService {

	private final AgentOrchestrator agentOrchestrator;

	private final AgentLogService agentLogService;

	public AgentAskResponse ask(String userMessage) {
		String traceId = UUID.randomUUID().toString().substring(0, 8);

		/*
		 * 用户问题可能包含 StackTrace、密码或 Token， 普通应用日志不直接打印完整正文。 具体诊断内容由受治理的 Trace 保存。
		 */
		log.info("Agent 请求开始，traceId={}, userMessageLength={}", traceId,
				userMessage == null ? 0 : userMessage.length());

		AgentAskResponse response = agentOrchestrator.execute(userMessage, traceId);

		try {
			agentLogService.saveRunLog(userMessage, response);
		}
		catch (Exception e) {
			/*
			 * Agent 已经生成答案时，日志数据库故障不应把本次回答改成失败。
			 *
			 * AgentLogService 内部有事务： 如果主记录或任意步骤保存失败，当前运行日志会整体回滚。
			 */
			log.error("Agent 运行日志保存失败，已保留本次回答，traceId={}", traceId, e);
		}

		log.info("Agent 请求完成，traceId={}, usedTool={}, agentCostMs={}", traceId, response.getUsedTool(),
				response.getAgentCostMs());

		return response;
	}

}