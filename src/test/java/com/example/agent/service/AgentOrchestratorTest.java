package com.example.agent.service;

import com.example.agent.client.DeepSeekDecisionClient;
import com.example.agent.config.AgentProperties;
import com.example.agent.dto.AgentAskResponse;
import com.example.agent.dto.AgentTraceStep;
import com.example.agent.dto.ToolDecision;
import com.example.agent.dto.ToolExecutionResult;
import com.example.agent.rag.KnowledgeSearchResult;
import com.example.agent.rag.SimpleRagRetriever;
import com.example.agent.support.AgentJsonHelper;
import com.example.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import org.mockito.ArgumentCaptor;

class AgentOrchestratorTest {

	private final DeepSeekDecisionClient decisionClient = mock(DeepSeekDecisionClient.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final SimpleRagRetriever ragRetriever = mock(SimpleRagRetriever.class);

	private final AgentProperties agentProperties = new AgentProperties();

	private final AgentJsonHelper agentJsonHelper = new AgentJsonHelper(new ObjectMapper());

	private final AgentOrchestrator agentOrchestrator = new AgentOrchestrator(decisionClient, toolRegistry,
			ragRetriever, agentJsonHelper, new ObjectMapper(), agentProperties);

	@BeforeEach
	void setUp() {
		when(ragRetriever.retrieve(anyString())).thenReturn(KnowledgeSearchResult.notFound());
	}

	@Test
	void shouldStopWhenSameToolAndArgumentsRepeated() {
		ToolDecision firstDecision = buildToolDecision("getTableSchema", Map.of("tableName", "agent_run_log"));

		ToolDecision repeatedDecision = buildToolDecision("getTableSchema", Map.of("tableName", "agent_run_log"));

		ToolExecutionResult successResult = new ToolExecutionResult("getTableSchema", true,
				"{\"tableName\":\"agent_run_log\"}", null, 10L);

		when(decisionClient.createDecisionMessages("查询表结构")).thenReturn(new ArrayList<>());

		when(decisionClient.decide(anyList())).thenReturn(firstDecision, repeatedDecision);

		when(toolRegistry.executeWithResult(eq("getTableSchema"), anyMap())).thenReturn(successResult);

		AgentAskResponse response = agentOrchestrator.execute("查询表结构", "test-trace");

		assertEquals("Agent 检测到重复的工具调用，已停止继续执行。", response.getAnswer());

		assertEquals(6, response.getSteps().size());

		AgentTraceStep guardStep = response.getSteps().get(5);

		assertEquals("AGENT_GUARD", guardStep.getStepName());
		assertFalse(guardStep.getSuccess());
		assertEquals("Agent 检测到重复的工具调用，已停止继续执行。", guardStep.getErrorMessage());

		verify(toolRegistry, times(1)).executeWithResult(eq("getTableSchema"), anyMap());
	}

	@Test
	void shouldStopAfterMaximumToolCalls() {
		ToolDecision firstDecision = buildToolDecision("getTableSchema", Map.of("tableName", "table_1"));

		ToolDecision secondDecision = buildToolDecision("getTableSchema", Map.of("tableName", "table_2"));

		ToolDecision thirdDecision = buildToolDecision("getTableSchema", Map.of("tableName", "table_3"));

		ToolDecision fourthDecision = buildToolDecision("getTableSchema", Map.of("tableName", "table_4"));

		ToolExecutionResult successResult = new ToolExecutionResult("getTableSchema", true, "{\"result\":\"success\"}",
				null, 10L);

		when(decisionClient.createDecisionMessages("连续查询表结构")).thenReturn(new ArrayList<>());

		when(decisionClient.decide(anyList())).thenReturn(firstDecision, secondDecision, thirdDecision, fourthDecision);

		when(toolRegistry.executeWithResult(eq("getTableSchema"), anyMap())).thenReturn(successResult);

		AgentAskResponse response = agentOrchestrator.execute("连续查询表结构", "max-call-trace");

		assertEquals("Agent 已达到最大工具调用次数，已停止继续执行。", response.getAnswer());

		assertEquals(12, response.getSteps().size());

		AgentTraceStep guardStep = response.getSteps().get(11);

		assertEquals("AGENT_GUARD", guardStep.getStepName());
		assertFalse(guardStep.getSuccess());
		assertEquals("Agent 已达到最大工具调用次数，已停止继续执行。", guardStep.getErrorMessage());

		verify(toolRegistry, times(3)).executeWithResult(eq("getTableSchema"), anyMap());
	}

	@Test
	void shouldAppendRetrievedKnowledgeAfterToolSuccess() {
		String userMessage = "Unknown column 'theme_code' in 'field list'";

		ToolDecision toolDecision = buildToolDecision("analyzeSqlErrorWithSchema",
				Map.of("errorLog", userMessage, "tableName", "theme"));

		ToolDecision finalDecision = buildDirectAnswerDecision("theme_code 字段在真实表结构中不存在。");

		ToolExecutionResult successResult = new ToolExecutionResult("analyzeSqlErrorWithSchema", true,
				"{\"missingColumns\":[\"theme_code\"]}", null, 10L);

		KnowledgeSearchResult knowledgeResult = KnowledgeSearchResult.found("docs/knowledge/sql-error-guide.md",
				"排查顺序：先确认真实表结构，再检查 Mapper XML。");

		when(decisionClient.createDecisionMessages(userMessage)).thenReturn(new ArrayList<>());

		when(decisionClient.decide(anyList())).thenReturn(toolDecision, finalDecision);

		when(toolRegistry.executeWithResult(eq("analyzeSqlErrorWithSchema"), anyMap())).thenReturn(successResult);

		when(ragRetriever.retrieve(argThat(query -> query.contains(userMessage) && query.contains("missingColumns"))))
			.thenReturn(knowledgeResult);

		AgentAskResponse response = agentOrchestrator.execute(userMessage, "rag-trace");

		assertEquals("theme_code 字段在真实表结构中不存在。", response.getAnswer());

		assertEquals(5, response.getSteps().size());

		AgentTraceStep preDecisionRetrievalStep = response.getSteps().get(0);
		assertEquals("KNOWLEDGE_RETRIEVAL", preDecisionRetrievalStep.getStepName());
		assertEquals("PRE_DECISION：未检索到相关本地知识", preDecisionRetrievalStep.getDescription());

		AgentTraceStep postToolRetrievalStep = response.getSteps().get(3);
		assertEquals("KNOWLEDGE_RETRIEVAL", postToolRetrievalStep.getStepName());
		assertEquals("POST_TOOL：检索到相关本地知识", postToolRetrievalStep.getDescription());

		assertEquals("AI_SUMMARY", response.getSteps().get(4).getStepName());

		verify(decisionClient).appendToolResult(anyList(), eq("analyzeSqlErrorWithSchema"), anyMap(),
				eq(successResult.getResult()), eq(knowledgeResult));
	}

	@Test
	void shouldSkipPostToolRetrievalWhenPreDecisionKnowledgeMatched() {
		String userMessage = "Unknown column 'theme_code' in 'field list'";

		ToolDecision toolDecision = buildToolDecision("analyzeSqlErrorWithSchema",
				Map.of("errorLog", userMessage, "tableName", "theme"));

		ToolDecision finalDecision = buildDirectAnswerDecision("theme_code 字段在真实表结构中不存在。");

		ToolExecutionResult successResult = new ToolExecutionResult("analyzeSqlErrorWithSchema", true,
				"{\"missingColumns\":[\"theme_code\"]}", null, 10L);

		KnowledgeSearchResult preDecisionKnowledgeResult = KnowledgeSearchResult
			.found("docs/knowledge/sql-error-guide.md", "排查顺序：先确认真实表结构，再检查 Mapper XML。");

		when(decisionClient.createDecisionMessages(userMessage)).thenReturn(new ArrayList<>());

		when(ragRetriever.retrieve(userMessage)).thenReturn(preDecisionKnowledgeResult);

		when(decisionClient.decide(anyList())).thenReturn(toolDecision, finalDecision);

		when(toolRegistry.executeWithResult(eq("analyzeSqlErrorWithSchema"), anyMap())).thenReturn(successResult);

		AgentAskResponse response = agentOrchestrator.execute(userMessage, "post-tool-skip-trace");

		assertEquals("theme_code 字段在真实表结构中不存在。", response.getAnswer());

		assertEquals(5, response.getSteps().size());

		AgentTraceStep preDecisionStep = response.getSteps().get(0);
		assertEquals("PRE_DECISION：检索到相关本地知识", preDecisionStep.getDescription());

		AgentTraceStep postToolStep = response.getSteps().get(3);
		assertEquals("KNOWLEDGE_RETRIEVAL", postToolStep.getStepName());
		assertEquals("POST_TOOL：PRE_DECISION 已命中知识，跳过补充检索", postToolStep.getDescription());

		assertEquals("AI_SUMMARY", response.getSteps().get(4).getStepName());

		verify(ragRetriever, times(1)).retrieve(anyString());

		verify(decisionClient).appendToolResult(anyList(), eq("analyzeSqlErrorWithSchema"), anyMap(),
				eq(successResult.getResult()), argThat(result -> result != null && !result.matched()));
	}

	@Test
	void shouldNotAppendSameKnowledgeRepeatedlyAfterMultipleTools() {
		String userMessage = "Unknown column 'theme_code' in 'field list'";

		ToolDecision firstToolDecision = buildToolDecision("analyzeSqlErrorWithSchema",
				Map.of("errorLog", userMessage, "tableName", "theme"));

		ToolDecision secondToolDecision = buildToolDecision("analyzeSqlErrorWithSchema",
				Map.of("errorLog", userMessage, "tableName", "game_plan"));

		ToolDecision finalDecision = buildDirectAnswerDecision("两个表的字段问题已经完成排查。");

		ToolExecutionResult firstSuccessResult = new ToolExecutionResult("analyzeSqlErrorWithSchema", true,
				"{\"stage\":\"first\",\"missingColumns\":[\"theme_code\"]}", null, 10L);

		ToolExecutionResult secondSuccessResult = new ToolExecutionResult("analyzeSqlErrorWithSchema", true,
				"{\"stage\":\"second\",\"missingColumns\":[\"plan_code\"]}", null, 12L);

		KnowledgeSearchResult repeatedKnowledge = KnowledgeSearchResult.found("docs/knowledge/sql-error-guide.md",
				"排查顺序：先确认真实表结构，再检查 Mapper XML。");

		when(decisionClient.createDecisionMessages(userMessage)).thenReturn(new ArrayList<>());

		when(decisionClient.decide(anyList())).thenReturn(firstToolDecision, secondToolDecision, finalDecision);

		when(toolRegistry.executeWithResult(eq("analyzeSqlErrorWithSchema"), anyMap())).thenReturn(firstSuccessResult,
				secondSuccessResult);

		when(ragRetriever.retrieve(userMessage)).thenReturn(KnowledgeSearchResult.notFound());

		when(ragRetriever.retrieve(argThat(query -> query != null && query.contains("\"stage\":\"first\""))))
			.thenReturn(repeatedKnowledge);

		when(ragRetriever.retrieve(argThat(query -> query != null && query.contains("\"stage\":\"second\""))))
			.thenReturn(repeatedKnowledge);

		AgentAskResponse response = agentOrchestrator.execute(userMessage, "knowledge-deduplication-trace");

		assertEquals("两个表的字段问题已经完成排查。", response.getAnswer());
		assertEquals(9, response.getSteps().size());

		assertEquals("PRE_DECISION：未检索到相关本地知识", response.getSteps().get(0).getDescription());

		assertEquals("POST_TOOL：检索到相关本地知识", response.getSteps().get(3).getDescription());

		assertEquals("POST_TOOL：检索到相关本地知识", response.getSteps().get(6).getDescription());

		AgentTraceStep deduplicationStep = response.getSteps().get(7);
		assertEquals("KNOWLEDGE_DEDUPLICATION", deduplicationStep.getStepName());
		assertEquals("POST_TOOL：命中知识已经注入，跳过重复追加", deduplicationStep.getDescription());

		assertEquals("AI_SUMMARY", response.getSteps().get(8).getStepName());

		verify(ragRetriever, times(3)).retrieve(anyString());

		ArgumentCaptor<KnowledgeSearchResult> knowledgeCaptor = ArgumentCaptor.forClass(KnowledgeSearchResult.class);

		verify(decisionClient, times(2)).appendToolResult(anyList(), eq("analyzeSqlErrorWithSchema"), anyMap(),
				anyString(), knowledgeCaptor.capture());

		List<KnowledgeSearchResult> appendedKnowledgeResults = knowledgeCaptor.getAllValues();

		assertEquals(2, appendedKnowledgeResults.size());

		KnowledgeSearchResult firstAppendedKnowledge = appendedKnowledgeResults.get(0);
		assertTrue(firstAppendedKnowledge.matched());
		assertEquals(repeatedKnowledge.source(), firstAppendedKnowledge.source());
		assertEquals(repeatedKnowledge.content(), firstAppendedKnowledge.content());

		KnowledgeSearchResult secondAppendedKnowledge = appendedKnowledgeResults.get(1);
		assertTrue(!secondAppendedKnowledge.matched());
	}

	@Test
	void shouldAppendKnowledgeBeforeInitialDecisionWhenMatched() {
		String userMessage = "Unknown column 'status_code' in 'field list'";
		List<Map<String, String>> messages = new ArrayList<>();

		KnowledgeSearchResult knowledgeResult = KnowledgeSearchResult.found("docs/knowledge/sql-error-guide.md",
				"遇到 Unknown column 时，应先核对 SQL 字段名和真实表结构。");

		ToolDecision finalDecision = buildDirectAnswerDecision("建议先检查字段名和真实表结构。");

		when(decisionClient.createDecisionMessages(userMessage)).thenReturn(messages);
		when(ragRetriever.retrieve(userMessage)).thenReturn(knowledgeResult);
		when(decisionClient.decide(messages)).thenReturn(finalDecision);

		AgentAskResponse response = agentOrchestrator.execute(userMessage, "initial-rag-hit-trace");

		assertEquals("建议先检查字段名和真实表结构。", response.getAnswer());
		assertEquals(2, response.getSteps().size());
		assertEquals("KNOWLEDGE_RETRIEVAL", response.getSteps().get(0).getStepName());
		assertEquals("AI_DECISION", response.getSteps().get(1).getStepName());

		InOrder inOrder = inOrder(decisionClient);
		inOrder.verify(decisionClient).appendKnowledgeContext(messages, knowledgeResult);
		inOrder.verify(decisionClient).decide(messages);

		verify(toolRegistry, never()).executeWithResult(anyString(), anyMap());
	}

	@Test
	void shouldContinueInitialDecisionWhenKnowledgeNotMatched() {
		String userMessage = "应用查询数据库时报错";
		List<Map<String, String>> messages = new ArrayList<>();

		KnowledgeSearchResult knowledgeResult = KnowledgeSearchResult.notFound();
		ToolDecision finalDecision = buildDirectAnswerDecision("请补充完整的错误日志和执行 SQL。");

		when(decisionClient.createDecisionMessages(userMessage)).thenReturn(messages);
		when(ragRetriever.retrieve(userMessage)).thenReturn(knowledgeResult);
		when(decisionClient.decide(messages)).thenReturn(finalDecision);

		AgentAskResponse response = agentOrchestrator.execute(userMessage, "initial-rag-miss-trace");

		assertEquals("请补充完整的错误日志和执行 SQL。", response.getAnswer());
		assertEquals(2, response.getSteps().size());
		assertEquals("KNOWLEDGE_RETRIEVAL", response.getSteps().get(0).getStepName());
		assertEquals("AI_DECISION", response.getSteps().get(1).getStepName());

		InOrder inOrder = inOrder(decisionClient);
		inOrder.verify(decisionClient).appendKnowledgeContext(messages, knowledgeResult);
		inOrder.verify(decisionClient).decide(messages);

		verify(toolRegistry, never()).executeWithResult(anyString(), anyMap());
	}

	private ToolDecision buildToolDecision(String toolName, Map<String, Object> arguments) {
		ToolDecision decision = new ToolDecision();

		decision.setNeedTool(true);
		decision.setToolName(toolName);
		decision.setArguments(arguments);
		decision.setDirectAnswer("");

		return decision;
	}

	private ToolDecision buildDirectAnswerDecision(String directAnswer) {
		ToolDecision decision = new ToolDecision();

		decision.setNeedTool(false);
		decision.setToolName("");
		decision.setArguments(Map.of());
		decision.setDirectAnswer(directAnswer);

		return decision;
	}

}