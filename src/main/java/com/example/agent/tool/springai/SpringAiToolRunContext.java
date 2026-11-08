package com.example.agent.tool.springai;

import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.AgentTraceStep;
import com.example.agent.dto.KnowledgeCitationReport;
import com.example.agent.dto.ToolExecutionResult;
import com.example.agent.rag.KnowledgeChunk;
import com.example.agent.rag.KnowledgeRetrievalResult;
import com.example.agent.rag.KnowledgeSearchResult;
import com.example.agent.support.AgentJsonHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.util.*;
import java.util.function.Supplier;

/**
 * 一次 Spring AI Agent 请求的运行上下文。
 *
 * 该对象由 Service 为每次 HTTP 请求单独创建， 再通过 Spring AI ToolContext 传递给工具方法。
 *
 * 它不会作为工具参数发送给大模型。
 */
public class SpringAiToolRunContext {

	/**
	 * ToolContext Map 中保存运行上下文时使用的键。
	 */
	public static final String CONTEXT_KEY = SpringAiToolRunContext.class.getName();

	/**
	 * 单次 Agent 请求最多执行三个受治理工具调用。
	 */
	private static final int MAX_TOOL_CALLS = 3;

	/**
	 * PRE_DECISION 是否已经准备了知识上下文。
	 *
	 * 当前保持原策略：有候选时跳过 POST_TOOL 检索。 这不代表候选已经通过可靠性验证。
	 */
	private boolean preDecisionKnowledgeMatched;

	/**
	 * 当前请求已经选入上下文的片段。 按检索方式和 Chunk ID 去重，不按来源文档去重。
	 */
	private final Set<String> injectedKnowledgeKeys = new HashSet<>();

	/**
	 * 本次请求已经选入的知识片段快照。
	 *
	 * key 同时作为引用允许集合；value 用于回答完成后关联来源和正文。 不再额外维护一份 ID 集合，避免两份状态不一致。
	 */
	private final Map<String, KnowledgeChunk> preparedKnowledgeChunks = new LinkedHashMap<>();

	/**
	 * 只有正常模型答案执行审计后，才替换此默认结果。
	 */
	private KnowledgeCitationReport knowledgeCitations = KnowledgeCitationReport.notChecked();

	/**
	 * 本次请求已准备的知识正文长度与片段数。 多次 POST_TOOL 共用这份预算。
	 */
	private int knowledgeContextChars;

	private int knowledgeContextChunks;

	/**
	 * 本次请求已选入知识片段的累计 token 估算值。
	 *
	 * 仅在 token 预算开启时累计，不代表模型服务返回的 usage。
	 */
	private int knowledgeContextEstimatedTokens;

	/**
	 * 本次请求中，各来源已经选入的片段数量。
	 *
	 * 与字符预算一样跨上下文筛选调用累计，不能放在共享的 Support Bean 中。 只有真正选入的片段才计数。
	 */
	private final Map<String, Integer> knowledgeChunksBySource = new HashMap<>();

	/**
	 * 本次筛选的 token 预算信息。
	 *
	 * addedEstimatedTokens、skippedCount 是本次筛选值； totalEstimatedTokens 是整个请求的累计值。
	 * enabled=false 时，零值表示未计量，不代表实际不消耗 token。
	 */
	public record KnowledgeTokenUsage(boolean enabled, String estimatorName, String countingScope,
			int maxEstimatedTokens, int addedEstimatedTokens, int totalEstimatedTokens, int skippedCount) {
	}

	/**
	 * 上下文准备结果，不单独证明模型已收到或使用这些证据。
	 */
	public record KnowledgeContextSelection(KnowledgeRetrievalResult selected, int candidateCount, int duplicateCount,
			int invalidCount, int oversizedCount, int budgetSkippedCount, int addedChars, int totalChars,
			int totalChunks, int maxChunksPerSource, int sourceLimitSkippedCount, KnowledgeTokenUsage tokenBudget) {
	}

	@Getter
	private final String traceId;

	@Getter
	private final String userMessage;

	private final long agentStartTime;

	private final long maxExecutionTimeMs;

	private final ObjectMapper objectMapper;

	private final AgentJsonHelper agentJsonHelper;

	private final List<AgentTraceStep> steps = new ArrayList<>();

	private final Set<String> executedToolCalls = new HashSet<>();

	private int toolCallCount;

	private boolean usedTool;

	private long totalToolCostMs;

	private ToolExecutionResult lastToolExecutionResult;

	/**
	 * 工具失败或保护触发后的确定性最终答案。
	 */
	private String abortAnswer;

	public SpringAiToolRunContext(String traceId, String userMessage, long maxExecutionTimeMs,
			ObjectMapper objectMapper, AgentJsonHelper agentJsonHelper) {

		if (traceId == null || traceId.isBlank()) {
			throw new IllegalArgumentException("traceId 不能为空");
		}

		if (maxExecutionTimeMs <= 0) {
			throw new IllegalArgumentException("maxExecutionTimeMs 必须大于 0");
		}

		this.traceId = traceId;
		this.userMessage = userMessage;
		this.maxExecutionTimeMs = maxExecutionTimeMs;
		this.objectMapper = objectMapper;
		this.agentJsonHelper = agentJsonHelper;
		this.agentStartTime = System.currentTimeMillis();
	}

	/**
	 * 在请求级上下文中执行一次受治理工具调用。
	 *
	 * synchronized 的目的： 如果模型一次返回多个 tool_calls，确保调用次数、重复签名和 Trace 状态不会因为并发修改而失效。
	 */
	public synchronized ToolExecutionResult executeGovernedTool(String toolName, Map<String, Object> arguments,
			Supplier<ToolExecutionResult> execution) {

		checkExecutionTimeout(toolName, arguments);
		checkMaxToolCalls(toolName, arguments);
		checkRepeatedToolCall(toolName, arguments);

		/*
		 * 这里统计的是模型请求执行工具的次数。 即使 Registry 最终返回参数非法或权限拒绝， 也说明模型已经发起了一次工具调用。
		 */
		toolCallCount++;
		usedTool = true;

		ToolExecutionResult executionResult = execution.get();

		recordToolExecution(arguments, executionResult);

		if (!Boolean.TRUE.equals(executionResult.getSuccess())) {
			abortAnswer = buildFailureAnswer(executionResult);

			throw new SpringAiToolAbortException(abortAnswer);
		}

		return executionResult;
	}

	/**
	 * 添加本次 ChatClient 整体调用步骤。
	 *
	 * 该耗时包含： - 第一次模型调用； - 工具执行； - 后续模型总结； - Spring AI 内部转换开销。
	 *
	 * Spring AI 1.1 的内部 Tool Calling Loop 没有直接暴露每一轮模型耗时， 因此不伪造 decisionCostMs 和
	 * summaryCostMs。
	 */
	public synchronized void addSpringAiChatStep(boolean success, long costMs, String answer, String errorMessage) {

		String description;

		if (!success) {
			description = hasAbortAnswer() ? "Spring AI Tool Calling 因治理失败终止" : "Spring AI 模型调用失败";
		}
		else if (usedTool) {
			description = "Spring AI 根据工具证据生成最终回答";
		}
		else {
			description = "Spring AI 判断无需工具并直接生成回答";
		}

		steps
			.add(new AgentTraceStep("SPRING_AI_CHAT", description, success, costMs, userMessage, answer, errorMessage));
	}

	/**
	 * 记录在模型调用前由请求边界策略返回的确定性澄清。
	 */
	public synchronized void addDeterministicClarificationStep(long costMs, String answer) {
		steps.add(new AgentTraceStep("AGENT_CLARIFICATION", "输入证据不足，跳过模型和工具并返回确定性澄清", true, costMs, userMessage, answer,
				null));
	}

	/**
	 * 构造正式响应，并附带已经完成的引用审计结果。
	 */
	public synchronized AgentAskResponse buildResponse(String answer, long agentCostMs) {
		for (AgentTraceStep step : steps) {
			step.setInputView(agentJsonHelper.parseJsonIfPossible(step.getInput()));
			step.setOutputView(agentJsonHelper.parseJsonIfPossible(step.getOutput()));
		}

		String toolName = lastToolExecutionResult == null ? null : lastToolExecutionResult.getToolName();

		Object toolResult = lastToolExecutionResult == null ? null
				: agentJsonHelper.parseJsonIfPossible(lastToolExecutionResult.getResult());

		Long toolCostMs = usedTool ? totalToolCostMs : null;

		return new AgentAskResponse(answer, usedTool, toolName, toolResult, toolCostMs, agentCostMs, traceId, null,
				null, new ArrayList<>(steps), knowledgeCitations);
	}

	public synchronized boolean hasAbortAnswer() {
		return abortAnswer != null && !abortAnswer.isBlank();
	}

	public synchronized String getAbortAnswer() {
		return abortAnswer;
	}

	public synchronized int getToolCallCount() {
		return toolCallCount;
	}

	/**
	 * 在真正执行工具前拒绝缺少原始输入证据的调用。
	 */
	synchronized void rejectToolCall(String description, String toolName, Map<String, Object> arguments,
			String errorMessage) {
		Map<String, Object> guardInput = new LinkedHashMap<>();
		guardInput.put("toolName", toolName);
		guardInput.put("arguments", arguments);

		addGuardStep(description, toJsonSafely(guardInput), errorMessage);

		abortAnswer = errorMessage;

		throw new SpringAiToolAbortException(errorMessage);
	}

	public synchronized List<AgentTraceStep> getSteps() {
		return new ArrayList<>(steps);
	}

	public synchronized ToolExecutionResult getLastToolExecutionResult() {
		return lastToolExecutionResult;
	}

	private void checkExecutionTimeout(String toolName, Map<String, Object> arguments) {

		long elapsedTimeMs = System.currentTimeMillis() - agentStartTime;

		if (elapsedTimeMs < maxExecutionTimeMs) {
			return;
		}

		String errorMessage = "Agent 执行时间已超过 " + maxExecutionTimeMs + " 毫秒，已停止继续执行。";

		addGuardStep("总执行时间保护",
				toJsonSafely(Map.of("toolName", toolName, "arguments", arguments, "elapsedTimeMs", elapsedTimeMs)),
				errorMessage);

		abortAnswer = errorMessage;

		throw new SpringAiToolAbortException(errorMessage);
	}

	private void checkMaxToolCalls(String toolName, Map<String, Object> arguments) {

		if (toolCallCount < MAX_TOOL_CALLS) {
			return;
		}

		String errorMessage = "Agent 已达到最大工具调用次数，已停止继续执行。";

		addGuardStep("最大工具调用次数保护",
				toJsonSafely(Map.of("toolName", toolName, "arguments", arguments, "toolCallCount", toolCallCount)),
				errorMessage);

		abortAnswer = errorMessage;

		throw new SpringAiToolAbortException(errorMessage);
	}

	private void checkRepeatedToolCall(String toolName, Map<String, Object> arguments) {

		String signature = buildToolCallSignature(toolName, arguments);

		if (executedToolCalls.add(signature)) {
			return;
		}

		String errorMessage = "Agent 检测到重复的工具调用，已停止继续执行。";

		addGuardStep("重复工具调用保护", signature, errorMessage);

		abortAnswer = errorMessage;

		throw new SpringAiToolAbortException(errorMessage);
	}

	private void recordToolExecution(Map<String, Object> arguments, ToolExecutionResult executionResult) {

		if (executionResult == null) {
			throw new IllegalStateException("ToolRegistry 返回结果不能为空");
		}

		lastToolExecutionResult = executionResult;

		if (executionResult.getCostMs() != null) {
			totalToolCostMs += executionResult.getCostMs();
		}

		steps.add(new AgentTraceStep("TOOL_EXECUTION", "执行工具：" + executionResult.getToolName(),
				executionResult.getSuccess(), executionResult.getCostMs(), toJsonSafely(arguments),
				executionResult.getResult(), executionResult.getStatus(), executionResult.getErrorCode(),
				executionResult.getErrorMessage()));
	}

	private void addGuardStep(String description, String input, String errorMessage) {

		steps.add(new AgentTraceStep("AGENT_GUARD", description, false, 0L, input, errorMessage, errorMessage));
	}

	private String buildFailureAnswer(ToolExecutionResult executionResult) {

		if (executionResult.getResult() != null && !executionResult.getResult().isBlank()) {

			return executionResult.getResult();
		}

		if (executionResult.getErrorMessage() != null && !executionResult.getErrorMessage().isBlank()) {

			return executionResult.getErrorMessage();
		}

		return "工具执行失败，toolName=" + executionResult.getToolName() + "，status=" + executionResult.getStatus();
	}

	private String buildToolCallSignature(String toolName, Map<String, Object> arguments) {

		String normalizedToolName = toolName == null ? "" : toolName.trim();

		Map<String, Object> normalizedArguments = arguments == null ? Map.of() : new TreeMap<>(arguments);

		return normalizedToolName + "|" + toJsonSafely(normalizedArguments);
	}

	private String toJsonSafely(Object value) {
		if (value == null) {
			return null;
		}

		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception e) {
			return String.valueOf(value);
		}
	}

	/**
	 * 记录 PRE_DECISION 最终选入上下文的结果。 去重登记由 selectFreshKnowledge 统一完成。
	 */
	public synchronized void recordPreDecisionKnowledge(KnowledgeRetrievalResult result) {
		preDecisionKnowledgeMatched = result != null && result.matched();
	}

	public synchronized boolean hasPreDecisionKnowledge() {
		return preDecisionKnowledgeMatched;
	}

	/**
	 * 不限制来源数量的入口，保持现有调用的行为。
	 */
	public synchronized KnowledgeContextSelection selectFreshKnowledge(KnowledgeRetrievalResult result, int maxChunks,
			int maxChunkChars, int maxTotalChars) {

		return selectFreshKnowledge(result, maxChunks, maxChunkChars, maxTotalChars, 0);
	}

	/**
	 * 保留不启用 token 预算的入口。
	 */
	public synchronized KnowledgeContextSelection selectFreshKnowledge(KnowledgeRetrievalResult result, int maxChunks,
			int maxChunkChars, int maxTotalChars, int maxChunksPerSource) {

		return selectFreshKnowledge(result, maxChunks, maxChunkChars, maxTotalChars, maxChunksPerSource,
				KnowledgeTokenBudget.disabled());
	}

	/**
	 * 按输入顺序选择片段，并累计请求级字符、来源和 token 估算用量。
	 *
	 * 各排除原因只记录首先触发的条件。 只有通过全部约束的片段，才登记引用、去重和预算占用。
	 */
	public synchronized KnowledgeContextSelection selectFreshKnowledge(KnowledgeRetrievalResult result, int maxChunks,
			int maxChunkChars, int maxTotalChars, int maxChunksPerSource, KnowledgeTokenBudget tokenBudget) {

		if (maxChunks <= 0 || maxChunkChars <= 0 || maxTotalChars <= 0) {
			throw new IllegalArgumentException("知识上下文预算必须大于 0");
		}

		if (maxChunksPerSource < 0) {
			throw new IllegalArgumentException("单来源片段上限不能为负数，0 表示关闭");
		}

		Objects.requireNonNull(result, "检索结果不能为空");
		Objects.requireNonNull(tokenBudget, "token 预算规则不能为空");

		List<KnowledgeChunk> selected = new ArrayList<>();

		int duplicateCount = 0;
		int invalidCount = 0;
		int oversizedCount = 0;
		int budgetSkippedCount = 0;
		int sourceLimitSkippedCount = 0;
		int tokenBudgetSkippedCount = 0;
		int addedChars = 0;
		int addedEstimatedTokens = 0;

		for (KnowledgeChunk chunk : result.chunks()) {
			if (chunk == null || chunk.chunkId() == null || chunk.chunkId().isBlank() || chunk.content() == null
					|| chunk.content().isBlank()) {
				invalidCount++;
				continue;
			}

			String key = result.retriever() + ":" + chunk.chunkId();

			if (injectedKnowledgeKeys.contains(key)) {
				duplicateCount++;
				continue;
			}

			int chars = chunk.content().length();

			if (chars > maxChunkChars) {
				oversizedCount++;
				continue;
			}

			if (knowledgeContextChunks >= maxChunks || chars > maxTotalChars - knowledgeContextChars) {
				budgetSkippedCount++;
				continue;
			}

			String sourceKey = chunk.source() == null ? "" : chunk.source().trim();
			int sourceCount = knowledgeChunksBySource.getOrDefault(sourceKey, 0);

			if (maxChunksPerSource > 0 && sourceCount >= maxChunksPerSource) {
				sourceLimitSkippedCount++;
				continue;
			}

			/*
			 * 前面的确定性条件通过后才分词计数。 当前片段放不下时继续考虑后续片段，不截断正文。
			 */
			int estimatedTokens = tokenBudget.estimate(chunk);

			if (tokenBudget.enabled()
					&& estimatedTokens > tokenBudget.maxEstimatedTokens() - knowledgeContextEstimatedTokens) {
				tokenBudgetSkippedCount++;
				continue;
			}

			injectedKnowledgeKeys.add(key);

			// 同一个 ID 保留首次选入的片段快照，不在回答阶段重新检索。
			preparedKnowledgeChunks.putIfAbsent(chunk.chunkId(), chunk);

			selected.add(chunk);

			knowledgeContextChunks++;
			knowledgeContextChars += chars;
			addedChars += chars;
			knowledgeChunksBySource.put(sourceKey, sourceCount + 1);

			knowledgeContextEstimatedTokens += estimatedTokens;
			addedEstimatedTokens += estimatedTokens;
		}

		KnowledgeTokenUsage tokenUsage = new KnowledgeTokenUsage(tokenBudget.enabled(), tokenBudget.estimatorName(),
				tokenBudget.countingScope(), tokenBudget.maxEstimatedTokens(), addedEstimatedTokens,
				knowledgeContextEstimatedTokens, tokenBudgetSkippedCount);

		return new KnowledgeContextSelection(new KnowledgeRetrievalResult(result.retriever(), selected),
				result.chunks().size(), duplicateCount, invalidCount, oversizedCount, budgetSkippedCount, addedChars,
				knowledgeContextChars, knowledgeContextChunks, maxChunksPerSource, sourceLimitSkippedCount, tokenUsage);
	}

	/**
	 * 从实际选入片段中派生引用允许集合，返回不可变副本。
	 */
	public synchronized Set<String> getPreparedKnowledgeChunkIds() {
		return Set.copyOf(preparedKnowledgeChunks.keySet());
	}

	/**
	 * 检查最终回答的引用 ID，并关联本次选入的片段快照。
	 *
	 * 顺序沿用回答中 ID 的首次出现顺序。 未选入的 ID 只作为 unknownIds 返回，不补造来源。
	 */
	public synchronized KnowledgeCitationReport auditKnowledgeCitations(String answer) {
		KnowledgeCitationChecker.CheckResult check = KnowledgeCitationChecker.check(answer,
				preparedKnowledgeChunks.keySet());

		List<KnowledgeCitationReport.Citation> citations = new ArrayList<>();

		for (String id : check.citedIds()) {
			KnowledgeChunk chunk = preparedKnowledgeChunks.get(id);

			if (chunk == null) {
				continue;
			}

			citations.add(new KnowledgeCitationReport.Citation(chunk.chunkId(), chunk.source(), chunk.title(),
					chunk.section(), chunk.content(), false));
		}

		knowledgeCitations = new KnowledgeCitationReport(check.status(), check.citedIds(), check.unknownIds(),
				citations);

		return knowledgeCitations;
	}

	/**
	 * Spring AI 周边组件统一通过运行上下文追加 Trace。
	 */
	public synchronized void addStep(AgentTraceStep step) {
		if (step != null) {
			steps.add(step);
		}
	}

}
