package com.example.agent.rag.rerank;

import com.example.agent.exception.BusinessException;
import com.example.agent.rag.KnowledgeChunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * M6-3 的远程重排适配器。
 *
 * 输入：已经完成范围过滤和去重的候选片段。 输出：所有候选的完整重排结果，不在这里截取最终 TopK。
 *
 * 本类只负责内容重排，不负责召回、RRF 融合和评测。
 */
public final class SiliconFlowReranker {

	public static final String MODEL = "BAAI/bge-reranker-v2-m3";

	private static final URI ENDPOINT = URI.create("https://api.siliconflow.cn/v1/rerank");

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	/*
	 * 项目策略：现有 Benchmark 每路最多 20 条，两路并集最多 40 条。 这是本项目的保护上限，不是服务商或模型的统一限制。
	 */
	private static final int MAX_CANDIDATES = 40;

	/*
	 * 显式固定服务端长文本处理参数，避免依赖未记录的默认值。 它们不改变本项目已有的 Markdown Chunk 身份。
	 */
	private static final int MAX_CHUNKS_PER_DOC = 1024;

	private static final int OVERLAP_TOKENS = 80;

	private final ObjectMapper objectMapper;

	private final HttpClient httpClient;

	private final String apiKey;

	public SiliconFlowReranker(ObjectMapper objectMapper, String apiKey) {
		this(objectMapper, apiKey,
				HttpClient.newBuilder()
					.connectTimeout(CONNECT_TIMEOUT)
					.followRedirects(HttpClient.Redirect.NEVER)
					.build());
	}

	/*
	 * 包可见构造器：测试注入模拟 HTTP 客户端。 正常业务代码使用上面的公开构造器。
	 */
	SiliconFlowReranker(ObjectMapper objectMapper, String apiKey, HttpClient httpClient) {

		this.objectMapper = Objects.requireNonNull(objectMapper);
		this.httpClient = Objects.requireNonNull(httpClient);

		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalArgumentException("缺少 SILICONFLOW_API_KEY，无法调用重排服务");
		}
		this.apiKey = apiKey.trim();
	}

	/**
	 * 对所有候选打分并排序。
	 *
	 * costMs 包含请求构造、HTTP 等待、响应解析、校验和本地排序， 不包含前置 Dense/BM25 检索。
	 */
	public RerankResult rerank(String query, List<KnowledgeChunk> candidates) {

		validateInput(query, candidates);
		List<KnowledgeChunk> snapshot = List.copyOf(candidates);

		// 空召回是合法结果；不为它发送一个无意义的远程请求。
		if (snapshot.isEmpty()) {
			return new RerankResult(List.of(), 0.0, null);
		}

		long start = System.nanoTime();

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", MODEL);
		body.put("query", query.trim());

		/*
		 * content 已由现有 Chunker 组织。 不额外拼接标准答案、expected.section 或其他评测标签。
		 */
		body.put("documents", snapshot.stream().map(KnowledgeChunk::content).toList());

		// 请求全部候选的分数，最终 TopK 由 Benchmark 层统一截取。
		body.put("top_n", snapshot.size());
		body.put("return_documents", false);
		body.put("max_chunks_per_doc", MAX_CHUNKS_PER_DOC);
		body.put("overlap_tokens", OVERLAP_TOKENS);

		String requestJson;
		try {
			requestJson = objectMapper.writeValueAsString(body);
		}
		catch (JsonProcessingException e) {
			// 本地序列化错误属于实现问题，不伪装成远程检索未命中。
			throw new IllegalStateException("无法序列化重排请求", e);
		}

		HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
			.timeout(REQUEST_TIMEOUT)
			.header("Authorization", "Bearer " + apiKey)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
			.build();

		HttpResponse<String> response = send(request);

		JsonNode root;
		try {
			root = objectMapper.readTree(response.body());
		}
		catch (JsonProcessingException e) {
			throw invalidResponse("响应不是合法 JSON");
		}

		List<KnowledgeChunk> ranking = decodeRanking(root, snapshot);

		JsonNode idNode = root.get("id");
		String requestId = idNode != null && idNode.isTextual() ? idNode.textValue() : null;

		double costMs = (System.nanoTime() - start) / 1_000_000.0;
		return new RerankResult(ranking, costMs, requestId);
	}

	/**
	 * 供后续 Benchmark 记录实验配置。
	 *
	 * 不包含 API Key、单次耗时、请求 ID 等秘密或动态数据。
	 */
	public Map<String, Object> configuration() {
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("provider", "siliconflow");
		config.put("endpoint", ENDPOINT.toString());
		config.put("model", MODEL);
		config.put("modelRevisionVerification", "PROVIDER_ALIAS_ONLY");
		config.put("documentInput", "KnowledgeChunk.content");
		config.put("returnAllCandidates", true);
		config.put("maxCandidates", MAX_CANDIDATES);
		config.put("maxChunksPerDoc", MAX_CHUNKS_PER_DOC);
		config.put("overlapTokens", OVERLAP_TOKENS);
		config.put("connectTimeoutMs", CONNECT_TIMEOUT.toMillis());
		config.put("requestTimeoutMs", REQUEST_TIMEOUT.toMillis());
		config.put("applicationRetries", 0);
		config.put("tieBreak", "chunkId-asc");
		config.put("timingScope", "request-build-http-decode-validate-sort");
		return Map.copyOf(config);
	}

	private void validateInput(String query, List<KnowledgeChunk> candidates) {

		if (query == null || query.isBlank() || query.length() > 2000) {
			throw new IllegalArgumentException("重排 query 长度须为 1～2000");
		}
		if (candidates == null || candidates.size() > MAX_CANDIDATES) {
			throw new IllegalArgumentException("重排候选数量须为 0～" + MAX_CANDIDATES);
		}

		Set<String> ids = new HashSet<>();
		for (KnowledgeChunk chunk : candidates) {
			if (chunk == null || chunk.chunkId() == null || chunk.chunkId().isBlank() || chunk.content() == null
					|| chunk.content().isBlank()) {
				throw new IllegalArgumentException("重排候选必须具有非空 chunkId 和 content");
			}

			// 不在这里悄悄去重；重复 ID 表明上游候选构造有问题。
			if (!ids.add(chunk.chunkId())) {
				throw new IllegalArgumentException("重排候选存在重复 chunkId：" + chunk.chunkId());
			}
		}
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			HttpResponse<String> response = httpClient.send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			if (response.statusCode() != 200) {
				/*
				 * 请求失败必须显式暴露，不能返回旧 RRF 结果冒充重排成功。 不把远端原始响应正文或认证信息写进业务异常。
				 */
				throw new BusinessException(502, "重排服务调用失败，上游 HTTP " + response.statusCode());
			}
			return response;
		}
		catch (HttpTimeoutException e) {
			throw new BusinessException(504, "重排服务请求超时");
		}
		catch (InterruptedException e) {
			// 恢复中断标志，让上层取消/停止机制仍能感知中断。
			Thread.currentThread().interrupt();
			throw new BusinessException(503, "重排服务请求被中断");
		}
		catch (IOException e) {
			throw new BusinessException(502, "重排服务网络调用失败");
		}
	}

	private List<KnowledgeChunk> decodeRanking(JsonNode root, List<KnowledgeChunk> candidates) {

		if (root == null || !root.isObject()) {
			throw invalidResponse("顶层必须是 JSON 对象");
		}

		JsonNode results = root.get("results");

		/*
		 * 我们发送 top_n = 候选数，因此要求每个候选恰好返回一次。 少返回、重复返回或越界都属于协议异常，不能作为正常 MISS。
		 */
		if (results == null || !results.isArray() || results.size() != candidates.size()) {
			throw invalidResponse("results 数量与候选数量不一致");
		}

		boolean[] seen = new boolean[candidates.size()];
		List<KnowledgeChunk> ranking = new ArrayList<>();

		for (JsonNode item : results) {
			JsonNode indexNode = item.get("index");
			JsonNode scoreNode = item.get("relevance_score");

			/*
			 * 不能直接 asInt()/asDouble()： 缺失字段可能被转换为默认 0，造成静默身份错配。
			 */
			if (indexNode == null || !indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
				throw invalidResponse("index 必须是整数");
			}
			int index = indexNode.intValue();

			if (index < 0 || index >= candidates.size() || seen[index]) {
				throw invalidResponse("index 越界或重复");
			}

			if (scoreNode == null || !scoreNode.isNumber() || !Double.isFinite(scoreNode.doubleValue())) {
				throw invalidResponse("relevance_score 必须是有限数值");
			}
			seen[index] = true;

			KnowledgeChunk original = candidates.get(index);

			// 身份、来源与正文来自本地原片段，只更新相关性分数。
			ranking.add(new KnowledgeChunk(original.chunkId(), original.source(), original.title(), original.section(),
					scoreNode.doubleValue(), original.content()));
		}

		/*
		 * 本地显式排序，避免依赖远端返回顺序。 分数相同时按 chunkId 排序，使本次实验的 tie-break 可解释。
		 */
		ranking.sort(Comparator.comparingDouble((KnowledgeChunk chunk) -> chunk.score())
			.reversed()
			.thenComparing(KnowledgeChunk::chunkId));

		return List.copyOf(ranking);
	}

	private static BusinessException invalidResponse(String reason) {
		return new BusinessException(502, "重排服务响应不符合契约：" + reason);
	}

	/**
	 * ranking 保留完整候选排序。 requestId 由服务商返回，缺失时为 null，不自行伪造。
	 */
	public record RerankResult(List<KnowledgeChunk> ranking, double costMs, String requestId) {
	}

}