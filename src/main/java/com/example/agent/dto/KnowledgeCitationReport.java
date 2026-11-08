package com.example.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 最终回答的知识引用归属与出处。
 *
 * 只说明引用 ID 能否关联到本次准备的片段， 不证明片段支持回答结论，也不证明回答引用充分。
 */
public record KnowledgeCitationReport(String status, List<String> citedIds, List<String> unknownIds,
		List<Citation> citations) {

	public KnowledgeCitationReport {
		// 保存不可变列表，防止响应处理反向修改请求中的审计结果。
		citedIds = List.copyOf(citedIds);
		unknownIds = List.copyOf(unknownIds);
		citations = List.copyOf(citations);
	}

	/**
	 * 调用失败、工具治理终止或确定性澄清等未执行引用审计的情况。
	 */
	public static KnowledgeCitationReport notChecked() {
		return new KnowledgeCitationReport("NOT_CHECKED", List.of(), List.of(), List.of());
	}

	/**
	 * 明确告知接口使用者：当前没有执行引用语义正确性检查。
	 */
	@JsonProperty("semanticSupportChecked")
	public boolean semanticSupportChecked() {
		return false;
	}

	/**
	 * 回答实际引用的一个合法片段。
	 *
	 * content 来自本次选入的片段，不是重新检索结果。 displayModified 表示展示字段被脱敏或裁剪，不代表模型输入被修改。
	 */
	public record Citation(String chunkId, String source, String title, String section, String content,
			boolean displayModified) {
	}
}