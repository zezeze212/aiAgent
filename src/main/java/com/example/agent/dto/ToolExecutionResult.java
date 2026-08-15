package com.example.agent.dto;

import com.example.agent.tool.ToolExecutionStatus;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 工具统一执行结果。
 *
 * status： 描述框架级执行结果，例如参数非法、工具不存在、执行异常。
 *
 * errorCode： 保留给具体工具的业务错误码，例如 TABLE_NOT_FOUND。
 *
 * errorMessage： 面向日志和排查的具体失败说明。
 */
@Data
@NoArgsConstructor
public class ToolExecutionResult {

	private String toolName;

	private Boolean success;

	private ToolExecutionStatus status;

	private String result;

	private String errorCode;

	private String errorMessage;

	private Long costMs;

	/**
	 * 保留原有五参数构造器。
	 *
	 * 这样 AgentOrchestratorTest 等旧测试不需要因为 M2-2A 一次性全部修改。
	 */
	public ToolExecutionResult(String toolName, Boolean success, String result, String errorMessage, Long costMs) {

		this(toolName, success,
				Boolean.TRUE.equals(success) ? ToolExecutionStatus.SUCCESS : ToolExecutionStatus.EXECUTION_ERROR,
				result, null, errorMessage, costMs);
	}

	public ToolExecutionResult(String toolName, Boolean success, ToolExecutionStatus status, String result,
			String errorCode, String errorMessage, Long costMs) {

		if (status == null) {
			throw new IllegalArgumentException("工具执行状态不能为空");
		}

		if (Boolean.TRUE.equals(success) && status != ToolExecutionStatus.SUCCESS) {

			throw new IllegalArgumentException("工具执行成功时 status 必须为 SUCCESS");
		}

		if (Boolean.FALSE.equals(success) && status == ToolExecutionStatus.SUCCESS) {

			throw new IllegalArgumentException("工具执行失败时 status 不能为 SUCCESS");
		}

		this.toolName = toolName;
		this.success = success;
		this.status = status;
		this.result = result;
		this.errorCode = errorCode;
		this.errorMessage = errorMessage;
		this.costMs = costMs;
	}

	public static ToolExecutionResult success(String toolName, String result, Long costMs) {

		return new ToolExecutionResult(toolName, true, ToolExecutionStatus.SUCCESS, result, null, null, costMs);
	}

	public static ToolExecutionResult failure(String toolName, ToolExecutionStatus status, String result,
			String errorMessage, Long costMs) {

		return new ToolExecutionResult(toolName, false, status, result, null, errorMessage, costMs);
	}

	public static ToolExecutionResult businessFailure(String toolName, String errorCode, String result,
			String errorMessage, Long costMs) {

		if (errorCode == null || errorCode.isBlank()) {
			throw new IllegalArgumentException("业务失败必须提供 errorCode");
		}

		return new ToolExecutionResult(toolName, false, ToolExecutionStatus.BUSINESS_ERROR, result, errorCode,
				errorMessage, costMs);
	}

}