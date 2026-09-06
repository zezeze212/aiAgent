package com.example.agent.rag.vector;

import java.util.List;
import java.util.Map;

/**
 * 一次成功同步后的知识索引状态。
 *
 * documents 的 key 是 source 路径。 fingerprint 用于判断是否需要重新入库； chunkIds 用于后续删除旧文档产生的 Point。
 *
 * 只有同步成功后才能保存此状态，不能把“计划”直接当成成功记录。
 */
public record KnowledgeIndexState(String collectionName, Map<String, DocumentState> documents) {

	public record DocumentState(String fingerprint, List<String> chunkIds) {
	}
}