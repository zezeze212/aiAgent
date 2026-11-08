package com.example.agent.tool.springai;

import com.example.agent.dto.KnowledgeCitationReport;
import com.example.agent.rag.KnowledgeChunk;
import com.example.agent.rag.KnowledgeRetrievalResult;
import com.example.agent.support.AgentJsonHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeCitationReportTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private final AgentJsonHelper helper = new AgentJsonHelper(mapper);

	@Test
	void shouldResolveOnlyCitedChunksInFirstAppearanceOrder() {
		SpringAiToolRunContext context = context();

		context.selectFreshKnowledge(
				result(chunk("c1", "a.md", "原因"), chunk("c2", "b.md", "处理步骤"), chunk("c3", "c.md", "未被回答引用的内容")), 5,
				2000, 6000);

		String answer = "先处理 [知识:c2]，原因见 [知识:c1]。" + "再次引用 [知识:c2]，未知引用 [知识:fake]。";

		KnowledgeCitationReport report = context.auditKnowledgeCitations(answer);

		assertThat(report.status()).isEqualTo("INVALID_IDS");
		assertThat(report.citedIds()).containsExactly("c2", "c1", "fake");
		assertThat(report.unknownIds()).containsExactly("fake");

		// 去掉未知 ID、重复引用和未被引用的已选入片段。
		assertThat(report.citations()).extracting(KnowledgeCitationReport.Citation::chunkId)
			.containsExactly("c2", "c1");

		assertThat(report.citations().get(0).source()).isEqualTo("b.md");
		assertThat(report.citations().get(0).content()).isEqualTo("处理步骤");
		assertThat(report.semanticSupportChecked()).isFalse();

		var response = context.buildResponse(answer, 10L);

		assertThat(response.getAnswer()).isEqualTo(answer);
		assertThat(response.getKnowledgeCitations()).isEqualTo(report);
	}

	@Test
	void shouldNotResolveRetrievedButRejectedChunk() {
		SpringAiToolRunContext context = context();

		// 两个候选来自同一文档，来源配额只允许 c1 选入。
		context.selectFreshKnowledge(result(chunk("c1", "a.md", "原因"), chunk("c2", "a.md", "步骤")), 5, 2000, 6000, 1);

		KnowledgeCitationReport report = context.auditKnowledgeCitations("处理步骤见 [知识:c2]。");

		assertThat(report.status()).isEqualTo("INVALID_IDS");
		assertThat(report.unknownIds()).containsExactly("c2");
		assertThat(report.citations()).isEmpty();
		assertThat(context.getPreparedKnowledgeChunkIds()).containsExactly("c1");
	}

	@Test
	void shouldDistinguishNotCheckedFromNoCitations() {
		SpringAiToolRunContext context = context();

		assertThat(context.buildResponse("调用失败", 1L).getKnowledgeCitations().status()).isEqualTo("NOT_CHECKED");

		KnowledgeCitationReport report = context.auditKnowledgeCitations("这是一个没有知识引用的回答。");

		assertThat(report.status()).isEqualTo("NO_CITATIONS");
		assertThat(report.citations()).isEmpty();
		assertThat(report.semanticSupportChecked()).isFalse();
	}

	private SpringAiToolRunContext context() {
		return new SpringAiToolRunContext("citation-test", "请解释唯一键冲突", 120_000L, mapper, helper);
	}

	private KnowledgeRetrievalResult result(KnowledgeChunk... chunks) {
		return new KnowledgeRetrievalResult("hybrid", List.of(chunks));
	}

	private KnowledgeChunk chunk(String id, String source, String content) {
		return new KnowledgeChunk(id, source, "测试标题", "测试章节", 0.8, content);
	}

}