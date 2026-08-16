package com.example.agent.tool;

import com.example.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolAccessPolicyTest {

	@Test
	void shouldAllowToolWhenPermissionRiskAndSideEffectAreAllowed() {
		AgentProperties properties = buildProperties(List.of("DATABASE_METADATA_READ"), ToolRiskLevel.LOW, false);

		ToolAccessPolicy policy = new ToolAccessPolicy(properties);

		ToolDefinition definition = buildDefinition(ToolRiskLevel.LOW, true, ToolSideEffect.NONE,
				List.of("DATABASE_METADATA_READ"));

		ToolAccessDecision decision = policy.evaluate(definition);

		assertTrue(decision.allowed());
	}

	@Test
	void shouldDenyToolWhenRequiredPermissionIsMissing() {
		AgentProperties properties = buildProperties(List.of(), ToolRiskLevel.LOW, false);

		ToolAccessPolicy policy = new ToolAccessPolicy(properties);

		ToolDefinition definition = buildDefinition(ToolRiskLevel.LOW, true, ToolSideEffect.NONE,
				List.of("DATABASE_METADATA_READ"));

		ToolAccessDecision decision = policy.evaluate(definition);

		assertFalse(decision.allowed());
		assertTrue(decision.reason().contains("DATABASE_METADATA_READ"));
	}

	@Test
	void shouldDenyToolWhenRiskLevelExceedsAllowedLevel() {
		AgentProperties properties = buildProperties(List.of("HTTP_EXTERNAL_READ"), ToolRiskLevel.LOW, false);

		ToolAccessPolicy policy = new ToolAccessPolicy(properties);

		ToolDefinition definition = buildDefinition(ToolRiskLevel.MEDIUM, true, ToolSideEffect.NONE,
				List.of("HTTP_EXTERNAL_READ"));

		ToolAccessDecision decision = policy.evaluate(definition);

		assertFalse(decision.allowed());
		assertTrue(decision.reason().contains("风险等级"));
	}

	@Test
	void shouldDenySideEffectToolByDefault() {
		AgentProperties properties = buildProperties(List.of("TEST_WRITE"), ToolRiskLevel.LOW, false);

		ToolAccessPolicy policy = new ToolAccessPolicy(properties);

		ToolDefinition definition = buildDefinition(ToolRiskLevel.LOW, false, ToolSideEffect.MUTATING,
				List.of("TEST_WRITE"));

		ToolAccessDecision decision = policy.evaluate(definition);

		assertFalse(decision.allowed());
		assertTrue(decision.reason().contains("副作用"));
	}

	private AgentProperties buildProperties(List<String> allowedPermissions, ToolRiskLevel maxRiskLevel,
			boolean allowSideEffectTools) {

		AgentProperties properties = new AgentProperties();

		properties.getToolGovernance().setAllowedPermissions(allowedPermissions);

		properties.getToolGovernance().setMaxRiskLevel(maxRiskLevel);

		properties.getToolGovernance().setAllowSideEffectTools(allowSideEffectTools);

		return properties;
	}

	private ToolDefinition buildDefinition(ToolRiskLevel riskLevel, boolean readOnly, ToolSideEffect sideEffect,
			List<String> requiredPermissions) {

		return new ToolDefinition("testTool", "测试工具", List.of(), "测试结果", 1000L, readOnly, sideEffect, riskLevel,
				requiredPermissions);
	}

}