package com.example.agent.tool;

/**
 * 表示工具已经正常进入业务执行，但得到一个确定性的业务失败结果。
 *
 * <p>
 * 它与参数校验失败不同： INVALID_ARGUMENT 表示调用方传入的参数本身不合法，具体工具不会执行； ToolBusinessException
 * 表示参数合法，工具已经执行，只是目标业务条件不成立。
 *
 * <p>
 * 例如： TABLE_NOT_FOUND：数据库访问正常，但指定表不存在； HTTP_404：HTTP 请求正常完成，但目标资源不存在。
 *
 * <p>
 * 该异常由具体工具抛出，由 ToolRegistry 统一转换成 BUSINESS_ERROR 类型的 ToolExecutionResult。
 */
public class ToolBusinessException extends RuntimeException {

	private final String errorCode;

	private final String result;

	public ToolBusinessException(String errorCode, String message) {

		this(errorCode, message, message);
	}

	public ToolBusinessException(String errorCode, String result, String message) {

		super(message);

		if (errorCode == null || errorCode.isBlank()) {
			throw new IllegalArgumentException("工具业务错误码不能为空");
		}

		this.errorCode = errorCode;
		this.result = result;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public String getResult() {
		return result;
	}

}