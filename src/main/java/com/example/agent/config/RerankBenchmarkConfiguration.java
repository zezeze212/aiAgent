package com.example.agent.config;

import com.example.agent.rag.RagBenchmarkService;
import com.example.agent.rag.RagRerankBenchmarkService;
import com.example.agent.rag.rerank.SiliconFlowReranker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 只有 Benchmark 和重排实验都开启时，才创建这些 Bean。 普通 rule/dense/bm25/hybrid 评测不需要重排服务 Key。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agent.rag.benchmark", name = { "enabled", "rerank-enabled" }, havingValue = "true")
public class RerankBenchmarkConfiguration {

	@Bean
	public SiliconFlowReranker siliconFlowReranker(ObjectMapper objectMapper,
			@Value("${agent.rag.benchmark.rerank-api-key:}") String apiKey) {

		// 显式开启实验却没有 Key 时，客户端构造器会给出明确错误。
		return new SiliconFlowReranker(objectMapper, apiKey);
	}

	@Bean
	public RagRerankBenchmarkService ragRerankBenchmarkService(RagBenchmarkService benchmark,
			SiliconFlowReranker reranker) {

		return new RagRerankBenchmarkService(benchmark, reranker);
	}

}