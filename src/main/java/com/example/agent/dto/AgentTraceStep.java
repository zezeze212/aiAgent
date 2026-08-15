package com.example.agent.dto;

import com.example.agent.tool.ToolExecutionStatus;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AgentTraceStep {

	/**
	 * 步骤名称，比如 AI_DECISION、TOOL_EXECUTION、AI_SUMMARY。
	 */
	private String stepName;

	/**
	 * 步骤描述。
	 */
	private String description;

	/**
	 * 是否成功。
	 */
	private Boolean success;

	/**
	 * 步骤耗时，单位毫秒。
	 */
	private Long costMs;

	/**
	 * 步骤原始输入。
	 */
	private String input;

	/**
	 * 步骤原始输出。
	 */
	private String output;

	/**
	 * 用于接口展示的结构化输入。
	 */
	private Object inputView;

	/**
	 * 用于接口展示的结构化输出。
	 */
	private Object outputView;

	/**
	 * 工具执行框架状态。
	 *
	 * 目前只用于 TOOL_EXECUTION： SUCCESS、INVALID_ARGUMENT、TOOL_NOT_FOUND、
	 * PERMISSION_DENIED、TIMEOUT、BUSINESS_ERROR、EXECUTION_ERROR。
	 *
	 * 非工具步骤保持 null。
	 */
	private ToolExecutionStatus toolStatus;

	/**
	 * 工具业务错误码。
	 *
	 * 例如后续： TABLE_NOT_FOUND、HTTP_404。
	 *
	 * 框架错误通常只需要 toolStatus， 因此 INVALID_ARGUMENT、TOOL_NOT_FOUND 等场景可以为 null。
	 */
	private String errorCode;

	/**
	 * 失败原因。
	 */
	private String errorMessage;

	/**
	 * 保留原有步骤构造器。
	 *
	 * AI_DECISION、KNOWLEDGE_RETRIEVAL、AI_SUMMARY、 AGENT_GUARD 等现有调用无需修改。
	 */
	public AgentTraceStep(String stepName, String description, Boolean success, Long costMs, String input,
			String output, String errorMessage) {

		this(stepName, description, success, costMs, input, output, null, null, errorMessage);
	}

	/**
	 * TOOL_EXECUTION 使用的完整构造器。
	 */
	public AgentTraceStep(String stepName, String description, Boolean success, Long costMs, String input,
			String output, ToolExecutionStatus toolStatus, String errorCode, String errorMessage) {

		this.stepName = stepName;
		this.description = description;
		this.success = success;
		this.costMs = costMs;
		this.input = input;
		this.output = output;
		this.toolStatus = toolStatus;
		this.errorCode = errorCode;
		this.errorMessage = errorMessage;
	}

}