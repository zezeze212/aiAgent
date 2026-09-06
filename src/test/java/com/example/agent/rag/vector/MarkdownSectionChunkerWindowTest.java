package com.example.agent.rag.vector;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownSectionChunkerWindowTest {

	private final MarkdownSectionChunker chunker = new MarkdownSectionChunker();

	private final KnowledgeSource source = new KnowledgeSource("docs/knowledge/sql/test.md", "测试指南", "mysql", "public");

	@Test
	void shouldLimitLongBodyAndPreserveOverlapWithoutLosingText() {
		String body = "甲".repeat(950) + "乙".repeat(950) + "丙".repeat(400);

		List<Document> documents = chunker.chunk("test-v1", source, "# 测试指南\n\n## 原因\n\n" + body);

		assertThat(documents).hasSize(3);

		int previousEnd = 0;
		StringBuilder restored = new StringBuilder();

		for (Document document : documents) {
			int start = (Integer) document.getMetadata().get("bodyStart");
			int end = (Integer) document.getMetadata().get("bodyEnd");

			assertThat(end - start).isLessThanOrEqualTo(1000);
			assertThat(document.getText()).endsWith(body.substring(start, end));

			if (previousEnd > 0) {
				assertThat(previousEnd - start).isEqualTo(100);
			}

			// 去掉重叠部分后，应能还原完整正文，不能出现缺字或跳段。
			restored.append(body, previousEnd, end);
			previousEnd = end;
		}

		assertThat(restored.toString()).isEqualTo(body);
		assertThat(documents.stream().map(Document::getId).toList()).doesNotHaveDuplicates();
	}

	@Test
	void shouldPreferParagraphBoundary() {
		String body = "甲".repeat(600) + "\n\n" + "乙".repeat(600);

		List<Document> documents = chunker.chunk("test-v1", source, "## 原因\n\n" + body);

		assertThat(documents).hasSize(2);
		assertThat(documents.get(0).getMetadata().get("bodyEnd")).isEqualTo(602);
		assertThat(documents.get(1).getMetadata().get("bodyStart")).isEqualTo(502);
	}

	@Test
	void shouldPreserveIntroductionAndIgnoreHeadingInsideCodeFence() {
		String markdown = """
				# 测试指南

				这是文档介绍。

				## 配置

				```bash
				## 这是代码中的注释
				echo hello
				```

				## 排查

				检查配置。
				""";

		List<Document> documents = chunker.chunk("test-v1", source, markdown);

		assertThat(documents.stream().map(d -> d.getMetadata().get("section")).toList()).containsExactly("前言", "配置",
				"排查");

		assertThat(documents.get(0).getText()).contains("这是文档介绍。");
		assertThat(documents.get(1).getText()).contains("## 这是代码中的注释");
	}

	@Test
	void shouldProduceSameDocumentsForWindowsAndLinuxLineEndings() {
		String linux = "# 测试指南\n\n## 原因\n\n第一段。\n\n第二段。";
		String windows = "\uFEFF" + linux.replace("\n", "\r\n");

		List<Document> first = chunker.chunk("test-v1", source, linux);
		List<Document> second = chunker.chunk("test-v1", source, windows);

		assertThat(first.get(0).getId()).isEqualTo(second.get(0).getId());
		assertThat(first.get(0).getText()).isEqualTo(second.get(0).getText());
		assertThat(first.get(0).getMetadata()).isEqualTo(second.get(0).getMetadata());
	}

}