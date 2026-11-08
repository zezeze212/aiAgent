package com.example.agent.rag;

import com.example.agent.controller.RagQueryRewriteBenchmarkController;
import com.example.agent.exception.BusinessException;
import com.example.agent.rag.rewrite.AgentQueryRewriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RagQueryRewriteBenchmarkServiceTest {

	private HybridKnowledgeRetriever retriever;

	private AgentQueryRewriter rewriter;

	private RagBenchmarkService metadata;

	private RagQueryRewriteBenchmarkService service;

	@BeforeEach
	void setUp() {
		retriever = mock(HybridKnowledgeRetriever.class);
		rewriter = mock(AgentQueryRewriter.class);
		metadata = mock(RagBenchmarkService.class);

		when(rewriter.configuration())
			.thenReturn(Map.of("model", "test-model", "promptVersion", AgentQueryRewriter.PROMPT_VERSION));

		when(metadata.formalHybridConfiguration(5)).thenReturn(Map.of("corpusSha256", "stable"));

		service = new RagQueryRewriteBenchmarkService(retriever, rewriter, metadata, 5000);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void shouldCompareBothQueriesWithRequestedRetrievalOrder(boolean rewriteFirst) {
		when(rewriter.rewrite("原问题")).thenReturn(outcome("改写问题", "APPLIED"));
		when(retriever.retrieve("原问题", 5)).thenReturn(result("original-hit"));
		when(retriever.retrieve("改写问题", 5)).thenReturn(result("rewritten-hit"));

		var pair = service.compare("原问题", 5, rewriteFirst);

		assertThat(pair.original().query()).isEqualTo("原问题");
		assertThat(pair.rewritten().query()).isEqualTo("改写问题");
		assertThat(pair.original().result().chunks().get(0).chunkId()).isEqualTo("original-hit");
		assertThat(pair.rewritten().result().chunks().get(0).chunkId()).isEqualTo("rewritten-hit");
		assertThat(pair.sameQueryReused()).isFalse();

		var order = inOrder(retriever);

		if (rewriteFirst) {
			order.verify(retriever).retrieve("改写问题", 5);
			order.verify(retriever).retrieve("原问题", 5);
			assertThat(pair.retrievalOrder()).containsExactly("rewritten", "original");
		}
		else {
			order.verify(retriever).retrieve("原问题", 5);
			order.verify(retriever).retrieve("改写问题", 5);
			assertThat(pair.retrievalOrder()).containsExactly("original", "rewritten");
		}

		// 同一问题只调用一次改写；两侧检索共享正式检索器。
		verify(rewriter, times(1)).rewrite("原问题");
		verifyNoMoreInteractions(retriever);
		verify(metadata, times(2)).formalHybridConfiguration(5);

		assertThat(pair.rewrittenStageCostMs()).isEqualTo(7.0 + pair.rewritten().costMs());
	}

	@ParameterizedTest
	@ValueSource(strings = { "FALLBACK", "UNCHANGED" })
	void shouldReuseOneRetrievalWhenEffectiveQueryIsUnchanged(String status) {
		when(rewriter.rewrite("原问题")).thenReturn(outcome("原问题", status));
		when(retriever.retrieve("原问题", 5)).thenReturn(result("same-hit"));

		var pair = service.compare("原问题", 5, true);

		assertThat(pair.sameQueryReused()).isTrue();
		assertThat(pair.rewritten()).isSameAs(pair.original());
		assertThat(pair.retrievalOrder()).containsExactly("original");
		assertThat(pair.rewrite().trace().status()).isEqualTo(status);

		// 即使回退，已经消耗的改写时间仍保留。
		assertThat(pair.rewrittenStageCostMs()).isEqualTo(7.0 + pair.original().costMs());

		verify(retriever, times(1)).retrieve("原问题", 5);
		verifyNoMoreInteractions(retriever);
	}

	@Test
	void shouldSkipRewriteForDeterministicClarification() {
		String query = "Unknown column 'model_name'，请结合真实表结构诊断";
		when(retriever.retrieve(query, 5)).thenReturn(result("hit"));

		var pair = service.compare(query, 5, false);

		assertThat(pair.rewrite().trace().status()).isEqualTo("SKIPPED");
		assertThat(pair.rewrite().trace().reason()).isEqualTo("DETERMINISTIC_CLARIFICATION");
		assertThat(pair.sameQueryReused()).isTrue();

		verify(rewriter, never()).rewrite(anyString());
		verify(retriever, times(1)).retrieve(query, 5);
	}

	@Test
	void shouldRejectSnapshotChangeDuringPair() {
		when(metadata.formalHybridConfiguration(5)).thenReturn(Map.of("corpusSha256", "before"),
				Map.of("corpusSha256", "after"));
		when(rewriter.rewrite("原问题")).thenReturn(outcome("原问题", "UNCHANGED"));
		when(retriever.retrieve("原问题", 5)).thenReturn(result("hit"));

		assertThatThrownBy(() -> service.compare("原问题", 5, false)).isInstanceOf(BusinessException.class)
			.hasMessageContaining("发生变化");
	}

	@Test
	void shouldPropagateRetrievalFailureInsteadOfReturningHalfPair() {
		when(rewriter.rewrite("原问题")).thenReturn(outcome("改写问题", "APPLIED"));
		when(retriever.retrieve("原问题", 5)).thenReturn(result("hit"));
		when(retriever.retrieve("改写问题", 5)).thenThrow(new IllegalStateException("模拟向量服务失败"));

		assertThatThrownBy(() -> service.compare("原问题", 5, false)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("模拟向量服务失败");
	}

	@Test
	void shouldRejectNonHybridRetriever() {
		KnowledgeRetriever other = mock(KnowledgeRetriever.class);

		assertThatThrownBy(() -> new RagQueryRewriteBenchmarkService(other, rewriter, metadata, 5000))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("agent.rag.mode=hybrid");
	}

	@Test
	void shouldRejectInvalidInputBeforeModelOrRetrieval() {
		assertThatThrownBy(() -> service.compare(" ", 5, false)).isInstanceOf(BusinessException.class);

		verifyNoInteractions(retriever);
		verify(rewriter, never()).rewrite(anyString());
		verify(metadata, never()).formalHybridConfiguration(anyInt());
	}

	@Test
	void shouldRequireBothBenchmarkFlagsAndReuseExistingBeans() {
		var runner = new ApplicationContextRunner()
			.withUserConfiguration(RagQueryRewriteBenchmarkService.class, RagQueryRewriteBenchmarkController.class)
			.withBean(KnowledgeRetriever.class, () -> retriever)
			.withBean(AgentQueryRewriter.class, () -> rewriter)
			.withBean(RagBenchmarkService.class, () -> metadata);

		runner.withPropertyValues("agent.rag.benchmark.enabled=true").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(RagQueryRewriteBenchmarkService.class);
			assertThat(context).doesNotHaveBean(RagQueryRewriteBenchmarkController.class);
		});

		runner.withPropertyValues("agent.rag.benchmark.enabled=true", "agent.rag.benchmark.rewrite-enabled=true")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(RagQueryRewriteBenchmarkService.class);
				assertThat(context).hasSingleBean(RagQueryRewriteBenchmarkController.class);
				assertThat(context.getBean(KnowledgeRetriever.class)).isSameAs(retriever);
				assertThat(context.getBean(AgentQueryRewriter.class)).isSameAs(rewriter);
			});
	}

	private AgentQueryRewriter.Outcome outcome(String query, String status) {
		String reason = "FALLBACK".equals(status) ? "MODEL_UNAVAILABLE" : null;

		return new AgentQueryRewriter.Outcome(query,
				new AgentQueryRewriter.Trace(status, reason, "test-model", AgentQueryRewriter.PROMPT_VERSION, query),
				7L);
	}

	private KnowledgeRetrievalResult result(String id) {
		return new KnowledgeRetrievalResult("hybrid",
				List.of(new KnowledgeChunk(id, "fixture.md", "测试文档", "测试章节", 0.03, "测试证据")));
	}

}