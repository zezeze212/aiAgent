package com.example.agent.service;

import com.example.agent.dto.AgentTaskSnapshot;
import com.example.agent.entity.AgentTask;
import com.example.agent.exception.BusinessException;
import com.example.agent.task.AgentTaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTaskSseServiceTest {

	private AgentTaskService taskService;

	private VirtualTimeScheduler timer;

	private AgentTaskSseService service;

	@BeforeEach
	void setUp() {
		taskService = mock(AgentTaskService.class);
		timer = VirtualTimeScheduler.create();

		// 查询由 Mockito 提供，直接执行；定时信号由虚拟时钟控制。
		service = new AgentTaskSseService(taskService, timer, Schedulers.immediate());
	}

	@AfterEach
	void tearDown() {
		timer.dispose();
	}

	@Test
	void shouldSendInitialHeartbeatChangesAndTerminal() {
		when(taskService.getTask("t1")).thenReturn(task(AgentTaskStatus.QUEUED), task(AgentTaskStatus.QUEUED),
				task(AgentTaskStatus.RUNNING), task(AgentTaskStatus.SUCCEEDED));

		StepVerifier.create(service.stream("t1"))
			.assertNext(event -> assertStatus(event, AgentTaskStatus.QUEUED))
			.then(this::advanceOneSecond)
			.assertNext(event -> {
				assertNull(event.data());
				assertEquals("keepalive", event.comment());
			})
			.then(this::advanceOneSecond)
			.assertNext(event -> assertStatus(event, AgentTaskStatus.RUNNING))
			.then(this::advanceOneSecond)
			.assertNext(event -> assertStatus(event, AgentTaskStatus.SUCCEEDED))
			.verifyComplete();

		// 终态以后不应继续查询。
		timer.advanceTimeBy(Duration.ofSeconds(10));
		verify(taskService, times(4)).getTask("t1");
	}

	@ParameterizedTest
	@EnumSource(value = AgentTaskStatus.class, names = { "SUCCEEDED", "FAILED", "REJECTED", "TIMED_OUT" })
	void shouldSendExistingTerminalTaskOnce(AgentTaskStatus status) {
		when(taskService.getTask("t1")).thenReturn(task(status));

		StepVerifier.create(service.stream("t1")).assertNext(event -> assertStatus(event, status)).verifyComplete();

		timer.advanceTimeBy(Duration.ofSeconds(10));
		verify(taskService, times(1)).getTask("t1");
	}

	@Test
	void shouldStopPollingWhenSubscriberCancels() {
		when(taskService.getTask("t1")).thenReturn(task(AgentTaskStatus.RUNNING));

		StepVerifier.create(service.stream("t1"))
			.assertNext(event -> assertStatus(event, AgentTaskStatus.RUNNING))
			.thenCancel()
			.verify();

		timer.advanceTimeBy(Duration.ofSeconds(10));

		// 取消订阅后没有额外查询，也没有提交、重试等调用。
		verify(taskService, times(1)).getTask("t1");
		verifyNoMoreInteractions(taskService);
	}

	@Test
	void shouldRejectMissingTaskBeforeReturningStream() {
		when(taskService.getTask("missing")).thenThrow(new BusinessException(404, "TASK_NOT_FOUND：任务不存在"));

		BusinessException error = assertThrows(BusinessException.class, () -> service.stream("missing"));

		assertEquals(404, error.getCode());
	}

	@Test
	void shouldCloseStreamWhenLaterQueryFails() {
		when(taskService.getTask("t1")).thenReturn(task(AgentTaskStatus.RUNNING))
			.thenThrow(new IllegalStateException("模拟数据库查询异常"));

		StepVerifier.create(service.stream("t1"))
			.assertNext(event -> assertStatus(event, AgentTaskStatus.RUNNING))
			.then(this::advanceOneSecond)
			.verifyComplete();

		verify(taskService, times(2)).getTask("t1");
		verifyNoMoreInteractions(taskService);
	}

	private void advanceOneSecond() {
		timer.advanceTimeBy(Duration.ofSeconds(1));
	}

	private AgentTask task(AgentTaskStatus status) {
		AgentTask task = new AgentTask();
		task.setTaskId("t1");
		task.setStatus(status);

		if (status == AgentTaskStatus.SUCCEEDED) {
			task.setTraceId("trace-demo");
		}

		return task;
	}

	private void assertStatus(ServerSentEvent<AgentTaskSnapshot> event, AgentTaskStatus expected) {

		assertEquals("task", event.event());
		assertNotNull(event.data());
		assertEquals(expected, event.data().status());
	}

}