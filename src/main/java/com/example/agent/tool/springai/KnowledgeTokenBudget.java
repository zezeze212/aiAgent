package com.example.agent.tool.springai;

import com.example.agent.rag.KnowledgeChunk;

import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * 一次知识筛选使用的 token 预算规则。
 *
 * 规则对象不保存请求累计用量；累计状态属于 SpringAiToolRunContext。 counter 允许根据 PRE_DECISION / POST_TOOL
 * 的实际表示形式计数。
 */
public record KnowledgeTokenBudget(int maxEstimatedTokens, String estimatorName, String countingScope,
		ToIntFunction<KnowledgeChunk> counter) {

	public KnowledgeTokenBudget {
		if (maxEstimatedTokens < 0) {
			throw new IllegalArgumentException("token 估算预算不能为负数");
		}

		Objects.requireNonNull(estimatorName, "计数器名称不能为空");
		Objects.requireNonNull(countingScope, "统计范围不能为空");
		Objects.requireNonNull(counter, "计数方法不能为空");
	}

	public static KnowledgeTokenBudget disabled() {
		return new KnowledgeTokenBudget(0, "NONE", "DISABLED", chunk -> 0);
	}

	public boolean enabled() {
		return maxEstimatedTokens > 0;
	}

	public int estimate(KnowledgeChunk chunk) {
		if (!enabled()) {
			return 0;
		}

		int tokens = counter.applyAsInt(chunk);

		// 计数异常不能被当作零成本，否则会绕过预算限制。
		if (tokens < 0) {
			throw new IllegalStateException("片段 token 估算值不能为负数");
		}

		return tokens;
	}
}