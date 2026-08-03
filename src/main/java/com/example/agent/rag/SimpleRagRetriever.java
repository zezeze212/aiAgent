package com.example.agent.rag;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 基于关键词和本地 Markdown 文档的简单知识检索器。
 */
@Component
public class SimpleRagRetriever {

	private static final List<KnowledgeRule> RULES = List.of(
			new KnowledgeRule(List.of("unknown column", "未知列", "字段不存在"), "docs/knowledge/sql/unknown-column.md"),
			new KnowledgeRule(List.of("table doesn't exist", "table does not exist", "表不存在"),
					"docs/knowledge/sql/table-not-exist.md"),
			new KnowledgeRule(List.of("duplicate entry", "重复键", "唯一键冲突"), "docs/knowledge/sql/duplicate-entry.md"),
			new KnowledgeRule(List.of("data too long", "数据过长", "字段长度"), "docs/knowledge/sql/data-too-long.md"),
			new KnowledgeRule(List.of("foreign key constraint", "cannot delete or update parent row", "外键约束"),
					"docs/knowledge/sql/foreign-key-constraint.md"));

	/**
	 * 根据用户问题检索相关知识。
	 * @param query 用户问题或错误信息
	 * @return 知识检索结果
	 */
	public KnowledgeSearchResult retrieve(String query) {
		if (query == null || query.isBlank()) {
			return KnowledgeSearchResult.notFound();
		}

		return retrieveByQuery(query);
	}

	/**
	 * 根据用户问题和工具结果检索相关知识。
	 * @param userMessage 用户原始问题
	 * @param toolResult 工具执行结果
	 * @return 知识检索结果
	 */
	public KnowledgeSearchResult retrieve(String userMessage, String toolResult) {
		String query = buildQuery(userMessage, toolResult);

		if (query.isBlank()) {
			return KnowledgeSearchResult.notFound();
		}

		return retrieveByQuery(query);
	}

	private KnowledgeSearchResult retrieveByQuery(String query) {
		String normalizedQuery = query.toLowerCase(Locale.ROOT);

		for (KnowledgeRule rule : RULES) {
			if (rule.matches(normalizedQuery)) {
				return readKnowledge(rule);
			}
		}

		return KnowledgeSearchResult.notFound();
	}

	private String buildQuery(String userMessage, String toolResult) {
		return (safeText(userMessage) + System.lineSeparator() + safeText(toolResult)).trim();
	}

	private String safeText(String text) {
		return text == null ? "" : text;
	}

	/**
	 * 从应用 classpath 中读取知识文档。
	 */
	private KnowledgeSearchResult readKnowledge(KnowledgeRule rule) {
		Resource resource = new ClassPathResource(rule.source());

		if (!resource.exists()) {
			throw new IllegalStateException("知识文档不存在，classpath=" + rule.source());
		}

		try (InputStream inputStream = resource.getInputStream()) {
			String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

			return KnowledgeSearchResult.found(rule.source(), content);
		}
		catch (IOException e) {
			throw new IllegalStateException("读取知识文档失败，classpath=" + rule.source(), e);
		}
	}

	private record KnowledgeRule(List<String> keywords, String source) {

		private boolean matches(String query) {
			return keywords.stream().anyMatch(query::contains);
		}

	}

}