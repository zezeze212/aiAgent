package com.example.agent.service;

import com.example.agent.entity.AgentTask;
import com.example.agent.exception.BusinessException;
import com.example.agent.mapper.AgentRunLogMapper;
import com.example.agent.mapper.AgentTaskMapper;
import com.example.agent.model.AgentModelRegistry;
import com.example.agent.task.AgentTaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AgentTaskRetryTest {

	private AgentTaskMapper tasks;

	private AgentRunLogMapper runs;

	private AgentModelRegistry registry;

	private SpringAiToolChatService chat;

	private TaskExecutor executor;

	private AgentTaskService service;

	private AgentTask source;

	@BeforeEach
	void setUp() {
		tasks = mock(AgentTaskMapper.class);
		runs = mock(AgentRunLogMapper.class);
		registry = mock(AgentModelRegistry.class);
		chat = mock(SpringAiToolChatService.class);
		executor = mock(TaskExecutor.class);

		service = new AgentTaskService(tasks, runs, registry, chat, executor, 120);

		source = new AgentTask();
		source.setTaskId("source-task");
		source.setRequestedModelId(null);
		source.setModelId("deepseek-main");
		source.setStatus(AgentTaskStatus.REJECTED);
		source.setRequestMessage("简要解释 Java 接口的作用。");

		AtomicReference<AgentTask> inserted = new AtomicReference<>();

		when(tasks.selectById(anyString())).thenAnswer(invocation -> {
			String taskId = invocation.getArgument(0);

			if (source.getTaskId().equals(taskId)) {
				return source;
			}

			AgentTask child = inserted.get();

			return child != null && child.getTaskId().equals(taskId) ? child : null;
		});

		when(tasks.insert(any(AgentTask.class))).thenAnswer(invocation -> {
			inserted.set(invocation.getArgument(0));
			return 1;
		});

		when(registry.resolve("deepseek-main")).thenReturn(new AgentModelRegistry.Entry("deepseek-main", "deepseek",
				"configured-test-model", true, true, mock(ChatClient.class)));
	}

	@Test
	void shouldCreateChildAndPinOriginalModel() {
		AgentTask child = service.retry(source.getTaskId());

		assertThat(child.getTaskId()).isNotEqualTo(source.getTaskId());
		assertThat(child.getRetryOfTaskId()).isEqualTo(source.getTaskId());
		assertThat(child.getRequestMessage()).isEqualTo(source.getRequestMessage());
		assertThat(child.getRequestedModelId()).isEqualTo("deepseek-main");
		assertThat(child.getModelId()).isEqualTo("deepseek-main");
		assertThat(child.getTimeoutSeconds()).isEqualTo(120);
		assertThat(child.getIdempotencyKey()).isNull();
		assertThat(child.getRequestFingerprint()).isNull();

		InOrder order = inOrder(tasks, executor);
		order.verify(tasks).insert(any(AgentTask.class));
		order.verify(executor).execute(any(Runnable.class));

		// 本测试只捕获投递，不真正执行 Runnable。
		verifyNoInteractions(chat, runs);
	}

	@Test
	void shouldReturnExistingChildWithoutDispatchingAgain() {
		AgentTask existing = existingChild();

		when(tasks.selectByRetryOfTaskId(source.getTaskId())).thenReturn(existing);

		AgentTask returned = service.retry(source.getTaskId());

		assertThat(returned).isSameAs(existing);
		verify(tasks, never()).insert(any(AgentTask.class));
		verifyNoInteractions(registry, executor, chat);
	}

	@Test
	void shouldReturnWinnerWhenConcurrentInsertLoses() {
		AgentTask winner = existingChild();

		when(tasks.selectByRetryOfTaskId(source.getTaskId())).thenReturn(null, winner);

		when(tasks.insert(any(AgentTask.class))).thenThrow(new DuplicateKeyException("retry source exists"));

		AgentTask returned = service.retry(source.getTaskId());

		assertThat(returned).isSameAs(winner);
		verifyNoInteractions(executor, chat);
	}

	@Test
	void shouldRejectTaskThatAlreadyStarted() {
		source.setStatus(AgentTaskStatus.TIMED_OUT);
		source.setStartedTime(LocalDateTime.now());

		assertThatThrownBy(() -> service.retry(source.getTaskId())).isInstanceOf(BusinessException.class)
			.hasMessageContaining("TASK_RETRY_NOT_ALLOWED");

		verify(tasks, never()).insert(any(AgentTask.class));
		verifyNoInteractions(registry, executor, chat);
	}

	@Test
	void shouldRejectActiveTaskEvenIfItHasNotStarted() {
		source.setStatus(AgentTaskStatus.QUEUED);

		assertThatThrownBy(() -> service.retry(source.getTaskId())).isInstanceOf(BusinessException.class)
			.hasMessageContaining("TASK_RETRY_NOT_ALLOWED");

		verifyNoInteractions(executor, chat);
	}

	@Test
	void shouldRejectRetryOfChildTask() {
		source.setRetryOfTaskId("original-task");

		assertThatThrownBy(() -> service.retry(source.getTaskId())).isInstanceOf(BusinessException.class)
			.hasMessageContaining("TASK_RETRY_LIMIT_REACHED");

		verify(tasks, never()).insert(any(AgentTask.class));
		verifyNoInteractions(executor, chat);
	}

	@Test
	void shouldRejectLegacyTaskWithoutStoredRequest() {
		source.setRequestMessage(null);

		assertThatThrownBy(() -> service.retry(source.getTaskId())).isInstanceOf(BusinessException.class)
			.hasMessageContaining("TASK_RETRY_REQUEST_MISSING");

		verifyNoInteractions(registry, executor, chat);
	}

	@Test
	void shouldAllowUnstartedTaskRecoveredAfterRestart() {
		source.setStatus(AgentTaskStatus.FAILED);
		source.setErrorCode("TASK_PROCESS_RESTARTED");

		AgentTask child = service.retry(source.getTaskId());

		assertThat(child.getRetryOfTaskId()).isEqualTo(source.getTaskId());
		verify(executor).execute(any(Runnable.class));
	}

	@Test
	void shouldNotExposeStoredRequestInTaskJson() throws Exception {
		String json = new ObjectMapper().writeValueAsString(source);

		assertThat(json).doesNotContain("requestMessage");
		assertThat(json).doesNotContain(source.getRequestMessage());
	}

	private AgentTask existingChild() {
		AgentTask child = new AgentTask();
		child.setTaskId("existing-child");
		child.setRetryOfTaskId(source.getTaskId());
		child.setStatus(AgentTaskStatus.SUCCEEDED);
		child.setTraceId("existing-trace");
		return child;
	}

}