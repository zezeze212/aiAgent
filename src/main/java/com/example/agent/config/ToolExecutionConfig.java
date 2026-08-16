package com.example.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Agent 工具执行线程池。
 *
 * 工具执行可能包含数据库查询、HTTP 请求等阻塞操作， 因此单独使用线程池，避免占用 Web 请求线程或其他公共异步线程池。
 *
 * 当前线程数先使用保守默认值。 M2 阶段重点是建立工具执行隔离和超时边界， 后续如果有实际并发数据，再考虑配置外部化和容量调优。
 */
@Configuration
public class ToolExecutionConfig {

	@Bean(name = "toolExecutionExecutor")
	public ThreadPoolTaskExecutor toolExecutionExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(32);
		executor.setThreadNamePrefix("agent-tool-");

		/*
		 * 应用关闭时不无限等待工具任务。 当前工具均应该受到自己的 timeoutMs 约束。
		 */
		executor.setWaitForTasksToCompleteOnShutdown(false);
		executor.setAwaitTerminationSeconds(5);

		return executor;
	}

}