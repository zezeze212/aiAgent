package com.example.agent.dto;

/**
 * 一次成功的索引同步结果。
 *
 * plan 表示与上次成功状态相比的变化； writtenChunkCount / deletedChunkCount 表示本次实际提交的操作数量。
 * 恢复模式下，即使计划显示未变，也会重新写入当前目标数据。
 */
public record KnowledgeIndexSyncResponse(KnowledgeIndexPlanResponse plan, boolean recovery, int writtenChunkCount,
		int deletedChunkCount, long costMs) {
}