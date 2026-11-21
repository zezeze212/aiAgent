package com.example.agent.config;

import com.example.agent.mapper.AgentTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "agent.task.sweep-enabled", havingValue = "true", matchIfMissing = true)
public class AgentTaskDeadlineSweep {

	private final AgentTaskMapper taskMapper;

	public AgentTaskDeadlineSweep(AgentTaskMapper taskMapper) {
		this.taskMapper = taskMapper;
	}

	@Scheduled(fixedDelayString = "${agent.task.sweep-delay-ms:1000}")
	public void expireDueTasks() {
		try {
			// 每轮只做一批短数据库更新，不调用模型，也不等待 Agent 线程。
			int updated = taskMapper.expireDueBatch();

			if (updated > 0) {
				log.info("Agent 任务超时扫描完成，updated={}", updated);
			}
		}
		catch (Exception e) {
			// 数据库暂时不可用时记录异常，下一轮继续尝试。
			log.error("Agent 任务超时扫描失败", e);
		}
	}

}