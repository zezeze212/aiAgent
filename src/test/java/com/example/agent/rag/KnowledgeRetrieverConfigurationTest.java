package com.example.agent.rag;

import com.example.agent.config.KnowledgeRetrieverConfiguration;
import com.example.agent.dto.VectorKnowledgeSearchHit;
import com.example.agent.dto.VectorKnowledgeSearchResponse;
import com.example.agent.rag.vector.VectorKnowledgeRetriever;
import com.example.agent.rag.vector.VectorKnowledgeService;
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
		.withBean(SimpleRagRetriever.class, SimpleRagRetriever::new);

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
				.hasStackTraceContaining("只支持 rule 或 vector");
		});
	}

}