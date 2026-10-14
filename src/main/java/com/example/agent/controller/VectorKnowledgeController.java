package com.example.agent.controller;

import com.example.agent.common.Result;
import com.example.agent.dto.*;
import com.example.agent.exception.BusinessException;
import com.example.agent.rag.vector.KnowledgeIndexPlanner;
import com.example.agent.rag.vector.KnowledgeIndexSyncService;
import com.example.agent.rag.vector.VectorKnowledgeService;
import com.example.agent.rag.vector.VectorRagService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M5 Vector RAG 入库、检索与回答验证接口。
 *
 * 当前用于本地工程验证，不是最终面向用户的 Agent API。
 */
@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agent.rag.vector", name = "enabled", havingValue = "true")
public class VectorKnowledgeController {

	private final VectorKnowledgeService vectorKnowledgeService;

	private final VectorRagService vectorRagService;

	private final KnowledgeIndexPlanner knowledgeIndexPlanner;

	private final KnowledgeIndexSyncService knowledgeIndexSyncService;

	/**
	 * 同步知识索引。
	 *
	 * 首次建立成功状态，后续按变化执行增量写入和删除。
	 */
	@PostMapping("/index")
	public Result<KnowledgeIndexSyncResponse> index() {
		return Result.success(knowledgeIndexSyncService.sync());
	}

	/**
	 * 检索验证入口。
	 *
	 * similarityThreshold 和 source 仅影响当前请求， 不修改正式 Agent 使用的服务端配置。
	 */
	@GetMapping("/search")
	public Result<VectorKnowledgeSearchResponse> search(@RequestParam String query,
			@RequestParam(defaultValue = "5") Integer topK, @RequestParam(required = false) Double similarityThreshold,
			@RequestParam(required = false) String source) {

		validateQuery(query);
		validateTopK(topK);

		if (similarityThreshold != null
				&& (!Double.isFinite(similarityThreshold) || similarityThreshold < 0 || similarityThreshold > 1)) {

			throw new BusinessException(400, "similarityThreshold 必须是 0～1 之间的有限数值");
		}

		if (source != null && (source.isBlank() || source.length() > 500)) {
			throw new BusinessException(400, "source 长度必须在 1～500 之间");
		}

		return Result.success(vectorKnowledgeService.search(query.trim(), topK, similarityThreshold, source));
	}

	/**
	 * 预览知识索引变化。
	 *
	 * 只计算计划，不执行 Embedding、Qdrant 写入或状态保存。
	 */
	@PostMapping("/index/plan")
	public Result<KnowledgeIndexPlanResponse> previewIndexPlan() {
		return Result.success(knowledgeIndexPlanner.preview());
	}

	@PostMapping("/ask")
	public Result<VectorRagAskResponse> ask(@RequestBody VectorRagAskRequest request) {

		if (request == null) {
			throw new BusinessException(400, "请求体不能为空");
		}

		validateQuery(request.query());

		int topK = request.topK() == null ? 5 : request.topK();
		validateTopK(topK);

		return Result.success(vectorRagService.ask(request.query().trim(), topK));
	}

	private void validateQuery(String query) {
		if (query == null || query.isBlank()) {
			throw new BusinessException(400, "query 不能为空");
		}

		if (query.length() > 2000) {
			throw new BusinessException(400, "query 长度不能超过 2000");
		}
	}

	private void validateTopK(Integer topK) {
		if (topK == null || topK < 1 || topK > 20) {
			throw new BusinessException(400, "topK 必须在 1～20 之间");
		}
	}

}