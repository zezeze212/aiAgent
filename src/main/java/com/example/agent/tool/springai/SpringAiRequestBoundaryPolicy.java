package com.example.agent.tool.springai;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 在进入模型和 Tool Calling Loop 前处理能够确定判断的信息不足场景。
 *
 * 这类场景不需要模型自由生成答案，否则即使模型正确要求用户补充参数，仍可能附带没有工具证据的确定性结论。
 */
public final class SpringAiRequestBoundaryPolicy {

	private static final Pattern UNKNOWN_COLUMN_ERROR = Pattern.compile("(?iu)unknown\\s+column|未知字段|字段(?:不存在|找不到)");

	private static final Pattern REAL_SCHEMA_DIAGNOSIS_REQUEST = Pattern
		.compile("(?iu)(?:结合|对照|根据).{0,16}(?:真实)?表结构|真实表结构");

	private static final String MISSING_TABLE_NAME_ANSWER = """
			当前无法结合真实表结构完成诊断，因为问题中没有提供目标表名，也没有包含目标表的完整 SQL。

			请补充以下任一信息：

			1. 目标表名；
			2. 包含 FROM、UPDATE 或 INSERT INTO 的完整 SQL。

			获得表名并查询真实结构后，才能判断字段状态；当前仅根据这段报错不能下确定性结论。
			""";

	private SpringAiRequestBoundaryPolicy() {
	}

	/**
	 * 返回确定性澄清回答；空值表示当前请求应继续进入模型链路。
	 */
	public static Optional<String> findClarificationAnswer(String userMessage) {
		if (userMessage == null || userMessage.isBlank()) {
			return Optional.empty();
		}

		if (!UNKNOWN_COLUMN_ERROR.matcher(userMessage).find()
				|| !REAL_SCHEMA_DIAGNOSIS_REQUEST.matcher(userMessage).find()) {
			return Optional.empty();
		}

		if (!SpringAiToolAvailabilityPolicy.extractExplicitTableNames(userMessage).isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(MISSING_TABLE_NAME_ANSWER);
	}

}
