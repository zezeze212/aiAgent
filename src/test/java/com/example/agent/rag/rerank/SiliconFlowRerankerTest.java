package com.example.agent.rag.rerank;

import com.example.agent.exception.BusinessException;
import com.example.agent.rag.KnowledgeChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SiliconFlowRerankerTest {

	private ObjectMapper mapper;

	private HttpClient http;

	private SiliconFlowReranker reranker;

	@BeforeEach
	void setUp() {
		mapper = spy(new ObjectMapper());
		http = mock(HttpClient.class);

		// 假 Key 只用于测试；HTTP 客户端已经被 mock，不访问外部服务。
		reranker = new SiliconFlowReranker(mapper, "test-key", http);
	}

	@Test
	void shouldSendAllCandidatesAndMapScoresByInputIndex() throws Exception {
		List<KnowledgeChunk> input = List.of(chunk("chunk-a", "第一段正文"), chunk("chunk-b", "第二段正文"));

		reply(200, """
				{
				  "id": "request-1",
				  "results": [
				    {"index": 1, "relevance_score": 0.9},
				    {"index": 0, "relevance_score": 0.2}
				  ]
				}
				""");

		var result = reranker.rerank("  字段不存在  ", input);

		assertThat(result.ranking()).extracting(KnowledgeChunk::chunkId).containsExactly("chunk-b", "chunk-a");

		// 高分必须归属输入 index=1 的片段，不能按返回位置套用原列表。
		KnowledgeChunk first = result.ranking().get(0);
		assertThat(first.source()).isEqualTo(input.get(1).source());
		assertThat(first.title()).isEqualTo(input.get(1).title());
		assertThat(first.section()).isEqualTo(input.get(1).section());
		assertThat(first.content()).isEqualTo(input.get(1).content());
		assertThat(first.score()).isEqualTo(0.9);
		assertThat(result.requestId()).isEqualTo("request-1");
		assertThat(result.costMs()).isGreaterThanOrEqualTo(0.0);

		// 原输入对象没有被修改，后续仍可保留原始检索分数作诊断。
		assertThat(input.get(1).score()).isEqualTo(123.0);

		ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
		verify(mapper).writeValueAsString(payload.capture());

		JsonNode body = mapper.valueToTree(payload.getValue());
		assertThat(body.get("query").textValue()).isEqualTo("字段不存在");
		assertThat(body.get("top_n").intValue()).isEqualTo(2);
		assertThat(body.get("return_documents").booleanValue()).isFalse();
		assertThat(body.get("documents").size()).isEqualTo(2);
		assertThat(body.get("documents").get(0).textValue()).isEqualTo("第一段正文");
		assertThat(body.get("documents").get(1).textValue()).isEqualTo("第二段正文");

		// 实验配置不能包含认证 Key。
		assertThat(reranker.configuration().toString()).doesNotContain("test-key");
	}

	@Test
	void shouldUseChunkIdToBreakEqualScoreTies() throws Exception {
		reply(200, """
				{
				  "results": [
				    {"index": 0, "relevance_score": 0.8},
				    {"index": 1, "relevance_score": 0.8}
				  ]
				}
				""");

		var result = reranker.rerank("字段不存在", List.of(chunk("chunk-b", "正文 B"), chunk("chunk-a", "正文 A")));

		assertThat(result.ranking()).extracting(KnowledgeChunk::chunkId).containsExactly("chunk-a", "chunk-b");
	}

	@Test
	void shouldReturnEmptyWithoutCallingRemoteService() {
		var result = reranker.rerank("字段不存在", List.of());

		assertThat(result.ranking()).isEmpty();
		assertThat(result.costMs()).isZero();
		verifyNoInteractions(http);
	}

	@Test
	void shouldRejectDuplicateCandidateIdsBeforeHttpCall() {
		assertThatThrownBy(() -> reranker.rerank("字段不存在", List.of(chunk("same-id", "正文 A"), chunk("same-id", "正文 B"))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("重复 chunkId");

		verifyNoInteractions(http);
	}

	/*
	 * 这些都是协议错误，而不是检索未命中： 数量不足、索引重复/越界/缺失、分数类型错误、非法 JSON。
	 */
	@ParameterizedTest
	@ValueSource(strings = { "{\"results\":[]}",
			"{\"results\":[" + "{\"index\":0,\"relevance_score\":0.8}," + "{\"index\":0,\"relevance_score\":0.7}]}",
			"{\"results\":[" + "{\"index\":0,\"relevance_score\":0.8}," + "{\"index\":2,\"relevance_score\":0.7}]}",
			"{\"results\":[" + "{\"relevance_score\":0.8}," + "{\"index\":1,\"relevance_score\":0.7}]}",
			"{\"results\":[" + "{\"index\":0,\"relevance_score\":\"0.8\"}," + "{\"index\":1,\"relevance_score\":0.7}]}",
			"not-json" })
	void shouldRejectMalformedResponses(String json) throws Exception {
		reply(200, json);

		BusinessException error = assertThrows(BusinessException.class,
				() -> reranker.rerank("字段不存在", twoCandidates()));

		assertThat(error.getCode()).isEqualTo(502);
		assertThat(error.getMessage()).contains("响应不符合契约");
	}

	@Test
	void shouldExposeUpstreamFailureWithoutReturningFallback() throws Exception {

		reply(429, "upstream-private-body");

		BusinessException error = assertThrows(BusinessException.class,
				() -> reranker.rerank("字段不存在", twoCandidates()));

		assertThat(error.getCode()).isEqualTo(502);
		assertThat(error.getMessage()).contains("429").doesNotContain("upstream-private-body", "test-key");
	}

	@Test
	void shouldReportTimeoutExplicitly() throws Exception {
		when(http.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
			.thenThrow(new HttpTimeoutException("simulated timeout"));

		BusinessException error = assertThrows(BusinessException.class,
				() -> reranker.rerank("字段不存在", twoCandidates()));

		assertThat(error.getCode()).isEqualTo(504);
	}

	@Test
	void shouldRestoreInterruptFlag() throws Exception {
		when(http.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
			.thenThrow(new InterruptedException("simulated interrupt"));

		try {
			BusinessException error = assertThrows(BusinessException.class,
					() -> reranker.rerank("字段不存在", twoCandidates()));

			assertThat(error.getCode()).isEqualTo(503);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			// 清除本测试产生的标志，避免影响同一测试线程后续执行。
			Thread.interrupted();
		}
	}

	@SuppressWarnings("unchecked")
	private void reply(int status, String body) throws Exception {
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.body()).thenReturn(body);

		when(http.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
			.thenReturn(response);
	}

	private static List<KnowledgeChunk> twoCandidates() {
		return List.of(chunk("chunk-a", "正文 A"), chunk("chunk-b", "正文 B"));
	}

	private static KnowledgeChunk chunk(String id, String content) {
		return new KnowledgeChunk(id, "docs/knowledge/sql/unknown-column.md", "字段不存在", "常见原因", 123.0, content);
	}

}