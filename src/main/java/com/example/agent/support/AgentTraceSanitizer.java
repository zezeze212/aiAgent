package com.example.agent.support;

import com.example.agent.dto.AgentTraceStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent Trace 对外展示前的统一治理组件。
 *
 * 只处理 Trace 中的 input / output / errorMessage， 不修改 Agent 真正参与模型调用和工具执行的数据。
 */
@Component
@RequiredArgsConstructor
public class AgentTraceSanitizer {

	private final TraceContentSanitizer traceContentSanitizer;

	private final AgentJsonHelper agentJsonHelper;

	public void sanitize(List<AgentTraceStep> steps) {
		if (steps == null || steps.isEmpty()) {
			return;
		}

		for (AgentTraceStep step : steps) {
			sanitizeStep(step);
		}
	}

	private void sanitizeStep(AgentTraceStep step) {
		if (step == null) {
			return;
		}

		String sanitizedInput = traceContentSanitizer.sanitize(step.getInput());

		String sanitizedOutput = traceContentSanitizer.sanitize(step.getOutput());

		String sanitizedErrorMessage = traceContentSanitizer.sanitize(step.getErrorMessage());

		step.setInput(sanitizedInput);
		step.setOutput(sanitizedOutput);
		step.setErrorMessage(sanitizedErrorMessage);

		/*
		 * inputView / outputView 原来是根据未脱敏字符串生成的。
		 *
		 * 如果这里只修改 input/output，却不重新生成 View， API 仍然可能通过 inputView/outputView 泄露原始敏感值。
		 */
		step.setInputView(agentJsonHelper.parseJsonIfPossible(sanitizedInput));

		step.setOutputView(agentJsonHelper.parseJsonIfPossible(sanitizedOutput));
	}

}