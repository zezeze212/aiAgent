package com.example.agent.rag;

import com.example.agent.dto.RagBenchmarkResponse;
import com.example.agent.exception.BusinessException;
import com.example.agent.rag.vector.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.apache.lucene.util.Version;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import jakarta.annotation.PreDestroy;

/**
 * M6-0 对真实 Java 检索实现做轻量适配。语料检查与元数据读取不计入 costMs； HTTP 客户端耗时包含整个请求。配置回显不证明在线 Qdrant
 * 与本地状态完全一致。
 */
@Service
@ConditionalOnProperty(prefix = "agent.rag.benchmark", name = "enabled", havingValue = "true")
public class RagBenchmarkService {

	private final SimpleRagRetriever rule;

	private final ObjectProvider<VectorKnowledgeService> vectorProvider;

	private final KnowledgeManifestLoader loader;

	private final ObjectMapper objectMapper;

	private final String collection;

	private final String embeddingModel;

	private final String embeddingRevision;

	private final Path stateDirectory;

	private final MarkdownSectionChunker sectionChunker;

	private Bm25KnowledgeIndex bm25Index;

	private String bm25SnapshotKey;

	private double bm25BuildMs;

	public RagBenchmarkService(SimpleRagRetriever rule, ObjectProvider<VectorKnowledgeService> vectorProvider,
			KnowledgeManifestLoader loader, MarkdownSectionChunker sectionChunker, ObjectMapper objectMapper,
			@Value("${spring.ai.vectorstore.qdrant.collection-name:}") String collection,
			@Value("${spring.ai.ollama.embedding.model:}") String embeddingModel,
			@Value("${agent.rag.vector.embedding-revision:}") String embeddingRevision,
			@Value("${agent.rag.vector.state-directory:./data/knowledge-index}") String stateDirectory) {

		this.rule = rule;
		this.vectorProvider = vectorProvider;
		this.loader = loader;
		this.sectionChunker = sectionChunker;
		this.objectMapper = objectMapper;
		this.collection = collection;
		this.embeddingModel = embeddingModel;
		this.embeddingRevision = embeddingRevision;
		this.stateDirectory = Path.of(stateDirectory);
	}

	public RagBenchmarkResponse search(String strategy, String query, int topK) {
		String selected = strategy == null ? "" : strategy.trim().toLowerCase(Locale.ROOT);

		if (!Set.of("rule", "dense", "bm25", "hybrid").contains(selected)) {
			throw new BusinessException(400, "strategy 只支持 rule、dense、bm25 或 hybrid");
		}

		if (query == null || query.isBlank() || query.length() > 2000 || topK < 1 || topK > 20) {
			throw new BusinessException(400, "query 长度须为 1～2000，topK 须为 1～20");
		}
		String normalizedQuery = query.trim();

		if ("hybrid".equals(selected)) {
			return searchHybrid(normalizedQuery, topK);
		}

		KnowledgeManifest manifest = loader.loadManifest();
		Map<String, Object> config = corpusConfiguration(manifest);

		if ("bm25".equals(selected)) {
			return searchBm25(normalizedQuery, topK, manifest, config);
		}
		List<KnowledgeChunk> hits;
		double costMs;
		int effectiveTopK;
		String unit;

		if ("rule".equals(selected)) {
			// 不把过滤后的首条结果冒充原规则行为；范围不一致时直接拒绝比较。
			Set<String> manifestSources = Set
				.copyOf(manifest.documents().stream().map(KnowledgeSource::source).toList());
			if (!manifestSources.equals(Set.copyOf(rule.sources()))) {
				throw new BusinessException(409, "规则来源与 Manifest 不一致，请先确认比较语料范围");
			}
			config.put("scopeMechanism", "fixed-rule-sources-verified-against-manifest");
			RuleKnowledgeRetriever retriever = new RuleKnowledgeRetriever(rule);
			long start = System.nanoTime();
			hits = retriever.retrieve(normalizedQuery, topK).chunks();
			costMs = elapsedMs(start);
			effectiveTopK = 1;
			unit = "document";
		}
		else {
			VectorKnowledgeService vector = vectorProvider.getIfAvailable();
			if (vector == null) {
				throw new BusinessException(503, "dense 检索服务未启用，不能降级冒充向量结果");
			}
			VectorKnowledgeService.SearchPolicy policy = vector.searchPolicy();
			if (!policy.category().equals(config.get("category"))
					|| !policy.visibility().equals(config.get("visibility"))) {
				throw new BusinessException(409, "dense 过滤范围与当前 Manifest 不一致");
			}
			if (!collection.matches("[A-Za-z0-9_-]+") || embeddingModel.isBlank() || embeddingRevision.isBlank()) {
				throw new BusinessException(409, "dense 基线缺少有效的集合或 Embedding 版本配置");
			}
			Path state = stateDirectory.resolve(collection + ".json");
			Path pending = state.resolveSibling(state.getFileName() + ".pending.json");
			if (Files.exists(pending)) {
				throw new BusinessException(409, "索引存在 pending 恢复记录，请完成同步后再评测");
			}
			String stateHash = stateHash(state);
			config.put("scopeMechanism", "vectorstore-metadata-filter");
			config.put("similarityThreshold", policy.similarityThreshold());
			config.put("configuredCollection", collection);
			config.put("configuredEmbeddingModel", embeddingModel);
			config.put("configuredEmbeddingRevision", embeddingRevision);
			config.put("indexStatePresent", stateHash != null);
			config.put("indexStateSha256", stateHash);
			config.put("indexVerification", "LOCAL_STATE_ONLY_NOT_LIVE_QDRANT_VERIFICATION");
			long start = System.nanoTime();
			var response = vector.search(normalizedQuery, topK);
			hits = response.hits()
				.stream()
				.map(hit -> new KnowledgeChunk(hit.id(), hit.source(), hit.title(), hit.section(), hit.score(),
						hit.content()))
				.toList();
			costMs = elapsedMs(start);
			if (Files.exists(pending) || !java.util.Objects.equals(stateHash, stateHash(state))) {
				throw new BusinessException(409, "检索期间索引状态发生变化，本次结果不用于基线");
			}
			effectiveTopK = topK;
			unit = "chunk";
		}
		return new RagBenchmarkResponse("rag-benchmark-response-v1", selected, unit, normalizedQuery, topK,
				effectiveTopK, costMs, List.copyOf(hits), config);
	}

	/**
	 * 当前面向串行基线评测。 synchronized 使 BM25 构建、查询和关闭互斥，不用于推断并发容量。
	 */
	private synchronized RagBenchmarkResponse searchBm25(String query, int topK, KnowledgeManifest manifest,
			Map<String, Object> config) {

		String snapshotKey = manifest.knowledgeVersion() + "|" + config.get("corpusSha256");

		if (bm25Index != null && !snapshotKey.equals(bm25SnapshotKey)) {
			throw new BusinessException(409, "BM25 语料已变化，请重启服务重建索引后再评测");
		}

		try {
			if (bm25Index == null) {
				long buildStart = System.nanoTime();

				Map<String, String> expectedHashes = new LinkedHashMap<>();

				for (Object value : (List<?>) config.get("sourceInventory")) {
					Map<?, ?> item = (Map<?, ?>) value;
					expectedHashes.put((String) item.get("source"), (String) item.get("contentSha256"));
				}

				List<KnowledgeChunk> chunks = new ArrayList<>();

				var sources = manifest.documents()
					.stream()
					.sorted(Comparator.comparing(KnowledgeSource::source))
					.toList();

				for (KnowledgeSource source : sources) {
					String markdown = loader.readMarkdown(source).replace("\r\n", "\n").replace('\r', '\n');

					String actualHash = sha256(markdown.getBytes(StandardCharsets.UTF_8));

					if (!actualHash.equals(expectedHashes.get(source.source()))) {
						throw new BusinessException(409, "BM25 建索引期间语料发生变化，请重新评测");
					}

					for (var document : sectionChunker.chunk(manifest.knowledgeVersion(), source, markdown)) {

						chunks.add(new KnowledgeChunk(document.getId(), source.source(), source.title(),
								(String) document.getMetadata().get("section"), null, document.getText()));
					}
				}

				Bm25KnowledgeIndex builtIndex = new Bm25KnowledgeIndex(chunks);
				bm25Index = builtIndex;
				bm25SnapshotKey = snapshotKey;
				bm25BuildMs = elapsedMs(buildStart);
			}

			long searchStart = System.nanoTime();
			List<KnowledgeChunk> hits = bm25Index.search(query, topK);
			double costMs = elapsedMs(searchStart);

			config.put("scopeMechanism", "manifest-scope-at-index-build");
			config.put("engine", "lucene");
			config.put("luceneVersion", Version.LATEST.toString());
			config.put("analyzer", "SmartChineseAnalyzer");
			config.put("queryOperator", "SHOULD");
			config.put("k1", Bm25KnowledgeIndex.K1);
			config.put("b", Bm25KnowledgeIndex.B);
			config.put("indexedChunkCount", bm25Index.chunkCount());
			config.put("indexBuildMs", bm25BuildMs);

			return new RagBenchmarkResponse("rag-benchmark-response-v1", "bm25", "chunk", query, topK, topK, costMs,
					hits, config);
		}
		catch (IOException e) {
			throw new IllegalStateException("BM25 索引或查询失败", e);
		}
	}

	private RagBenchmarkResponse searchHybrid(String query, int topK) {
		int candidateTopK = topK;

		// 本轮串行查询；任一路异常直接向外传播，不静默降级。
		RagBenchmarkResponse dense = search("dense", query, candidateTopK);
		RagBenchmarkResponse bm25 = search("bm25", query, candidateTopK);

		for (String key : List.of("knowledgeVersion", "corpusSha256", "category", "visibility", "sourceFilter",
				"sourceInventory")) {

			if (!Objects.equals(dense.configuration().get(key), bm25.configuration().get(key))) {
				throw new BusinessException(409, "Hybrid 两路语料或范围不一致：" + key);
			}
		}

		long fusionStart = System.nanoTime();

		RrfKnowledgeFusion.FusionResult fusion = RrfKnowledgeFusion.fuse(dense.hits(), bm25.hits(), topK);

		double fusionMs = elapsedMs(fusionStart);

		// 保留 dense 的集合、Embedding、阈值与索引状态等配置。
		Map<String, Object> config = new LinkedHashMap<>(dense.configuration());

		config.put("scopeMechanism", "dense-filter-and-bm25-manifest");
		config.put("engine", "rrf");
		config.put("rrfRankConstant", RrfKnowledgeFusion.RANK_CONSTANT);
		config.put("rrfWeights", List.of(1, 1));
		config.put("candidateTopK", candidateTopK);
		config.put("outputTopK", topK);
		config.put("tieBreak", "chunkId-asc");
		config.put("execution", "sequential");
		config.put("timingScope", "sum-branch-costMs-plus-fusion");
		config.put("bm25Configuration", bm25.configuration());

		Map<String, Object> diagnostics = new LinkedHashMap<>();
		diagnostics.put("denseHits", dense.hits());
		diagnostics.put("bm25Hits", bm25.hits());
		diagnostics.put("ranking", fusion.ranking());
		diagnostics.put("denseCostMs", dense.costMs());
		diagnostics.put("bm25CostMs", bm25.costMs());
		diagnostics.put("fusionCostMs", fusionMs);

		double costMs = dense.costMs() + bm25.costMs() + fusionMs;

		return new RagBenchmarkResponse("rag-benchmark-response-v1", "hybrid", "chunk", query, topK, topK, costMs,
				fusion.hits(), config, diagnostics);
	}

	@PreDestroy
	public synchronized void closeBm25Index() throws IOException {
		if (bm25Index != null) {
			bm25Index.close();
			bm25Index = null;
		}
	}

	private Map<String, Object> corpusConfiguration(KnowledgeManifest manifest) {
		var documents = manifest.documents().stream().sorted(Comparator.comparing(KnowledgeSource::source)).toList();
		if (documents.isEmpty()) {
			throw new BusinessException(409, "比较语料不能为空");
		}
		String category = documents.get(0).category();
		String visibility = documents.get(0).visibility();
		List<Map<String, String>> fingerprints = new ArrayList<>();
		for (KnowledgeSource source : documents) {
			if (!category.equals(source.category()) || !visibility.equals(source.visibility())) {
				throw new BusinessException(409, "M6-0 要求同一 category/visibility 的固定语料");
			}
			String markdown = loader.readMarkdown(source).replace("\r\n", "\n").replace('\r', '\n');
			Map<String, String> item = new TreeMap<>();
			item.put("source", source.source());
			item.put("title", source.title());
			item.put("category", source.category());
			item.put("visibility", source.visibility());
			item.put("contentSha256", sha256(markdown.getBytes(StandardCharsets.UTF_8)));
			fingerprints.add(item);
		}
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("knowledgeVersion", manifest.knowledgeVersion());
		config.put("category", category);
		config.put("visibility", visibility);
		config.put("sourceFilter", null);
		config.put("corpusDocumentCount", documents.size());
		config.put("sourceInventory", fingerprints);
		try {
			config.put("corpusSha256", sha256(objectMapper.writeValueAsBytes(fingerprints)));
		}
		catch (IOException e) {
			throw new IllegalStateException("无法计算比较语料指纹", e);
		}
		return config;
	}

	private static String stateHash(Path path) {
		try {
			return Files.exists(path) ? sha256(Files.readAllBytes(path)) : null;
		}
		catch (IOException e) {
			throw new IllegalStateException("无法读取索引状态指纹", e);
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static double elapsedMs(long start) {
		return (System.nanoTime() - start) / 1_000_000.0;
	}

}
