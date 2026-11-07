package com.example.agent.rag.rerank;

import com.example.agent.exception.BusinessException;
import com.example.agent.rag.KnowledgeChunk;
import com.example.agent.rag.KnowledgeRetrievalResult;

import java.util.List;
import java.util.Objects;

/**
 * 正式 Agent 的重排策略。
 *
 * 适配器负责远程调用与协议校验；本类决定何时跳过、 哪些失败允许回退，以及需要记录哪些诊断信息。
 */
public final class AgentKnowledgeReranker {

	private final SiliconFlowReranker reranker;

	public AgentKnowledgeReranker(SiliconFlowReranker reranker) {
		this.reranker = Objects.requireNonNull(reranker);
	}

	public Outcome rerank(String query, KnowledgeRetrievalResult candidates) {
		Objects.requireNonNull(candidates);

		// 取消信号不能当成普通远程故障后继续调用模型。
		if (Thread.currentThread().isInterrupted()) {
			throw new BusinessException(503, "重排处理已被中断");
		}

		// 只有零条或一条候选时，重排无法改变顺序，不发送远程请求。
		if (candidates.chunks().size() < 2) {
			return outcome(candidates, candidates, "SKIPPED", "INSUFFICIENT_CANDIDATES", 0L, null, null);
		}

		// 工具后查询可能很长。保留完整检索查询，不偷偷截断其语义。
		if (query == null || query.isBlank() || query.length() > SiliconFlowReranker.MAX_QUERY_CHARS) {
			return outcome(candidates, candidates, "SKIPPED", "QUERY_OUT_OF_RANGE", 0L, null, null);
		}

		long start = System.nanoTime();

		try {
			SiliconFlowReranker.RerankResult response = reranker.rerank(query, candidates.chunks());

			/*
			 * 重排改变顺序和 score，不改变召回来源。 保留 retriever，确保已有的 retriever + chunkId 去重键稳定。
			 */
			KnowledgeRetrievalResult reordered = new KnowledgeRetrievalResult(candidates.retriever(),
					response.ranking());

			return outcome(candidates, reordered, "APPLIED", null, elapsedMs(start), response.requestId(), null);
		}
		catch (BusinessException e) {
			// 适配器收到 InterruptedException 后会恢复中断标志。
			if (Thread.currentThread().isInterrupted()) {
				throw e;
			}

			/*
			 * 远程 HTTP、网络、超时和响应协议失败允许回退。 不捕获所有 RuntimeException，避免把本地实现错误隐藏为正常降级。
			 */
			return outcome(candidates, candidates, "FALLBACK", "RERANK_UNAVAILABLE", elapsedMs(start), null,
					e.getCode());
		}
	}

	private Outcome outcome(KnowledgeRetrievalResult original, KnowledgeRetrievalResult result, String status,
			String reason, long costMs, String requestId, Integer errorCode) {

		List<String> inputIds = original.chunks().stream().map(KnowledgeChunk::chunkId).toList();

		List<RankedChunk> outputRanking = result.chunks()
			.stream()
			.map(chunk -> new RankedChunk(chunk.chunkId(), chunk.score()))
			.toList();

		/*
		 * 不把正文或 API Key 再复制进重排 Trace。 原始候选正文已经由 KNOWLEDGE_RETRIEVAL 记录。
		 */
		Trace trace = new Trace(status, reason, SiliconFlowReranker.MODEL, original.retriever(),
				"APPLIED".equals(status) ? "RERANK_RELEVANCE" : "RETRIEVAL_SCORE", original.chunks().size(), inputIds,
				outputRanking, requestId, errorCode);

		return new Outcome(result, trace, costMs);
	}

	private long elapsedMs(long start) {
		return (System.nanoTime() - start) / 1_000_000L;
	}

	public record Outcome(KnowledgeRetrievalResult result, Trace trace, long costMs) {
	}

	public record RankedChunk(String chunkId, Double score) {
	}

	public record Trace(String status, String reason, String model, String retriever, String scoreType,
			int candidateCount, List<String> inputChunkIds, List<RankedChunk> outputRanking, String requestId,
			Integer errorCode) {
	}

}