package com.example.agent.rag;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 基于关键词和本地 Markdown 文档的简单知识检索器。
 *
 * 当前只支持 Unknown column 类型的 SQL 错误。
 */
@Component
public class SimpleRagRetriever {

	private static final String SQL_ERROR_GUIDE_SOURCE = "docs/knowledge/sql-error-guide.md";

	private static final Path SQL_ERROR_GUIDE_PATH = Path.of("docs", "knowledge", "sql-error-guide.md");

	/**
	 * 根据用户问题检索相关知识。
	 * @param query 用户问题或错误信息
	 * @return 知识检索结果
	 */
	public KnowledgeSearchResult retrieve(String query) {
		if (query == null || query.isBlank()) {
			return KnowledgeSearchResult.notFound();
		}

		String normalizedQuery = query.toLowerCase(Locale.ROOT);

		if (!normalizedQuery.contains("unknown column")) {
			return KnowledgeSearchResult.notFound();
		}

		return readSqlErrorGuide();
	}

	/**
	 * 读取 SQL 错误排查知识文档。
	 */
	private KnowledgeSearchResult readSqlErrorGuide() {
		try {
			String content = Files.readString(SQL_ERROR_GUIDE_PATH, StandardCharsets.UTF_8);

			return KnowledgeSearchResult.found(SQL_ERROR_GUIDE_SOURCE, content);
		}
		catch (IOException e) {
			throw new IllegalStateException("读取知识文档失败，path=" + SQL_ERROR_GUIDE_PATH, e);
		}
	}

}