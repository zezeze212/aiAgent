package com.example.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RrfKnowledgeFusionTest {

    @Test
    void shouldFuseByRankAndKeepDeterministicTies() {
        var dense = List.of(
                chunk("a", 0.99, "A"),
                chunk("b", 0.10, "B"));

        var bm25 = List.of(
                chunk("c", 900.0, "C"),
                chunk("b", 0.01, "B"));

        var result = RrfKnowledgeFusion.fuse(dense, bm25, 3);

        assertThat(result.hits())
                .extracting(KnowledgeChunk::chunkId)
                .containsExactly("b", "a", "c");

        assertThat(result.hits().get(0).score())
                .isCloseTo(2.0 / 62, within(1e-12));

        assertThat(result.hits().get(0).content()).isEqualTo("B");
        assertThat(result.ranking().get(0).denseRank()).isEqualTo(2);
        assertThat(result.ranking().get(0).bm25Rank()).isEqualTo(2);

        var limited = RrfKnowledgeFusion.fuse(dense, bm25, 1);
        assertThat(limited.hits()).hasSize(1);
        assertThat(limited.ranking()).hasSize(3);
    }

    @Test
    void shouldAcceptSuccessfulEmptyBranches() {
        var result = RrfKnowledgeFusion.fuse(
                List.of(),
                List.of(chunk("a", 100.0, "A")),
                5);

        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).score())
                .isCloseTo(1.0 / 61, within(1e-12));
        assertThat(result.ranking().get(0).denseRank()).isNull();

        assertThat(RrfKnowledgeFusion.fuse(
                List.of(), List.of(), 5).hits()).isEmpty();
    }

    @Test
    void shouldRejectDuplicateIdsInsideOneBranch() {
        var a = chunk("a", 1.0, "A");

        assertThatThrownBy(() -> RrfKnowledgeFusion.fuse(
                List.of(a, a), List.of(), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单路结果存在重复");
    }

    @Test
    void shouldRejectDifferentContentForTheSameId() {
        assertThatThrownBy(() -> RrfKnowledgeFusion.fuse(
                List.of(chunk("a", 0.9, "旧正文")),
                List.of(chunk("a", 9.0, "新正文")),
                5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("正文或元数据不一致");
    }

    private static KnowledgeChunk chunk(
            String id, double score, String content) {

        return new KnowledgeChunk(
                id, "fixture.md", "测试文档", "测试章节", score, content);
    }
}