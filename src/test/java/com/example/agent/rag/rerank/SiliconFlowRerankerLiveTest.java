package com.example.agent.rag.rerank;

import com.example.agent.rag.KnowledgeChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实服务连通测试，不属于默认离线测试。
 *
 * 不硬编码“某条必须第一名”，避免把模型质量判断混入协议测试。
 */
@EnabledIfEnvironmentVariable(named = "RUN_RERANK_LIVE_TEST", matches = "true")
class SiliconFlowRerankerLiveTest {

	@Test
	void shouldRerankThroughRealProvider() {
		SiliconFlowReranker reranker = new SiliconFlowReranker(new ObjectMapper(),
				System.getenv("SILICONFLOW_API_KEY"));

		List<KnowledgeChunk> candidates = List.of(
				new KnowledgeChunk("smoke-duplicate", "smoke/duplicate-entry", "唯一键冲突", "常见原因", null,
						"Duplicate entry 通常与唯一键重复有关，" + "应检查重复提交和幂等控制。"),
				new KnowledgeChunk("smoke-unknown-column", "smoke/unknown-column", "字段不存在", "常见原因", null,
						"Unknown column 表示 SQL 引用了不存在的字段。" + "如果测试环境正常而生产环境报错，" + "应检查数据库迁移是否执行以及表结构是否一致。"));

		var result = reranker.rerank("测试环境正常，生产环境出现 Unknown column，应该检查什么？", candidates);

		// 只验证真实响应满足完整性契约，不把这个小例子当作质量评测。
		assertThat(result.ranking()).extracting(KnowledgeChunk::chunkId)
			.containsExactlyInAnyOrder("smoke-duplicate", "smoke-unknown-column");

		System.out.printf("model=%s, costMs=%.3f, requestId=%s%n", SiliconFlowReranker.MODEL, result.costMs(),
				result.requestId());

		for (int i = 0; i < result.ranking().size(); i++) {
			KnowledgeChunk chunk = result.ranking().get(i);
			System.out.printf("rank=%d, chunkId=%s, score=%.8f%n", i + 1, chunk.chunkId(), chunk.score());
		}
	}

}