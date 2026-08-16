package com.example.agent.config;

import com.example.agent.tool.ToolRiskLevel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

	/**
	 * Agent 单次请求允许的最大执行时间，默认 120 秒。
	 */
	private long maxExecutionTimeMs = 120_000L;

	/**
	 * 工具治理配置。
	 *
	 * 当前是应用实例级别的能力控制，不是用户级 RBAC。 作用是限制当前部署环境能够执行哪些工具。
	 */
	private ToolGovernance toolGovernance = new ToolGovernance();

	@Data
	public static class ToolGovernance {

		/**
		 * 当前运行环境允许的工具权限。
		 *
		 * 默认保持为空，即“默认拒绝”。 具体允许项由 application.yml 显式声明。
		 */
		private List<String> allowedPermissions = new ArrayList<>();

		/**
		 * 当前运行环境允许执行的最高工具风险等级。
		 */
		private ToolRiskLevel maxRiskLevel = ToolRiskLevel.LOW;

		/**
		 * 是否允许执行存在副作用或非只读的工具。
		 *
		 * 当前故障诊断 Agent 默认只允许只读/无副作用工具。
		 */
		private boolean allowSideEffectTools = false;

	}

}