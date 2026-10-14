package com.example.agent.tool.springai;

import com.example.agent.rag.KnowledgeChunk;
import com.example.agent.rag.KnowledgeRetrievalResult;
import com.example.agent.rag.KnowledgeRetriever;
import com.example.agent.support.AgentJsonHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SpringAiKnowledgeSupportTest {

	private KnowledgeRetriever retriever;

	private ObjectMapper objectMapper;

	private SpringAiKnowledgeSupport support;

	private SpringAiToolRunContext context;

	@BeforeEach
	void setUp() {
		retriever = mock(KnowledgeRetriever.class);
		objectMapper = new ObjectMapper();

		AgentJsonHelper helper = new AgentJsonHelper(objectMapper);

		support = new SpringAiKnowledgeSupport(retriever, helper, objectMapper, 5, 5, 2000, 6000);

		context = new SpringAiToolRunContext("test1234", "请解释唯一键冲突", 120_000L, objectMapper, helper);
	}

	@Test
	void shouldPrepareMultipleChunksWithoutChangingOriginalInput() {
		String original = context.getUserMessage();

		when(retriever.retrieve(original, 5)).thenReturn(result(chunk("c1"), chunk("c2")));

		String prompt = support.prepareInitialUserMessage(context);

		assertThat(prompt).contains("[知识:c1]", "[知识:c2]").contains("工具返回值是当前环境的确定性证据");

		assertThat(context.getUserMessage()).isEqualTo(original);
		assertThat(context.hasPreDecisionKnowledge()).isTrue();

		assertThat(context.getSteps()).anySatisfy(step -> {
			assertThat(step.getStepName()).isEqualTo("KNOWLEDGE_CONTEXT");
			assertThat(step.getOutput()).contains("c1", "c2");
		});
	}

	@Test
	void shouldKeepExistingPostToolSkipPolicy() {
		when(retriever.retrieve(context.getUserMessage(), 5)).thenReturn(result(chunk("c1")));

		support.prepareInitialUserMessage(context);

		KnowledgeRetrievalResult post = support.retrievePostToolKnowledge(context, "工具结果");

		assertThat(post.matched()).isFalse();

		// 只发生首次检索，没有工具后的第二次检索。
		verify(retriever).retrieve(context.getUserMessage(), 5);
		verifyNoMoreInteractions(retriever);
	}

	@Test
	void shouldDeduplicateChunksAcrossToolsAndPreserveToolEvidence() throws Exception {

		String firstTool = "{\"evidence\":\"first\"}";
		String secondTool = "{\"evidence\":\"second\"}";

		when(retriever.retrieve(query(firstTool), 5)).thenReturn(result(chunk("c1"), chunk("c2")));

		when(retriever.retrieve(query(secondTool), 5)).thenReturn(result(chunk("c2"), chunk("c3")));

		KnowledgeRetrievalResult first = support.retrievePostToolKnowledge(context, firstTool);

		KnowledgeRetrievalResult second = support.retrievePostToolKnowledge(context, secondTool);

		assertThat(first.chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("c1", "c2");

		assertThat(second.chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("c3");

		String payload = """
				{
				  "success": true,
				  "result": {"actualColumn": "id"}
				}
				""";

		JsonNode parsed = objectMapper.readTree(support.appendKnowledgeToToolPayload(payload, second));

		assertThat(parsed.path("result")).isEqualTo(objectMapper.readTree(payload).path("result"));

		assertThat(parsed.path("knowledge").path("chunks").size()).isEqualTo(1);

		assertThat(parsed.path("knowledge").path("chunks").get(0).path("chunkId").asText()).isEqualTo("c3");
	}

	@Test
	void shouldContinueWithOriginalInputWhenRetrievalFails() {
		when(retriever.retrieve(context.getUserMessage(), 5)).thenThrow(new IllegalStateException("向量服务不可用"));

		String prompt = support.prepareInitialUserMessage(context);

		assertThat(prompt).isEqualTo(context.getUserMessage());
		assertThat(context.hasPreDecisionKnowledge()).isFalse();

		assertThat(context.getSteps()).anySatisfy(step -> {
			assertThat(step.getStepName()).isEqualTo("KNOWLEDGE_RETRIEVAL");
			assertThat(step.getSuccess()).isFalse();
			assertThat(step.getErrorMessage()).contains("向量服务不可用");
		});
	}

	@Test
	void shouldApplyBudgetAcrossCallsWithoutRememberingRejectedChunks() {
		KnowledgeRetrievalResult firstCandidates = result(budgetChunk("c1", 6), budgetChunk("c2", 5),
				budgetChunk("c3", 4));

		var first = context.selectFreshKnowledge(firstCandidates, 5, 20, 10);

		// c1 占 6，c2 放不下，继续选 c3，刚好达到 10。
		assertThat(first.selected().chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("c1", "c3");

		assertThat(first.totalChars()).isEqualTo(10);
		assertThat(first.budgetSkippedCount()).isEqualTo(1);

		var second = context
			.selectFreshKnowledge(result(budgetChunk("c1", 6), budgetChunk("c2", 5), budgetChunk("c4", 1)), 5, 20, 10);

		assertThat(second.selected().matched()).isFalse();

		// c1 已选入，所以重复；c2 上次未选入，不能算重复。
		assertThat(second.duplicateCount()).isEqualTo(1);
		assertThat(second.budgetSkippedCount()).isEqualTo(2);
		assertThat(second.totalChars()).isEqualTo(10);
	}

	@Test
	void shouldLimitChunkCountAcrossCalls() {
		var first = context.selectFreshKnowledge(result(budgetChunk("c1", 1)), 2, 20, 100);

		var second = context.selectFreshKnowledge(result(budgetChunk("c2", 1), budgetChunk("c3", 1)), 2, 20, 100);

		assertThat(first.totalChunks()).isEqualTo(1);

		assertThat(second.selected().chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("c2");

		assertThat(second.totalChunks()).isEqualTo(2);
		assertThat(second.budgetSkippedCount()).isEqualTo(1);
	}

	@Test
	void shouldAllowPostToolRetrievalWhenPreDecisionChunksAreOversized() {
		support = new SpringAiKnowledgeSupport(retriever, new AgentJsonHelper(objectMapper), objectMapper, 5, 5, 5, 20);

		when(retriever.retrieve(context.getUserMessage(), 5)).thenReturn(result(budgetChunk("too-long", 6)));

		String prompt = support.prepareInitialUserMessage(context);

		assertThat(prompt).isEqualTo(context.getUserMessage());
		assertThat(context.hasPreDecisionKnowledge()).isFalse();

		String toolResult = "工具证据";

		when(retriever.retrieve(query(toolResult), 5)).thenReturn(result(budgetChunk("fits", 5)));

		KnowledgeRetrievalResult post = support.retrievePostToolKnowledge(context, toolResult);

		assertThat(post.chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("fits");

		verify(retriever).retrieve(query(toolResult), 5);
	}

	private KnowledgeChunk budgetChunk(String id, int chars) {
		return new KnowledgeChunk(id, "docs/knowledge/test.md", "测试文档", "测试章节", 0.8, "x".repeat(chars));
	}

	private String query(String toolResult) {
		return context.getUserMessage() + System.lineSeparator() + toolResult;
	}

	private KnowledgeRetrievalResult result(KnowledgeChunk... chunks) {
		return new KnowledgeRetrievalResult("vector", List.of(chunks));
	}

	private KnowledgeChunk chunk(String id) {
		// 刻意使用相同来源，验证不同 Chunk 不会被按文档误删。
		return new KnowledgeChunk(id, "docs/knowledge/sql/duplicate-entry.md", "唯一键冲突", "章节-" + id, 0.8, "证据-" + id);
	}

}