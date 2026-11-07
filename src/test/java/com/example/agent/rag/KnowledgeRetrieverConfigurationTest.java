package com.example.agent.rag;

import com.example.agent.config.KnowledgeRetrieverConfiguration;
import com.example.agent.dto.VectorKnowledgeSearchHit;
import com.example.agent.dto.VectorKnowledgeSearchResponse;
import com.example.agent.rag.vector.KnowledgeManifestLoader;
import com.example.agent.rag.vector.MarkdownSectionChunker;
import com.example.agent.rag.vector.VectorKnowledgeRetriever;
import com.example.agent.rag.vector.VectorKnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证配置选择和统一结果契约。 不启动 MySQL、Ollama、Qdrant 或真实模型。
 */
class KnowledgeRetrieverConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(KnowledgeRetrieverConfiguration.class)
		.withBean(SimpleRagRetriever.class, SimpleRagRetriever::new)
		.withBean(KnowledgeManifestLoader.class, () -> new KnowledgeManifestLoader(new ObjectMapper()))
		.withBean(MarkdownSectionChunker.class, MarkdownSectionChunker::new);

	@Test
	void shouldUseRuleByDefaultWithoutVectorService() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(KnowledgeRetriever.class);
			assertThat(context).doesNotHaveBean(VectorKnowledgeService.class);

			KnowledgeRetriever retriever = context.getBean(KnowledgeRetriever.class);
			assertThat(retriever).isInstanceOf(RuleKnowledgeRetriever.class);

			KnowledgeRetrievalResult result = retriever.retrieve("Unknown column 'model_name'", 5);

			assertThat(result.retriever()).isEqualTo("rule");
			assertThat(result.matched()).isTrue();
			assertThat(result.chunks()).hasSize(1);
			assertThat(result.chunks().get(0).source()).isEqualTo("docs/knowledge/sql/unknown-column.md");
			assertThat(result.chunks().get(0).score()).isNull();
		});
	}

	@Test
	void shouldSelectVectorAndPreserveChunks() {
		VectorKnowledgeService service = mock(VectorKnowledgeService.class);

		List<VectorKnowledgeSearchHit> hits = List.of(
				new VectorKnowledgeSearchHit("chunk-1", 0.87, "docs/a.md", "唯一键冲突", "原因", "第一条证据"),
				new VectorKnowledgeSearchHit("chunk-2", 0.81, "docs/a.md", "唯一键冲突", "排查", "第二条证据"));

		when(service.search("重复提交", 5)).thenReturn(new VectorKnowledgeSearchResponse("重复提交", 5, 10L, hits));

		contextRunner.withPropertyValues("agent.rag.mode=vector", "agent.rag.vector.enabled=true")
			.withBean(VectorKnowledgeService.class, () -> service)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(KnowledgeRetriever.class);

				KnowledgeRetriever retriever = context.getBean(KnowledgeRetriever.class);

				assertThat(retriever).isInstanceOf(VectorKnowledgeRetriever.class);

				KnowledgeRetrievalResult result = retriever.retrieve("重复提交", 5);

				assertThat(result.retriever()).isEqualTo("vector");
				assertThat(result.chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("chunk-1", "chunk-2");

				assertThat(result.chunks()).extracting(KnowledgeChunk::content).containsExactly("第一条证据", "第二条证据");

				verify(service).search("重复提交", 5);
			});
	}

	@Test
	void shouldFailWhenVectorServiceIsMissing() {
		contextRunner.withPropertyValues("agent.rag.mode=vector").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
				.hasStackTraceContaining("agent.rag.vector.enabled=true");
		});
	}

	@Test
	void shouldRejectUnknownMode() {
		contextRunner.withPropertyValues("agent.rag.mode=vectro").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasStackTraceContaining("只支持 rule、vector、bm25 或 hybrid");
		});
	}

	@Test
	void shouldSelectBm25WithoutVectorOrBenchmarkService() {
		contextRunner.withPropertyValues("agent.rag.mode=bm25", "agent.rag.benchmark.enabled=false").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(KnowledgeRetriever.class);
			assertThat(context).doesNotHaveBean(VectorKnowledgeService.class);
			assertThat(context).doesNotHaveBean(RagBenchmarkService.class);

			KnowledgeRetriever retriever = context.getBean(KnowledgeRetriever.class);
			assertThat(retriever).isInstanceOf(Bm25KnowledgeRetriever.class);

			var result = retriever.retrieve("Unknown column", 5);
			assertThat(result.retriever()).isEqualTo("bm25");
			assertThat(result.chunks()).isNotEmpty();
		});
	}

	@Test
	void shouldSelectHybridWithoutBenchmarkService() {
		VectorKnowledgeService service = mock(VectorKnowledgeService.class);

		// Hybrid 要求向量检索与 BM25 使用相同的知识范围。
		when(service.searchPolicy()).thenReturn(new VectorKnowledgeService.SearchPolicy(0.0, "mysql", "public"));

		// 模拟向量检索正常完成但没有命中，融合结果仍可来自 BM25。
		// 这是正常空结果，不是向量服务调用失败。
		when(service.search("Unknown column", 5))
			.thenReturn(new VectorKnowledgeSearchResponse("Unknown column", 5, 0L, List.of()));

		contextRunner.withPropertyValues("agent.rag.mode=hybrid",
				// 即使实例来自 mock，也需要满足服务类上的条件注解。
				"agent.rag.vector.enabled=true", "agent.rag.benchmark.enabled=false")
			.withBean(VectorKnowledgeService.class, () -> service)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(KnowledgeRetriever.class);

				// 明确确认容器使用的是本测试提供的 mock。
				assertThat(context.getBean(VectorKnowledgeService.class)).isSameAs(service);
				assertThat(context).doesNotHaveBean(RagBenchmarkService.class);

				KnowledgeRetriever retriever = context.getBean(KnowledgeRetriever.class);
				assertThat(retriever).isInstanceOf(HybridKnowledgeRetriever.class);

				KnowledgeRetrievalResult result = retriever.retrieve("Unknown column", 5);

				assertThat(result.retriever()).isEqualTo("hybrid");
				assertThat(result.chunks()).isNotEmpty();
				verify(service).search("Unknown column", 5);
			});
	}

	@Test
	void shouldFailWhenHybridVectorServiceIsMissing() {
		contextRunner.withPropertyValues("agent.rag.mode=hybrid").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
				.hasStackTraceContaining("agent.rag.mode=hybrid")
				.hasStackTraceContaining("agent.rag.vector.enabled=true");
		});
	}

}