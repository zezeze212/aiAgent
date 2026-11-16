package com.example.agent.service;

import com.example.agent.config.AgentProperties;
import com.example.agent.dto.AgentAskResponse;
import com.example.agent.exception.BusinessException;
import com.example.agent.model.AgentModelRegistry;
import com.example.agent.support.AgentJsonHelper;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.tool.springai.SpringAiDiagnosticTools;
import com.example.agent.tool.springai.SpringAiKnowledgeSupport;
import com.example.agent.tool.springai.SpringAiToolRunContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AgentModelRoutingTest {

	private ChatClient primary;

	private ChatClient second;

	private SpringAiKnowledgeSupport knowledgeSupport;

	private AgentLogService logService;

	private SpringAiToolChatService service;

	@BeforeEach
	void setUp() {
		primary = mock(ChatClient.class);
		second = mock(ChatClient.class);
		knowledgeSupport = mock(SpringAiKnowledgeSupport.class);
		logService = mock(AgentLogService.class);

		AgentModelRegistry registry = new AgentModelRegistry("deepseek-main",
				List.of(new AgentModelRegistry.Entry("deepseek-main", "test", "configured-primary", true, true,
						primary),
						new AgentModelRegistry.Entry("test-second", "test", "configured-second", true, true, second),
						new AgentModelRegistry.Entry("disabled-model", "test", "disabled", false, true, null),
						new AgentModelRegistry.Entry("no-tools", "test", "plain-chat", true, false, second)));

		ObjectMapper mapper = new ObjectMapper();
		AgentJsonHelper helper = new AgentJsonHelper(mapper);

		SpringAiDiagnosticTools tools = new SpringAiDiagnosticTools(mock(ToolRegistry.class), helper, mapper,
				knowledgeSupport);

		service = new SpringAiToolChatService(registry, tools, new AgentProperties(), mapper, helper, logService,
				knowledgeSupport);

		// 此测试聚焦模型路由；检索本身沿用已有测试。
		when(knowledgeSupport.prepareInitialUserMessage(any(SpringAiToolRunContext.class)))
			.thenAnswer(invocation -> invocation.<SpringAiToolRunContext>getArgument(0).getUserMessage());
	}

	@Test
	void shouldUseDefaultForMissingOrBlankId() {
		stubResponse(primary, "reported-primary");

		AgentAskResponse first = service.chat("解释唯一键冲突", null);
		AgentAskResponse secondResponse = service.chat("解释唯一键冲突", " ");

		assertThat(first.getModelExecution().modelId()).isEqualTo("deepseek-main");
		assertThat(first.getModelExecution().reportedModel()).isEqualTo("reported-primary");
		assertThat(first.getModelExecution().configuredModel()).isEqualTo("configured-primary");
		assertThat(secondResponse.getModelExecution().requestedModelId()).isNull();

		verify(primary, times(2)).prompt();
		verifyNoInteractions(second);
	}

	@Test
	void shouldRouteExplicitSelectionAndKeepToolContext() {
		ChatClient.ChatClientRequestSpec request = stubResponse(second, "reported-second");

		AgentAskResponse response = service.chat("请查询 agent_run_log 的实际表结构", "test-second");

		assertThat(response.getModelExecution().modelId()).isEqualTo("test-second");
		assertThat(response.getModelExecution().callStatus()).isEqualTo("RETURNED");
		assertThat(response.getModelExecution().providerResponseId()).isEqualTo("response-test");

		verify(request).system(SpringAiToolChatService.SYSTEM_PROMPT);
		// 明确匹配 List<ToolCallback>，避免与其他 toolCallbacks 重载产生歧义。
		verify(request).toolCallbacks(ArgumentMatchers.<List<ToolCallback>>argThat(callbacks -> callbacks.stream()
			.anyMatch(callback -> "getTableSchema".equals(callback.getToolDefinition().name()))));
		verify(request).toolContext(
				argThat(context -> context.get(SpringAiToolRunContext.CONTEXT_KEY) instanceof SpringAiToolRunContext));

		verifyNoInteractions(primary);
		verify(logService).saveRunLog(eq("请查询 agent_run_log 的实际表结构"), same(response));

		assertThat(response.getSteps()).anySatisfy(step -> {
			assertThat(step.getStepName()).isEqualTo("MODEL_EXECUTION");
			assertThat(step.getOutput()).contains("test-second", "reported-second");
		});
	}

	@Test
	void shouldRejectInvalidSelectionsBeforeRagOrModelCalls() {
		Map<String, Integer> rejected = Map.of("missing-model", 400, "disabled-model", 503, "no-tools", 400);

		rejected.forEach((id, code) -> assertThatThrownBy(() -> service.chat("解释唯一键冲突", id)).isInstanceOfSatisfying(
				BusinessException.class, exception -> assertThat(exception.getCode()).isEqualTo(code)));

		verifyNoInteractions(primary, second, knowledgeSupport, logService);
	}

	@Test
	void shouldNotFallbackWhenSelectedClientFails() {
		when(second.prompt()).thenThrow(new IllegalStateException("模拟调用失败"));

		AgentAskResponse response = service.chat("解释唯一键冲突", "test-second");

		assertThat(response.getModelExecution().modelId()).isEqualTo("test-second");
		assertThat(response.getModelExecution().callStatus()).isEqualTo("FAILED");
		assertThat(response.getModelExecution().reportedModel()).isNull();

		assertThat(response.getSteps()).anySatisfy(step -> {
			assertThat(step.getStepName()).isEqualTo("SPRING_AI_CHAT");
			assertThat(step.getSuccess()).isFalse();
		});

		verifyNoInteractions(primary);
	}

	@Test
	void shouldNotPretendClarificationCalledAnswerModel() {
		AgentAskResponse response = service.chat("出现 Unknown column，请结合真实表结构诊断", "deepseek-main");

		assertThat(response.getModelExecution().callStatus()).isEqualTo("NOT_CALLED");
		assertThat(response.getModelExecution().reportedModel()).isNull();
		verifyNoInteractions(primary, second);
	}

	@Test
	void shouldNotInventReportedModelWhenMetadataIsMissing() {
		stubResponse(primary, "");

		AgentAskResponse response = service.chat("解释唯一键冲突");

		assertThat(response.getModelExecution().configuredModel()).isEqualTo("configured-primary");
		assertThat(response.getModelExecution().reportedModel()).isNull();
		assertThat(response.getModelExecution().callStatus()).isEqualTo("RETURNED");
	}

	private ChatClient.ChatClientRequestSpec stubResponse(ChatClient client, String reportedModel) {

		ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
		ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);

		ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("测试回答"))),
				ChatResponseMetadata.builder().model(reportedModel).id("response-test").build());

		when(client.prompt()).thenReturn(request);
		when(request.call()).thenReturn(call);
		when(call.chatResponse()).thenReturn(response);

		return request;
	}

}