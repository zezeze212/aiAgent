package com.example.agent.rag;

import com.example.agent.dto.RagBenchmarkResponse;
import com.example.agent.rag.rerank.SiliconFlowReranker;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * M6-3B：一次召回、同一候选并集、两种排序。
 *
 * 复用现有 Hybrid 完成检索与 RRF；随后对完整并集进行内容重排。 不调用生成模型，也不改变正式 Agent 的检索策略。
 */
public final class RagRerankBenchmarkService {

	private final RagBenchmarkService benchmark;

	private final SiliconFlowReranker reranker;

	public RagRerankBenchmarkService(RagBenchmarkService benchmark, SiliconFlowReranker reranker) {
		this.benchmark = benchmark;
		this.reranker = reranker;
	}

	public PairResponse compare(String query, int topK) {
		/*
		 * 只调用一次 Hybrid。 查询校验、两路检索、语料范围检查与 RRF 继续使用已有实现。
		 */
		RagBenchmarkResponse baseline = benchmark.search("hybrid", query, topK);

		/*
		 * TreeMap 按 chunkId 固定模型输入顺序。 候选身份来自完整分支结果，不能从 baseline.hits() 构造， 因为 hits
		 * 已经被截成最终 TopK。
		 */
		Map<String, KnowledgeChunk> byId = new TreeMap<>();

		for (String branch : List.of("denseHits", "bm25Hits")) {
			for (KnowledgeChunk hit : branchHits(baseline, branch)) {
				/*
				 * Hybrid 中的 RRF 已检查： 同一 chunkId 在两路中的正文和元数据必须一致。
				 *
				 * 清除旧 score，避免把某一路原始分数误当成统一候选分数。 Reranker 实际接收的仍然只有 content。
				 */
				byId.putIfAbsent(hit.chunkId(), new KnowledgeChunk(hit.chunkId(), hit.source(), hit.title(),
						hit.section(), null, hit.content()));
			}
		}

		List<KnowledgeChunk> candidates = List.copyOf(byId.values());

		/*
		 * 使用 Hybrid 已规范化的查询。 重排异常直接传播；本接口不返回 RRF 结果冒充重排成功。
		 */
		SiliconFlowReranker.RerankResult reranked = reranker.rerank(baseline.query(), candidates);

		return new PairResponse("rag-rerank-pair-v1", baseline, candidates, reranked, reranker.configuration());
	}

	private static List<KnowledgeChunk> branchHits(RagBenchmarkResponse response, String branch) {

		Object value = response.diagnostics().get(branch);

		if (!(value instanceof List<?> hits)) {
			throw new IllegalStateException("Hybrid 缺少完整分支候选：" + branch);
		}

		/*
		 * 此处读取同一 Java 进程内的 diagnostics，并未经过 JSON 反序列化， 所以元素应当是 KnowledgeChunk，而不是 Map。
		 * 类型不符合说明内部契约已变化，应直接暴露问题。
		 */
		return hits.stream().map(KnowledgeChunk.class::cast).toList();
	}

	/**
	 * baseline：RRF TopK 及原始检索诊断。 candidates：送入模型的完整候选，按 chunkId 排列。
	 * rerank：完整重排结果及其耗时，评分时再统一取 TopK。
	 */
	public record PairResponse(String schemaVersion, RagBenchmarkResponse baseline, List<KnowledgeChunk> candidates,
			SiliconFlowReranker.RerankResult rerank, Map<String, Object> rerankerConfiguration) {
	}

}