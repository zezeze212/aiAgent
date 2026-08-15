package com.example.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentStepLog {

	private Long id;

	private String traceId;

	private String stepName;

	private String description;

	private Integer success;

	/**
	 * 工具执行框架状态。
	 *
	 * 数据库存储枚举名称，例如 INVALID_ARGUMENT。 非 TOOL_EXECUTION 步骤为 null。
	 */
	private String toolStatus;

	/**
	 * 工具业务错误码。
	 */
	private String errorCode;

	private Long costMs;

	private String inputText;

	private String outputText;

	private String errorMessage;

	private Integer stepOrder;

	private LocalDateTime createdTime;

}