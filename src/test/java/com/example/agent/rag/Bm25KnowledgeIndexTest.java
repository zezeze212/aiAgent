package com.example.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25KnowledgeIndexTest {

	@Test
	void shouldPreserveIdentityAndReturnOnlyMatchingChunks() throws Exception {
		KnowledgeChunk target = chunk("target", "Idempotency prevents duplicate submissions.");
		KnowledgeChunk other = chunk("other", "Foreign references require parent records.");

		try (var index = new Bm25KnowledgeIndex(List.of(other, target))) {
			var hits = index.search("idempotency", 5);

			assertThat(hits).hasSize(1);
			assertThat(hits.get(0).chunkId()).isEqualTo(target.chunkId());
			assertThat(hits.get(0).source()).isEqualTo(target.source());
			assertThat(hits.get(0).title()).isEqualTo(target.title());
			assertThat(hits.get(0).section()).isEqualTo(target.section());
			assertThat(hits.get(0).content()).isEqualTo(target.content());
			assertThat(hits.get(0).score()).isPositive();
		}
	}

	@Test
	void shouldReturnEmptyWhenNoTermsMatch() throws Exception {
		try (var index = new Bm25KnowledgeIndex(List.of(chunk("one", "Database transaction rollback.")))) {

			assertThat(index.search("qzxvnotpresent", 5)).isEmpty();
			assertThat(index.search("!!!", 5)).isEmpty();
		}
	}

	@Test
	void shouldApplyLengthNormalizationForEqualTermFrequency() throws Exception {
		KnowledgeChunk longer = chunk("longer", "needle " + "filler ".repeat(80));
		KnowledgeChunk shorter = chunk("shorter", "needle");

		try (var index = new Bm25KnowledgeIndex(List.of(longer, shorter))) {
			assertThat(index.search("needle", 2)).extracting(KnowledgeChunk::chunkId)
				.containsExactly("shorter", "longer");
		}
	}

	private static KnowledgeChunk chunk(String id, String content) {
		return new KnowledgeChunk(id, "fixture/" + id + ".md", "测试知识", "测试章节", null, content);
	}

}