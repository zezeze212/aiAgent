package com.example.agent.config;

import com.example.agent.rag.KnowledgeRetriever;
import com.example.agent.rag.RuleKnowledgeRetriever;
import com.example.agent.rag.SimpleRagRetriever;
import com.example.agent.rag.vector.VectorKnowledgeRetriever;
import com.example.agent.rag.vector.VectorKnowledgeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * 根据配置选择正式 Agent 使用的知识检索实现。
 *
 * 配置错误在启动阶段暴露； 不在选择 vector 失败时静默切换为 rule。
 */
@Configuration(proxyBeanMethods = false)
public class KnowledgeRetrieverConfiguration {

	@Bean
	public KnowledgeRetriever knowledgeRetriever(@Value("${agent.rag.mode:rule}") String mode,
			SimpleRagRetriever simpleRagRetriever, ObjectProvider<VectorKnowledgeService> vectorServiceProvider) {

		String normalizedMode = mode.trim().toLowerCase(Locale.ROOT);

		return switch (normalizedMode) {
			case "rule" -> new RuleKnowledgeRetriever(simpleRagRetriever);
			case "vector" -> createVectorRetriever(vectorServiceProvider);
			default -> throw new IllegalArgumentException("agent.rag.mode 只支持 rule 或 vector，当前值：" + mode);
		};
	}

	private KnowledgeRetriever createVectorRetriever(ObjectProvider<VectorKnowledgeService> vectorServiceProvider) {

		VectorKnowledgeService service = vectorServiceProvider.getIfAvailable();

		if (service == null) {
			throw new IllegalStateException(
					"agent.rag.mode=vector 时必须启用 agent.rag.vector.enabled=true" + "，并正确配置 VectorKnowledgeService 的依赖");
		}

		return new VectorKnowledgeRetriever(service);
	}

}