package com.example.agent.client;

import com.example.agent.config.DeepSeekProperties;
import com.example.agent.rag.KnowledgeSearchResult;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.validator.ToolDecisionValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DeepSeekDecisionClientTest {

	private DeepSeekDecisionClient decisionClient;

	@BeforeEach
	void setUp() {
		decisionClient = new DeepSeekDecisionClient(mock(WebClient.class), mock(DeepSeekProperties.class),
				mock(ObjectMapper.class), mock(ToolRegistry.class), mock(ToolDecisionValidator.class));
	}

	@Test
	void shouldAppendKnowledgeContextWhenKnowledgeMatched() {
		List<Map<String, String>> messages = new ArrayList<>();
		messages.add(Map.of("role", "user", "content", "Unknown column 'status_code'"));

		KnowledgeSearchResult knowledgeResult = KnowledgeSearchResult.found("docs/knowledge/sql-error-guide.md",
				"遇到 Unknown column 时，应核对 SQL 字段名和真实表结构。");

		decisionClient.appendKnowledgeContext(messages, knowledgeResult);

		assertEquals(2, messages.size());

		Map<String, String> knowledgeMessage = messages.get(1);
		String content = knowledgeMessage.get("content");
		assertTrue(content.contains("docs/knowledge/sql-error-guide.md"), content);
		assertTrue(content.contains("应核对 SQL 字段名和真实表结构"), content);
		assertTrue(content.contains("不代表当前系统的真实情况"), content);
	}

	@Test
	void shouldNotAppendKnowledgeContextWhenKnowledgeNotMatched() {
		List<Map<String, String>> messages = new ArrayList<>();
		messages.add(Map.of("role", "user", "content", "应用查询数据库时报错"));

		decisionClient.appendKnowledgeContext(messages, KnowledgeSearchResult.notFound());

		assertEquals(1, messages.size());
		assertEquals("应用查询数据库时报错", messages.get(0).get("content"));
	}

}