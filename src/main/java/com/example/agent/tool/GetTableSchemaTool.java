package com.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GetTableSchemaTool implements AgentTool {

	private static final String TABLE_NAME_PATTERN = "^[a-zA-Z0-9_]+$";

	private static final ToolDefinition DEFINITION = new ToolDefinition("getTableSchema",
			"查询当前 MySQL 数据库中某张表的字段结构。适用于用户询问表有哪些字段、字段类型、字段注释、表结构、SQL 报错中字段是否存在等场景。",
			List.of(ToolParameterDefinition.requiredString("tableName", 1, 64, TABLE_NAME_PATTERN, true,
					"需要查询结构的数据库表名，例如 agent_run_log")),
			"返回指定表的字段结构 JSON；表不存在时返回明确提示和可能的相似表名。", 5000L, true, ToolSideEffect.NONE, ToolRiskLevel.LOW,
			List.of("DATABASE_METADATA_READ"));

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	@Override
	public ToolDefinition definition() {
		return DEFINITION;
	}

	@Override
	public String execute(Map<String, Object> arguments) {
		String tableName = getStringArg(arguments, "tableName");

		/*
		 * 正常调用应该已经经过 ToolArgumentValidator。 这里仍保留防御性校验，避免 Java 代码绕过 ToolRegistry 直接调用具体
		 * Tool 时产生不可控行为。
		 */
		if (tableName == null || tableName.isBlank()) {
			throw new IllegalArgumentException("缺少参数 tableName，无法查询表结构。");
		}

		tableName = tableName.trim();

		if (!isSafeTableName(tableName)) {
			throw new IllegalArgumentException("表名格式不合法，只允许字母、数字和下划线，tableName=" + tableName);
		}

		List<Map<String, Object>> columns = queryTableColumns(tableName);

		if (columns.isEmpty()) {
			/*
			 * 数据库查询本身已经成功完成，只是目标表不存在。
			 *
			 * 这不是程序异常，也不是参数格式错误，而是一个确定性的 业务失败，因此通过 ToolBusinessException 交给 Registry 转换成
			 * BUSINESS_ERROR + TABLE_NOT_FOUND。
			 */
			String result = buildTableNotFoundMessage(tableName);

			throw new ToolBusinessException("TABLE_NOT_FOUND", result, "数据库中不存在目标表，tableName=" + tableName);
		}

		try {
			return objectMapper.writeValueAsString(columns);
		}
		catch (Exception e) {
			/*
			 * 查询结果已经得到，即使 JSON 序列化失败，也尽量保留 原有降级行为，避免 M2 重构弱化现有工具能力。
			 */
			return columns.toString();
		}
	}

	private List<Map<String, Object>> queryTableColumns(String tableName) {

		String sql = """
				SELECT
				    COLUMN_NAME AS columnName,
				    COLUMN_TYPE AS columnType,
				    IS_NULLABLE AS nullable,
				    COLUMN_DEFAULT AS defaultValue,
				    COLUMN_COMMENT AS columnComment,
				    ORDINAL_POSITION AS ordinalPosition
				FROM information_schema.COLUMNS
				WHERE TABLE_SCHEMA = DATABASE()
				  AND TABLE_NAME = ?
				ORDER BY ORDINAL_POSITION ASC
				""";

		return jdbcTemplate.queryForList(sql, tableName);
	}

	/**
	 * 表不存在时构造给 Agent / 用户看的确定性结果。
	 *
	 * 如果数据库中存在相似表名，同时给出候选表， 保留原有工具的友好排查能力。
	 */
	private String buildTableNotFoundMessage(String inputTableName) {

		List<String> allTableNames = queryAllTableNames();

		List<String> similarTables = allTableNames.stream()
			.map(tableName -> new TableSimilarity(tableName, calculateDistance(inputTableName, tableName)))
			.filter(item -> item.distance <= Math.max(2, inputTableName.length() / 3))
			.sorted(Comparator.comparingInt(item -> item.distance))
			.limit(5)
			.map(item -> item.tableName)
			.toList();

		if (similarTables.isEmpty()) {
			return "未查询到表结构，请确认表名是否正确，tableName=" + inputTableName;
		}

		return "未查询到表结构，tableName=" + inputTableName + "。你是不是想查询：" + String.join("、", similarTables);
	}

	private List<String> queryAllTableNames() {
		String sql = """
				SELECT TABLE_NAME
				FROM information_schema.TABLES
				WHERE TABLE_SCHEMA = DATABASE()
				ORDER BY TABLE_NAME ASC
				""";

		return jdbcTemplate.queryForList(sql, String.class);
	}

	private boolean isSafeTableName(String tableName) {
		return tableName.matches(TABLE_NAME_PATTERN);
	}

	private String getStringArg(Map<String, Object> arguments, String key) {

		if (arguments == null || arguments.get(key) == null) {

			return null;
		}

		return String.valueOf(arguments.get(key));
	}

	/**
	 * 计算两个字符串的 Levenshtein 编辑距离。
	 *
	 * 插入、删除、替换一个字符的代价均为 1， 最终距离越小，两个表名越相似。
	 */
	private int calculateDistance(String a, String b) {

		int[][] dp = new int[a.length() + 1][b.length() + 1];

		for (int i = 0; i <= a.length(); i++) {

			dp[i][0] = i;
		}

		for (int j = 0; j <= b.length(); j++) {

			dp[0][j] = j;
		}

		for (int i = 1; i <= a.length(); i++) {

			for (int j = 1; j <= b.length(); j++) {

				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;

				dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
			}
		}

		return dp[a.length()][b.length()];
	}

	private record TableSimilarity(String tableName, int distance) {
	}

}