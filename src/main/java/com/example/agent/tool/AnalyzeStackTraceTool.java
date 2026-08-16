package com.example.agent.tool;

import com.example.agent.stacktrace.StackTraceAnalysisResult;
import com.example.agent.stacktrace.StackTraceParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Java StackTrace 确定性分析工具。
 *
 * 该工具不调用大模型，只负责把原始异常堆栈解析成结构化证据， 后续由 Agent 根据这些确定性证据继续判断或组合其他工具。
 */
@Component
@RequiredArgsConstructor
public class AnalyzeStackTraceTool implements AgentTool {

	private static final ToolDefinition DEFINITION = new ToolDefinition("analyzeStackTrace",
			"解析 Java 异常堆栈，提取顶层异常、root cause、cause 链和关键应用代码栈帧。",
			List.of(ToolParameterDefinition.requiredString("stackTrace", 1, 50_000, null, true,
					"完整 Java 异常 StackTrace 文本"),
					new ToolParameterDefinition("applicationPackagePrefix", ToolParameterType.STRING, false, 1, 200,
							"^[a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*\\.?$", List.of(), true,
							"可选的业务代码包名前缀，例如 com.example.agent")),
			"返回顶层异常、root cause、cause 链、应用代码栈帧数量和关键业务栈帧。", 1000L, true, ToolSideEffect.NONE, ToolRiskLevel.LOW,
			List.of("LOCAL_DIAGNOSTIC_ANALYZE"));

	private final StackTraceParser stackTraceParser;

	private final ObjectMapper objectMapper;

	@Override
	public ToolDefinition definition() {
		return DEFINITION;
	}

	@Override
	public String execute(Map<String, Object> arguments) {
		String stackTrace = getStringArg(arguments, "stackTrace");
		String applicationPackagePrefix = getStringArg(arguments, "applicationPackagePrefix");

		/*
		 * 正常 Agent 调用会先经过 ToolArgumentValidator。 这里继续保留最基本的防御性检查，避免 Java 代码绕开 Registry
		 * 直接调用。
		 */
		if (stackTrace == null || stackTrace.isBlank()) {
			throw new IllegalArgumentException("缺少参数 stackTrace，无法分析 Java 异常堆栈。");
		}

		StackTraceAnalysisResult analysisResult = stackTraceParser.parse(stackTrace, applicationPackagePrefix)
			.orElseThrow(() -> new ToolBusinessException("STACK_TRACE_NOT_RECOGNIZED", "未识别到合法 Java 异常堆栈。",
					"输入文本中没有识别到 Java Exception/Error/Throwable 结构。"));

		try {
			return objectMapper.writeValueAsString(analysisResult);
		}
		catch (Exception e) {
			/*
			 * 与普通文本工具不同，该工具声明返回结构化 JSON。 如果结构化结果无法序列化，应作为真正的工具执行异常暴露， 而不是悄悄返回不可预测格式。
			 */
			throw new IllegalStateException("StackTrace 分析结果 JSON 序列化失败", e);
		}
	}

	private String getStringArg(Map<String, Object> arguments, String key) {
		if (arguments == null || arguments.get(key) == null) {
			return null;
		}

		return String.valueOf(arguments.get(key));
	}

}