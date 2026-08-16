package com.example.agent.tool;

/**
 * 工具访问策略判断结果。
 *
 * ToolAccessPolicy 只负责判断当前环境是否允许执行工具， ToolRegistry 根据判断结果生成最终 ToolExecutionResult。
 */
public record ToolAccessDecision(boolean allowed, String reason) {

	/**
	 * 创建“允许执行”的访问决策。
	 *
	 * 不能命名为 allowed()，因为 record 的 allowed 字段 已经自动生成了实例方法 allowed()。
	 */
	public static ToolAccessDecision permit() {
		return new ToolAccessDecision(true, null);
	}

	/**
	 * 创建“拒绝执行”的访问决策。
	 */
	public static ToolAccessDecision denied(String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("拒绝工具访问时必须提供原因");
		}

		return new ToolAccessDecision(false, reason);
	}

}