package com.example.agent.dto;

import com.example.agent.tool.ToolDefinition;
import com.example.agent.tool.ToolParameterDefinition;
import com.example.agent.tool.ToolRiskLevel;
import com.example.agent.tool.ToolSideEffect;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolInfo {

	private String name;

	private String description;

	private List<ToolParameterDefinition> parameters;

	private String resultDescription;

	private Long timeoutMs;

	private Boolean readOnly;

	private ToolSideEffect sideEffect;

	private ToolRiskLevel riskLevel;

	private List<String> requiredPermissions;

	public static ToolInfo from(ToolDefinition definition) {
		return new ToolInfo(definition.name(), definition.description(), definition.parameters(),
				definition.resultDescription(), definition.timeoutMs(), definition.readOnly(), definition.sideEffect(),
				definition.riskLevel(), definition.requiredPermissions());
	}

}