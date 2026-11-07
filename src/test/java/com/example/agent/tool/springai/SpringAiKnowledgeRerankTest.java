package com.example.agent.tool.springai;

import com.example.agent.dto.AgentTraceStep;
import com.example.agent.exception.BusinessException;
import com.example.agent.rag.KnowledgeChunk;
import com.example.agent.rag.KnowledgeRetrievalResult;
import com.example.agent.rag.KnowledgeRetriever;
import com.example.agent.rag.rerank.AgentKnowledgeReranker;
import com.example.agent.rag.rerank.SiliconFlowReranker;
import com.example.agent.support.AgentJsonHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SpringAiKnowledgeRerankTest {

	private KnowledgeRetriever retriever;

	private SiliconFlowReranker remote;

	private ObjectMapper mapper;

	private AgentJsonHelper helper;

	private KnowledgeRetrievalResult candidates;

	@BeforeEach
	void setUp() {
		retriever = mock(KnowledgeRetriever.class);
		remote = mock(SiliconFlowReranker.class);
		mapper = new ObjectMapper();
		helper = new AgentJsonHelper(mapper);

		candidates = new KnowledgeRetrievalResult("vector", List.of(chunk("c1", 0.8), chunk("c2", 0.7)));
	}

	@Test
	void shouldApplyRankingBeforeContextBudget() throws Exception {
		SpringAiToolRunContext context = context("请解释唯一键冲突");
		when(retriever.retrieve(context.getUserMessage(), 5)).thenReturn(candidates);
		applyRanking(context.getUserMessage());

		// 预算只能容纳一个片段；必须选入重排后的 c2。
		String prompt = support(true, 1).prepareInitialUserMessage(context);

		assertThat(prompt).contains("[知识:c2]").doesNotContain("[知识:c1]");
		assertThat(context.getPreparedKnowledgeChunkIds()).containsExactly("c2");
		assertThat(context.getUserMessage()).isEqualTo("请解释唯一键冲突");

		AgentTraceStep step = lastRerankStep(context);
		JsonNode trace = mapper.readTree(step.getOutput());

		assertThat(step.getSuccess()).isTrue();
		assertThat(trace.path("status").asText()).isEqualTo("APPLIED");
		assertThat(trace.path("scoreType").asText()).isEqualTo("RERANK_RELEVANCE");
		assertThat(trace.path("retriever").asText()).isEqualTo("vector");
		assertThat(trace.path("inputChunkIds").get(0).asText()).isEqualTo("c1");
		assertThat(trace.path("outputRanking").get(0).path("chunkId").asText()).isEqualTo("c2");

		// 原始召回对象保持原顺序和原分数。
		assertThat(candidates.chunks().get(0).chunkId()).isEqualTo("c1");
		assertThat(candidates.chunks().get(0).score()).isEqualTo(0.8);
	}

	@Test
	void shouldKeepOriginalOrderWhenDisabled() {
		SpringAiToolRunContext context = context("请解释唯一键冲突");
		when(retriever.retrieve(context.getUserMessage(), 5)).thenReturn(candidates);

		String prompt = support(false, 1).prepareInitialUserMessage(context);

		assertThat(prompt).contains("[知识:c1]").doesNotContain("[知识:c2]");
		assertThat(context.getSteps()).extracting(AgentTraceStep::getStepName).doesNotContain("KNOWLEDGE_RERANK");
		verifyNoInteractions(remote);
	}

	@Test
	void shouldKeepRetrievedEvidenceWhenRemoteTimesOut() throws Exception {
		SpringAiToolRunContext context = context("请解释唯一键冲突");
		when(retriever.retrieve(context.getUserMessage(), 5)).thenReturn(candidates);
		when(remote.rerank(context.getUserMessage(), candidates.chunks()))
			.thenThrow(new BusinessException(504, "模拟超时"));

		String prompt = support(true, 1).prepareInitialUserMessage(context);

		// 远程失败不应清空已经成功召回的证据。
		assertThat(prompt).contains("[知识:c1]").doesNotContain("[知识:c2]");

		AgentTraceStep step = lastRerankStep(context);
		JsonNode trace = mapper.readTree(step.getOutput());

		assertThat(step.getSuccess()).isFalse();
		assertThat(trace.path("status").asText()).isEqualTo("FALLBACK");
		assertThat(trace.path("errorCode").asInt()).isEqualTo(504);
		assertThat(trace.path("scoreType").asText()).isEqualTo("RETRIEVAL_SCORE");

		assertThat(context.getSteps()).anySatisfy(retrievalStep -> {
			assertThat(retrievalStep.getStepName()).isEqualTo("KNOWLEDGE_RETRIEVAL");
			assertThat(retrievalStep.getSuccess()).isTrue();
		});
	}

	@Test
	void shouldKeepDeduplicationAcrossAppliedAndFallbackResults() {
		SpringAiKnowledgeSupport support = support(true, 5);
		SpringAiToolRunContext context = context("请解释唯一键冲突");
		String firstQuery = context.getUserMessage() + System.lineSeparator() + "第一次工具结果";
		String secondQuery = context.getUserMessage() + System.lineSeparator() + "第二次工具结果";

		when(retriever.retrieve(firstQuery, 5)).thenReturn(candidates);
		when(retriever.retrieve(secondQuery, 5)).thenReturn(candidates);
		applyRanking(firstQuery);
		when(remote.rerank(secondQuery, candidates.chunks())).thenThrow(new BusinessException(502, "模拟上游失败"));

		KnowledgeRetrievalResult first = support.retrievePostToolKnowledge(context, "第一次工具结果");
		KnowledgeRetrievalResult second = support.retrievePostToolKnowledge(context, "第二次工具结果");

		// 返回的片段列表承担排序语义，必须保留重排后的顺序。
		assertThat(first.chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("c2", "c1");

		assertThat(first.retriever()).isEqualTo("vector");

		// 第二次候选已经使用过，不应再次注入上下文。
		assertThat(second.chunks()).isEmpty();

		// 引用登记集合只表达“哪些 ID 已准备”，不承诺遍历顺序。
		// 同时检查没有缺失 ID，也没有额外 ID。
		assertThat(context.getPreparedKnowledgeChunkIds()).containsExactlyInAnyOrder("c1", "c2");
	}

	@Test
	void shouldSkipLongPostToolQueryWithoutTruncatingRetrieval() throws Exception {
		SpringAiToolRunContext context = context("请解释唯一键冲突");
		String toolResult = "x".repeat(2001);
		String fullQuery = context.getUserMessage() + System.lineSeparator() + toolResult;

		when(retriever.retrieve(fullQuery, 5)).thenReturn(candidates);

		KnowledgeRetrievalResult result = support(true, 5).retrievePostToolKnowledge(context, toolResult);

		assertThat(result.chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("c1", "c2");
		verify(retriever).retrieve(fullQuery, 5);
		verifyNoInteractions(remote);

		JsonNode trace = mapper.readTree(lastRerankStep(context).getOutput());
		assertThat(trace.path("status").asText()).isEqualTo("SKIPPED");
		assertThat(trace.path("reason").asText()).isEqualTo("QUERY_OUT_OF_RANGE");
	}

	@Test
	void shouldSkipEmptyAndSingleCandidateWithoutRemoteCall() {
		AgentKnowledgeReranker processor = new AgentKnowledgeReranker(remote);

		var empty = processor.rerank("查询", KnowledgeRetrievalResult.empty("vector"));
		var single = processor.rerank("查询", new KnowledgeRetrievalResult("vector", List.of(chunk("c1", 0.8))));

		assertThat(empty.trace().status()).isEqualTo("SKIPPED");
		assertThat(single.trace().status()).isEqualTo("SKIPPED");
		assertThat(single.result().chunks()).hasSize(1);
		verifyNoInteractions(remote);
	}

	@Test
	void shouldNotHideLocalProgrammingErrorsAsFallback() {
		when(remote.rerank("查询", candidates.chunks())).thenThrow(new IllegalStateException("模拟本地实现错误"));

		AgentKnowledgeReranker processor = new AgentKnowledgeReranker(remote);

		assertThatThrownBy(() -> processor.rerank("查询", candidates)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void shouldPropagateInterruptionInsteadOfContinuingWithFallback() {
		when(remote.rerank("查询", candidates.chunks())).thenAnswer(invocation -> {
			// 模拟适配器恢复中断标志后抛出异常的行为。
			Thread.currentThread().interrupt();
			throw new BusinessException(503, "模拟中断");
		});

		AgentKnowledgeReranker processor = new AgentKnowledgeReranker(remote);

		try {
			assertThatThrownBy(() -> processor.rerank("查询", candidates)).isInstanceOf(BusinessException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			// 清理测试线程，避免影响后续测试。
			Thread.interrupted();
		}
	}

	private SpringAiKnowledgeSupport support(boolean enabled, int maxChunks) {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

		if (enabled) {
			factory.registerSingleton("agentKnowledgeReranker", new AgentKnowledgeReranker(remote));
		}

		return new SpringAiKnowledgeSupport(retriever, helper, mapper, 5, maxChunks, 2000, 6000,
				factory.getBeanProvider(AgentKnowledgeReranker.class));
	}

	private SpringAiToolRunContext context(String message) {
		return new SpringAiToolRunContext("rerank-test", message, 120_000L, mapper, helper);
	}

	private void applyRanking(String query) {
		when(remote.rerank(query, candidates.chunks())).thenReturn(new SiliconFlowReranker.RerankResult(
				List.of(chunk("c2", 0.95), chunk("c1", 0.2)), 12.0, "request-test"));
	}

	private KnowledgeChunk chunk(String id, double score) {
		return new KnowledgeChunk(id, "docs/knowledge/test.md", "测试文档", "测试章节", score, "证据正文-" + id);
	}

	private AgentTraceStep lastRerankStep(SpringAiToolRunContext context) {
		return context.getSteps()
			.stream()
			.filter(step -> "KNOWLEDGE_RERANK".equals(step.getStepName()))
			.reduce((first, second) -> second)
			.orElseThrow();
	}

}