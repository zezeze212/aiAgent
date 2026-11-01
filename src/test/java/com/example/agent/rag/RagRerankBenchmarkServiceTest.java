package com.example.agent.rag;

import com.example.agent.config.RerankBenchmarkConfiguration;
import com.example.agent.controller.RagRerankBenchmarkController;
import com.example.agent.dto.RagBenchmarkResponse;
import com.example.agent.exception.BusinessException;
import com.example.agent.rag.rerank.SiliconFlowReranker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RagRerankBenchmarkServiceTest {

	@Test
	void shouldRerankCompleteUnionFromOneHybridCall() {
		RagBenchmarkService benchmark = mock(RagBenchmarkService.class);
		SiliconFlowReranker reranker = mock(SiliconFlowReranker.class);

		KnowledgeChunk a = chunk("a", 0.9);
		KnowledgeChunk b = chunk("b", 9.0);

		// 基线只返回 a，但完整两路并集中还有 b。
		RagBenchmarkResponse baseline = new RagBenchmarkResponse("rag-benchmark-response-v1", "hybrid", "chunk", "问题",
				1, 1, 4.0, List.of(chunk("a", 1.0 / 61)), Map.of(),
				Map.of("denseHits", List.of(a), "bm25Hits", List.of(b)));

		List<KnowledgeChunk> candidates = List.of(chunk("a", null), chunk("b", null));

		var ranked = new SiliconFlowReranker.RerankResult(List.of(chunk("b", 0.95), chunk("a", 0.1)), 30.0,
				"request-1");

		when(benchmark.search("hybrid", " 问题 ", 1)).thenReturn(baseline);
		when(reranker.rerank("问题", candidates)).thenReturn(ranked);
		when(reranker.configuration()).thenReturn(Map.of("model", "test"));

		var service = new RagRerankBenchmarkService(benchmark, reranker);
		var result = service.compare(" 问题 ", 1);

		assertThat(result.baseline()).isSameAs(baseline);
		assertThat(result.candidates()).isEqualTo(candidates);
		assertThat(result.rerank().ranking()).extracting(KnowledgeChunk::chunkId).containsExactly("b", "a");

		// 精确验证只召回一次，并使用规范化查询与完整候选重排。
		verify(benchmark, times(1)).search("hybrid", " 问题 ", 1);
		verifyNoMoreInteractions(benchmark);
		verify(reranker).rerank("问题", candidates);
	}

	@Test
	void shouldPropagateRetrievalFailureWithoutCallingReranker() {
		RagBenchmarkService benchmark = mock(RagBenchmarkService.class);
		SiliconFlowReranker reranker = mock(SiliconFlowReranker.class);
		when(benchmark.search("hybrid", "问题", 5)).thenThrow(new BusinessException(503, "dense 不可用"));

		var service = new RagRerankBenchmarkService(benchmark, reranker);

		assertThatThrownBy(() -> service.compare("问题", 5)).isInstanceOf(BusinessException.class)
			.hasMessageContaining("dense 不可用");

		verifyNoInteractions(reranker);
	}

	@Test
	void shouldPropagateRerankFailureWithoutReturningBaseline() {
		RagBenchmarkService benchmark = mock(RagBenchmarkService.class);
		SiliconFlowReranker reranker = mock(SiliconFlowReranker.class);

		var baseline = new RagBenchmarkResponse("rag-benchmark-response-v1", "hybrid", "chunk", "问题", 5, 5, 4.0,
				List.of(chunk("a", 0.1)), Map.of(),
				Map.of("denseHits", List.of(chunk("a", 0.9)), "bm25Hits", List.of()));

		when(benchmark.search("hybrid", "问题", 5)).thenReturn(baseline);
		when(reranker.rerank(eq("问题"), anyList())).thenThrow(new BusinessException(504, "重排超时"));

		var service = new RagRerankBenchmarkService(benchmark, reranker);

		assertThatThrownBy(() -> service.compare("问题", 5)).isInstanceOf(BusinessException.class)
			.hasMessageContaining("重排超时");
	}

	@Test
	void shouldRequireBothEnablementFlags() {
		var runner = new ApplicationContextRunner()
			.withUserConfiguration(RerankBenchmarkConfiguration.class, RagRerankBenchmarkController.class)
			.withBean(ObjectMapper.class, ObjectMapper::new)
			.withBean(RagBenchmarkService.class, () -> mock(RagBenchmarkService.class));

		// 只开启旧 Benchmark，不应要求 Key，也不创建新入口。
		runner.withPropertyValues("agent.rag.benchmark.enabled=true").run(context -> {
			assertThat(context).doesNotHaveBean(SiliconFlowReranker.class);
			assertThat(context).doesNotHaveBean(RagRerankBenchmarkController.class);
		});

		runner
			.withPropertyValues("agent.rag.benchmark.enabled=true", "agent.rag.benchmark.rerank-enabled=true",
					"agent.rag.benchmark.rerank-api-key=test-key")
			.run(context -> {
				assertThat(context).hasSingleBean(SiliconFlowReranker.class);
				assertThat(context).hasSingleBean(RagRerankBenchmarkService.class);
				assertThat(context).hasSingleBean(RagRerankBenchmarkController.class);
			});
	}

	private static KnowledgeChunk chunk(String id, Double score) {
		return new KnowledgeChunk(id, "fixture.md", "测试文档", "章节", score, "正文 " + id);
	}

}