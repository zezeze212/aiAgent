package com.example.agent.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleRagRetrieverTest {

	private final SimpleRagRetriever retriever = new SimpleRagRetriever();

	@Test
	void shouldReturnKnowledgeWhenQueryContainsUnknownColumn() {
		KnowledgeSearchResult result = retriever.retrieve("Unknown column 'theme_code' in 'field list'");

		assertTrue(result.matched());
		assertEquals("docs/knowledge/sql-error-guide.md", result.source());
		assertNotNull(result.content());
		assertTrue(result.content().contains("排查顺序"));
	}

	@Test
	void shouldIgnoreKeywordCase() {
		KnowledgeSearchResult result = retriever.retrieve("UNKNOWN COLUMN 'theme_code'");

		assertTrue(result.matched());
		assertNotNull(result.content());
	}

	@Test
	void shouldReturnNotFoundForUnrelatedQuestion() {
		KnowledgeSearchResult result = retriever.retrieve("Spring 事务为什么失效");

		assertFalse(result.matched());
		assertNull(result.source());
		assertNull(result.content());
	}

	@Test
	void shouldReturnNotFoundForEmptyQuery() {
		KnowledgeSearchResult nullResult = retriever.retrieve(null);
		KnowledgeSearchResult blankResult = retriever.retrieve("   ");

		assertFalse(nullResult.matched());
		assertFalse(blankResult.matched());
	}

}