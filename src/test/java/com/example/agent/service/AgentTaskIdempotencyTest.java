package com.example.agent.service;

import com.example.agent.dto.AgentAskRequest;
import com.example.agent.entity.AgentTask;
import com.example.agent.exception.BusinessException;
import com.example.agent.mapper.AgentRunLogMapper;
import com.example.agent.mapper.AgentTaskMapper;
import com.example.agent.model.AgentModelRegistry;
import com.example.agent.task.AgentTaskStatus;
import com.example.agent.task.TaskRequestFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTaskIdempotencyTest {

	private static final String KEY = "m8-idempotency-test-001";

	private AgentTaskMapper tasks;

	private AgentModelRegistry registry;

	private SpringAiToolChatService chat;

	private TaskExecutor executor;

	private AgentTaskService service;

	@BeforeEach
	void setUp() {
		tasks = mock(AgentTaskMapper.class);
		registry = mock(AgentModelRegistry.class);
		chat = mock(SpringAiToolChatService.class);
		executor = mock(TaskExecutor.class);

		service = new AgentTaskService(tasks, mock(AgentRunLogMapper.class), registry, chat, executor, 120);

		when(registry.resolve(nullable(String.class))).thenReturn(new AgentModelRegistry.Entry("deepseek-main", "test",
				"test-model", true, true, mock(ChatClient.class)));

		when(tasks.insert(any())).thenReturn(1);
	}

	@Test
	void shouldCreateOnceAndReuseSameTask() {
		AtomicReference<AgentTask> saved = new AtomicReference<>();

		when(tasks.selectByIdempotencyKey(KEY)).thenAnswer(invocation -> saved.get());
		when(tasks.insert(any())).thenAnswer(invocation -> {
			saved.set(invocation.getArgument(0));
			return 1;
		});
		when(tasks.selectById(anyString())).thenAnswer(invocation -> saved.get());

		AgentTask first = service.submit(request(), KEY);
		AgentTask second = service.submit(request(), KEY);

		assertThat(second.getTaskId()).isEqualTo(first.getTaskId());
		assertThat(first.getIdempotencyKey()).isEqualTo(KEY);
		assertThat(first.getRequestFingerprint()).hasSize(64);

		verify(tasks, times(1)).insert(any());
		verify(executor, times(1)).execute(any(Runnable.class));
		verify(registry, times(1)).resolve(null);
		verifyNoInteractions(chat);
	}

	@ParameterizedTest
	@EnumSource(AgentTaskStatus.class)
	void shouldReuseEveryExistingStatusWithoutRedispatch(AgentTaskStatus status) {
		AgentTask existing = existing(status);
		when(tasks.selectByIdempotencyKey(KEY)).thenReturn(existing);

		assertThat(service.submit(request(), KEY)).isSameAs(existing);

		verify(tasks, never()).insert(any());
		verifyNoInteractions(registry, executor, chat);
	}

	@Test
	void shouldRejectChangedMessageOrModelSelection() {
		when(tasks.selectByIdempotencyKey(KEY)).thenReturn(existing(AgentTaskStatus.SUCCEEDED));

		AgentAskRequest changedMessage = request();
		changedMessage.setMessage("解释 Java 抽象类");

		AgentAskRequest changedModel = request();
		changedModel.setModelId("deepseek-main");

		// 未指定模型与显式指定默认模型，按本项目契约属于不同请求。
		for (AgentAskRequest changed : List.of(changedMessage, changedModel)) {
			assertThatThrownBy(() -> service.submit(changed, KEY)).isInstanceOfSatisfying(BusinessException.class,
					e -> assertThat(e.getCode()).isEqualTo(409));
		}

		verify(tasks, never()).insert(any());
		verifyNoInteractions(registry, executor, chat);
	}

	@Test
	void shouldReadWinnerAfterConcurrentInsertConflict() {
		AgentTask winner = existing(AgentTaskStatus.RUNNING);

		// 首次查询没有记录；插入冲突后，读取到另一个请求创建的任务。
		when(tasks.selectByIdempotencyKey(KEY)).thenReturn(null, winner);
		when(tasks.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));

		assertThat(service.submit(request(), KEY)).isSameAs(winner);

		verify(tasks, times(2)).selectByIdempotencyKey(KEY);
		verifyNoInteractions(executor, chat);
	}

	@Test
	void shouldNotHideUnrelatedDuplicateKeyFailure() {
		DuplicateKeyException failure = new DuplicateKeyException("other key");
		when(tasks.insert(any())).thenThrow(failure);

		// 两次幂等查询均没有对应记录，不能伪装成一次成功重放。
		assertThatThrownBy(() -> service.submit(request(), KEY)).isSameAs(failure);

		verifyNoInteractions(executor, chat);
	}

	@Test
	void shouldKeepCreatingNewTasksWhenKeyIsMissing() {
		when(tasks.selectById(anyString())).thenAnswer(invocation -> {
			AgentTask task = new AgentTask();
			task.setTaskId(invocation.getArgument(0));
			task.setStatus(AgentTaskStatus.QUEUED);
			return task;
		});

		AgentTask first = service.submit(request());
		AgentTask second = service.submit(request());

		assertThat(first.getTaskId()).isNotEqualTo(second.getTaskId());
		verify(tasks, times(2)).insert(any());
		verify(executor, times(2)).execute(any(Runnable.class));
		verify(tasks, never()).selectByIdempotencyKey(anyString());
	}

	@Test
	void shouldRejectInvalidKeysBeforeCreatingTask() {
		for (String key : List.of("", "bad key", "x".repeat(129))) {
			assertThatThrownBy(() -> service.submit(request(), key)).isInstanceOfSatisfying(BusinessException.class,
					e -> assertThat(e.getCode()).isEqualTo(400));
		}

		verifyNoInteractions(tasks, registry, executor, chat);
	}

	private AgentTask existing(AgentTaskStatus status) {
		AgentTask task = new AgentTask();
		task.setTaskId("existing-task");
		task.setIdempotencyKey(KEY);
		task.setRequestFingerprint(TaskRequestFingerprint.calculate(request().getMessage(), null));
		task.setModelId("deepseek-main");
		task.setStatus(status);
		return task;
	}

	private AgentAskRequest request() {
		AgentAskRequest request = new AgentAskRequest();
		request.setMessage("解释 Java 接口");
		return request;
	}

}