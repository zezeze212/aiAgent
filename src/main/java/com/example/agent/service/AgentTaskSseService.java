package com.example.agent.service;

import com.example.agent.dto.AgentTaskSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class AgentTaskSseService {

	private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

	private static final Duration CONNECTION_LIFETIME = Duration.ofSeconds(60);

	private final AgentTaskService taskService;

	private final Scheduler timer;

	private final Scheduler queryScheduler;

	public AgentTaskSseService(AgentTaskService taskService, @Qualifier("agentTaskSseTimer") Scheduler timer,
			@Qualifier("agentTaskSseQueryScheduler") Scheduler queryScheduler) {

		this.taskService = taskService;
		this.timer = timer;
		this.queryScheduler = queryScheduler;
	}

	public Flux<ServerSentEvent<AgentTaskSnapshot>> stream(String taskId) {
		/*
		 * 在 Controller 返回事件流之前查询一次。 不存在的任务仍走普通 HTTP 404，不先建立一个成功的 SSE 响应。
		 */
		AgentTaskSnapshot initial = readSnapshot(taskId);

		return Flux.defer(() -> {
			// 每条订阅独立保存上一次快照，不在不同客户端间共享。
			AtomicReference<AgentTaskSnapshot> previous = new AtomicReference<>();

			return Flux.concat(Mono.just(initial), pollSnapshots(taskId))
				// takeUntil 会先发送匹配的终态快照，再结束流。
				.takeUntil(AgentTaskSnapshot::terminal)
				.map(snapshot -> toEvent(snapshot, previous))
				.take(CONNECTION_LIFETIME, timer)
				.onErrorResume(error -> {
					/*
					 * 查询失败只结束订阅，不把 Agent 任务改成 FAILED。 已开始的 SSE 响应也不再尝试改写成普通 JSON 错误响应。
					 */
					log.warn("任务状态订阅中断，taskId={}", taskId, error);
					return Flux.empty();
				})
				.doFinally(signal -> log.debug("任务状态订阅结束，taskId={}, signal={}", taskId, signal));
		});
	}

	private Flux<AgentTaskSnapshot> pollSnapshots(String taskId) {
		return Flux.interval(POLL_INTERVAL, timer)
			/*
			 * 下游繁忙时丢弃多余的检查信号，不积压轮询任务。 丢弃的是定时信号；下一次仍查询数据库的最新状态。
			 */
			.onBackpressureDrop()
			.concatMap(tick -> Mono.fromCallable(() -> readSnapshot(taskId)).subscribeOn(queryScheduler), 1);
	}

	private AgentTaskSnapshot readSnapshot(String taskId) {
		return AgentTaskSnapshot.from(taskService.getTask(taskId));
	}

	private ServerSentEvent<AgentTaskSnapshot> toEvent(AgentTaskSnapshot snapshot,
			AtomicReference<AgentTaskSnapshot> previous) {

		AgentTaskSnapshot last = previous.getAndSet(snapshot);

		if (snapshot.equals(last)) {
			// 注释型心跳没有业务 data，不触发浏览器的 task 事件处理器。
			return ServerSentEvent.<AgentTaskSnapshot>builder().comment("keepalive").build();
		}

		return ServerSentEvent.<AgentTaskSnapshot>builder()
			.event("task")
			.retry(Duration.ofSeconds(2))
			.data(snapshot)
			.build();
	}

}