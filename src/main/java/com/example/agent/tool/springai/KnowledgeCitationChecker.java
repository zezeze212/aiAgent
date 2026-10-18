package com.example.agent.tool.springai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查约定格式 [知识:chunkId] 的引用归属。
 *
 * 只检查 ID 是否属于本次准备的知识片段： 不判断片段是否支持结论，也不判断回答是否遗漏必要引用。
 */
public final class KnowledgeCitationChecker {

	private static final Pattern CITATION_PATTERN = Pattern.compile("\\[知识:([^\\]\\r\\n]*)\\]");

	private KnowledgeCitationChecker() {
	}

	public record CheckResult(String status, List<String> citedIds, List<String> unknownIds) {
	}

	public static CheckResult check(String answer, Set<String> allowedChunkIds) {

		Set<String> allowed = allowedChunkIds == null ? Set.of() : Set.copyOf(allowedChunkIds);

		Set<String> cited = new LinkedHashSet<>();

		Matcher matcher = CITATION_PATTERN.matcher(answer == null ? "" : answer);

		while (matcher.find()) {
			cited.add(matcher.group(1).trim());
		}

		List<String> unknown = cited.stream().filter(id -> !allowed.contains(id)).toList();

		String status;

		if (cited.isEmpty()) {
			status = "NO_CITATIONS";
		}
		else if (!unknown.isEmpty()) {
			status = "INVALID_IDS";
		}
		else {
			status = "VALID_IDS";
		}

		return new CheckResult(status, List.copyOf(cited), unknown);
	}

}