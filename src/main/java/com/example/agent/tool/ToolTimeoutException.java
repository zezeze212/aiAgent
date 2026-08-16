package com.example.agent.tool;

/**
 * 表示工具执行时间超过 ToolDefinition.timeoutMs。
 *
 * 该异常只用于框架内部： ToolExecutionRunner 负责抛出， ToolRegistry 负责转换为 TIMEOUT 类型的
 * ToolExecutionResult。
 */
public class ToolTimeoutException extends RuntimeException {

	private final long timeoutMs;

	public ToolTimeoutException(String toolName, long timeoutMs, Throwable cause) {

		super("工具执行超时，toolName=" + toolName + "，timeoutMs=" + timeoutMs, cause);

		this.timeoutMs = timeoutMs;
	}

	public long getTimeoutMs() {
		return timeoutMs;
	}

}