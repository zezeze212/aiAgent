package com.example.agent.dto;

import com.example.agent.tool.ToolExecutionStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentTraceStepResponse {

	private Integer stepOrder;

	private String stepName;

	private String description;

	private Boolean success;

	private Long costMs;

	private String input;

	private String output;

	private Object inputView;

	private Object outputView;

	/**
	 * 工具执行框架状态。
	 *
	 * 非 TOOL_EXECUTION 步骤为 null。
	 */
	private ToolExecutionStatus toolStatus;

	/**
	 * 工具业务错误码。
	 */
	private String errorCode;

	private String errorMessage;

}