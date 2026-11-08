package com.example.agent.support;

import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.AgentTraceStep;
import com.example.agent.dto.KnowledgeCitationReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Agent Trace 与知识引用对外展示前的治理组件。
 *
 * 不修改真正参与模型调用和工具执行的运行时证据。
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

	/**
	 * 正式 HTTP 响应出口使用此入口，统一处理 Trace 和结构化知识引用。
	 */
	public void sanitizeResponse(AgentAskResponse response) {
		if (response == null) {
			return;
		}

		sanitize(response.getSteps());

		response.setKnowledgeCitations(sanitizeKnowledgeCitations(response.getKnowledgeCitations()));
	}

	private KnowledgeCitationReport sanitizeKnowledgeCitations(KnowledgeCitationReport report) {

		if (report == null) {
			return KnowledgeCitationReport.notChecked();
		}

		List<KnowledgeCitationReport.Citation> citations = report.citations()
			.stream()
			.map(this::sanitizeCitation)
			.toList();

		// 引用 ID 保持原样用于关联；来源和正文等展示字段使用处理后的副本。
		return new KnowledgeCitationReport(report.status(), report.citedIds(), report.unknownIds(), citations);
	}

	private KnowledgeCitationReport.Citation sanitizeCitation(KnowledgeCitationReport.Citation citation) {

		String source = traceContentSanitizer.sanitize(citation.source());
		String title = traceContentSanitizer.sanitize(citation.title());
		String section = traceContentSanitizer.sanitize(citation.section());
		String content = traceContentSanitizer.sanitize(citation.content());

		boolean modified = citation.displayModified() || !Objects.equals(source, citation.source())
				|| !Objects.equals(title, citation.title()) || !Objects.equals(section, citation.section())
				|| !Objects.equals(content, citation.content());

		return new KnowledgeCitationReport.Citation(citation.chunkId(), source, title, section, content, modified);
	}

}