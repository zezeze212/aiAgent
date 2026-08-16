package com.example.agent.tool;

import com.example.agent.dto.ToolExecutionResult;
import com.example.agent.dto.ToolInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ToolRegistry {

	private final Map<String, AgentTool> toolMap = new HashMap<>();

	private final ToolArgumentValidator toolArgumentValidator;

	private final ToolExecutionRunner toolExecutionRunner;

	private final ToolAccessPolicy toolAccessPolicy;

	public ToolRegistry(List<AgentTool> tools, ToolArgumentValidator toolArgumentValidator,
			ToolExecutionRunner toolExecutionRunner, ToolAccessPolicy toolAccessPolicy) {

		this.toolArgumentValidator = toolArgumentValidator;

		this.toolExecutionRunner = toolExecutionRunner;

		this.toolAccessPolicy = toolAccessPolicy;

		for (AgentTool tool : tools) {
			ToolDefinition definition = tool.definition();

			String toolName = definition.name();

			if (toolMap.containsKey(toolName)) {
				throw new IllegalArgumentException("工具名称重复：" + toolName);
			}

			toolMap.put(toolName, tool);
		}

		log.info("Agent 工具注册完成，工具数量={}, 工具列表={}", toolMap.size(), toolMap.keySet());
	}

	public String execute(String toolName, Map<String, Object> arguments) {

		return executeWithResult(toolName, arguments).getResult();
	}

	public ToolExecutionResult executeWithResult(String toolName, Map<String, Object> arguments) {

		long startTime = System.currentTimeMillis();

		/*
		 * arguments 可能包含 StackTrace、Token 等内容， INFO 日志只记录工具名，完整参数进入受治理 Trace。
		 */
		log.info("开始执行 Agent 工具，toolName={}", toolName);

		AgentTool tool = toolMap.get(toolName);

		/*
		 * 第一道边界：工具必须存在。
		 */
		if (tool == null) {
			long costMs = System.currentTimeMillis() - startTime;

			String errorMessage = "未知工具：" + toolName;

			return ToolExecutionResult.failure(toolName, ToolExecutionStatus.TOOL_NOT_FOUND, errorMessage, errorMessage,
					costMs);
		}

		ToolDefinition definition = tool.definition();

		/*
		 * 第二道边界：检查当前运行环境是否允许执行该工具。
		 *
		 * 权限检查发生在参数详细校验之前， 未授权工具不继续暴露或处理更多调用细节。
		 */
		ToolAccessDecision accessDecision = toolAccessPolicy.evaluate(definition);

		if (!accessDecision.allowed()) {
			long costMs = System.currentTimeMillis() - startTime;

			String errorMessage = accessDecision.reason();

			log.warn("Agent 工具访问被拒绝，toolName={}, status={}, costMs={}, reason={}", toolName,
					ToolExecutionStatus.PERMISSION_DENIED, costMs, errorMessage);

			return ToolExecutionResult.failure(toolName, ToolExecutionStatus.PERMISSION_DENIED,
					"当前环境不允许执行工具：" + toolName, errorMessage, costMs);
		}

		Map<String, Object> normalizedArguments;

		try {
			/*
			 * 第三道边界：参数必须符合机器可读工具契约。
			 */
			normalizedArguments = toolArgumentValidator.validateAndNormalize(definition, arguments);
		}
		catch (IllegalArgumentException e) {
			long costMs = System.currentTimeMillis() - startTime;

			String errorMessage = e.getMessage();

			String result = "工具参数校验失败：" + errorMessage;

			return ToolExecutionResult.failure(toolName, ToolExecutionStatus.INVALID_ARGUMENT, result, errorMessage,
					costMs);
		}

		try {
			/*
			 * 参数和访问策略均通过后， 才真正占用工具执行线程池。
			 */
			String result = toolExecutionRunner.execute(tool, normalizedArguments);

			long costMs = System.currentTimeMillis() - startTime;

			log.info("Agent 工具执行成功，toolName={}, status={}, costMs={}, resultLength={}", toolName,
					ToolExecutionStatus.SUCCESS, costMs, result == null ? 0 : result.length());

			return ToolExecutionResult.success(toolName, result, costMs);
		}
		catch (ToolTimeoutException e) {
			long costMs = System.currentTimeMillis() - startTime;

			String result = "工具执行超时，已停止等待。toolName=" + toolName;

			log.warn("Agent 工具执行超时，toolName={}, status={}, timeoutMs={}, costMs={}", toolName,
					ToolExecutionStatus.TIMEOUT, e.getTimeoutMs(), costMs);

			return ToolExecutionResult.failure(toolName, ToolExecutionStatus.TIMEOUT, result, e.getMessage(), costMs);
		}
		catch (ToolBusinessException e) {
			long costMs = System.currentTimeMillis() - startTime;

			log.warn("Agent 工具业务失败，toolName={}, status={}, errorCode={}, costMs={}, error={}", toolName,
					ToolExecutionStatus.BUSINESS_ERROR, e.getErrorCode(), costMs, e.getMessage());

			return ToolExecutionResult.businessFailure(toolName, e.getErrorCode(), e.getResult(), e.getMessage(),
					costMs);
		}
		catch (Exception e) {
			long costMs = System.currentTimeMillis() - startTime;

			String errorMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();

			log.error("Agent 工具执行异常，toolName={}, status={}, costMs={}", toolName, ToolExecutionStatus.EXECUTION_ERROR,
					costMs, e);

			return ToolExecutionResult.failure(toolName, ToolExecutionStatus.EXECUTION_ERROR, "工具执行失败：" + errorMessage,
					errorMessage, costMs);
		}
	}

	public String buildToolsPrompt() {
		StringBuilder builder = new StringBuilder();

		for (AgentTool tool : toolMap.values()) {
			ToolDefinition definition = tool.definition();

			builder.append("工具名称：").append(definition.name()).append("\n");

			builder.append("工具描述：").append(definition.description()).append("\n");

			builder.append("参数约束：\n").append(buildParameterPrompt(definition));

			builder.append("返回结果：").append(definition.resultDescription()).append("\n");

			builder.append("工具属性：readOnly=")
				.append(definition.readOnly())
				.append(", sideEffect=")
				.append(definition.sideEffect())
				.append(", riskLevel=")
				.append(definition.riskLevel())
				.append(", timeoutMs=")
				.append(definition.timeoutMs())
				.append(", requiredPermissions=")
				.append(definition.requiredPermissions())
				.append("\n\n");
		}

		return builder.toString();
	}

	public List<ToolInfo> listTools() {
		return toolMap.values().stream().map(AgentTool::definition).map(ToolInfo::from).toList();
	}

	private String buildParameterPrompt(ToolDefinition definition) {

		if (definition.parameters().isEmpty()) {
			return "- 无参数\n";
		}

		StringBuilder builder = new StringBuilder();

		for (ToolParameterDefinition parameter : definition.parameters()) {

			builder.append("- ")
				.append(parameter.name())
				.append(": type=")
				.append(parameter.type())
				.append(", required=")
				.append(parameter.required());

			if (parameter.minLength() != null) {
				builder.append(", minLength=").append(parameter.minLength());
			}

			if (parameter.maxLength() != null) {
				builder.append(", maxLength=").append(parameter.maxLength());
			}

			if (parameter.pattern() != null && !parameter.pattern().isBlank()) {

				builder.append(", pattern=").append(parameter.pattern());
			}

			if (!parameter.allowedValues().isEmpty()) {
				builder.append(", allowedValues=").append(parameter.allowedValues());
			}

			if (parameter.description() != null && !parameter.description().isBlank()) {

				builder.append(", description=").append(parameter.description());
			}

			builder.append("\n");
		}

		return builder.toString();
	}

}