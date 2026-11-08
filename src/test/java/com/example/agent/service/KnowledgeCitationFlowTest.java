package com.example.agent.service;

import com.example.agent.config.AgentProperties;
import com.example.agent.controller.AgentController;
import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.KnowledgeCitationReport;
import com.example.agent.entity.AgentStepLog;
import com.example.agent.mapper.AgentRunLogMapper;
import com.example.agent.mapper.AgentStepLogMapper;
import com.example.agent.rag.KnowledgeChunk;
import com.example.agent.rag.KnowledgeRetrievalResult;
import com.example.agent.support.AgentJsonHelper;
import com.example.agent.support.AgentTraceSanitizer;
import com.example.agent.support.TraceContentSanitizer;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.tool.springai.SpringAiDiagnosticTools;
import com.example.agent.tool.springai.SpringAiKnowledgeSupport;
import com.example.agent.tool.springai.SpringAiToolRunContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeCitationFlowTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private final AgentJsonHelper helper = new AgentJsonHelper(mapper);

	@Test
	void shouldCarryCitationsThroughServiceAndPersistAuditStep() throws Exception {
		ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_SELF);
		ChatClient client = mock(ChatClient.class);
		ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
		ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);

		when(builder.build()).thenReturn(client);
		when(client.prompt()).thenReturn(request);
		when(request.call()).thenReturn(call);
		when(call.content()).thenReturn("原因说明 [知识:c1]。");

		SpringAiKnowledgeSupport knowledgeSupport = mock(SpringAiKnowledgeSupport.class);

		/*
		 * 此测试关注 Service 后半段的引用和日志连接。 在知识准备入口放入确定的已选片段，不调用真实检索或模型。
		 */
		when(knowledgeSupport.prepareInitialUserMessage(any(SpringAiToolRunContext.class))).thenAnswer(invocation -> {
			SpringAiToolRunContext context = invocation.getArgument(0);

			context.selectFreshKnowledge(result("唯一键冲突说明"), 5, 2000, 6000);

			return context.getUserMessage();
		});

		AgentRunLogMapper runMapper = mock(AgentRunLogMapper.class);
		AgentStepLogMapper stepMapper = mock(AgentStepLogMapper.class);

		// 使用真实日志服务，Mapper 用内存 mock，不连接数据库。
		AgentLogService logService = new AgentLogService(runMapper, stepMapper, helper, new TraceContentSanitizer());

		SpringAiDiagnosticTools tools = new SpringAiDiagnosticTools(mock(ToolRegistry.class), helper, mapper,
				knowledgeSupport);

		SpringAiToolChatService service = new SpringAiToolChatService(builder, tools, new AgentProperties(), mapper,
				helper, logService, knowledgeSupport);

		AgentAskResponse response = service.chat("请解释唯一键冲突");

		assertThat(response.getKnowledgeCitations().status()).isEqualTo("VALID_IDS");

		assertThat(response.getKnowledgeCitations().citations()).extracting(KnowledgeCitationReport.Citation::chunkId)
			.containsExactly("c1");

		ArgumentCaptor<AgentStepLog> captor = ArgumentCaptor.forClass(AgentStepLog.class);

		verify(stepMapper, atLeastOnce()).insert(captor.capture());

		AgentStepLog citationStep = captor.getAllValues()
			.stream()
			.filter(step -> "KNOWLEDGE_CITATION_CHECK".equals(step.getStepName()))
			.findFirst()
			.orElseThrow();

		JsonNode persisted = mapper.readTree(citationStep.getOutputText());

		assertThat(persisted.path("status").asText()).isEqualTo("VALID_IDS");
		assertThat(persisted.path("citations").get(0).path("source").asText()).isEqualTo("docs/knowledge/test.md");
		assertThat(persisted.path("citations").get(0).path("content").asText()).isEqualTo("唯一键冲突说明");
		assertThat(persisted.path("semanticSupportChecked").asBoolean()).isFalse();
	}

	@Test
	void shouldReturnSanitizedCitationJsonWithoutChangingEvidence() throws Exception {
		SpringAiToolRunContext context = new SpringAiToolRunContext("http-citation-test", "请解释唯一键冲突", 120_000L, mapper,
				helper);

		context.selectFreshKnowledge(result("password=example-secret"), 5, 2000, 6000);

		String answer = "说明见 [知识:c1]。";
		KnowledgeCitationReport originalReport = context.auditKnowledgeCitations(answer);

		AgentAskResponse response = context.buildResponse(answer, 10L);

		SpringAiToolChatService service = mock(SpringAiToolChatService.class);

		when(service.chat("请解释唯一键冲突")).thenReturn(response);

		AgentTraceSanitizer sanitizer = new AgentTraceSanitizer(new TraceContentSanitizer(), helper);

		AgentController controller = new AgentController(service, mock(ToolRegistry.class), mock(AgentLogService.class),
				sanitizer);

		MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

		mvc.perform(post("/agent/ask").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"请解释唯一键冲突\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.answer").value(answer))
			.andExpect(jsonPath("$.data.knowledgeCitations.status").value("VALID_IDS"))
			.andExpect(jsonPath("$.data.knowledgeCitations.citations[0].chunkId").value("c1"))
			.andExpect(jsonPath("$.data.knowledgeCitations.citations[0].content").value("password=***"))
			.andExpect(jsonPath("$.data.knowledgeCitations.citations[0].displayModified").value(true))
			.andExpect(jsonPath("$.data.knowledgeCitations.semanticSupportChecked").value(false));

		// HTTP 出口替换的是展示结果，没有改写原来的不可变报告。
		assertThat(originalReport.citations().get(0).content()).isEqualTo("password=example-secret");

		// 再次从请求内快照关联，仍然能取得原始证据。
		assertThat(context.auditKnowledgeCitations(answer).citations().get(0).content())
			.isEqualTo("password=example-secret");
	}

	private KnowledgeRetrievalResult result(String content) {
		KnowledgeChunk chunk = new KnowledgeChunk("c1", "docs/knowledge/test.md", "测试标题", "测试章节", 0.8, content);

		return new KnowledgeRetrievalResult("hybrid", List.of(chunk));
	}

}