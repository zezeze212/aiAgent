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
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SpringAiKnowledgeTokenBudgetTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private final AgentJsonHelper helper = new AgentJsonHelper(mapper);

	@Test
	void shouldShareTokenBudgetAcrossCallsAndSkipWholeChunks() {
		SpringAiToolRunContext context = context();

		Map<String, Integer> costs = Map.of("c1", 6, "c2", 5, "c3", 4, "c4", 1);

		KnowledgeTokenBudget budget = new KnowledgeTokenBudget(10, "TEST_FIXED", "TEST_CHUNKS",
				chunk -> costs.get(chunk.chunkId()));

		var first = context.selectFreshKnowledge(result(chunk("c1", "a.md"), chunk("c2", "b.md"), chunk("c3", "c.md")),
				10, 100, 1000, 0, budget);

		// c2 放不下，继续选择 c3；不截断任何片段。
		assertThat(first.selected().chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("c1", "c3");

		assertThat(first.tokenBudget().addedEstimatedTokens()).isEqualTo(10);
		assertThat(first.tokenBudget().totalEstimatedTokens()).isEqualTo(10);
		assertThat(first.tokenBudget().skippedCount()).isEqualTo(1);

		var second = context.selectFreshKnowledge(result(chunk("c1", "a.md"), chunk("c2", "b.md"), chunk("c4", "d.md")),
				10, 100, 1000, 0, budget);

		assertThat(second.selected().chunks()).isEmpty();
		assertThat(second.duplicateCount()).isEqualTo(1);

		// c2 上次没有选入，因此本次不能被当作重复片段。
		assertThat(second.tokenBudget().skippedCount()).isEqualTo(2);
		assertThat(second.tokenBudget().addedEstimatedTokens()).isZero();
		assertThat(second.tokenBudget().totalEstimatedTokens()).isEqualTo(10);

		assertThat(context.getPreparedKnowledgeChunkIds()).containsExactlyInAnyOrder("c1", "c3");
	}

	@Test
	void shouldNotConsumeSourceQuotaWhenTokenBudgetRejectsChunk() {
		SpringAiToolRunContext context = context();

		Map<String, Integer> costs = Map.of("a1", 2, "a2", 2, "b1", 2, "b2", 1);

		KnowledgeTokenBudget budget = new KnowledgeTokenBudget(3, "TEST_FIXED", "TEST_CHUNKS",
				chunk -> costs.get(chunk.chunkId()));

		var selection = context.selectFreshKnowledge(
				result(chunk("a1", "a.md"), chunk("a2", "a.md"), chunk("b1", "b.md"), chunk("b2", "b.md")), 10, 100,
				1000, 1, budget);

		/*
		 * a2 被来源配额排除； b1 因 token 放不下而被排除，不应占用 b.md 的来源名额； 因此 b2 仍然能够选入。
		 */
		assertThat(selection.selected().chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("a1", "b2");

		assertThat(selection.sourceLimitSkippedCount()).isEqualTo(1);
		assertThat(selection.tokenBudget().skippedCount()).isEqualTo(1);
		assertThat(selection.tokenBudget().totalEstimatedTokens()).isEqualTo(3);

		assertThat(context.getPreparedKnowledgeChunkIds()).containsExactlyInAnyOrder("a1", "b2");
	}

	@Test
	void shouldNotCallCounterWhenDisabled() {
		KnowledgeTokenBudget disabled = new KnowledgeTokenBudget(0, "TEST", "DISABLED", chunk -> {
			throw new AssertionError("关闭预算时不应执行 token 计数");
		});

		var selection = context().selectFreshKnowledge(result(chunk("c1", "a.md"), chunk("c2", "a.md")), 5, 100, 1000,
				0, disabled);

		assertThat(selection.selected().chunks()).hasSize(2);
		assertThat(selection.tokenBudget().enabled()).isFalse();
		assertThat(selection.tokenBudget().totalEstimatedTokens()).isZero();
	}

	@Test
	void shouldIncludePreDecisionCitationAndMetadataInTokenCost() throws Exception {
		KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
		SpringAiToolRunContext context = context();
		KnowledgeChunk candidate = chunk("c1", "docs/knowledge/test.md");

		int bodyOnlyTokens = new JTokkitTokenCountEstimator().estimate(candidate.content());

		assertThat(bodyOnlyTokens).isPositive();

		SpringAiKnowledgeSupport support = support(retriever, bodyOnlyTokens);

		when(retriever.retrieve(context.getUserMessage(), 5)).thenReturn(result(candidate));

		String prompt = support.prepareInitialUserMessage(context);

		/*
		 * 预算只能容纳正文，不能容纳引用、来源等完整片段包装。 如果实现错误地只统计 content，这个片段就会被选入。
		 */
		assertThat(prompt).doesNotContain("[知识:c1]");
		assertThat(context.getPreparedKnowledgeChunkIds()).isEmpty();

		JsonNode trace = lastContextTrace(context).path("tokenBudget");

		assertThat(trace.path("enabled").asBoolean()).isTrue();
		assertThat(trace.path("estimatorName").asText()).isEqualTo("CL100K_BASE_PROXY");
		assertThat(trace.path("countingScope").asText()).isEqualTo("PRE_FORMATTED_CHUNK_SUM");
		assertThat(trace.path("skippedCount").asInt()).isEqualTo(1);
		assertThat(trace.path("totalEstimatedTokens").asInt()).isZero();
	}

	@Test
	void shouldCountPostToolChunkJsonAndPreserveToolEvidence() throws Exception {
		KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
		SpringAiToolRunContext context = context();
		SpringAiKnowledgeSupport support = support(retriever, 2000);

		String toolPayload = """
				{"success":true,"result":{"actualColumn":"id"}}
				""";

		String query = context.getUserMessage() + System.lineSeparator() + toolPayload;

		when(retriever.retrieve(query, 5)).thenReturn(result(chunk("c1", "docs/knowledge/test.md")));

		KnowledgeRetrievalResult selected = support.retrievePostToolKnowledge(context, toolPayload);

		JsonNode output = mapper.readTree(support.appendKnowledgeToToolPayload(toolPayload, selected));

		JsonNode sentChunk = output.path("knowledge").path("chunks").get(0);

		// 从实际附加到工具结果的片段 JSON 计算期望成本。
		int expectedTokens = new JTokkitTokenCountEstimator().estimate(mapper.writeValueAsString(sentChunk));

		JsonNode trace = lastContextTrace(context).path("tokenBudget");

		assertThat(trace.path("countingScope").asText()).isEqualTo("POST_JSON_CHUNK_SUM");

		assertThat(trace.path("addedEstimatedTokens").asInt()).isEqualTo(expectedTokens);

		assertThat(trace.path("totalEstimatedTokens").asInt()).isEqualTo(expectedTokens);

		assertThat(output.path("result")).isEqualTo(mapper.readTree(toolPayload).path("result"));
	}

	private SpringAiKnowledgeSupport support(KnowledgeRetriever retriever, int maxEstimatedTokens) {

		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

		return new SpringAiKnowledgeSupport(retriever, helper, mapper, 5, 5, 2000, 6000, 0, maxEstimatedTokens,
				factory.getBeanProvider(AgentKnowledgeReranker.class),
				factory.getBeanProvider(AgentQueryRewriter.class));
	}

	private JsonNode lastContextTrace(SpringAiToolRunContext context) throws Exception {
		AgentTraceStep step = context.getSteps()
			.stream()
			.filter(item -> "KNOWLEDGE_CONTEXT".equals(item.getStepName()))
			.reduce((previous, current) -> current)
			.orElseThrow();

		return mapper.readTree(step.getOutput());
	}

	private SpringAiToolRunContext context() {
		return new SpringAiToolRunContext("token-budget-test", "请解释唯一键冲突", 120_000L, mapper, helper);
	}

	private KnowledgeRetrievalResult result(KnowledgeChunk... chunks) {
		return new KnowledgeRetrievalResult("hybrid", List.of(chunks));
	}

	private KnowledgeChunk chunk(String id, String source) {
		return new KnowledgeChunk(id, source, "测试标题", "测试章节", 0.8, "x");
	}

}