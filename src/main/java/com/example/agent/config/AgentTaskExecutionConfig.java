package com.example.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AgentTaskExecutionConfig {

	@Bean(name = "agentTaskExecutor")
	public ThreadPoolTaskExecutor agentTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

		// 本单元采用保守容量：同时执行 2 个 Agent，最多等待 8 个。
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(8);
		executor.setThreadNamePrefix("agent-task-");

		// 满载时明确拒绝，不能让 HTTP 请求线程代替执行长任务。
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

		executor.setWaitForTasksToCompleteOnShutdown(false);
		executor.setAwaitTerminationSeconds(5);

		return executor;
	}

}