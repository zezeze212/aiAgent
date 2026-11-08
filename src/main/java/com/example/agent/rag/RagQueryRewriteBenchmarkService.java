package com.example.agent.rag;

import com.example.agent.exception.BusinessException;
import com.example.agent.rag.rewrite.AgentQueryRewriter;
import com.example.agent.tool.springai.SpringAiRequestBoundaryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 使用正式改写器和正式 Hybrid 检索器进行成对比较。
 *
 * 比较范围截止于召回结果，不执行重排、上下文筛选或答案生成。
 */
@Service
@ConditionalOnProperty(prefix = "agent.rag.benchmark", name = { "enabled", "rewrite-enabled" }, havingValue = "true")
public class RagQueryRewriteBenchmarkService {

	private final KnowledgeRetriever retriever;

	private final AgentQueryRewriter rewriter;

	private final RagBenchmarkService metadata;

	private final long rewriteReadTimeoutMs;

	public RagQueryRewriteBenchmarkService(KnowledgeRetriever retriever, AgentQueryRewriter rewriter,
			RagBenchmarkService metadata,
			@Value("${agent.rag.query-rewrite.read-timeout-ms:5000}") long rewriteReadTimeoutMs) {

		/*
		 * 本轮固定 Hybrid，防止应用切回 vector/rule 后， 实验接口仍被误认为是在运行同一个对照。
		 */
		if (!(retriever instanceof HybridKnowledgeRetriever)) {
			throw new IllegalArgumentException("查询改写成对评测要求 agent.rag.mode=hybrid");
		}

		if (rewriteReadTimeoutMs <= 0) {
			throw new IllegalArgumentException("查询改写读取超时必须大于 0");
		}

		this.retriever = retriever;
		this.rewriter = Objects.requireNonNull(rewriter);
		this.metadata = Objects.requireNonNull(metadata);
		this.rewriteReadTimeoutMs = rewriteReadTimeoutMs;
	}

	public PairResponse compare(String query, int topK, boolean rewriteFirst) {
		if (query == null || query.isBlank() || query.length() > AgentQueryRewriter.MAX_INPUT_CHARS || topK < 1
				|| topK > 20) {
			throw new BusinessException(400, "query 长度须为 1～2000，topK 须为 1～20");
		}

		Map<String, Object> before = configuration(topK);

		// 原问题不做覆盖，改写结果单独保存在 outcome 中。
		AgentQueryRewriter.Outcome outcome = rewrite(query);

		Branch original;
		Branch rewritten;
		List<String> retrievalOrder;

		boolean sameQueryReused = query.equals(outcome.query());

		if (sameQueryReused) {
			/*
			 * FALLBACK/UNCHANGED/SKIPPED 通常进入这里。 相同查询只检索一次，不能将重复调用波动解释为改写收益。
			 */
			original = retrieve(query, topK);
			rewritten = original;
			retrievalOrder = List.of("original");
		}
		else if (rewriteFirst) {
			rewritten = retrieve(outcome.query(), topK);
			original = retrieve(query, topK);
			retrievalOrder = List.of("rewritten", "original");
		}
		else {
			original = retrieve(query, topK);
			rewritten = retrieve(outcome.query(), topK);
			retrievalOrder = List.of("original", "rewritten");
		}

		Map<String, Object> after = configuration(topK);

		if (!before.equals(after)) {
			throw new BusinessException(409, "成对比较期间配置、语料或索引状态发生变化");
		}

		/*
		 * 这是“改写步骤 + 有效查询检索”的阶段耗时之和， 不是整对 HTTP 耗时，也不包含最终回答生成。
		 */
		double rewrittenStageCostMs = outcome.costMs() + rewritten.costMs();

		return new PairResponse("rag-query-rewrite-pair-v1", query, topK, retrievalOrder, sameQueryReused, outcome,
				original, rewritten, rewrittenStageCostMs, before);
	}

	private AgentQueryRewriter.Outcome rewrite(String query) {
		/*
		 * 与正式 PRE_DECISION 保持相同的确定性澄清跳过条件。 这里复用已有判断规则，不重新编写表名识别或澄清逻辑。
		 */
		if (SpringAiRequestBoundaryPolicy.findClarificationAnswer(query).isPresent()) {
			String model = (String) rewriter.configuration().get("model");

			return new AgentQueryRewriter.Outcome(query, new AgentQueryRewriter.Trace("SKIPPED",
					"DETERMINISTIC_CLARIFICATION", model, AgentQueryRewriter.PROMPT_VERSION, query), 0L);
		}

		return rewriter.rewrite(query);
	}

	private Branch retrieve(String query, int topK) {
		long start = System.nanoTime();

		// 检索异常直接传播，不用空列表冒充正常未命中。
		KnowledgeRetrievalResult result = retriever.retrieve(query, topK);

		double costMs = (System.nanoTime() - start) / 1_000_000.0;

		if (result == null || !"hybrid".equals(result.retriever())) {
			throw new IllegalStateException("正式 Hybrid 返回了不符合约定的结果");
		}

		return new Branch(query, result, costMs);
	}

	private Map<String, Object> configuration(int topK) {
		Map<String, Object> rewriteConfig = new LinkedHashMap<>(rewriter.configuration());

		// 与正式改写客户端读取同一个配置项。
		rewriteConfig.put("readTimeoutMs", rewriteReadTimeoutMs);

		return Map.of("evaluationScope", "RETRIEVAL_BEFORE_RERANK_AND_CONTEXT", "retrieval",
				metadata.formalHybridConfiguration(topK), "rewrite", Map.copyOf(rewriteConfig), "rerankApplied", false,
				"contextSelectionApplied", false, "answerGenerationApplied", false);
	}

	public record Branch(String query, KnowledgeRetrievalResult result, double costMs) {
	}

	public record PairResponse(String schemaVersion, String originalQuery, int topK, List<String> retrievalOrder,
			boolean sameQueryReused, AgentQueryRewriter.Outcome rewrite, Branch original, Branch rewritten,
			double rewrittenStageCostMs, Map<String, Object> configuration) {
	}

}