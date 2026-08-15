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

	public ToolRegistry(List<AgentTool> tools, ToolArgumentValidator toolArgumentValidator) {

		this.toolArgumentValidator = toolArgumentValidator;

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

		log.info("开始执行 Agent 工具，toolName={}, arguments={}", toolName, arguments);

		AgentTool tool = toolMap.get(toolName);

		if (tool == null) {
			long costMs = System.currentTimeMillis() - startTime;

			String errorMessage = "未知工具：" + toolName;

			log.warn("Agent 工具执行失败，toolName={}, status={}, costMs={}, error={}", toolName,
					ToolExecutionStatus.TOOL_NOT_FOUND, costMs, errorMessage);

			return ToolExecutionResult.failure(toolName, ToolExecutionStatus.TOOL_NOT_FOUND, errorMessage, errorMessage,
					costMs);
		}

		Map<String, Object> normalizedArguments;

		try {
			normalizedArguments = toolArgumentValidator.validateAndNormalize(tool.definition(), arguments);
		}
		catch (IllegalArgumentException e) {
			long costMs = System.currentTimeMillis() - startTime;

			String errorMessage = e.getMessage();
			String result = "工具参数校验失败：" + errorMessage;

			log.warn("Agent 工具参数校验失败，toolName={}, status={}, costMs={}, error={}", toolName,
					ToolExecutionStatus.INVALID_ARGUMENT, costMs, errorMessage);

			return ToolExecutionResult.failure(toolName, ToolExecutionStatus.INVALID_ARGUMENT, result, errorMessage,
					costMs);
		}

		try {
			String result = tool.execute(normalizedArguments);

			long costMs = System.currentTimeMillis() - startTime;

			log.info("Agent 工具执行成功，toolName={}, status={}, costMs={}, resultLength={}", toolName,
					ToolExecutionStatus.SUCCESS, costMs, result == null ? 0 : result.length());

			return ToolExecutionResult.success(toolName, result, costMs);
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