package com.example.agent.rag.vector;

import com.example.agent.dto.KnowledgeIndexPlanResponse;
import com.example.agent.dto.KnowledgeIndexSyncResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单实例知识索引同步服务。
 *
 * 正常同步只向量化新增或修改的文档。 操作前保存 pending 记录，失败后通过重新同步当前目标数据恢复。
 *
 * 本地状态必须和对应 Qdrant 集合配套保存。
 */
@Service
@ConditionalOnProperty(prefix = "agent.rag.vector", name = "enabled", havingValue = "true")
public class KnowledgeIndexSyncService {

	private final KnowledgeIndexPlanner planner;

	private final VectorStore vectorStore;

	private final ObjectMapper objectMapper;

	private final String qdrantRestUrl;

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	public KnowledgeIndexSyncService(KnowledgeIndexPlanner planner, VectorStore vectorStore, ObjectMapper objectMapper,
			@Value("${agent.rag.vector.qdrant-rest-url}") String qdrantRestUrl) {

		this.planner = planner;
		this.vectorStore = vectorStore;
		this.objectMapper = objectMapper;
		this.qdrantRestUrl = qdrantRestUrl.replaceAll("/+$", "");
	}

	/**
	 * 串行执行一次同步。
	 *
	 * 与 Planner 预览使用同一把锁，避免本进程中预览和同步同时读取状态。 该锁不支持多个 Java 实例共同管理同一个集合。
	 */
	public KnowledgeIndexSyncResponse sync() {
		synchronized (planner) {
			return doSync();
		}
	}

	private KnowledgeIndexSyncResponse doSync() {
		long startTime = System.nanoTime();

		Path statePath = planner.statePath();
		Path pendingPath = statePath.resolveSibling(statePath.getFileName() + ".pending.json");

		boolean baselinePresent = Files.exists(statePath);
		boolean recovery = Files.exists(pendingPath);

		KnowledgeIndexPlanner.Snapshot snapshot = planner.buildSnapshot();
		KnowledgeIndexState target = snapshot.state();
		KnowledgeIndexState previous = planner.readPreviousState(baselinePresent);

		KnowledgeIndexPlanResponse plan = KnowledgeIndexPlanner.compare(target.collectionName(), baselinePresent,
				previous.documents(), target.documents());

		/*
		 * 没有任何历史记录时，只允许从空集合开始。 有 pending 说明这是一次中断同步的重试，集合可以已经有部分数据。
		 */
		if (!baselinePresent && !recovery) {
			requireEmptyCollection(target.collectionName());
		}

		List<Document> documentsToWrite = new ArrayList<>();

		for (var entry : target.documents().entrySet()) {
			var before = previous.documents().get(entry.getKey());
			var now = entry.getValue();

			if (recovery || before == null || !before.fingerprint().equals(now.fingerprint())) {
				documentsToWrite.addAll(snapshot.documentsBySource().get(entry.getKey()));
			}
		}

		Set<String> targetIds = collectIds(target);
		Set<String> trackedIds = collectIds(previous);

		if (recovery) {
			PendingSync pending = readPending(pendingPath, target.collectionName());
			trackedIds.addAll(pending.trackedIds());
		}

		/*
		 * 记录旧状态、上次失败和本次目标涉及的全部 ID。 即使 add 部分成功后失败，下次也能找到可能遗留的 Point。
		 */
		trackedIds.addAll(targetIds);

		List<String> obsoleteIds = trackedIds.stream().filter(id -> !targetIds.contains(id)).toList();

		writeAtomically(pendingPath, new PendingSync(target.collectionName(), List.copyOf(trackedIds)));

		// 相同 ID 的 upsert 允许重试；未变化文档在正常模式下不会进入这里。
		if (!documentsToWrite.isEmpty()) {
			vectorStore.add(documentsToWrite);
		}

		/*
		 * 必须排除仍属于当前目标的 ID。 部分新旧 Chunk 的 ID 相同，不能先写入后把这些 ID 一并删掉。
		 */
		if (!obsoleteIds.isEmpty()) {
			vectorStore.delete(obsoleteIds);
		}

		// 所有向量操作成功后，才把目标状态标记为成功状态。
		writeAtomically(statePath, target);

		try {
			Files.deleteIfExists(pendingPath);
		}
		catch (IOException e) {
			// 保留 pending 是安全的：下次会多做一次恢复同步，而不会漏数据。
			throw new IllegalStateException("同步已写入，但恢复记录清理失败，请重试", e);
		}

		long costMs = (System.nanoTime() - startTime) / 1_000_000;

		return new KnowledgeIndexSyncResponse(plan, recovery, documentsToWrite.size(), obsoleteIds.size(), costMs);
	}

	private Set<String> collectIds(KnowledgeIndexState state) {
		Set<String> ids = new LinkedHashSet<>();

		for (var document : state.documents().values()) {
			ids.addAll(document.chunkIds());
		}

		return ids;
	}

	private PendingSync readPending(Path path, String collectionName) {
		try {
			PendingSync pending = objectMapper.readValue(path.toFile(), PendingSync.class);

			if (pending == null || !collectionName.equals(pending.collectionName()) || pending.trackedIds() == null
					|| pending.trackedIds().stream().anyMatch(id -> id == null || id.isBlank())) {
				throw new IllegalStateException("恢复记录无效，path=" + path);
			}

			return pending;
		}
		catch (IOException e) {
			throw new IllegalStateException("读取恢复记录失败，path=" + path, e);
		}
	}

	/**
	 * 在同一目录写临时文件，再原子替换。
	 *
	 * 避免直接覆盖状态文件时，进程退出留下半份 JSON。 文件系统不支持原子移动时直接报错，不悄悄降低状态保存保证。
	 */
	private void writeAtomically(Path path, Object value) {
		Path temporary = null;

		try {
			Files.createDirectories(path.getParent());
			temporary = Files.createTempFile(path.getParent(), "knowledge-", ".tmp");
			objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);

			Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e) {
			throw new IllegalStateException("保存索引状态失败，path=" + path, e);
		}
		finally {
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				}
				catch (IOException ignored) {
					// 未使用的临时文件不影响已提交状态，避免掩盖原始异常。
				}
			}
		}
	}

	/**
	 * 首次接管前使用 Qdrant REST API 精确计数。
	 *
	 * 常规 add/delete 仍由 Spring AI 完成； REST 只用于 VectorStore 通用接口未提供的集合计数。
	 */
	private void requireEmptyCollection(String collectionName) {
		URI uri = URI.create(qdrantRestUrl + "/collections/" + collectionName + "/points/count");

		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(Duration.ofSeconds(10))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{\"exact\":true}"))
			.build();

		try {
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				throw new IllegalStateException("无法检查 Qdrant 集合，HTTP=" + response.statusCode());
			}

			JsonNode count = objectMapper.readTree(response.body()).path("result").path("count");

			if (!count.isIntegralNumber() || count.longValue() != 0) {
				throw new IllegalStateException("没有索引状态，且集合非空或计数无效。请使用新的空集合建立基线。");
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("检查 Qdrant 集合被中断", e);
		}
		catch (IOException e) {
			throw new IllegalStateException("检查 Qdrant 集合失败", e);
		}
	}

	public record PendingSync(String collectionName, List<String> trackedIds) {
	}

}