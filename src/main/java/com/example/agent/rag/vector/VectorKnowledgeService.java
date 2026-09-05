package com.example.agent.rag.vector;

import com.example.agent.dto.VectorKnowledgeIndexResponse;
import com.example.agent.dto.VectorKnowledgeSearchHit;
import com.example.agent.dto.VectorKnowledgeSearchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Qdrant Dense Vector 知识入库与检索服务。
 *
 * 当前只负责 M5 最小竖切，不负责增量删除、checksum 和正式 Agent Prompt 注入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agent.rag.vector", name = "enabled", havingValue = "true")
public class VectorKnowledgeService {

	private final KnowledgeManifestLoader manifestLoader;

	private final MarkdownSectionChunker sectionChunker;

	private final VectorStore vectorStore;

	public VectorKnowledgeIndexResponse indexManifest() {
		long startTime = System.currentTimeMillis();

		KnowledgeManifest manifest = manifestLoader.loadManifest();
		List<Document> documents = new ArrayList<>();

		for (KnowledgeSource source : manifest.documents()) {
			String markdown = manifestLoader.readMarkdown(source);
			documents.addAll(sectionChunker.chunk(manifest.knowledgeVersion(), source, markdown));
		}

		if (documents.isEmpty()) {
			throw new IllegalStateException("Manifest 没有生成任何知识 Chunk");
		}

		vectorStore.add(documents);

		long costMs = System.currentTimeMillis() - startTime;

		log.info("向量知识入库完成，knowledgeVersion={}, sourceCount={}, chunkCount={}, costMs={}", manifest.knowledgeVersion(),
				manifest.documents().size(), documents.size(), costMs);

		return new VectorKnowledgeIndexResponse(manifest.knowledgeVersion(), manifest.documents().size(),
				documents.size(), costMs);
	}

	public VectorKnowledgeSearchResponse search(String query, int topK) {
		long startTime = System.currentTimeMillis();

		SearchRequest request = SearchRequest.builder().query(query).topK(topK).similarityThresholdAll().build();

		List<Document> documents = vectorStore.similaritySearch(request);

		List<VectorKnowledgeSearchHit> hits = documents == null ? List.of()
				: documents.stream().map(this::toSearchHit).toList();

		long costMs = System.currentTimeMillis() - startTime;

		log.info("向量知识检索完成，queryLength={}, topK={}, hitCount={}, costMs={}", query.length(), topK, hits.size(), costMs);

		return new VectorKnowledgeSearchResponse(query, topK, costMs, hits);
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