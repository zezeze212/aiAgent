package com.example.agent.tool.springai;

import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.AgentTraceStep;
import com.example.agent.dto.ToolExecutionResult;
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
	 * 与旧 AgentOrchestrator 保持一致： 单次请求最多真正执行三个工具调用。
	 */
	private static final int MAX_TOOL_CALLS = 3;

	/**
	 * PRE_DECISION 阶段是否已经命中并注入知识。
	 *
	 * 如果已经命中，POST_TOOL 阶段不再重复检索， 保持和旧 AgentOrchestrator 一致的行为。
	 */
	private boolean preDecisionKnowledgeMatched;

	/**
	 * 当前请求已经注入过的知识，避免多工具调用时重复追加相同文档。
	 */
	private final Set<String> injectedKnowledgeKeys = new HashSet<>();

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
	 * 构造与旧 Agent 链路可比较的响应。
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
				null, new ArrayList<>(steps));
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
	 * 记录 PRE_DECISION 检索结果。
	 */
	public void recordPreDecisionKnowledge(KnowledgeSearchResult result) {
		preDecisionKnowledgeMatched = result != null && result.matched();

		if (preDecisionKnowledgeMatched) {
			rememberInjectedKnowledge(result);
		}
	}

	public boolean hasPreDecisionKnowledge() {
		return preDecisionKnowledgeMatched;
	}

	/**
	 * 记录已经注入给模型的知识。
	 * @return true：首次注入；false：已经注入过
	 */
	public boolean rememberInjectedKnowledge(KnowledgeSearchResult result) {
		if (result == null || !result.matched()) {
			return false;
		}

		String knowledgeKey = Objects.toString(result.source(), "") + System.lineSeparator()
				+ Objects.toString(result.content(), "");

		return injectedKnowledgeKeys.add(knowledgeKey);
	}

	/**
	 * Spring AI 周边组件统一通过运行上下文追加 Trace。
	 */
	public void addStep(AgentTraceStep step) {
		if (step != null) {
			steps.add(step);
		}
	}

}
