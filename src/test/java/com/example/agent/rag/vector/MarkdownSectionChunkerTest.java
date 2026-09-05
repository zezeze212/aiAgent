package com.example.agent.rag.vector;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownSectionChunkerTest {

	private final MarkdownSectionChunker chunker = new MarkdownSectionChunker();

	@Test
	void shouldSplitMarkdownBySecondLevelHeading() {
		KnowledgeSource source = new KnowledgeSource("docs/knowledge/test.md", "测试指南", "test", "public");

		String markdown = """
				# 测试指南

				## 常见原因

				第一种原因。
				第二种原因。

				## 排查顺序

				先检查配置。
				再检查日志。
				""";

		List<Document> documents = chunker.chunk("test-v1", source, markdown);

		assertThat(documents).hasSize(2);

		assertThat(documents.get(0).getMetadata()).containsEntry("source", "docs/knowledge/test.md")
			.containsEntry("section", "常见原因")
			.containsEntry("knowledgeVersion", "test-v1")
			.containsEntry("chunkIndex", 0);

		assertThat(documents.get(0).getText()).contains("测试指南", "常见原因", "第一种原因");

		assertThat(documents.get(1).getMetadata()).containsEntry("section", "排查顺序").containsEntry("chunkIndex", 1);
	}

	@Test
	void shouldGenerateStableDocumentIds() {
		KnowledgeSource source = new KnowledgeSource("docs/knowledge/test.md", "测试指南", "test", "public");

		String markdown = """
				# 测试指南

				## 排查顺序

				先检查配置。
				""";

		List<Document> first = chunker.chunk("test-v1", source, markdown);
		List<Document> second = chunker.chunk("test-v1", source, markdown);

		assertThat(first.get(0).getId()).isEqualTo(second.get(0).getId());
	}

}