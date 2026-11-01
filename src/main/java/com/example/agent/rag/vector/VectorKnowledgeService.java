package com.example.agent.rag.vector;

import com.example.agent.dto.VectorKnowledgeSearchHit;
import com.example.agent.dto.VectorKnowledgeSearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Dense Vector 知识检索服务。
 *
 * 统一负责 TopK、相似度阈值和 metadata 范围过滤。 正式 Agent、独立 RAG 回答和检索验证接口复用此服务。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "agent.rag.vector", name = "enabled", havingValue = "true")
public class VectorKnowledgeService {

	private final VectorStore vectorStore;

	private final double defaultThreshold;

	private final String category;

	private final String visibility;

	public VectorKnowledgeService(VectorStore vectorStore,
			@Value("${agent.rag.vector.similarity-threshold:0.0}") double defaultThreshold,
			@Value("${agent.rag.vector.scope.category:mysql}") String category,
			@Value("${agent.rag.vector.scope.visibility:public}") String visibility) {

		validateThreshold(defaultThreshold);

		if (category == null || category.isBlank()) {
			throw new IllegalArgumentException("知识检索 category 不能为空");
		}

		if (visibility == null || visibility.isBlank()) {
			throw new IllegalArgumentException("知识检索 visibility 不能为空");
		}

		this.vectorStore = vectorStore;
		this.defaultThreshold = defaultThreshold;
		this.category = category.trim();
		this.visibility = visibility.trim();
	}

	/**
	 * 正式 Agent 和独立 RAG 回答使用服务端默认策略。 保留原方法签名，上层调用方无需修改。
	 */
	public VectorKnowledgeSearchResponse search(String query, int topK) {
		return search(query, topK, null, null);
	}

	/**
	 * 检索验证接口可指定实验阈值和来源文档。
	 *
	 * threshold 为 null 时使用配置值。 source 为 null 时搜索基础范围内的全部文档； 指定 source 时，仅进一步缩小范围。
	 */
	public VectorKnowledgeSearchResponse search(String query, int topK, Double threshold, String source) {

		if (query == null || query.isBlank()) {
			throw new IllegalArgumentException("query 不能为空");
		}

		if (topK < 1 || topK > 20) {
			throw new IllegalArgumentException("topK 必须在 1～20 之间");
		}

		double effectiveThreshold = threshold == null ? defaultThreshold : threshold;

		validateThreshold(effectiveThreshold);

		if (source != null && (source.isBlank() || source.length() > 500)) {
			throw new IllegalArgumentException("source 长度必须在 1～500 之间");
		}

		String normalizedQuery = query.trim();
		String normalizedSource = source == null ? null : source.trim();

		SearchRequest request = SearchRequest.builder()
			.query(normalizedQuery)
			.topK(topK)
			.similarityThreshold(effectiveThreshold)
			.filterExpression(buildFilter(normalizedSource))
			.build();

		long startTime = System.nanoTime();

		/*
		 * 阈值和范围进入 VectorStore 查询。 这里不先取全库 TopK，再在 Java 中过滤。
		 */
		List<Document> documents = vectorStore.similaritySearch(request);

		List<VectorKnowledgeSearchHit> hits = documents == null ? List.of()
				: documents.stream().map(this::toSearchHit).toList();

		long costMs = (System.nanoTime() - startTime) / 1_000_000;

		log.info("向量检索完成，topK={}, threshold={}, category={}, visibility={}, " + "source={}, hitCount={}, costMs={}",
				topK, effectiveThreshold, category, visibility, normalizedSource, hits.size(), costMs);

		return new VectorKnowledgeSearchResponse(normalizedQuery, topK, costMs, hits);
	}

	/**
	 * 基础范围始终生效，source 只能增加约束。
	 *
	 * 使用结构化表达式处理值，不拼接过滤表达式字符串。
	 */
	private Filter.Expression buildFilter(String source) {
		FilterExpressionBuilder builder = new FilterExpressionBuilder();

		if (source == null) {
			return builder.and(builder.eq("category", category), builder.eq("visibility", visibility)).build();
		}

		return builder
			.and(builder.and(builder.eq("category", category), builder.eq("visibility", visibility)),
					builder.eq("source", source))
			.build();
	}

	/** 返回本实例真正用于 search(query, topK) 的策略，避免评测端重复读取配置。 */
	public SearchPolicy searchPolicy() {
		return new SearchPolicy(defaultThreshold, category, visibility);
	}

	public record SearchPolicy(double similarityThreshold, String category, String visibility) {
	}

	private static void validateThreshold(double threshold) {
		if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
			throw new IllegalArgumentException("similarityThreshold 必须是 0～1 之间的有限数值");
		}
	}

	private VectorKnowledgeSearchHit toSearchHit(Document document) {
		Map<String, Object> metadata = document.getMetadata();

		return new VectorKnowledgeSearchHit(document.getId(), document.getScore(), metadataText(metadata, "source"),
				metadataText(metadata, "title"), metadataText(metadata, "section"), document.getText());
	}

	private String metadataText(Map<String, Object> metadata, String key) {
		Object value = metadata.get(key);
		return value == null ? null : value.toString();
	}

}
