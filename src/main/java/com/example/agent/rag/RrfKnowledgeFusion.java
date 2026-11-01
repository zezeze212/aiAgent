package com.example.agent.rag;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RrfKnowledgeFusion {

	public static final int RANK_CONSTANT = 60;

	private RrfKnowledgeFusion() {
	}

	public static FusionResult fuse(List<KnowledgeChunk> dense, List<KnowledgeChunk> bm25, int topK) {

		if (topK < 1 || topK > 20) {
			throw new IllegalArgumentException("topK 须为 1～20");
		}

		Map<String, KnowledgeChunk> chunks = new HashMap<>();

		Map<String, Integer> denseRanks = indexRanks(dense, chunks);
		Map<String, Integer> bm25Ranks = indexRanks(bm25, chunks);

		List<RankContribution> ranking = chunks.keySet()
			.stream()
			.map(id -> new RankContribution(id, denseRanks.get(id), bm25Ranks.get(id),
					contribution(denseRanks.get(id)) + contribution(bm25Ranks.get(id))))
			.sorted(Comparator.comparingDouble(RankContribution::rrfScore)
				.reversed()
				.thenComparing(RankContribution::chunkId))
			.toList();

		List<KnowledgeChunk> hits = ranking.stream().limit(topK).map(item -> {
			KnowledgeChunk original = chunks.get(item.chunkId());

			return new KnowledgeChunk(original.chunkId(), original.source(), original.title(), original.section(),
					item.rrfScore(), original.content());
		}).toList();

		return new FusionResult(hits, ranking);
	}

	private static Map<String, Integer> indexRanks(List<KnowledgeChunk> hits, Map<String, KnowledgeChunk> chunks) {

		Map<String, Integer> ranks = new HashMap<>();

		for (int i = 0; i < hits.size(); i++) {
			KnowledgeChunk hit = hits.get(i);

			if (hit == null || hit.chunkId() == null || hit.chunkId().isBlank() || hit.content() == null
					|| hit.content().isBlank()) {
				throw new IllegalArgumentException("候选片段 ID 和正文不能为空");
			}

			if (ranks.putIfAbsent(hit.chunkId(), i + 1) != null) {
				throw new IllegalArgumentException("单路结果存在重复 chunkId：" + hit.chunkId());
			}

			KnowledgeChunk previous = chunks.putIfAbsent(hit.chunkId(), hit);

			if (previous != null && (!Objects.equals(previous.source(), hit.source())
					|| !Objects.equals(previous.title(), hit.title())
					|| !Objects.equals(previous.section(), hit.section())
					|| !Objects.equals(previous.content(), hit.content()))) {
				throw new IllegalStateException("同一 chunkId 的正文或元数据不一致：" + hit.chunkId());
			}
		}

		return ranks;
	}

	private static double contribution(Integer rank) {
		return rank == null ? 0.0 : 1.0 / (RANK_CONSTANT + rank);
	}

	public record RankContribution(String chunkId, Integer denseRank, Integer bm25Rank, double rrfScore) {
	}

	public record FusionResult(List<KnowledgeChunk> hits, List<RankContribution> ranking) {
	}

}