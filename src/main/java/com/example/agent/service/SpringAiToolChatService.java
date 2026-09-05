package com.example.agent.service;

import com.example.agent.config.AgentProperties;
import com.example.agent.dto.AgentAskResponse;
import com.example.agent.support.AgentJsonHelper;
import com.example.agent.tool.springai.SpringAiDiagnosticTools;
import com.example.agent.tool.springai.SpringAiKnowledgeSupport;
import com.example.agent.tool.springai.SpringAiRequestBoundaryPolicy;
import com.example.agent.tool.springai.SpringAiToolAvailabilityPolicy;
import com.example.agent.tool.springai.SpringAiToolRunContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SpringAiToolChatService {

	static final String SYSTEM_PROMPT = """
			你是一个 Java 后端故障诊断助手，可以使用数据库和异常分析工具获取确定性证据。

			参数与证据规则：

			1. Tool 的必填参数必须来自原始用户问题，或者来自用户提供的 SQL、日志、StackTrace；
			   不得把 Tool 描述中的示例、本地知识或历史经验当成用户已经提供的参数。

			2. tableName 只有在原始用户问题明确给出表名，
			   或者用户提供的完整 SQL 能唯一确定目标表时才可使用；不得猜测、补全或选择默认表名。

			3. 需要查询真实表结构但无法确定 tableName 时，不要调用 getTableSchema 或 analyzeSqlErrorWithSchema；
			   应说明缺少表名，并请用户补充表名或包含目标表的完整 SQL。

			工具选择规则：

			1. 查询数据库表字段、类型、默认值、可空性或注释时，
			   使用 getTableSchema。

			2. SQL、MyBatis 或 JDBC 报错需要结合真实表结构诊断时，
			   使用 analyzeSqlErrorWithSchema。
			   该工具内部已经查询表结构，不要重复调用 getTableSchema。
			   调用前必须满足上述 tableName 证据规则。

			3. 一般 Java Exception、Error 或完整 StackTrace，
			   使用 analyzeStackTrace。

			4. Java、Spring 或数据库概念问题不要调用工具。

			回答规则：

			1. 工具结果是确定性证据，必须优先于模型自身知识。
			2. 不得编造工具结果中不存在的表、字段、异常或代码位置。
			3. 应先说明直接原因，再说明证据，最后给出排查建议。
			4. 不要使用相同参数重复调用同一个工具。
			""";

	private final ChatClient chatClient;

	private final Map<String, ToolCallback> diagnosticToolCallbacks;

	private final AgentProperties agentProperties;

	private final ObjectMapper objectMapper;

	private final AgentJsonHelper agentJsonHelper;

	private final AgentLogService agentLogService;

	private final SpringAiKnowledgeSupport knowledgeSupport;

	public SpringAiToolChatService(ChatClient.Builder chatClientBuilder, SpringAiDiagnosticTools diagnosticTools,
			AgentProperties agentProperties, ObjectMapper objectMapper, AgentJsonHelper agentJsonHelper,
			AgentLogService agentLogService, SpringAiKnowledgeSupport knowledgeSupport) {

		this.chatClient = chatClientBuilder.defaultSystem(SYSTEM_PROMPT).build();

		this.diagnosticToolCallbacks = Arrays.stream(ToolCallbacks.from(diagnosticTools))
			.collect(
					Collectors.toUnmodifiableMap(callback -> callback.getToolDefinition().name(), Function.identity()));
		this.agentProperties = agentProperties;
		this.objectMapper = objectMapper;
		this.agentJsonHelper = agentJsonHelper;
		this.agentLogService = agentLogService;
		this.knowledgeSupport = knowledgeSupport;
	}

	public AgentAskResponse chat(String userMessage) {
		String traceId = UUID.randomUUID().toString().substring(0, 8);

		long agentStartTime = System.currentTimeMillis();

		SpringAiToolRunContext runContext = new SpringAiToolRunContext(traceId, userMessage,
				agentProperties.getMaxExecutionTimeMs(), objectMapper, agentJsonHelper);

		log.info("Spring AI Agent 请求开始，traceId={}, userMessageLength={}", traceId,
				userMessage == null ? 0 : userMessage.length());

		String answer;
		boolean chatSuccess;
		String chatErrorMessage = null;

		long chatStartTime = System.currentTimeMillis();
		String modelUserMessage = knowledgeSupport.prepareInitialUserMessage(runContext);
		Optional<String> clarificationAnswer = SpringAiRequestBoundaryPolicy.findClarificationAnswer(userMessage);
		List<ToolCallback> availableToolCallbacks = SpringAiToolAvailabilityPolicy.selectToolNames(userMessage)
			.stream()
			.map(this::getDiagnosticToolCallback)
			.toList();

		log.info("Spring AI 本次请求可用工具，traceId={}, toolNames={}", traceId,
				availableToolCallbacks.stream().map(callback -> callback.getToolDefinition().name()).toList());

		if (clarificationAnswer.isPresent()) {
			answer = clarificationAnswer.get();
			chatSuccess = true;

			log.info("Spring AI 请求在模型调用前返回确定性澄清，traceId={}", traceId);
		}
		else {
			try {
				ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt().user(modelUserMessage);

				if (!availableToolCallbacks.isEmpty()) {
					requestSpec = requestSpec.toolCallbacks(availableToolCallbacks);
				}

				String modelAnswer = requestSpec
					/*
					 * ToolContext 只传给本地工具，不发送给 DeepSeek。
					 */
					.toolContext(Map.of(SpringAiToolRunContext.CONTEXT_KEY, runContext))
					.call()
					.content();

				/*
				 * 双重保护： 即使某些情况下框架没有把工具异常继续抛出， 只要上下文已经记录失败，就不能使用模型生成的答案覆盖它。
				 */
				if (runContext.hasAbortAnswer()) {
					answer = runContext.getAbortAnswer();
					chatSuccess = false;
					chatErrorMessage = "Spring AI Tool Calling 因工具治理结果终止";
				}
				else {
					answer = modelAnswer;
					chatSuccess = true;
				}
			}
			catch (Exception e) {
				chatSuccess = false;

				if (runContext.hasAbortAnswer()) {
					answer = runContext.getAbortAnswer();
					chatErrorMessage = "Spring AI Tool Calling 因工具治理结果终止";
				}
				else {
					answer = "Spring AI 调用失败，请稍后重试。";
					chatErrorMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				}

				log.warn("Spring AI Agent 调用未正常完成，traceId={}, hasToolAbort={}, exceptionType={}", traceId,
						runContext.hasAbortAnswer(), e.getClass().getSimpleName());
			}
		}

		long chatCostMs = System.currentTimeMillis() - chatStartTime;

		if (clarificationAnswer.isPresent()) {
			runContext.addDeterministicClarificationStep(chatCostMs, answer);
		}
		else {
			runContext.addSpringAiChatStep(chatSuccess, chatCostMs, answer, chatErrorMessage);
		}

		long agentCostMs = System.currentTimeMillis() - agentStartTime;

		AgentAskResponse response = runContext.buildResponse(answer, agentCostMs);

		try {
			agentLogService.saveRunLog(userMessage, response);
		}
		catch (Exception e) {
			log.error("Spring AI Agent 运行日志保存失败，已保留本次回答，traceId={}", traceId, e);
		}

		log.info("Spring AI Agent 请求完成，traceId={}, usedTool={}, toolCallCount={}, agentCostMs={}", traceId,
				response.getUsedTool(), runContext.getToolCallCount(), response.getAgentCostMs());

		return response;
	}

	private ToolCallback getDiagnosticToolCallback(String toolName) {
		ToolCallback callback = diagnosticToolCallbacks.get(toolName);

		if (callback == null) {
			throw new IllegalStateException("未注册 Spring AI 诊断工具，toolName=" + toolName);
		}

		return callback;
	}

}
