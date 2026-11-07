package com.example.agent.config;

import com.example.agent.rag.Bm25KnowledgeRetriever;
import com.example.agent.rag.HybridKnowledgeRetriever;
import com.example.agent.rag.KnowledgeRetriever;
import com.example.agent.rag.RuleKnowledgeRetriever;
import com.example.agent.rag.SimpleRagRetriever;
import com.example.agent.rag.vector.KnowledgeManifestLoader;
import com.example.agent.rag.vector.MarkdownSectionChunker;
import com.example.agent.rag.vector.VectorKnowledgeRetriever;
import com.example.agent.rag.vector.VectorKnowledgeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Locale;

/**
 * 根据配置选择一个正式 Agent 检索器。
 *
 * BM25/Hybrid 的内部对象不额外注册为 KnowledgeRetriever Bean， 避免正式入口出现多个同类型候选。
 */
@Configuration(proxyBeanMethods = false)
public class KnowledgeRetrieverConfiguration {

	@Bean
	public KnowledgeRetriever knowledgeRetriever(@Value("${agent.rag.mode:rule}") String mode,
			SimpleRagRetriever simpleRagRetriever, ObjectProvider<VectorKnowledgeService> vectorProvider,
			ObjectProvider<KnowledgeManifestLoader> loaderProvider,
			ObjectProvider<MarkdownSectionChunker> chunkerProvider,
			@Value("${agent.rag.vector.scope.category:mysql}") String category,
			@Value("${agent.rag.vector.scope.visibility:public}") String visibility) throws IOException {

		String normalizedMode = mode.trim().toLowerCase(Locale.ROOT);

		return switch (normalizedMode) {
			case "rule" -> new RuleKnowledgeRetriever(simpleRagRetriever);

			case "vector" -> new VectorKnowledgeRetriever(requireVector(vectorProvider, "vector"));

			case "bm25" -> createBm25(loaderProvider, chunkerProvider, category, visibility);

			case "hybrid" -> {
				// 先确认 Vector 服务存在，避免无效配置下先分配 BM25 资源。
				VectorKnowledgeService vector = requireVector(vectorProvider, "hybrid");
				Bm25KnowledgeRetriever bm25 = createBm25(loaderProvider, chunkerProvider, category, visibility);

				try {
					yield new HybridKnowledgeRetriever(vector, bm25);
				}
				catch (RuntimeException e) {
					/*
					 * 构造失败时还没有成功注册 Bean， 因此需要主动释放刚创建的索引。
					 */
					try {
						bm25.close();
					}
					catch (IOException closeError) {
						e.addSuppressed(closeError);
					}
					throw e;
				}
			}

			default -> throw new IllegalArgumentException("agent.rag.mode 只支持 rule、vector、bm25 或 hybrid，当前值：" + mode);
		};
	}

	private Bm25KnowledgeRetriever createBm25(ObjectProvider<KnowledgeManifestLoader> loaderProvider,
			ObjectProvider<MarkdownSectionChunker> chunkerProvider, String category, String visibility)
			throws IOException {

		return new Bm25KnowledgeRetriever(loaderProvider.getObject(), chunkerProvider.getObject(), category,
				visibility);
	}

	private VectorKnowledgeService requireVector(ObjectProvider<VectorKnowledgeService> provider, String mode) {

		VectorKnowledgeService service = provider.getIfAvailable();

		if (service == null) {
			throw new IllegalStateException("agent.rag.mode=" + mode + " 时必须启用 agent.rag.vector.enabled=true"
					+ "，并正确配置 VectorKnowledgeService 的依赖");
		}

		return service;
	}

}