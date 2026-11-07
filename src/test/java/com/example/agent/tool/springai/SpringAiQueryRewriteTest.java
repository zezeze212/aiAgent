package com.example.agent.tool.springai;

import com.example.agent.dto.AgentTraceStep;
import com.example.agent.exception.BusinessException;
import com.example.agent.rag.KnowledgeChunk;
import com.example.agent.rag.KnowledgeRetrievalResult;
import com.example.agent.rag.KnowledgeRetriever;
import com.example.agent.rag.rerank.AgentKnowledgeReranker;
import com.example.agent.rag.rerank.SiliconFlowReranker;
import com.example.agent.rag.rewrite.AgentQueryRewriter;
import com.example.agent.support.AgentJsonHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.client.RestClientException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SpringAiQueryRewriteTest {

	private ChatModel model;

	private KnowledgeRetriever retriever;

	private SiliconFlowReranker remoteReranker;

	private ObjectMapper mapper;

	private AgentJsonHelper helper;

	private AgentQueryRewriter rewriter;

	private KnowledgeRetrievalResult candidates;

	@BeforeEach
	void setUp() {
		model = mock(ChatModel.class);
		retriever = mock(KnowledgeRetriever.class);
		remoteReranker = mock(SiliconFlowReranker.class);
		mapper = new ObjectMapper();
		helper = new AgentJsonHelper(mapper);
		rewriter = new AgentQueryRewriter(model, mapper, "test-model");

		candidates = new KnowledgeRetrievalResult("hybrid", List.of(chunk("c1"), chunk("c2")));
	}

	@Test
	void shouldRewriteRetrievalButKeepOriginalQuestionForRerankAndAnswer() {
		String original = "插入时提示值已经存在，唯一键那种，通常怎么查？";
		String rewritten = "MySQL Duplicate entry 唯一键冲突排查";

		respond("""
				{"query":"MySQL Duplicate entry 唯一键冲突排查"}
				""", "stop");

		when(retriever.retrieve(rewritten, 5)).thenReturn(candidates);
		when(remoteReranker.rerank(original, candidates.chunks()))
			.thenReturn(new SiliconFlowReranker.RerankResult(List.of(chunk("c2"), chunk("c1")), 10.0, "test-request"));

		SpringAiToolRunContext context = context(original);

		// 预算只允许一个片段，检查改写、重排、预算的衔接。
		String prompt = support(true, true).prepareInitialUserMessage(context);

		verify(retriever).retrieve(rewritten, 5);
		verify(remoteReranker).rerank(original, candidates.chunks());
		verify(model).call(any(Prompt.class));

		assertThat(context.getUserMessage()).isEqualTo(original);
		assertThat(prompt).contains(original, "[知识:c2]").doesNotContain("[知识:c1]", rewritten);

		assertThat(context.getSteps()).extracting(AgentTraceStep::getStepName)
			.containsSubsequence("KNOWLEDGE_QUERY_REWRITE", "KNOWLEDGE_RETRIEVAL", "KNOWLEDGE_RERANK",
					"KNOWLEDGE_CONTEXT");
	}

	@Test
	void shouldNotCallRewriteModelWhenDisabled() {
		String original = "请解释唯一键冲突";
		when(retriever.retrieve(original, 5)).thenReturn(candidates);

		SpringAiToolRunContext context = context(original);
		support(false, false).prepareInitialUserMessage(context);

		verify(retriever).retrieve(original, 5);
		verifyNoInteractions(model);
		assertThat(context.getSteps()).extracting(AgentTraceStep::getStepName)
			.doesNotContain("KNOWLEDGE_QUERY_REWRITE");
	}

	@Test
	void shouldUseOriginalQueryWhenModelIsUnavailable() {
		String original = "请解释唯一键冲突";
		when(model.call(any(Prompt.class))).thenThrow(new RestClientException("模拟读取超时"));
		when(retriever.retrieve(original, 5)).thenReturn(candidates);

		SpringAiToolRunContext context = context(original);
		String prompt = support(true, false).prepareInitialUserMessage(context);

		verify(retriever).retrieve(original, 5);
		assertThat(prompt).contains("[知识:c1]");

		assertThat(context.getSteps()).anySatisfy(step -> {
			assertThat(step.getStepName()).isEqualTo("KNOWLEDGE_QUERY_REWRITE");
			assertThat(step.getSuccess()).isFalse();
			assertThat(step.getOutput()).contains("FALLBACK", "MODEL_UNAVAILABLE");
		});

		assertThat(context.getSteps()).anySatisfy(step -> {
			assertThat(step.getStepName()).isEqualTo("KNOWLEDGE_RETRIEVAL");
			assertThat(step.getSuccess()).isTrue();
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "不是 JSON", "[]", "{\"query\":\"\"}", "{\"query\":123}",
			"{\"query\":\"model_name\",\"extra\":true}", "{\"query\":\"model_name\"} {\"query\":\"other\"}",
			"{\"query\":\"model_name\",\"query\":\"other\"}", "{\"query\":\"model_name\\n排查\"}",
			"{\"query\":\"Unknown column 排查\"}" })
	void shouldRejectInvalidResponseOrLostIdentifier(String response) {
		respond(response, "stop");

		String original = "Unknown column 'model_name'";
		AgentQueryRewriter.Outcome outcome = rewriter.rewrite(original);

		assertThat(outcome.query()).isEqualTo(original);
		assertThat(outcome.trace().status()).isEqualTo("FALLBACK");
	}

	@Test
	void shouldRejectOverlongQuery() {
		respond("{\"query\":\"" + "查".repeat(513) + "\"}", "stop");

		AgentQueryRewriter.Outcome outcome = rewriter.rewrite("请解释唯一键冲突");

		assertThat(outcome.trace().status()).isEqualTo("FALLBACK");
		assertThat(outcome.trace().reason()).isEqualTo("INVALID_RESPONSE");
	}

	@Test
	void shouldRejectTruncatedGenerationEvenWhenJsonLooksValid() {
		respond("{\"query\":\"唯一键冲突排查\"}", "length");

		AgentQueryRewriter.Outcome outcome = rewriter.rewrite("请解释唯一键冲突");

		assertThat(outcome.trace().status()).isEqualTo("FALLBACK");
		assertThat(outcome.trace().reason()).isEqualTo("INCOMPLETE_RESPONSE");
	}

	@Test
	void shouldSkipOverlongInputWithoutCallingModel() {
		String original = "查".repeat(2001);

		AgentQueryRewriter.Outcome outcome = rewriter.rewrite(original);

		assertThat(outcome.query()).isEqualTo(original);
		assertThat(outcome.trace().status()).isEqualTo("SKIPPED");
		verifyNoInteractions(model);
	}

	@Test
	void shouldReportUnchangedQuery() {
		respond("{\"query\":\"唯一键冲突\"}", "stop");

		AgentQueryRewriter.Outcome outcome = rewriter.rewrite("唯一键冲突");

		assertThat(outcome.query()).isEqualTo("唯一键冲突");
		assertThat(outcome.trace().status()).isEqualTo("UNCHANGED");
	}

	@Test
	void shouldKeepPostToolQueryUnchanged() {
		SpringAiToolRunContext context = context("请解释唯一键冲突");
		String query = context.getUserMessage() + System.lineSeparator() + "工具证据";

		when(retriever.retrieve(query, 5)).thenReturn(candidates);

		support(true, false).retrievePostToolKnowledge(context, "工具证据");

		verify(retriever).retrieve(query, 5);
		verifyNoInteractions(model);
	}

	@Test
	void shouldSkipRewriteForDeterministicClarification() {
		String original = "Unknown column 'model_name'，请结合真实表结构诊断";
		when(retriever.retrieve(original, 5)).thenReturn(candidates);

		support(true, false).prepareInitialUserMessage(context(original));

		verifyNoInteractions(model);
		verify(retriever).retrieve(original, 5);
	}

	@Test
	void shouldNotHideProgrammingErrors() {
		when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("模拟本地程序错误"));

		assertThatThrownBy(() -> rewriter.rewrite("唯一键冲突")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void shouldPropagateInterruption() {
		when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
			Thread.currentThread().interrupt();
			throw new RestClientException("模拟中断");
		});

		try {
			assertThatThrownBy(() -> rewriter.rewrite("唯一键冲突")).isInstanceOf(BusinessException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			// 清除测试线程中断标志，避免影响其他测试。
			Thread.interrupted();
		}
	}

	private void respond(String body, String finishReason) {
		Generation generation = new Generation(new AssistantMessage(body),
				ChatGenerationMetadata.builder().finishReason(finishReason).build());

		when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(generation)));
	}

	private SpringAiKnowledgeSupport support(boolean rewriteEnabled, boolean rerankEnabled) {

		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

		if (rewriteEnabled) {
			factory.registerSingleton("agentQueryRewriter", rewriter);
		}

		if (rerankEnabled) {
			factory.registerSingleton("agentKnowledgeReranker", new AgentKnowledgeReranker(remoteReranker));
		}

		return new SpringAiKnowledgeSupport(retriever, helper, mapper, 5, 1, 2000, 6000,
				factory.getBeanProvider(AgentKnowledgeReranker.class),
				factory.getBeanProvider(AgentQueryRewriter.class));
	}

	private SpringAiToolRunContext context(String message) {
		return new SpringAiToolRunContext("rewrite-test", message, 120_000L, mapper, helper);
	}

	private KnowledgeChunk chunk(String id) {
		return new KnowledgeChunk(id, "docs/knowledge/test.md", "测试文档", "测试章节", 0.8, "测试证据-" + id);
	}

}