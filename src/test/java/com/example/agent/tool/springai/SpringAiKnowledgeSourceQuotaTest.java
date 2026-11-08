package com.example.agent.tool.springai;

import com.example.agent.dto.AgentTraceStep;
import com.example.agent.rag.KnowledgeChunk;
import com.example.agent.rag.KnowledgeRetrievalResult;
import com.example.agent.rag.KnowledgeRetriever;
import com.example.agent.rag.rerank.AgentKnowledgeReranker;
import com.example.agent.rag.rewrite.AgentQueryRewriter;
import com.example.agent.support.AgentJsonHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SpringAiKnowledgeSourceQuotaTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private final AgentJsonHelper helper = new AgentJsonHelper(mapper);

	@Test
	void shouldPreserveOrderAndKeepOldBehaviorWhenDisabled() {
		KnowledgeRetrievalResult candidates = result(chunk("a1", "a.md", 2), chunk("a2", "a.md", 2),
				chunk("b1", "b.md", 2), chunk("c1", "c.md", 2));

		var disabled = context().selectFreshKnowledge(candidates, 3, 20, 100, 0);
		var enabled = context().selectFreshKnowledge(candidates, 3, 20, 100, 1);

		assertThat(disabled.selected().chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("a1", "a2", "b1");

		assertThat(disabled.sourceLimitSkippedCount()).isZero();

		// 跳过 a2 后继续考虑后续来源，保留候选之间的相对顺序。
		assertThat(enabled.selected().chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("a1", "b1", "c1");

		assertThat(enabled.sourceLimitSkippedCount()).isEqualTo(1);

		// 筛选不会修改原始召回结果。
		assertThat(candidates.chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("a1", "a2", "b1", "c1");
	}

	@Test
	void shouldShareQuotaAcrossCallsWithoutRegisteringRejectedChunks() {
		SpringAiToolRunContext context = context();

		var first = context.selectFreshKnowledge(
				result(chunk("a1", "a.md", 2), chunk("a2", "a.md", 2), chunk("b1", "b.md", 2)), 5, 20, 100, 1);

		var second = context.selectFreshKnowledge(result(chunk("a2", "a.md", 2), chunk("c1", "c.md", 2)), 5, 20, 100,
				1);

		assertThat(first.selected().chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("a1", "b1");

		assertThat(second.selected().chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("c1");

		// a2 上次没有选入，本次仍是来源受限，不能误报为重复片段。
		assertThat(second.duplicateCount()).isZero();
		assertThat(second.sourceLimitSkippedCount()).isEqualTo(1);
		assertThat(second.totalChunks()).isEqualTo(3);
		assertThat(second.totalChars()).isEqualTo(6);

		assertThat(context.getPreparedKnowledgeChunkIds()).containsExactlyInAnyOrder("a1", "b1", "c1");
	}

	@Test
	void shouldNotConsumeSourceQuotaForOversizedOrOverBudgetChunks() {
		SpringAiToolRunContext context = context();

		var selection = context.selectFreshKnowledge(result(chunk("too-long", "a.md", 6), chunk("a1", "a.md", 4),
				chunk("b-large", "b.md", 4), chunk("b-small", "b.md", 2)), 5, 5, 7, 1);

		/*
		 * too-long 超过单片段限制，不占 a.md 配额； a1 选入后只剩 3 字符，b-large 放不下，但 b-small 可以选入。
		 */
		assertThat(selection.selected().chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("a1", "b-small");

		assertThat(selection.oversizedCount()).isEqualTo(1);
		assertThat(selection.budgetSkippedCount()).isEqualTo(1);
		assertThat(selection.sourceLimitSkippedCount()).isZero();
		assertThat(selection.totalChars()).isEqualTo(6);

		assertThat(context.getPreparedKnowledgeChunkIds()).containsExactlyInAnyOrder("a1", "b-small");
	}

	@Test
	void shouldGroupMissingSourcesTogether() {
		var selection = context().selectFreshKnowledge(
				result(chunk("unknown-1", null, 2), chunk("unknown-2", "  ", 2), chunk("a1", "a.md", 2)), 5, 20, 100,
				1);

		// 未知来源不被假定为多个独立文档。
		assertThat(selection.selected().chunks()).extracting(KnowledgeChunk::chunkId)
			.containsExactly("unknown-1", "a1");

		assertThat(selection.sourceLimitSkippedCount()).isEqualTo(1);
	}

	@Test
	void shouldApplyQuotaToFormalPromptAndTrace() throws Exception {
		KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

		SpringAiKnowledgeSupport support = new SpringAiKnowledgeSupport(retriever, helper, mapper, 5, 3, 20, 100, 1, 0,
				factory.getBeanProvider(AgentKnowledgeReranker.class),
				factory.getBeanProvider(AgentQueryRewriter.class));

		SpringAiToolRunContext context = context();

		when(retriever.retrieve(context.getUserMessage(), 5)).thenReturn(
				result(chunk("a1", "a.md", 2), chunk("a2", "a.md", 2), chunk("b1", "b.md", 2), chunk("c1", "c.md", 2)));

		// 调用正式知识准备入口，而不是只测试内部选择算法。
		String prompt = support.prepareInitialUserMessage(context);

		assertThat(prompt).contains("[知识:a1]", "[知识:b1]", "[知识:c1]").doesNotContain("[知识:a2]");

		assertThat(context.getPreparedKnowledgeChunkIds()).containsExactlyInAnyOrder("a1", "b1", "c1");

		AgentTraceStep contextStep = context.getSteps()
			.stream()
			.filter(step -> "KNOWLEDGE_CONTEXT".equals(step.getStepName()))
			.findFirst()
			.orElseThrow();

		JsonNode trace = mapper.readTree(contextStep.getOutput());

		assertThat(trace.path("maxChunksPerSource").asInt()).isEqualTo(1);
		assertThat(trace.path("sourceLimitSkippedCount").asInt()).isEqualTo(1);
		assertThat(trace.path("candidateCount").asInt()).isEqualTo(4);
		assertThat(trace.path("selected").path("chunks").size()).isEqualTo(3);

		verify(retriever).retrieve(context.getUserMessage(), 5);
		verifyNoMoreInteractions(retriever);
	}

	private SpringAiToolRunContext context() {
		return new SpringAiToolRunContext("source-quota-test", "请解释唯一键冲突", 120_000L, mapper, helper);
	}

	private KnowledgeRetrievalResult result(KnowledgeChunk... chunks) {
		return new KnowledgeRetrievalResult("hybrid", List.of(chunks));
	}

	private KnowledgeChunk chunk(String id, String source, int chars) {
		return new KnowledgeChunk(id, source, "测试文档", "测试章节", 0.8, "x".repeat(chars));
	}

}