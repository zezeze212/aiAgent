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
 * Markdown 章节感知分块器。
 *
 * 先识别 H2 章节，再对长章节执行有长度限制的分块。 输出 Document 的正文用于后续向量化，metadata 用于来源追溯和位置定位。
 *
 * 当前按 Java UTF-16 字符单位计数，不代表模型 token 数量。
 */
@Component
public class MarkdownSectionChunker {

	private static final Pattern H2_PATTERN = Pattern.compile("^##\\s+(.+?)\\s*$");

	// 识别常见 Markdown 代码围栏，避免将代码中的 ## 当成章节。
	private static final Pattern FENCE_PATTERN = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

	private static final int MAX_BODY_CHARS = 1000;

	private static final int OVERLAP_CHARS = 100;

	private static final String CHUNKING_VERSION = "h2-window-v2";

	/**
	 * 将一份 Markdown 转换为可独立检索的 Document 列表。
	 *
	 * 处理顺序：参数校验 → 文本清洗 → 章节识别 → 章节内分块 → Document。 本方法只处理文本，不访问 Ollama 或 Qdrant。
	 */
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

		String normalized = normalizeMarkdown(markdown);
		List<MarkdownSection> sections = splitSections(normalized);
		List<Document> documents = new ArrayList<>();

		for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
			MarkdownSection section = sections.get(sectionIndex);
			List<BodyChunk> chunks = splitBody(section.content());

			for (int sectionChunkIndex = 0; sectionChunkIndex < chunks.size(); sectionChunkIndex++) {
				documents.add(buildDocument(knowledgeVersion, source, section, chunks.get(sectionChunkIndex),
						sectionIndex, sectionChunkIndex, documents.size()));
			}
		}

		return List.copyOf(documents);
	}

	/**
	 * 统一 Windows/Linux 换行，避免相同文档在不同系统生成不同正文。 不压缩正文中的空格，因为缩进可能属于代码。
	 */
	private String normalizeMarkdown(String markdown) {
		String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');

		if (normalized.startsWith("\uFEFF")) {
			normalized = normalized.substring(1);
		}

		return normalized.strip();
	}

	/**
	 * 提取 H2 章节，同时保留第一个章节前的介绍。 对没有 H2 的文档，使用“全文”作为章节名称。
	 */
	private List<MarkdownSection> splitSections(String markdown) {
		List<MarkdownSection> sections = new ArrayList<>();
		String currentTitle = "前言";
		StringBuilder body = new StringBuilder();

		boolean foundH2 = false;
		boolean firstLine = true;
		char fenceChar = 0;
		int fenceLength = 0;

		// 正数 n 最多拆分 n-1 次，数组长度 ≤ n，末尾的空字符串会被保留 负数（如 -1） 无限次拆分，且保留所有尾部的空字符串 0（默认）
		// 无限次拆分，但丢弃所有尾部的空字符串
		for (String line : markdown.split("\n", -1)) {
			// 文档级 H1 将由 source.title() 统一添加，避免首行标题重复。
			if (firstLine && line.startsWith("# ")) {
				firstLine = false;
				continue;
			}
			firstLine = false;

			Matcher fence = FENCE_PATTERN.matcher(line);

			if (fence.matches()) {
				String marker = fence.group(1);

				if (fenceChar == 0) {
					fenceChar = marker.charAt(0);
					fenceLength = marker.length();
				}
				else if (marker.charAt(0) == fenceChar && marker.length() >= fenceLength && fence.group(2).isBlank()) {
					fenceChar = 0;
					fenceLength = 0;
				}

				body.append(line).append('\n');
				continue;
			}

			Matcher heading = H2_PATTERN.matcher(line);

			if (fenceChar == 0 && heading.matches()) {
				addSection(sections, currentTitle, body);
				currentTitle = heading.group(1).strip();
				body = new StringBuilder();
				foundH2 = true;
			}
			else {
				body.append(line).append('\n');
			}
		}

		addSection(sections, foundH2 ? currentTitle : "全文", body);
		return sections;
	}

	private void addSection(List<MarkdownSection> sections, String title, StringBuilder body) {
		String content = body.toString().strip();

		if (!content.isBlank()) {
			sections.add(new MarkdownSection(title, content));
		}
	}

	/**
	 * 在同一个章节内切分正文。
	 *
	 * 优先保留段落边界；找不到合适边界时使用换行，再退回字符上限。 overlap 只发生在同一章节内，不把前一章节内容带到下一章节。
	 */
	private List<BodyChunk> splitBody(String body) {
		List<BodyChunk> chunks = new ArrayList<>();
		int start = 0;

		while (start < body.length()) {
			int hardEnd = Math.min(start + MAX_BODY_CHARS, body.length());
			int end = chooseEnd(body, start, hardEnd);
			end = safeBoundary(body, end);

			// 保留原始子串，让位置与 overlap 可以准确验证。
			chunks.add(new BodyChunk(body.substring(start, end), start, end));

			if (end == body.length()) {
				break;
			}

			int nextStart = Math.max(start + 1, end - OVERLAP_CHARS);
			start = safeBoundary(body, nextStart);
		}

		return chunks;
	}

	/**
	 * 只在窗口后半段寻找自然边界，避免生成过小 Chunk。 end 使用 Java substring 的右开边界。
	 */
	private int chooseEnd(String body, int start, int hardEnd) {
		if (hardEnd == body.length()) {
			return hardEnd;
		}

		int minimumEnd = start + MAX_BODY_CHARS / 2;

		int paragraphBreak = body.lastIndexOf("\n\n", hardEnd - 2);
		if (paragraphBreak >= minimumEnd) {
			return paragraphBreak + 2;
		}

		int lineBreak = body.lastIndexOf('\n', hardEnd - 1);
		if (lineBreak >= minimumEnd) {
			return lineBreak + 1;
		}

		return hardEnd;
	}

	// 避免把 emoji 等字符的 UTF-16 代理对从中间切开。
	private int safeBoundary(String text, int index) {
		if (index > 0 && index < text.length() && Character.isHighSurrogate(text.charAt(index - 1))
				&& Character.isLowSurrogate(text.charAt(index))) {
			return index - 1;
		}

		return index;
	}

	/**
	 * 将正文和来源组装为 Spring AI Document。
	 *
	 * 每个子块都附带标题和章节，使其脱离整篇文章后仍有主题信息。 偏移量相对于清洗并去除首尾空白后的章节正文。
	 */
	private Document buildDocument(String knowledgeVersion, KnowledgeSource source, MarkdownSection section,
			BodyChunk chunk, int sectionIndex, int sectionChunkIndex, int chunkIndex) {

		String content = "# " + source.title() + "\n\n## " + section.title() + "\n\n" + chunk.text();

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("source", source.source());
		metadata.put("title", source.title());
		metadata.put("section", section.title());
		metadata.put("category", source.category());
		metadata.put("visibility", source.visibility());
		metadata.put("knowledgeVersion", knowledgeVersion);
		metadata.put("chunkIndex", chunkIndex);
		metadata.put("sectionIndex", sectionIndex);
		metadata.put("sectionChunkIndex", sectionChunkIndex);
		metadata.put("bodyStart", chunk.start());
		metadata.put("bodyEnd", chunk.end());
		metadata.put("bodyCharCount", chunk.text().length());
		metadata.put("chunkingVersion", CHUNKING_VERSION);
		metadata.put("maxBodyChars", MAX_BODY_CHARS);
		metadata.put("overlapChars", OVERLAP_CHARS);

		String identity = knowledgeVersion + "|" + source.source() + "|" + CHUNKING_VERSION + "|" + sectionIndex + "|"
				+ sectionChunkIndex;
		String id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();

		return Document.builder().id(id).text(content).metadata(metadata).build();
	}

	private record MarkdownSection(String title, String content) {
	}

	private record BodyChunk(String text, int start, int end) {
	}

}