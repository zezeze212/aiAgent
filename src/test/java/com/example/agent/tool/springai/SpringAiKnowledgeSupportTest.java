package com.example.agent.tool.springai;

import com.example.agent.rag.KnowledgeSearchResult;
import com.example.agent.rag.SimpleRagRetriever;
import com.example.agent.support.AgentJsonHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringAiKnowledgeSupportTest {

	@Mock
	private SimpleRagRetriever ragRetriever;

	private ObjectMapper objectMapper;

	private SpringAiKnowledgeSupport knowledgeSupport;

	private SpringAiToolRunContext runContext;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();

		AgentJsonHelper agentJsonHelper = new AgentJsonHelper(objectMapper);

		knowledgeSupport = new SpringAiKnowledgeSupport(ragRetriever, agentJsonHelper, objectMapper);

		runContext = new SpringAiToolRunContext("test1234", "Unknown column 'model_name' in 'field list'", 120_000L,
				objectMapper, agentJsonHelper);
	}

	@Test
	void shouldInjectPreDecisionKnowledgeIntoUserMessage() {
		KnowledgeSearchResult result = KnowledgeSearchResult.found("docs/knowledge/sql/unknown-column.md",
				"Unknown column 通常表示 SQL 字段与真实表结构不一致。");

		when(ragRetriever.retrieve(runContext.getUserMessage())).thenReturn(result);

		String modelUserMessage = knowledgeSupport.prepareInitialUserMessage(runContext);

		assertThat(modelUserMessage).contains(runContext.getUserMessage())
			.contains("docs/knowledge/sql/unknown-column.md")
			.contains("工具返回值是当前环境的确定性证据");

		assertThat(runContext.hasPreDecisionKnowledge()).isTrue();

		assertThat(runContext.getSteps()).anySatisfy(step -> {
			assertThat(step.getStepName()).isEqualTo("KNOWLEDGE_RETRIEVAL");
			assertThat(step.getDescription()).contains("PRE_DECISION", "检索到");
			assertThat(step.getSuccess()).isTrue();
		});
	}

	@Test
	void shouldSkipPostToolRetrievalWhenPreDecisionAlreadyMatched() {
		KnowledgeSearchResult result = KnowledgeSearchResult.found("docs/knowledge/sql/unknown-column.md",
				"Unknown column 排查指南");

		when(ragRetriever.retrieve(runContext.getUserMessage())).thenReturn(result);

		knowledgeSupport.prepareInitialUserMessage(runContext);

		KnowledgeSearchResult postToolResult = knowledgeSupport.retrievePostToolKnowledge(runContext,
				"{\"missingColumns\":[\"model_name\"]}");

		assertThat(postToolResult.matched()).isFalse();

		assertThat(runContext.getSteps()).anySatisfy(step -> {
			assertThat(step.getStepName()).isEqualTo("KNOWLEDGE_RETRIEVAL");
			assertThat(step.getDescription()).contains("POST_TOOL", "跳过");
		});
	}

	@Test
	void shouldAppendPostToolKnowledgeToToolPayload() throws Exception {
		SpringAiToolRunContext postToolContext = new SpringAiToolRunContext("test5678", "请分析这次 SQL 执行失败的原因", 120_000L,
				objectMapper, new AgentJsonHelper(objectMapper));

		String toolResult = "{\"errorCategory\":\"UNKNOWN_COLUMN\"}";

		KnowledgeSearchResult knowledge = KnowledgeSearchResult.found("docs/knowledge/sql/unknown-column.md",
				"需要对照数据库真实表结构检查字段。");

		when(ragRetriever.retrieve(postToolContext.getUserMessage(), toolResult)).thenReturn(knowledge);

		KnowledgeSearchResult postKnowledge = knowledgeSupport.retrievePostToolKnowledge(postToolContext, toolResult);

		String payload = knowledgeSupport.appendKnowledgeToToolPayload("""
				{
				  "toolName": "analyzeSqlErrorWithSchema",
				  "success": true,
				  "status": "SUCCESS",
				  "result": {
				    "errorCategory": "UNKNOWN_COLUMN"
				  }
				}
				""", postKnowledge);

		JsonNode payloadJson = objectMapper.readTree(payload);

		assertThat(payloadJson.get("knowledge").get("source").asText())
			.isEqualTo("docs/knowledge/sql/unknown-column.md");

		assertThat(payloadJson.get("knowledge").get("content").asText()).contains("数据库真实表结构");

		verify(ragRetriever).retrieve(postToolContext.getUserMessage(), toolResult);
	}

}