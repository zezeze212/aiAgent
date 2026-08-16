package com.example.agent.stacktrace;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java StackTrace 确定性解析器。
 *
 * 不调用大模型，只根据 Java StackTrace 的固定文本结构提取： - 顶层异常； - root cause； - Cause 链； - 调用栈； - 应用代码栈帧。
 */
@Component
public class StackTraceParser {

	private static final int MAX_APPLICATION_FRAMES = 20;

	/**
	 * 匹配：
	 *
	 * java.lang.NullPointerException: message
	 *
	 * 或：
	 *
	 * Exception in thread "main" java.lang.NullPointerException: message
	 */
	private static final Pattern EXCEPTION_PATTERN = Pattern
		.compile("^(?:Exception in thread \"[^\"]+\"\\s+)?" + "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*"
				+ "(?:Exception|Error|Throwable)(?:\\$[\\w$]+)?)" + "(?::\\s*(.*))?$");

	/**
	 * 匹配：
	 *
	 * Caused by: java.sql.SQLException: message
	 */
	private static final Pattern CAUSED_BY_PATTERN = Pattern
		.compile("^Caused by:\\s+" + "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*"
				+ "(?:Exception|Error|Throwable)(?:\\$[\\w$]+)?)" + "(?::\\s*(.*))?$");

	/**
	 * 匹配标准 Java 调用栈：
	 *
	 * at com.example.OrderService.create(OrderService.java:82)
	 *
	 * 同时兼容 Java 9+ module 前缀：
	 *
	 * at java.base/java.util.ArrayList.get(ArrayList.java:427)
	 */
	private static final Pattern STACK_FRAME_PATTERN = Pattern
		.compile("^\\s*at\\s+(?:[^/\\s]+/)?" + "([\\w.$]+)\\.([\\w$<>]+)\\(([^)]*)\\)\\s*$");

	/**
	 * 没有显式 applicationPackagePrefix 时， 使用这一组常见 JDK / 框架包作为排除规则。
	 *
	 * 这是确定性启发式，不是大模型判断。
	 */
	private static final List<String> FRAMEWORK_PREFIXES = List.of("java.", "javax.", "jakarta.", "jdk.", "sun.",
			"com.sun.", "org.springframework.", "org.apache.", "org.mybatis.", "org.hibernate.", "com.zaxxer.",
			"com.fasterxml.", "io.netty.", "reactor.");

	public Optional<StackTraceAnalysisResult> parse(String stackTrace, String applicationPackagePrefix) {

		if (stackTrace == null || stackTrace.isBlank()) {
			return Optional.empty();
		}

		List<StackTraceCause> causeChain = new ArrayList<>();
		List<StackTraceFrame> allFrames = new ArrayList<>();

		String normalizedStackTrace = normalizeStackTrace(stackTrace);

		String[] lines = normalizedStackTrace.split("\\R");

		for (String line : lines) {
			parseExceptionLine(line, causeChain);
			parseStackFrame(line).ifPresent(allFrames::add);
		}

		/*
		 * 至少需要识别到一个异常类型， 否则不能把普通日志文本误认为合法 Java StackTrace。
		 */
		if (causeChain.isEmpty()) {
			return Optional.empty();
		}

		StackTraceCause topCause = causeChain.get(0);
		StackTraceCause rootCause = causeChain.get(causeChain.size() - 1);

		List<StackTraceFrame> applicationFrames = allFrames.stream()
			.filter(frame -> isApplicationFrame(frame.className(), applicationPackagePrefix))
			.toList();

		StackTraceFrame firstApplicationFrame = applicationFrames.isEmpty() ? null : applicationFrames.get(0);

		/*
		 * StackTrace 可能非常长。 这里最多向最终 Tool Result 暴露前 20 条应用栈帧， 同时保留 applicationFrameCount
		 * 表示真实总数。
		 */
		List<StackTraceFrame> resultApplicationFrames = applicationFrames.stream()
			.limit(MAX_APPLICATION_FRAMES)
			.toList();

		return Optional
			.of(new StackTraceAnalysisResult(topCause.exceptionType(), topCause.message(), rootCause.exceptionType(),
					rootCause.message(), Math.max(0, causeChain.size() - 1), List.copyOf(causeChain),
					firstApplicationFrame, resultApplicationFrames, allFrames.size(), applicationFrames.size()));
	}

	/**
	 * 将可能被聊天窗口、JSON 或模型参数压成单行的 StackTrace 恢复成解析器能够识别的逻辑行。
	 *
	 * 标准 StackTrace 本来就是多行格式，因此正常输入不会受到影响。
	 *
	 * 这里只识别明确符合 Java 栈帧结构的 " at xxx.method(...)" 和 "Caused by:"，避免把普通异常消息中的英文 at 误拆成栈帧。
	 */
	private String normalizeStackTrace(String stackTrace) {
		String normalized = stackTrace.replace("\r\n", "\n").replace('\r', '\n');

		/*
		 * 恢复被压平的 Caused by。
		 *
		 * 例如：
		 *
		 * RuntimeException: xxx Caused by: SQLException: xxx
		 *
		 * 转换成：
		 *
		 * RuntimeException: xxx Caused by: SQLException: xxx
		 */
		normalized = normalized.replaceAll("\\s+(?=Caused by:\\s+)", "\n");

		/*
		 * 恢复被压平的标准 Java Stack Frame。
		 *
		 * 只有后面明确符合：
		 *
		 * 包名.类名.方法名(...)
		 *
		 * 才认为这里是 StackTrace 的 at， 避免把普通英文文本里的 at 误拆开。
		 */
		normalized = normalized.replaceAll("\\s+(?=at\\s+(?:[^/\\s]+/)?[\\w.$]+\\.[\\w$<>]+\\([^)]*\\))", "\n");

		return normalized;
	}

	private void parseExceptionLine(String line, List<StackTraceCause> causeChain) {

		String trimmedLine = line.trim();

		Matcher causeMatcher = CAUSED_BY_PATTERN.matcher(trimmedLine);

		if (causeMatcher.matches()) {
			causeChain.add(new StackTraceCause(causeMatcher.group(1), normalizeMessage(causeMatcher.group(2))));
			return;
		}

		/*
		 * 顶层异常只记录一次。 后续普通行即使形似异常文本，也不能覆盖真正的 top exception。
		 */
		if (!causeChain.isEmpty()) {
			return;
		}

		Matcher exceptionMatcher = EXCEPTION_PATTERN.matcher(trimmedLine);

		if (exceptionMatcher.matches()) {
			causeChain.add(new StackTraceCause(exceptionMatcher.group(1), normalizeMessage(exceptionMatcher.group(2))));
		}
	}

	private Optional<StackTraceFrame> parseStackFrame(String line) {
		Matcher matcher = STACK_FRAME_PATTERN.matcher(line);

		if (!matcher.matches()) {
			return Optional.empty();
		}

		String className = matcher.group(1);
		String methodName = matcher.group(2);
		String location = matcher.group(3);

		String fileName = null;
		Integer lineNumber = null;

		/*
		 * 正常源码位置格式：
		 *
		 * OrderService.java:82
		 *
		 * Native Method / Unknown Source 等场景则只保留 location。
		 */
		int separatorIndex = location.lastIndexOf(':');

		if (separatorIndex > 0 && separatorIndex < location.length() - 1) {
			String possibleLineNumber = location.substring(separatorIndex + 1);

			try {
				lineNumber = Integer.valueOf(possibleLineNumber);
				fileName = location.substring(0, separatorIndex);
			}
			catch (NumberFormatException ignored) {
				// location 不是标准 file:line 格式，保留原始 location 即可。
			}
		}
		else if (!"Native Method".equals(location) && !"Unknown Source".equals(location)) {
			fileName = location;
		}

		return Optional.of(new StackTraceFrame(className, methodName, fileName, lineNumber, location));
	}

	private boolean isApplicationFrame(String className, String applicationPackagePrefix) {

		if (applicationPackagePrefix != null && !applicationPackagePrefix.isBlank()) {
			String prefix = applicationPackagePrefix.trim();

			return className.equals(prefix) || className.startsWith(prefix.endsWith(".") ? prefix : prefix + ".");
		}

		/*
		 * 用户没有提供业务包名时使用排除法： 排除常见 JDK 和框架栈，其余栈帧暂视为应用代码。
		 */
		for (String frameworkPrefix : FRAMEWORK_PREFIXES) {
			if (className.startsWith(frameworkPrefix)) {
				return false;
			}
		}

		return true;
	}

	private String normalizeMessage(String message) {
		if (message == null || message.isBlank()) {
			return null;
		}

		return message.trim();
	}

}