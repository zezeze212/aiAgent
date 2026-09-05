package com.example.agent.tool.springai;

import com.example.agent.dto.ToolExecutionResult;
import com.example.agent.rag.KnowledgeSearchResult;
import com.example.agent.support.AgentJsonHelper;
import com.example.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将现有诊断工具适配为 Spring AI Tool。
 *
 * ToolContext 中携带本次请求的 SpringAiToolRunContext， 该上下文不会发送给大模型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringAiDiagnosticTools {

	private final ToolRegistry toolRegistry;

	private final AgentJsonHelper agentJsonHelper;

	private final ObjectMapper objectMapper;

	private final SpringAiKnowledgeSupport knowledgeSupport;

	@Tool(name = "getTableSchema", description = """
			查询当前 MySQL 数据库中指定表的真实字段结构。
			当用户询问某张表有哪些字段、字段类型、默认值、是否可空或字段注释时使用。
			只有当用户明确提供表名，或用户提供的完整 SQL 能唯一确定目标表时才能调用。
			如果无法确定表名，不得猜测或使用示例表名，应先请用户补充表名或完整 SQL。
			如果用户提供的是 SQL 或 MyBatis 报错并要求结合真实表结构诊断，
			应优先使用 analyzeSqlErrorWithSchema。
			""")
	public String getTableSchema(@ToolParam(description = "数据库表名。必须来自用户明确提供的内容，不得猜测；只允许字母、数字和下划线") String tableName,
			ToolContext toolContext) {

		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("tableName", tableName);

		return executeGovernedTool("getTableSchema", arguments, toolContext);
	}

	@Tool(name = "analyzeStackTrace", description = """
			解析完整的 Java 异常堆栈，提取顶层异常、root cause、cause 链和关键业务代码栈帧。
			当用户提供 Java Exception、Error 或完整 StackTrace，并要求定位根因时使用。
			如果问题是 SQL 字段与真实数据库表结构不一致，
			应优先使用 analyzeSqlErrorWithSchema。
			""")
	public String analyzeStackTrace(@ToolParam(description = "完整的 Java 异常 StackTrace 文本") String stackTrace,
			@ToolParam(description = "可选的业务代码包名前缀，例如 com.example.agent",
					required = false) String applicationPackagePrefix,
			ToolContext toolContext) {

		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("stackTrace", stackTrace);

		if (applicationPackagePrefix != null) {
			arguments.put("applicationPackagePrefix", applicationPackagePrefix);
		}

		return executeGovernedTool("analyzeStackTrace", arguments, toolContext);
	}

	@Tool(name = "analyzeSqlErrorWithSchema", description = """
			分析 SQL、MyBatis 或 JDBC 报错，并结合指定数据库表的真实结构进行诊断。
			适用于 Unknown column、字段不存在、实体字段与表字段不一致、
			Mapper SQL 与真实数据库结构不一致等问题。
			只有当用户明确提供表名，或用户提供的完整 SQL 能唯一确定目标表时才能调用。
			如果日志只有字段名但没有表名，不得猜测目标表，应先请用户补充表名或完整 SQL。
			该工具内部已经查询真实表结构，调用后通常不需要再调用 getTableSchema。
			""")
	public String analyzeSqlErrorWithSchema(@ToolParam(description = "完整的 SQL、MyBatis、JDBC 或相关 Java 报错日志") String log,
			@ToolParam(description = "需要对照真实结构的数据库表名。必须来自用户明确提供的内容或完整 SQL，不得猜测") String tableName,
			ToolContext toolContext) {

		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("log", log);
		arguments.put("tableName", tableName);

		return executeGovernedTool("analyzeSqlErrorWithSchema", arguments, toolContext);
	}

	private String executeGovernedTool(String toolName, Map<String, Object> arguments, ToolContext toolContext) {

		SpringAiToolRunContext runContext = resolveRunContext(toolContext);

		validateTableNameEvidence(toolName, arguments, runContext);

		log.info("Spring AI 请求执行受治理工具，traceId={}, toolName={}", runContext == null ? null : runContext.getTraceId(),
				toolName);

		ToolExecutionResult result = runContext.executeGovernedTool(toolName, arguments,
				() -> toolRegistry.executeWithResult(toolName, arguments));

		/*
		 * executeGovernedTool 在业务失败、超时、权限拒绝等情况下会直接抛出 SpringAiToolAbortException。
		 *
		 * 因此能够执行到这里，说明工具执行成功，可以进行 POST_TOOL 检索。
		 */
		KnowledgeSearchResult knowledgeResult = knowledgeSupport.retrievePostToolKnowledge(runContext,
				result.getResult());

		/*
		 * 面向模型构造工具结果： 如果 result 本身是 JSON，则还原成对象或数组， 避免作为转义后的字符串发送给模型。
		 */
		String toolPayload = buildModelToolResult(result);

		return knowledgeSupport.appendKnowledgeToToolPayload(toolPayload, knowledgeResult);
	}

	private void validateTableNameEvidence(String toolName, Map<String, Object> arguments,
			SpringAiToolRunContext runContext) {
		if (!"getTableSchema".equals(toolName) && !"analyzeSqlErrorWithSchema".equals(toolName)) {
			return;
		}

		Object tableNameValue = arguments.get("tableName");
		String tableName = tableNameValue == null ? null : String.valueOf(tableNameValue);

		if (SpringAiToolAvailabilityPolicy.hasExplicitTableNameEvidence(runContext.getUserMessage(), tableName)) {
			return;
		}

		String errorMessage = "缺少可验证的表名：tableName 必须来自原始用户问题，请补充表名或包含目标表的完整 SQL。";

		runContext.rejectToolCall("表名证据保护", toolName, arguments, errorMessage);
	}

	private SpringAiToolRunContext resolveRunContext(ToolContext toolContext) {

		if (toolContext == null || toolContext.getContext() == null) {

			throw new IllegalStateException("Spring AI ToolContext 不能为空");
		}

		Object contextValue = toolContext.getContext().get(SpringAiToolRunContext.CONTEXT_KEY);

		if (!(contextValue instanceof SpringAiToolRunContext runContext)) {

			throw new IllegalStateException("Spring AI 请求缺少 SpringAiToolRunContext");
		}

		return runContext;
	}

	private String buildModelToolResult(ToolExecutionResult executionResult) {

		Map<String, Object> modelResult = new LinkedHashMap<>();

		modelResult.put("toolName", executionResult.getToolName());

		modelResult.put("success", executionResult.getSuccess());

		modelResult.put("status", executionResult.getStatus().name());

		modelResult.put("result", agentJsonHelper.parseJsonIfPossible(executionResult.getResult()));

		modelResult.put("errorCode", executionResult.getErrorCode());

		modelResult.put("costMs", executionResult.getCostMs());

		try {
			return objectMapper.writeValueAsString(modelResult);
		}
		catch (Exception e) {
			throw new IllegalStateException("Spring AI 工具结果序列化失败，toolName=" + executionResult.getToolName(), e);
		}
	}

}
