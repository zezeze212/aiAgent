package com.example.agent.service;

import com.example.agent.dto.AgentAskRequest;
import com.example.agent.dto.AgentAskResponse;
import com.example.agent.entity.AgentRunLog;
import com.example.agent.entity.AgentTask;
import com.example.agent.exception.BusinessException;
import com.example.agent.mapper.AgentRunLogMapper;
import com.example.agent.mapper.AgentTaskMapper;
import com.example.agent.model.AgentModelRegistry;
import com.example.agent.task.AgentTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTaskServiceTest {

	private AgentTaskMapper tasks;

	private AgentRunLogMapper runs;

	private SpringAiToolChatService chat;

	private TaskExecutor executor;

	private AgentTaskService service;

	@BeforeEach
	void setUp() {
		tasks = mock(AgentTaskMapper.class);
		runs = mock(AgentRunLogMapper.class);
		chat = mock(SpringAiToolChatService.class);
		executor = mock(TaskExecutor.class);

		AgentModelRegistry registry = new AgentModelRegistry("deepseek-main",
				List.of(new AgentModelRegistry.Entry("deepseek-main", "test", "test-model", true, true,
						mock(ChatClient.class))));

		service = new AgentTaskService(tasks, runs, registry, chat, executor);

		when(tasks.insert(any())).thenReturn(1);
		when(tasks.selectById(anyString())).thenReturn(new AgentTask());
		when(tasks.markRunning(anyString())).thenReturn(1);
		when(tasks.finish(anyString(), any(), nullable(String.class), nullable(String.class), nullable(String.class)))
			.thenReturn(1);
		when(tasks.markRejected(anyString())).thenReturn(1);
	}

	@Test
	void shouldFinishSuccessfulRunAndSkipDuplicateExecution() {
		stubResponse("正常回答", 1);
		when(tasks.markRunning(anyString())).thenReturn(1, 0);

		Runnable worker = submitAndCaptureWorker();
		worker.run();
		worker.run();

		verify(chat, times(1)).chat("解释 Java 接口", null);
		verify(tasks).finish(anyString(), eq(AgentTaskStatus.SUCCEEDED), eq("trace-test"), isNull(), isNull());
	}

	@Test
	void shouldFailEvenWhenChatReturnsFailureTextNormally() {
		stubResponse("Spring AI 调用失败，请稍后重试。", 0);

		submitAndCaptureWorker().run();

		verify(tasks).finish(anyString(), eq(AgentTaskStatus.FAILED), eq("trace-test"), eq("AGENT_EXECUTION_FAILED"),
				anyString());
	}

	@Test
	void shouldFailWhenRunWasNotPersisted() {
		AgentAskResponse response = new AgentAskResponse();
		response.setTraceId("trace-test");
		response.setAnswer("已有回答");
		when(chat.chat("解释 Java 接口", null)).thenReturn(response);

		// runs 默认返回 null，模拟 chat 捕获日志保存异常后继续返回。
		submitAndCaptureWorker().run();

		verify(tasks).finish(anyString(), eq(AgentTaskStatus.FAILED), eq("trace-test"), eq("RUN_LOG_MISSING"),
				anyString());
	}

	@Test
	void shouldRejectBeforeCallingAgentWhenExecutorIsFull() {
		doThrow(new TaskRejectedException("full")).when(executor).execute(any(Runnable.class));

		service.submit(request());

		verify(tasks).markRejected(anyString());
		verifyNoInteractions(chat, runs);
		verify(tasks, never()).markRunning(anyString());
	}

	@Test
	void shouldRejectInvalidModelBeforeCreatingTask() {
		AgentAskRequest request = request();
		request.setModelId("missing-model");

		assertThatThrownBy(() -> service.submit(request)).isInstanceOf(BusinessException.class);

		verify(tasks, never()).insert(any());
		verifyNoInteractions(executor, chat);
	}

	@Test
	void shouldRecordUnexpectedExecutionFailure() {
		when(chat.chat("解释 Java 接口", null)).thenThrow(new IllegalStateException("模拟执行异常"));

		submitAndCaptureWorker().run();

		verify(tasks).finish(anyString(), eq(AgentTaskStatus.FAILED), isNull(), eq("TASK_EXECUTION_ERROR"),
				anyString());
	}

	private Runnable submitAndCaptureWorker() {
		service.submit(request());

		ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
		verify(executor).execute(captor.capture());

		// 验证提交阶段还没有调用模型，由测试显式运行捕获的后台任务。
		verifyNoInteractions(chat);

		var order = inOrder(tasks, executor);
		order.verify(tasks).insert(any(AgentTask.class));
		order.verify(executor).execute(any(Runnable.class));

		return captor.getValue();
	}

	private void stubResponse(String answer, int success) {
		AgentAskResponse response = new AgentAskResponse();
		response.setTraceId("trace-test");
		response.setAnswer(answer);
		when(chat.chat("解释 Java 接口", null)).thenReturn(response);

		AgentRunLog run = new AgentRunLog();
		run.setTraceId("trace-test");
		run.setSuccess(success);
		when(runs.selectByTraceId("trace-test")).thenReturn(run);
	}

	private AgentAskRequest request() {
		AgentAskRequest request = new AgentAskRequest();
		request.setMessage("解释 Java 接口");
		return request;
	}

}