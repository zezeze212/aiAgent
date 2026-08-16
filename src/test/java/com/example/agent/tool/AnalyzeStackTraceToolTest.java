package com.example.agent.tool;

import com.example.agent.stacktrace.StackTraceParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyzeStackTraceToolTest {

	private ObjectMapper objectMapper;

	private AnalyzeStackTraceTool tool;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();

		tool = new AnalyzeStackTraceTool(new StackTraceParser(), objectMapper);
	}

	@Test
	void shouldExtractExceptionCauseChainAndApplicationFrames() throws Exception {
		String stackTrace = """
				org.springframework.dao.DataAccessResourceFailureException: query failed
				    at org.springframework.jdbc.core.JdbcTemplate.query(JdbcTemplate.java:123)
				    at com.example.order.OrderService.load(OrderService.java:82)
				Caused by: java.sql.SQLSyntaxErrorException: Unknown column 'status_code' in 'field list'
				    at com.mysql.cj.jdbc.ClientPreparedStatement.executeInternal(ClientPreparedStatement.java:900)
				    at com.example.order.OrderRepository.find(OrderRepository.java:41)
				""";

		String result = tool.execute(Map.of("stackTrace", stackTrace, "applicationPackagePrefix", "com.example"));

		JsonNode json = objectMapper.readTree(result);

		assertThat(json.path("topExceptionType").asText())
			.isEqualTo("org.springframework.dao.DataAccessResourceFailureException");

		assertThat(json.path("topExceptionMessage").asText()).isEqualTo("query failed");

		assertThat(json.path("rootCauseType").asText()).isEqualTo("java.sql.SQLSyntaxErrorException");

		assertThat(json.path("rootCauseMessage").asText()).isEqualTo("Unknown column 'status_code' in 'field list'");

		assertThat(json.path("causeDepth").asInt()).isEqualTo(1);

		assertThat(json.path("causeChain").size()).isEqualTo(2);

		assertThat(json.path("firstApplicationFrame").path("className").asText())
			.isEqualTo("com.example.order.OrderService");

		assertThat(json.path("firstApplicationFrame").path("methodName").asText()).isEqualTo("load");

		assertThat(json.path("firstApplicationFrame").path("lineNumber").asInt()).isEqualTo(82);

		assertThat(json.path("applicationFrameCount").asInt()).isEqualTo(2);

		assertThat(json.path("totalFrameCount").asInt()).isEqualTo(4);
	}

	@Test
	void shouldUseFrameworkExclusionWhenPackagePrefixIsNotProvided() throws Exception {
		String stackTrace = """
				java.lang.IllegalStateException: create order failed
				    at org.springframework.aop.framework.CglibAopProxy.intercept(CglibAopProxy.java:100)
				    at com.example.order.OrderService.create(OrderService.java:52)
				    at java.base/java.lang.Thread.run(Thread.java:840)
				""";

		String result = tool.execute(Map.of("stackTrace", stackTrace));

		JsonNode json = objectMapper.readTree(result);

		assertThat(json.path("firstApplicationFrame").path("className").asText())
			.isEqualTo("com.example.order.OrderService");

		assertThat(json.path("applicationFrameCount").asInt()).isEqualTo(1);
	}

	@Test
	void shouldReportUnrecognizedTextAsBusinessError() {
		String ordinaryLog = """
				application started
				request finished
				everything looks fine
				""";

		assertThatThrownBy(() -> tool.execute(Map.of("stackTrace", ordinaryLog)))
			.isInstanceOf(ToolBusinessException.class)
			.satisfies(exception -> {
				ToolBusinessException businessException = (ToolBusinessException) exception;

				assertThat(businessException.getErrorCode()).isEqualTo("STACK_TRACE_NOT_RECOGNIZED");

				assertThat(businessException.getResult()).contains("未识别到合法 Java 异常堆栈");
			});
	}

	@Test
	void shouldExposeReadOnlyLowRiskToolContract() {
		ToolDefinition definition = tool.definition();

		assertThat(definition.name()).isEqualTo("analyzeStackTrace");

		assertThat(definition.readOnly()).isTrue();

		assertThat(definition.sideEffect()).isEqualTo(ToolSideEffect.NONE);

		assertThat(definition.riskLevel()).isEqualTo(ToolRiskLevel.LOW);

		assertThat(definition.timeoutMs()).isEqualTo(1000L);

		assertThat(definition.requiredPermissions()).containsExactly("LOCAL_DIAGNOSTIC_ANALYZE");

		assertThat(definition.parameters()).hasSize(2);
	}

	@Test
	void shouldParseFlattenedStackTrace() throws Exception {
		/*
		 * 模拟 StackTrace 在聊天窗口、JSON 转换或模型 Tool 参数中 被压缩成单行的真实情况。
		 */
		String stackTrace = "java.lang.NullPointerException: Cannot invoke \"String.length()\" because \"name\" is null "
				+ "at com.example.order.OrderService.createOrder(OrderService.java:87) "
				+ "at com.example.order.OrderController.create(OrderController.java:42) "
				+ "at org.springframework.web.servlet.FrameworkServlet.doPost(FrameworkServlet.java:909)";

		String result = tool.execute(Map.of("stackTrace", stackTrace, "applicationPackagePrefix", "com.example.order"));

		JsonNode json = objectMapper.readTree(result);

		assertThat(json.path("topExceptionType").asText()).isEqualTo("java.lang.NullPointerException");

		assertThat(json.path("topExceptionMessage").asText())
			.isEqualTo("Cannot invoke \"String.length()\" because \"name\" is null");

		assertThat(json.path("firstApplicationFrame").path("className").asText())
			.isEqualTo("com.example.order.OrderService");

		assertThat(json.path("firstApplicationFrame").path("methodName").asText()).isEqualTo("createOrder");

		assertThat(json.path("firstApplicationFrame").path("lineNumber").asInt()).isEqualTo(87);

		assertThat(json.path("applicationFrameCount").asInt()).isEqualTo(2);

		assertThat(json.path("totalFrameCount").asInt()).isEqualTo(3);
	}

}