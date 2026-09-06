package com.example.agent.rag.vector;

import com.example.agent.dto.KnowledgeIndexPlanResponse;
import com.example.agent.dto.KnowledgeIndexPlanResponse.Action;
import com.example.agent.dto.KnowledgeIndexPlanResponse.Change;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 知识索引变化规划器。
 *
 * 读取当前知识并计算指纹，与上次成功同步状态比较。 本类不调用 Embedding，不写入或删除 Qdrant Point，也不保存成功状态。
 */
@Service
@ConditionalOnProperty(prefix = "agent.rag.vector", name = "enabled", havingValue = "true")
public class KnowledgeIndexPlanner {

	private final KnowledgeManifestLoader manifestLoader;

	private final MarkdownSectionChunker sectionChunker;

	private final ObjectMapper objectMapper;

	private final String collectionName;

	private final String embeddingModel;

	private final String embeddingRevision;

	private final Path statePath;

	public KnowledgeIndexPlanner(KnowledgeManifestLoader manifestLoader, MarkdownSectionChunker sectionChunker,
			ObjectMapper objectMapper, @Value("${spring.ai.vectorstore.qdrant.collection-name}") String collectionName,
			@Value("${spring.ai.ollama.embedding.model}") String embeddingModel,
			@Value("${agent.rag.vector.embedding-revision}") String embeddingRevision,
			@Value("${agent.rag.vector.state-directory}") String stateDirectory) {

		this.manifestLoader = manifestLoader;
		this.sectionChunker = sectionChunker;
		this.objectMapper = objectMapper;
		this.collectionName = collectionName;
		this.embeddingModel = embeddingModel;
		this.embeddingRevision = embeddingRevision;

		// 每个集合使用独立状态文件，避免把旧集合的记录应用到新集合。
		if (!collectionName.matches("[A-Za-z0-9_-]+")) {
			throw new IllegalArgumentException("当前索引状态文件要求集合名只包含字母、数字、下划线或连字符");
		}

		this.statePath = Path.of(stateDirectory).resolve(collectionName + ".json").toAbsolutePath().normalize();
	}

	/**
	 * 预览当前知识相对于上次成功状态的变化。
	 *
	 * 没有状态文件时，明确返回 baselinePresent=false， 不猜测当前 Qdrant 中的数据是否与本地文件一致。
	 */
	public synchronized KnowledgeIndexPlanResponse preview() {
		Snapshot snapshot = buildSnapshot();

		boolean baselinePresent = Files.exists(statePath);
		KnowledgeIndexState previous = readPreviousState(baselinePresent);

		return compare(collectionName, baselinePresent, previous.documents(), snapshot.state().documents());
	}

	/**
	 * 一次性构建目标状态和准备写入的 Document。 同步服务复用这份快照，避免指纹和实际写入内容来自两次不同读取。
	 */
	Snapshot buildSnapshot() {
		KnowledgeManifest manifest = manifestLoader.loadManifest();

		Map<String, KnowledgeIndexState.DocumentState> states = new LinkedHashMap<>();
		Map<String, List<Document>> documentsBySource = new LinkedHashMap<>();

		// manifest 每篇文档的指纹获取
		for (KnowledgeSource source : manifest.documents()) {
			String markdown = manifestLoader.readMarkdown(source);
			List<Document> documents = sectionChunker.chunk(manifest.knowledgeVersion(), source, markdown);

			if (documents.isEmpty()) {
				throw new IllegalStateException("文档没有生成有效 Chunk，source=" + source.source());
			}

			List<String> chunkIds = documents.stream().map(Document::getId).toList();

			states.put(source.source(), new KnowledgeIndexState.DocumentState(fingerprint(documents), chunkIds));
			documentsBySource.put(source.source(), documents);
		}

		return new Snapshot(new KnowledgeIndexState(collectionName, Map.copyOf(states)), Map.copyOf(documentsBySource));
	}

	/**
	 * 包内共享的同步快照，不作为 HTTP DTO。
	 */
	record Snapshot(KnowledgeIndexState state, Map<String, List<Document>> documentsBySource) {
	}

	Path statePath() {
		return statePath;
	}

	/**
	 * 对准备入库的数据计算 SHA-256。
	 *
	 * metadata 使用 TreeMap 固定 key 顺序，避免仅因 Map 遍历顺序不同， 就把内容相同的文档误判为已修改。
	 */
	private String fingerprint(List<Document> documents) {
		List<Map<String, Object>> chunks = new ArrayList<>();

		for (Document document : documents) {
			Map<String, Object> chunk = new LinkedHashMap<>();
			chunk.put("id", document.getId());
			chunk.put("text", document.getText());
			chunk.put("metadata", new TreeMap<>(document.getMetadata()));
			chunks.add(chunk);
		}

		Map<String, Object> input = new LinkedHashMap<>();
		input.put("fingerprintVersion", "document-sha256-v1");
		input.put("embeddingModel", embeddingModel);
		input.put("embeddingRevision", embeddingRevision);
		input.put("chunks", chunks);

		try {
			byte[] bytes = objectMapper.writeValueAsBytes(input);
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			return HexFormat.of().formatHex(digest);
		}
		catch (IOException | NoSuchAlgorithmException e) {
			throw new IllegalStateException("计算知识指纹失败", e);
		}
	}

	/**
	 * 历史状态不存在时返回空基线。 如果文件存在但损坏，直接报错，不能静默当成首次同步。
	 */
	KnowledgeIndexState readPreviousState(boolean baselinePresent) {
		if (!baselinePresent) {
			return new KnowledgeIndexState(collectionName, Map.of());
		}

		try {
			KnowledgeIndexState state = objectMapper.readValue(statePath.toFile(), KnowledgeIndexState.class);

			if (state == null || !collectionName.equals(state.collectionName()) || state.documents() == null) {
				throw new IllegalStateException("索引状态内容无效，path=" + statePath);
			}

			for (var entry : state.documents().entrySet()) {
				var document = entry.getValue();

				if (entry.getKey() == null || entry.getKey().isBlank() || document == null
						|| document.fingerprint() == null || document.fingerprint().isBlank()
						|| document.chunkIds() == null) {
					throw new IllegalStateException("索引文档状态无效，path=" + statePath);
				}
			}

			return state;
		}
		catch (IOException e) {
			throw new IllegalStateException("读取索引状态失败，path=" + statePath, e);
		}
	}

	/**
	 * 比较两个状态集合。 这是纯 Java 判断，没有文件读写或网络调用，方便独立测试。
	 */
	static KnowledgeIndexPlanResponse compare(String collectionName, boolean baselinePresent,
			Map<String, KnowledgeIndexState.DocumentState> previous,
			Map<String, KnowledgeIndexState.DocumentState> current) {

		List<Change> changes = new ArrayList<>();

		for (var entry : current.entrySet()) {
			String source = entry.getKey();
			var now = entry.getValue();
			var before = previous.get(source);

			Action action;
			if (before == null) {
				action = Action.ADDED;
			}
			else if (before.fingerprint().equals(now.fingerprint())) {
				action = Action.UNCHANGED;
			}
			else {
				action = Action.UPDATED;
			}

			changes
				.add(new Change(source, action, before == null ? 0 : before.chunkIds().size(), now.chunkIds().size()));
		}

		// 当前 Manifest 中已不存在，但上次成功状态仍有记录的文档。
		for (var entry : previous.entrySet()) {
			if (!current.containsKey(entry.getKey())) {
				changes.add(new Change(entry.getKey(), Action.DELETED, entry.getValue().chunkIds().size(), 0));
			}
		}

		return new KnowledgeIndexPlanResponse(collectionName, baselinePresent, count(changes, Action.ADDED),
				count(changes, Action.UPDATED), count(changes, Action.UNCHANGED), count(changes, Action.DELETED),
				List.copyOf(changes));
	}

	private static int count(List<Change> changes, Action action) {
		return (int) changes.stream().filter(change -> change.action() == action).count();
	}

}