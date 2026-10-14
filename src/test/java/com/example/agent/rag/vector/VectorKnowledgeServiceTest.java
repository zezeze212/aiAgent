package com.example.agent.rag.vector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class VectorKnowledgeServiceTest {

	private VectorStore vectorStore;

	private VectorKnowledgeService service;

	@BeforeEach
	void setUp() {
		vectorStore = mock(VectorStore.class);
		service = new VectorKnowledgeService(vectorStore, 0.6, "mysql", "public");
	}

	@Test
	void shouldPassDefaultThresholdAndScopeToVectorStore() {
		when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

		service.search("唯一键冲突", 3);

		SearchRequest request = capturedRequest();

		assertThat(request.getQuery()).isEqualTo("唯一键冲突");
		assertThat(request.getTopK()).isEqualTo(3);
		assertThat(request.getSimilarityThreshold()).isEqualTo(0.6);

		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		Filter.Expression expected = builder.and(builder.eq("category", "mysql"), builder.eq("visibility", "public"))
			.build();

		assertThat(request.getFilterExpression()).usingRecursiveComparison().isEqualTo(expected);
	}

	@Test
	void shouldAddSourceConstraintWithoutReplacingBaseScope() {
		when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

		String source = "docs/knowledge/sql/duplicate-entry.md";

		service.search("唯一键冲突", 5, 0.7, source);

		SearchRequest request = capturedRequest();

		assertThat(request.getSimilarityThreshold()).isEqualTo(0.7);

		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		Filter.Expression expected = builder
			.and(builder.and(builder.eq("category", "mysql"), builder.eq("visibility", "public")),
					builder.eq("source", source))
			.build();

		assertThat(request.getFilterExpression()).usingRecursiveComparison().isEqualTo(expected);
	}

	@Test
	void shouldRejectInvalidParametersBeforeSearching() {
		assertThatThrownBy(() -> service.search("唯一键冲突", 0)).isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> service.search("唯一键冲突", 5, Double.NaN, null))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> service.search("唯一键冲突", 5, 1.1, null)).isInstanceOf(IllegalArgumentException.class);

		verifyNoInteractions(vectorStore);
	}

	private SearchRequest capturedRequest() {
		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);

		verify(vectorStore).similaritySearch(captor.capture());

		return captor.getValue();
	}

}