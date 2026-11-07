package com.example.agent.rag;

import com.example.agent.dto.VectorKnowledgeSearchHit;
import com.example.agent.dto.VectorKnowledgeSearchResponse;
import com.example.agent.rag.vector.KnowledgeManifest;
import com.example.agent.rag.vector.KnowledgeManifestLoader;
import com.example.agent.rag.vector.KnowledgeSource;
import com.example.agent.rag.vector.MarkdownSectionChunker;
import com.example.agent.rag.vector.VectorKnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AdvancedKnowledgeRetrieverTest {

	private VectorKnowledgeService vector;

	private Bm25KnowledgeRetriever bm25;

	@BeforeEach
	void setUp() {
		vector = mock(VectorKnowledgeService.class);
		bm25 = mock(Bm25KnowledgeRetriever.class);

		when(vector.searchPolicy()).thenReturn(new VectorKnowledgeService.SearchPolicy(0.0, "mysql", "public"));
		when(bm25.category()).thenReturn("mysql");
		when(bm25.visibility()).thenReturn("public");
	}

	@Test
	void shouldBuildBm25OnlyFromAllowedScopeAndReuseChunkIdentity() throws Exception {
		KnowledgeManifestLoader loader = mock(KnowledgeManifestLoader.class);
		MarkdownSectionChunker chunker = new MarkdownSectionChunker();

		KnowledgeSource mysql = new KnowledgeSource("docs/knowledge/mysql.md", "MySQL", "mysql", "public");
		KnowledgeSource java = new KnowledgeSource("docs/knowledge/java.md", "Java", "java", "public");
		String markdown = "# MySQL\n\n## 原因\n\nuniquealpha";

		when(loader.loadManifest()).thenReturn(new KnowledgeManifest("test-v1", List.of(mysql, java)));
		when(loader.readMarkdown(mysql)).thenReturn(markdown);

		try (var retriever = new Bm25KnowledgeRetriever(loader, chunker, "mysql", "public")) {

			var result = retriever.retrieve("uniquealpha", 5);
			var expected = chunker.chunk("test-v1", mysql, markdown).get(0);

			assertThat(result.retriever()).isEqualTo("bm25");
			assertThat(result.chunks()).hasSize(1);
			assertThat(result.chunks().get(0).chunkId()).isEqualTo(expected.getId());
			assertThat(result.chunks().get(0).content()).isEqualTo(expected.getText());
			assertThat(result.chunks().get(0).score()).isPositive();

			// 范围外文档连正文都不读取，不会先参与 TopK 再被过滤。
			verify(loader, never()).readMarkdown(java);
		}
	}

	@Test
	void shouldRejectUnknownOrChangedDenseChunks() throws Exception {
		try (var retriever = realBm25()) {
			KnowledgeChunk known = retriever.retrieve("uniquealpha", 5).chunks().get(0);

			// 分数不同是正常的，不影响片段身份校验。
			KnowledgeChunk rescored = new KnowledgeChunk(known.chunkId(), known.source(), known.title(),
					known.section(), 99.0, known.content());
			retriever.requireSameSnapshot(List.of(rescored));

			KnowledgeChunk changed = new KnowledgeChunk(known.chunkId(), known.source(), known.title(), known.section(),
					0.9, known.content() + " 已变化");

			assertThatThrownBy(() -> retriever.requireSameSnapshot(List.of(changed)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("快照不一致");

			assertThatThrownBy(() -> retriever.requireSameSnapshot(List.of(chunk("unknown", 0.9))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("快照不一致");
		}
	}

	@Test
	void shouldRejectScopeWithoutDocuments() {
		KnowledgeManifestLoader loader = mock(KnowledgeManifestLoader.class);
		KnowledgeSource source = new KnowledgeSource("docs/knowledge/java.md", "Java", "java", "public");
		when(loader.loadManifest()).thenReturn(new KnowledgeManifest("test-v1", List.of(source)));

		assertThatThrownBy(() -> new Bm25KnowledgeRetriever(loader, new MarkdownSectionChunker(), "mysql", "public"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("没有知识文档");
	}

	@Test
	void shouldCallBothBranchesAndReturnRrfTopK() {
		KnowledgeChunk a = chunk("a", 0.9);
		KnowledgeChunk b = chunk("b", 0.8);
		KnowledgeChunk c = chunk("c", 9.0);

		when(vector.search("查询", 2)).thenReturn(vectorResponse("查询", 2, a, b));
		when(bm25.retrieve("查询", 2)).thenReturn(new KnowledgeRetrievalResult("bm25", List.of(c, b)));

		var result = new HybridKnowledgeRetriever(vector, bm25).retrieve(" 查询 ", 2);

		// b 在两路都出现，优先于仅在单路出现的 a/c；最终只保留 Top2。
		assertThat(result.retriever()).isEqualTo("hybrid");
		assertThat(result.chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("b", "a");

		verify(vector).search("查询", 2);
		verify(bm25).requireSameSnapshot(List.of(a, b));
		verify(bm25).retrieve("查询", 2);
	}

	@Test
	void shouldUseBm25HitsWhenDenseNormallyReturnsEmpty() {
		when(vector.search("查询", 2)).thenReturn(vectorResponse("查询", 2));
		when(bm25.retrieve("查询", 2)).thenReturn(new KnowledgeRetrievalResult("bm25", List.of(chunk("c", 9.0))));

		var result = new HybridKnowledgeRetriever(vector, bm25).retrieve("查询", 2);

		assertThat(result.chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("c");
		verify(bm25).retrieve("查询", 2);
	}

	@Test
	void shouldPropagateDenseFailureInsteadOfPretendingItWasEmpty() {
		when(vector.search("查询", 2)).thenThrow(new IllegalStateException("模拟 Dense 故障"));

		var retriever = new HybridKnowledgeRetriever(vector, bm25);

		assertThatThrownBy(() -> retriever.retrieve("查询", 2)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Dense 故障");

		verify(bm25, never()).retrieve(anyString(), anyInt());
	}

	@Test
	void shouldPropagateBm25FailureAfterDenseSucceeds() {
		when(vector.search("查询", 2)).thenReturn(vectorResponse("查询", 2, chunk("a", 0.9)));
		when(bm25.retrieve("查询", 2)).thenThrow(new IllegalStateException("模拟 BM25 故障"));

		var retriever = new HybridKnowledgeRetriever(vector, bm25);

		assertThatThrownBy(() -> retriever.retrieve("查询", 2)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("BM25 故障");
	}

	@Test
	void shouldRejectDifferentBranchScopes() {
		when(bm25.category()).thenReturn("java");

		assertThatThrownBy(() -> new HybridKnowledgeRetriever(vector, bm25)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("范围不一致");
	}

	private Bm25KnowledgeRetriever realBm25() throws Exception {
		KnowledgeManifestLoader loader = mock(KnowledgeManifestLoader.class);
		KnowledgeSource source = new KnowledgeSource("docs/knowledge/mysql.md", "MySQL", "mysql", "public");

		when(loader.loadManifest()).thenReturn(new KnowledgeManifest("test-v1", List.of(source)));
		when(loader.readMarkdown(source)).thenReturn("# MySQL\n\n## 原因\n\nuniquealpha");

		return new Bm25KnowledgeRetriever(loader, new MarkdownSectionChunker(), "mysql", "public");
	}

	private KnowledgeChunk chunk(String id, double score) {
		return new KnowledgeChunk(id, "docs/knowledge/test.md", "测试", "章节", score, "正文-" + id);
	}

	private VectorKnowledgeSearchResponse vectorResponse(String query, int topK, KnowledgeChunk... chunks) {

		List<VectorKnowledgeSearchHit> hits = List.of(chunks)
			.stream()
			.map(chunk -> new VectorKnowledgeSearchHit(chunk.chunkId(), chunk.score(), chunk.source(), chunk.title(),
					chunk.section(), chunk.content()))
			.toList();

		return new VectorKnowledgeSearchResponse(query, topK, 0L, hits);
	}

}