package com.example.agent.rag;

import com.example.agent.rag.vector.KnowledgeManifest;
import com.example.agent.rag.vector.KnowledgeManifestLoader;
import com.example.agent.rag.vector.KnowledgeSource;
import com.example.agent.rag.vector.MarkdownSectionChunker;

import java.io.Closeable;
import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 正式 Agent 使用的 BM25 检索适配器。
 *
 * 启动时从 Manifest 构建固定范围的只读快照。 运行期间不自动热更新；知识变更后需要重新构建。
 */
public final class Bm25KnowledgeRetriever implements KnowledgeRetriever, Closeable {

	private final String category;

	private final String visibility;

	private final Map<String, KnowledgeChunk> snapshotChunks;

	private final Bm25KnowledgeIndex index;

	public Bm25KnowledgeRetriever(KnowledgeManifestLoader loader, MarkdownSectionChunker chunker, String category,
			String visibility) throws IOException {

		if (category == null || category.isBlank() || visibility == null || visibility.isBlank()) {
			throw new IllegalArgumentException("BM25 检索范围不能为空");
		}

		this.category = category.trim();
		this.visibility = visibility.trim();

		KnowledgeManifest manifest = loader.loadManifest();

		/*
		 * 先限制索引语料范围，再建立索引。 不采用“全库取 TopK 后再过滤”，避免范围外文档占用候选名额。
		 */
		List<KnowledgeSource> sources = manifest.documents()
			.stream()
			.filter(source -> this.category.equals(source.category()) && this.visibility.equals(source.visibility()))
			.sorted(Comparator.comparing(KnowledgeSource::source))
			.toList();

		if (sources.isEmpty()) {
			throw new IllegalStateException("BM25 当前范围没有知识文档，请检查 category/visibility");
		}

		Map<String, KnowledgeChunk> chunks = new LinkedHashMap<>();

		for (KnowledgeSource source : sources) {
			String markdown = loader.readMarkdown(source);

			// 与向量索引复用同一个分块器，保留相同的片段身份。
			for (var document : chunker.chunk(manifest.knowledgeVersion(), source, markdown)) {

				KnowledgeChunk chunk = new KnowledgeChunk(document.getId(), source.source(), source.title(),
						(String) document.getMetadata().get("section"), null, document.getText());

				if (chunks.putIfAbsent(chunk.chunkId(), chunk) != null) {
					throw new IllegalStateException("BM25 快照存在重复 chunkId：" + chunk.chunkId());
				}
			}
		}

		this.snapshotChunks = Map.copyOf(chunks);

		// 索引内部继续使用现有 SmartChineseAnalyzer 和 BM25Similarity。
		this.index = new Bm25KnowledgeIndex(List.copyOf(chunks.values()));
	}

	@Override
	public KnowledgeRetrievalResult retrieve(String query, int topK) {
		if (topK < 1 || topK > 20) {
			throw new IllegalArgumentException("topK 必须在 1～20 之间");
		}

		if (query == null || query.isBlank()) {
			return KnowledgeRetrievalResult.empty("bm25");
		}

		try {
			return new KnowledgeRetrievalResult("bm25", index.search(query.trim(), topK));
		}
		catch (IOException e) {
			// 查询失败不能伪装为正常的空召回。
			throw new IllegalStateException("正式 BM25 查询失败", e);
		}
	}

	/**
	 * 检查 Dense 候选是否属于当前快照。
	 *
	 * 比较正文及来源元数据，忽略 score： Dense 分数与 BM25 分数本来就不是同一量纲。
	 */
	public void requireSameSnapshot(List<KnowledgeChunk> denseHits) {
		for (KnowledgeChunk hit : denseHits) {
			if (hit == null || hit.chunkId() == null) {
				throw new IllegalStateException("Dense 返回了无效候选");
			}

			KnowledgeChunk expected = snapshotChunks.get(hit.chunkId());

			if (expected == null || !Objects.equals(expected.source(), hit.source())
					|| !Objects.equals(expected.title(), hit.title())
					|| !Objects.equals(expected.section(), hit.section())
					|| !Objects.equals(expected.content(), hit.content())) {

				throw new IllegalStateException(
						"Dense 候选与当前 BM25 语料快照不一致，" + "请检查知识版本及向量索引同步，chunkId=" + hit.chunkId());
			}
		}
	}

	public String category() {
		return category;
	}

	public String visibility() {
		return visibility;
	}

	@Override
	public void close() throws IOException {
		index.close();
	}

}