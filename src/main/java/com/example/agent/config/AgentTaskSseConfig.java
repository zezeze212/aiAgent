package com.example.agent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
public class AgentTaskSseConfig {

	@Bean(name = "agentTaskSseTimer", destroyMethod = "dispose")
	public Scheduler agentTaskSseTimer() {
		// 只负责产生定时信号，不在这条线程上访问数据库。
		return Schedulers.newSingle("agent-sse-timer");
	}

	@Bean(name = "agentTaskSseQueryScheduler", destroyMethod = "dispose")
	public Scheduler agentTaskSseQueryScheduler() {
		// MyBatis 查询属于阻塞操作，放入独立的有界调度器。
		// 最多 2 条工作线程；排队容量参数按每个后端线程计算。
		return Schedulers.newBoundedElastic(2, 32, "agent-sse-query");
	}

	@Bean(name = "agentSseWriteExecutor")
	public ThreadPoolTaskExecutor agentSseWriteExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(32);
		executor.setThreadNamePrefix("agent-sse-write-");

		// 容量耗尽时明确拒绝，避免把网络写入转移到定时线程。
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(false);
		executor.setAwaitTerminationSeconds(5);

		return executor;
	}

	@Bean
	public WebMvcConfigurer agentSseMvcConfigurer(@Qualifier("agentSseWriteExecutor") AsyncTaskExecutor executor) {

		return new WebMvcConfigurer() {
			@Override
			public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
				configurer.setTaskExecutor(executor);

				// 状态流会在 60 秒主动结束，MVC 超时多留 5 秒余量。
				configurer.setDefaultTimeout(65_000L);
			}
		};
	}

}