package com.example.agent.rag;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.QueryBuilder;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 根据现有知识片段建立只读 BM25 内存索引。
 *
 * 不重新定义分块，保留原始片段身份与正文。
 */
public final class Bm25KnowledgeIndex implements Closeable {

	public static final float K1 = 1.2f;

	public static final float B = 0.75f;

	private static final String CONTENT_FIELD = "content";

	private static final String ID_FIELD = "chunkId";

	private final Analyzer analyzer = new SmartChineseAnalyzer();

	private final Directory directory = new ByteBuffersDirectory();

	private final Map<String, KnowledgeChunk> chunksById = new HashMap<>();

	private final DirectoryReader reader;

	private final IndexSearcher searcher;

	public Bm25KnowledgeIndex(List<KnowledgeChunk> chunks) throws IOException {
		DirectoryReader openedReader = null;

		try {
			IndexWriterConfig writerConfig = new IndexWriterConfig(analyzer);
			writerConfig.setSimilarity(new BM25Similarity(K1, B));

			try (IndexWriter writer = new IndexWriter(directory, writerConfig)) {
				for (KnowledgeChunk chunk : chunks) {
					if (chunk.chunkId() == null || chunk.chunkId().isBlank() || chunk.content() == null
							|| chunk.content().isBlank()) {
						throw new IllegalArgumentException("片段 ID 和正文不能为空");
					}

					if (chunksById.putIfAbsent(chunk.chunkId(), chunk) != null) {
						throw new IllegalArgumentException("存在重复 chunkId：" + chunk.chunkId());
					}

					org.apache.lucene.document.Document document = new org.apache.lucene.document.Document();

					// 保存业务片段 ID，用于从命中结果找回原始片段。
					document.add(new StoredField(ID_FIELD, chunk.chunkId()));

					// 正文参与分词和评分；完整原文保存在 chunksById 中。
					document.add(new TextField(CONTENT_FIELD, chunk.content(), Field.Store.NO));

					writer.addDocument(document);
				}
			}

			openedReader = DirectoryReader.open(directory);
			this.reader = openedReader;
			this.searcher = new IndexSearcher(reader);
			this.searcher.setSimilarity(new BM25Similarity(K1, B));
		}
		catch (IOException | RuntimeException e) {
			IOUtils.closeWhileHandlingException(openedReader, directory, analyzer);
			throw e;
		}
	}

	public List<KnowledgeChunk> search(String query, int topK) throws IOException {
		if (query == null || query.isBlank()) {
			throw new IllegalArgumentException("query 不能为空");
		}
		if (topK < 1 || topK > 20) {
			throw new IllegalArgumentException("topK 须为 1～20");
		}

		Query luceneQuery = new QueryBuilder(analyzer).createBooleanQuery(CONTENT_FIELD, query,
				BooleanClause.Occur.SHOULD);

		// 输入经分析后可能没有有效词项，例如只有被移除的标点。
		if (luceneQuery == null) {
			return List.of();
		}

		var topDocs = searcher.search(luceneQuery, topK);
		var storedFields = reader.storedFields();
		List<KnowledgeChunk> hits = new ArrayList<>();

		for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
			String chunkId = storedFields.document(scoreDoc.doc).get(ID_FIELD);
			KnowledgeChunk original = chunksById.get(chunkId);

			if (original == null) {
				throw new IllegalStateException("索引缺少原始片段：" + chunkId);
			}

			hits.add(new KnowledgeChunk(original.chunkId(), original.source(), original.title(), original.section(),
					(double) scoreDoc.score, original.content()));
		}

		return List.copyOf(hits);
	}

	public int chunkCount() {
		return chunksById.size();
	}

	@Override
	public void close() throws IOException {
		IOUtils.close(reader, directory, analyzer);
	}

}