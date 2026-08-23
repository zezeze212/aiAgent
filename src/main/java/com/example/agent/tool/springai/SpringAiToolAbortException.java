package com.example.agent.tool.springai;

/**
 * Spring AI 工具循环需要立即终止时抛出的异常。
 *
 * 典型场景： 1. 工具业务失败； 2. 工具参数非法； 3. 工具权限不足； 4. 工具执行超时； 5. 重复调用保护； 6. 最大调用次数保护； 7. Agent
 * 总执行时间保护。
 *
 * 最终答案已经保存在 SpringAiToolRunContext 中， Service 捕获 Spring AI 外层异常后使用确定性结果返回。
 */
public class SpringAiToolAbortException extends RuntimeException {

	public SpringAiToolAbortException(String message) {
		super(message);
	}

}