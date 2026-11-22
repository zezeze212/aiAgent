package com.example.agent.config;

import com.example.agent.mapper.AgentTaskMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

@Slf4j
@Component("agentTaskStartupRecovery")
@DependsOnDatabaseInitialization
public class AgentTaskStartupRecovery {

	private final AgentTaskMapper taskMapper;

	private final boolean enabled;

	public AgentTaskStartupRecovery(AgentTaskMapper taskMapper,
			@Value("${agent.task.startup-recovery-enabled:true}") boolean enabled) {

		this.taskMapper = taskMapper;
		this.enabled = enabled;
	}

	@PostConstruct
	void recoverOnStartup() {
		if (!enabled) {
			log.info("Agent 任务启动恢复已关闭");
			return;
		}

		/*
		 * 当前策略仅适用于单 Java 实例： 此时尚未初始化任务服务，数据库中的活动记录属于遗留任务。
		 *
		 * 这里只更新状态，不重新调用模型或工具。
		 *
		 * 不吞掉数据库异常： 恢复失败时应阻止应用完成启动，避免继续接收新任务。
		 */
		int updated = taskMapper.recoverUnfinishedOnStartup();

		log.info("Agent 任务启动恢复完成，updated={}", updated);
	}

}