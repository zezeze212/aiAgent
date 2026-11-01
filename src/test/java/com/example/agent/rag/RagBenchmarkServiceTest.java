package com.example.agent.rag;

import com.example.agent.dto.RagBenchmarkResponse;
import com.example.agent.dto.VectorKnowledgeSearchHit;
import com.example.agent.dto.VectorKnowledgeSearchResponse;
import com.example.agent.exception.BusinessException;
import com.example.agent.rag.vector.KnowledgeManifestLoader;
import com.example.agent.rag.vector.MarkdownSectionChunker;
import com.example.agent.rag.vector.VectorKnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RagBenchmarkServiceTest {

	@TempDir
	Path stateDirectory;

	private SimpleRagRetriever rule;

	private VectorKnowledgeService vector;

	private RagBenchmarkService service;

	@BeforeEach
	void setUp() {
		rule = spy(new SimpleRagRetriever());
		vector = mock(VectorKnowledgeService.class);
		when(vector.searchPolicy()).thenReturn(new VectorKnowledgeService.SearchPolicy(0.0, "mysql", "public"));
		service = createService(true);
	}

	private RagBenchmarkService createService(boolean withVector) {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

		if (withVector) {
			factory.registerSingleton("vector", vector);
		}

		ObjectMapper mapper = new ObjectMapper();

		return new RagBenchmarkService(rule, factory.getBeanProvider(VectorKnowledgeService.class),
				new KnowledgeManifestLoader(mapper), new MarkdownSectionChunker(), mapper,
				"ai_agent_knowledge_managed_v1", "bge-m3:latest", "bge-m3-local-v1", stateDirectory.toString());
	}

	@Test
	void shouldUseActualRuleAndIdentifyDocumentGranularity() {
		var result = service.search("rule", "Duplicate entry 'A' for key 'uk_code'", 5);
		assertThat(result.resultUnit()).isEqualTo("document");
		assertThat(result.requestedTopK()).isEqualTo(5);
		assertThat(result.effectiveTopK()).isEqualTo(1);
		assertThat(result.hits()).hasSize(1);
		assertThat(result.hits().get(0).section()).isEqualTo("全文");
		assertThat(result.hits().get(0).chunkId()).startsWith("rule:");
		assertThat(result.hits().get(0).score()).isNull();
		assertThat(result.configuration().get("corpusSha256")).asString().hasSize(64);
		verify(rule).retrieve("Duplicate entry 'A' for key 'uk_code'");
		verifyNoInteractions(vector);
	}

	@Test
	void shouldPreserveDenseOrderIdsAndRealSearchPolicy() {
		when(vector.searchPolicy()).thenReturn(new VectorKnowledgeService.SearchPolicy(0.6, "mysql", "public"));
		var hits = List.of(
				new VectorKnowledgeSearchHit("chunk-2", 0.9, "docs/knowledge/sql/duplicate-entry.md", "重复键", "常见原因",
						"第一条证据"),
				new VectorKnowledgeSearchHit("chunk-1", 0.7, "docs/knowledge/sql/duplicate-entry.md", "重复键", "排查顺序",
						"第二条证据"));
		when(vector.search("重复提交", 5)).thenReturn(new VectorKnowledgeSearchResponse("重复提交", 5, 10L, hits));
		var dense = service.search("dense", " 重复提交 ", 5);
		var ruleResult = service.search("rule", "重复提交", 5);
		assertThat(dense.hits()).extracting(KnowledgeChunk::chunkId).containsExactly("chunk-2", "chunk-1");
		assertThat(dense.resultUnit()).isEqualTo("chunk");
		assertThat(dense.configuration().get("similarityThreshold")).isEqualTo(0.6);
		assertThat(dense.configuration().get("corpusSha256")).isEqualTo(ruleResult.configuration().get("corpusSha256"));
		assertThat(dense.configuration().get("indexStatePresent")).isEqualTo(false);
		assertThat(dense.configuration().get("indexStateSha256")).isNull();
		verify(vector).search("重复提交", 5);
	}

	@Test
	void shouldRefuseUnavailableDenseWithoutFallback() {
		assertThatThrownBy(() -> createService(false).search("dense", "唯一键冲突", 5)).isInstanceOf(BusinessException.class)
			.hasMessageContaining("未启用");
		verify(rule, never()).retrieve(anyString());
	}

	@Test
	void shouldRejectScopeMismatchInsteadOfComparingDifferentCorpora() {
		when(vector.searchPolicy()).thenReturn(new VectorKnowledgeService.SearchPolicy(0.0, "java", "public"));
		assertThatThrownBy(() -> service.search("dense", "异常", 5)).isInstanceOf(BusinessException.class)
			.hasMessageContaining("过滤范围");
		verify(vector, never()).search(anyString(), anyInt());
	}

	@Test
	void shouldRejectRuleInventoryMismatchInsteadOfSilentlyFilteringResults() {
		doReturn(List.of("docs/knowledge/sql/unknown-column.md")).when(rule).sources();
		assertThatThrownBy(() -> service.search("rule", "字段不存在", 5)).isInstanceOf(BusinessException.class)
			.hasMessageContaining("规则来源");
		verify(rule, never()).retrieve(anyString());
	}

	@Test
	void shouldRefusePendingIndexBeforeCallingDense() throws Exception {
		Files.writeString(stateDirectory.resolve("ai_agent_knowledge_managed_v1.json.pending.json"), "{}");
		assertThatThrownBy(() -> service.search("dense", "字段不存在", 5)).isInstanceOf(BusinessException.class)
			.hasMessageContaining("pending");
		verify(vector, never()).search(anyString(), anyInt());
	}

	@Test
	void shouldRejectIndexStateChangeDuringSearch() throws Exception {
		Path state = stateDirectory.resolve("ai_agent_knowledge_managed_v1.json");
		Files.writeString(state, "before");
		when(vector.search("字段不存在", 5)).thenAnswer(invocation -> {
			Files.writeString(state, "after");
			return new VectorKnowledgeSearchResponse("字段不存在", 5, 0L, List.of());
		});
		assertThatThrownBy(() -> service.search("dense", "字段不存在", 5)).isInstanceOf(BusinessException.class)
			.hasMessageContaining("发生变化");
	}

	@Test
	void shouldValidateInputsAndKeepNormalEmptyResult() {
		assertThatThrownBy(() -> service.search("unsupported", "问题", 5)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.search("rule", " ", 5)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.search("rule", "问题", 0)).isInstanceOf(BusinessException.class);
		assertThat(service.search("rule", "如何做一杯咖啡", 5).hits()).isEmpty();
		verifyNoInteractions(vector);
	}

	@AfterEach
	void closeIndexes() throws Exception {
		service.closeBm25Index();
	}

	@Test
	void shouldRunBm25WithoutVectorAndKeepConfigurationStable() {
		service = createService(false);

		var first = service.search("bm25", "Duplicate entry", 5);
		var second = service.search("bm25", "逻辑删除", 5);

		assertThat(first.strategy()).isEqualTo("bm25");
		assertThat(first.resultUnit()).isEqualTo("chunk");
		assertThat(first.hits()).isNotEmpty();

		assertThat(first.hits()).allSatisfy(hit -> {
			assertThat(hit.chunkId()).doesNotStartWith("rule:");
			assertThat(hit.score()).isPositive();
		});

		assertThat(first.configuration().get("engine")).isEqualTo("lucene");
		assertThat(second.configuration()).isEqualTo(first.configuration());

		verifyNoInteractions(vector);
	}

	@Test
	void shouldCombineBothBranchesAndExposeDiagnostics() {
		service = spy(service);

		var a = new KnowledgeChunk("a", "fixture.md", "测试", "章节", 0.9, "A");
		var b = new KnowledgeChunk("b", "fixture.md", "测试", "章节", 0.8, "B");
		var c = new KnowledgeChunk("c", "fixture.md", "测试", "章节", 9.0, "C");

		doReturn(branchResponse("dense", "same", List.of(a, b))).when(service).search("dense", "混合检索", 5);
		doReturn(branchResponse("bm25", "same", List.of(c, b))).when(service).search("bm25", "混合检索", 5);

		var result = service.search("hybrid", "混合检索", 5);

		assertThat(result.strategy()).isEqualTo("hybrid");
		assertThat(result.hits()).extracting(KnowledgeChunk::chunkId).containsExactly("b", "a", "c");

		assertThat(result.configuration().get("rrfRankConstant")).isEqualTo(60);
		assertThat(result.configuration().get("candidateTopK")).isEqualTo(5);
		assertThat(result.diagnostics()).containsKeys("denseHits", "bm25Hits", "ranking", "fusionCostMs");
		assertThat(result.costMs()).isGreaterThanOrEqualTo(4.0);

		verify(service).search("dense", "混合检索", 5);
		verify(service).search("bm25", "混合检索", 5);
	}

	@Test
	void shouldPropagateBranchFailureInsteadOfSilentFallback() {
		service = spy(service);

		doThrow(new BusinessException(503, "dense 未启用")).when(service).search("dense", "混合检索", 5);

		assertThatThrownBy(() -> service.search("hybrid", "混合检索", 5)).isInstanceOf(BusinessException.class)
			.hasMessageContaining("dense 未启用");

		verify(service, never()).search(eq("bm25"), anyString(), anyInt());
	}

	@Test
	void shouldRejectDifferentCorporaBetweenBranches() {
		service = spy(service);

		doReturn(branchResponse("dense", "corpus-a", List.of())).when(service).search("dense", "混合检索", 5);
		doReturn(branchResponse("bm25", "corpus-b", List.of())).when(service).search("bm25", "混合检索", 5);

		assertThatThrownBy(() -> service.search("hybrid", "混合检索", 5)).isInstanceOf(BusinessException.class)
			.hasMessageContaining("两路语料或范围不一致");
	}

	private RagBenchmarkResponse branchResponse(String strategy, String corpusHash, List<KnowledgeChunk> hits) {

		Map<String, Object> config = new LinkedHashMap<>();
		config.put("knowledgeVersion", "fixture-v1");
		config.put("corpusSha256", corpusHash);
		config.put("category", "mysql");
		config.put("visibility", "public");
		config.put("sourceFilter", null);
		config.put("sourceInventory", List.of(Map.of("source", "fixture.md")));

		return new RagBenchmarkResponse("rag-benchmark-response-v1", strategy, "chunk", "混合检索", 5, 5, 2.0, hits,
				config);
	}

}
