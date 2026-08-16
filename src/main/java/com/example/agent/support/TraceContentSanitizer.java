package com.example.agent.support;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent Trace 内容治理组件。
 *
 * 只用于可观测数据的展示和持久化： 1. 对常见密码、Token、API Key 等敏感值进行脱敏； 2. 对过长 Trace 内容进行裁剪，避免单次请求产生超大日志。
 *
 * 注意： 该组件不能用于修改真正发送给模型或 Tool 的原始数据， 否则可能破坏 Agent 的诊断证据。
 */
@Component
public class TraceContentSanitizer {

	private static final int MAX_CONTENT_LENGTH = 20_000;

	private static final String MASK_VALUE = "***";

	private static final String TRUNCATED_SUFFIX = "\n...[TRUNCATED]";

	/**
	 * JSON 中的常见敏感字段，例如：
	 *
	 * "password":"123456" "apiKey":"sk-xxx" "access_token":"xxx"
	 */
	private static final Pattern JSON_SECRET_PATTERN = Pattern
		.compile("(?i)(\"(?:password|passwd|pwd|api[_-]?key|" + "deepseek_api_key|token|access[_-]?token|"
				+ "refresh[_-]?token|secret|authorization)\"" + "\\s*:\\s*\")([^\"]*)(\")");

	/**
	 * 普通配置文本中的敏感字段，例如：
	 *
	 * password=123456 api_key: sk-xxx DEEPSEEK_API_KEY=sk-xxx
	 *
	 * 同样可以覆盖 JDBC URL 中：
	 *
	 * ?user=root&password=123456
	 */
	private static final Pattern KEY_VALUE_SECRET_PATTERN = Pattern
		.compile("(?i)(\\b(?:password|passwd|pwd|api[_-]?key|" + "deepseek_api_key|token|access[_-]?token|"
				+ "refresh[_-]?token|secret)\\b" + "\\s*[=:]\\s*)" + "([^\\s,&;]+)");

	/**
	 * HTTP Authorization Header，例如：
	 *
	 * Authorization: Bearer xxx Authorization: Basic xxx
	 */
	private static final Pattern AUTHORIZATION_PATTERN = Pattern
		.compile("(?i)(Authorization\\s*:\\s*" + "(?:Bearer|Basic)\\s+)" + "([^\\s,;]+)");

	public String sanitize(String content) {
		if (content == null || content.isEmpty()) {
			return content;
		}

		/*
		 * 先脱敏再裁剪。
		 *
		 * 这样可以保证最终保留下来的 Trace 片段中 不会因为裁剪顺序问题留下完整密钥。
		 */
		String sanitized = maskSensitiveValues(content);

		return truncate(sanitized);
	}

	private String maskSensitiveValues(String content) {
		String sanitized = maskJsonSecrets(content);

		sanitized = maskAuthorization(sanitized);

		sanitized = maskKeyValueSecrets(sanitized);

		return sanitized;
	}

	private String maskJsonSecrets(String content) {
		Matcher matcher = JSON_SECRET_PATTERN.matcher(content);

		StringBuffer result = new StringBuffer();

		while (matcher.find()) {
			String replacement = matcher.group(1) + MASK_VALUE + matcher.group(3);

			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}

		matcher.appendTail(result);

		return result.toString();
	}

	private String maskAuthorization(String content) {
		Matcher matcher = AUTHORIZATION_PATTERN.matcher(content);

		StringBuffer result = new StringBuffer();

		while (matcher.find()) {
			String replacement = matcher.group(1) + MASK_VALUE;

			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}

		matcher.appendTail(result);

		return result.toString();
	}

	private String maskKeyValueSecrets(String content) {
		Matcher matcher = KEY_VALUE_SECRET_PATTERN.matcher(content);

		StringBuffer result = new StringBuffer();

		while (matcher.find()) {
			String replacement = matcher.group(1) + MASK_VALUE;

			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}

		matcher.appendTail(result);

		return result.toString();
	}

	private String truncate(String content) {
		if (content.length() <= MAX_CONTENT_LENGTH) {
			return content;
		}

		/*
		 * Trace 的长度限制只影响可观测数据。
		 *
		 * Agent 在此之前已经完成模型调用和工具执行， 因此这里截断不会影响实际诊断结果。
		 */
		return content.substring(0, MAX_CONTENT_LENGTH) + TRUNCATED_SUFFIX;
	}

}