package com.example.agent.tool;

import com.example.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 工具访问策略。
 *
 * 当前实现的是“部署级 capability 控制”，不是用户级 RBAC：
 *
 * 1. requiredPermissions 必须全部被当前环境允许； 2. 工具风险等级不能超过配置允许的最大等级； 3. 默认禁止非只读或存在副作用的工具。
 *
 * 这样可以让 ToolDefinition 中的治理元数据真正参与执行决策。
 */
@Component
public class ToolAccessPolicy {

	private final AgentProperties agentProperties;

	public ToolAccessPolicy(AgentProperties agentProperties) {

		this.agentProperties = agentProperties;
	}

	public ToolAccessDecision evaluate(ToolDefinition definition) {

		if (definition == null) {
			throw new IllegalArgumentException("工具定义不能为空");
		}

		AgentProperties.ToolGovernance governance = agentProperties.getToolGovernance();

		/*
		 * 权限检查优先。
		 *
		 * requiredPermissions 使用“全部满足”语义： 工具声明需要 A、B，则当前环境必须同时拥有 A、B。
		 */
		ToolAccessDecision permissionDecision = checkRequiredPermissions(definition, governance);

		if (!permissionDecision.allowed()) {
			return permissionDecision;
		}

		/*
		 * 再检查工具风险等级。
		 *
		 * LOW 环境不能执行 MEDIUM / HIGH 工具。
		 */
		if (!isRiskAllowed(definition.riskLevel(), governance.getMaxRiskLevel())) {

			return ToolAccessDecision.denied("工具风险等级超过当前运行环境允许范围，toolName=" + definition.name() + "，toolRiskLevel="
					+ definition.riskLevel() + "，maxRiskLevel=" + governance.getMaxRiskLevel());
		}

		/*
		 * 最后检查副作用。
		 *
		 * 当前故障诊断 Agent 默认只允许： readOnly=true sideEffect=NONE
		 *
		 * 未来确实需要写操作时，必须显式打开策略。
		 */
		boolean hasSideEffect = !definition.readOnly() || definition.sideEffect() != ToolSideEffect.NONE;

		if (hasSideEffect && !governance.isAllowSideEffectTools()) {

			return ToolAccessDecision.denied("当前运行环境禁止执行存在副作用或非只读工具，toolName=" + definition.name() + "，readOnly="
					+ definition.readOnly() + "，sideEffect=" + definition.sideEffect());
		}

		return ToolAccessDecision.permit();
	}

	private ToolAccessDecision checkRequiredPermissions(ToolDefinition definition,
			AgentProperties.ToolGovernance governance) {

		List<String> requiredPermissions = definition.requiredPermissions();

		if (requiredPermissions == null || requiredPermissions.isEmpty()) {

			return ToolAccessDecision.permit();
		}

		Set<String> allowedPermissions = new HashSet<>();

		if (governance.getAllowedPermissions() != null) {
			allowedPermissions.addAll(governance.getAllowedPermissions());
		}

		List<String> missingPermissions = requiredPermissions.stream()
			.filter(permission -> !allowedPermissions.contains(permission))
			.toList();

		if (missingPermissions.isEmpty()) {
			return ToolAccessDecision.permit();
		}

		return ToolAccessDecision
			.denied("当前运行环境缺少工具所需权限，toolName=" + definition.name() + "，missingPermissions=" + missingPermissions);
	}

	/**
	 * 显式映射风险等级，而不是直接依赖 enum.ordinal()。
	 *
	 * ordinal() 会受到枚举声明顺序变化影响， 显式等级值更容易理解，也更不容易被以后重构误伤。
	 */
	private boolean isRiskAllowed(ToolRiskLevel toolRiskLevel, ToolRiskLevel maxRiskLevel) {

		return riskValue(toolRiskLevel) <= riskValue(maxRiskLevel);
	}

	private int riskValue(ToolRiskLevel riskLevel) {

		return switch (riskLevel) {
			case LOW -> 1;
			case MEDIUM -> 2;
			case HIGH -> 3;
		};
	}

}