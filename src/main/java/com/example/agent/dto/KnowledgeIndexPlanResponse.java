package com.example.agent.dto;

import java.util.List;

/**
 * 知识索引变化预览。
 *
 * baselinePresent=false 表示还没有可供比较的成功同步状态。 此时 ADDED 的意思是“状态中没有记录”，不代表 Qdrant 中一定没有数据。
 */
public record KnowledgeIndexPlanResponse(String collectionName, boolean baselinePresent, int addedCount,
		int updatedCount, int unchangedCount, int deletedCount, List<Change> changes) {

	public enum Action {

		ADDED, UPDATED, UNCHANGED, DELETED

	}

	public record Change(String source, Action action, int previousChunkCount, int currentChunkCount) {
	}
}