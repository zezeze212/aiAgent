package com.example.agent.tool;

/**
 * 工具执行的框架级结果分类。
 *
 * status 描述的是工具调用在框架层面的执行结果， 不用于描述具体工具自身的业务错误。
 */
public enum ToolExecutionStatus {

	/**
	 * 工具执行成功。
	 */
	SUCCESS,

	/**
	 * 调用参数不符合 ToolDefinition 契约。
	 */
	INVALID_ARGUMENT,

	/**
	 * 请求调用的工具没有注册。
	 */
	TOOL_NOT_FOUND,

	/**
	 * 当前调用方没有执行该工具的权限。
	 *
	 * M2-2A 仅定义语义，暂未实现权限检查。
	 */
	PERMISSION_DENIED,

	/**
	 * 工具执行超过允许时间。
	 *
	 * M2-2A 仅定义语义，暂未实现真正的工具超时控制。
	 */
	TIMEOUT,

	/**
	 * 工具正常运行，但得到了确定性的业务失败结果。
	 *
	 * 例如后续： TABLE_NOT_FOUND HTTP_404
	 */
	BUSINESS_ERROR,

	/**
	 * 工具执行过程中出现非预期程序异常。
	 */
	EXECUTION_ERROR

}