package com.example.agent.tool;

/**
 * 工具可能产生的副作用等级。
 *
 * M2-1 只声明元数据，不在运行时执行副作用授权。
 */
public enum ToolSideEffect {

	/**
	 * 纯读取或纯计算，不修改任何外部状态。
	 */
	NONE,

	/**
	 * 存在有限的外部影响。
	 */
	LIMITED,

	/**
	 * 会修改外部系统状态。
	 */
	MUTATING

}