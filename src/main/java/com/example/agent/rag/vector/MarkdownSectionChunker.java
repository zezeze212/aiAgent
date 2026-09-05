package com.example.agent.rag.vector;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第一版 Markdown 章节分块器。
 *
 * 当前以二级标题“##”作为 Chunk 边界，不做固定字符长度和 overlap。 现有种子文档每个章节较短，足以先建立端到端检索基线。
 */
@Component
public class MarkdownSectionChunker {

	private static final Pattern H2_PATTERN = Pattern.compile("^##\\s+(.+?)\\s*$");

	public List<Document> chunk(String knowledgeVersion, KnowledgeSource source, String markdown) {
		if (knowledgeVersion == null || knowledgeVersion.isBlank()) {
			throw new IllegalArgumentException("knowledgeVersion 不能为空");
		}

		if (source == null) {
			throw new IllegalArgumentException("knowledgeSource 不能为空");
		}

		if (markdown == null || markdown.isBlank()) {
			throw new IllegalArgumentException("Markdown 内容不能为空，source=" + source.source());
		}

		List<MarkdownSection> sections = splitSections(markdown);
		List<Document> documents = new ArrayList<>();

		for (int index = 0; index < sections.size(); index++) {
			MarkdownSection section = sections.get(index);
			documents.add(buildDocument(knowledgeVersion, source, section, index));
		}

		return List.copyOf(documents);
	}

	private List<MarkdownSection> splitSections(String markdown) {
		List<MarkdownSection> sections = new ArrayList<>();

		String currentSection = null;
		StringBuilder currentBody = new StringBuilder();

		for (String line : markdown.split("\\R")) {
			Matcher matcher = H2_PATTERN.matcher(line);

			if (matcher.matches()) {
				addSection(sections, currentSection, currentBody);
				currentSection = matcher.group(1).trim();
				currentBody = new StringBuilder();
				continue;
			}

			if (currentSection != null) {
				currentBody.append(line).append(System.lineSeparator());
			}
		}

		addSection(sections, currentSection, currentBody);

		if (sections.isEmpty()) {
			sections.add(new MarkdownSection("全文", markdown.trim()));
		}

		return sections;
	}

	private void addSection(List<MarkdownSection> sections, String section, StringBuilder body) {
		if (section == null) {
			return;
		}

		String content = body.toString().trim();

		if (!content.isBlank()) {
			sections.add(new MarkdownSection(section, content));
		}
	}

	private Document buildDocument(String knowledgeVersion, KnowledgeSource source, MarkdownSection section,
			int chunkIndex) {

		String content = """
				# %s

				## %s

				%s
				""".formatted(source.title(), section.title(), section.content()).trim();

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("source", source.source());
		metadata.put("title", source.title());
		metadata.put("section", section.title());
		metadata.put("category", source.category());
		metadata.put("visibility", source.visibility());
		metadata.put("knowledgeVersion", knowledgeVersion);
		metadata.put("chunkIndex", chunkIndex);

		return Document.builder()
			.id(buildStableId(knowledgeVersion, source.source(), section.title(), chunkIndex))
			.text(content)
			.metadata(metadata)
			.build();
	}

	private String buildStableId(String knowledgeVersion, String source, String section, int chunkIndex) {
		String identity = knowledgeVersion + "|" + source + "|" + section + "|" + chunkIndex;

		return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
	}

	private record MarkdownSection(String title, String content) {

	}

}