package com.example.agent.tool;

import com.example.agent.sql.SqlErrorEvidence;
import com.example.agent.sql.SqlErrorEvidenceExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AnalyzeSqlErrorWithSchemaTool implements AgentTool {

	private static final String TABLE_NAME_PATTERN = "^[a-zA-Z0-9_]+$";

	private static final ToolDefinition DEFINITION = new ToolDefinition("analyzeSqlErrorWithSchema",
			"提取 SQL/MyBatis 报错中的结构化错误证据，并查询相关数据库表结构。适用于 Unknown column、字段不存在、字段映射错误、SQL字段与真实表结构不一致等需要真实数据库信息的场景。",
			List.of(ToolParameterDefinition.requiredString("log", 1, 20_000, null, true,
					"用户提供的 SQL、MyBatis 或 Java 报错日志"),
					ToolParameterDefinition.requiredString("tableName", 1, 64, TABLE_NAME_PATTERN, true,
							"相关数据库表名，例如 agent_run_log")),
			"返回 SQL 错误证据、真实表结构、引用字段、缺失字段、诊断结论和建议动作。", 5000L, true, ToolSideEffect.NONE, ToolRiskLevel.LOW,
			List.of("DATABASE_METADATA_READ"));

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	private final SqlErrorEvidenceExtractor sqlErrorEvidenceExtractor;

	@Override
	public ToolDefinition definition() {
		return DEFINITION;
	}

	@Override
	public String execute(Map<String, Object> arguments) {
		String log = getStringArg(arguments, "log");

		String tableName = getStringArg(arguments, "tableName");

		/*
		 * ToolRegistry 是正式参数校验入口。 这里保留防御性检查，避免工具被 Java 代码直接调用时 接收明显非法的数据。
		 */
		if (log == null || log.isBlank()) {
			throw new IllegalArgumentException("缺少参数 log，无法分析 SQL 报错。");
		}

		if (tableName == null || tableName.isBlank()) {

			throw new IllegalArgumentException("缺少参数 tableName，无法结合表结构分析 SQL 报错。");
		}

		tableName = tableName.trim();

		if (!isSafeTableName(tableName)) {
			throw new IllegalArgumentException("表名格式不合法，只允许字母、数字和下划线，tableName=" + tableName);
		}

		/*
		 * SQL 错误文本解析是确定性步骤： 先从日志中提取 errorType、columns 等证据， 后续再与真实数据库结构进行对照。
		 */
		SqlErrorEvidence evidence = sqlErrorEvidenceExtractor.extract(log);

		List<Map<String, Object>> columns = queryTableColumns(tableName);

		if (columns.isEmpty()) {
			/*
			 * 表不存在时，SQL 分析工具无法继续完成 “错误证据 + 真实 Schema 对照”这一目标。
			 *
			 * 因此这里属于确定性的业务失败，而不是 SUCCESS。
			 */
			String result = "未查询到表结构，无法结合表结构分析。tableName=" + tableName;

			throw new ToolBusinessException("TABLE_NOT_FOUND", result,
					"数据库中不存在目标表，无法完成 SQL 错误与 Schema 对照，tableName=" + tableName);
		}

		Map<String, Object> toolData = buildToolData(evidence, tableName, columns);

		return toJsonSafely(toolData);
	}

	private Map<String, Object> buildToolData(SqlErrorEvidence errorEvidence, String tableName,
			List<Map<String, Object>> tableSchema) {

		List<String> referencedColumns = safeList(errorEvidence.getColumns());

		List<String> existingColumns = extractExistingColumns(tableSchema);

		List<String> missingColumns = findMissingColumns(referencedColumns, existingColumns);

		Map<String, Object> toolData = new LinkedHashMap<>();

		toolData.put("errorEvidence", errorEvidence);

		toolData.put("tableName", tableName);

		toolData.put("tableSchema", tableSchema);

		toolData.put("referencedColumns", referencedColumns);

		toolData.put("existingColumns", existingColumns);

		toolData.put("missingColumns", missingColumns);

		toolData.put("diagnosis", buildDiagnosis(errorEvidence, tableName, missingColumns));

		toolData.put("suggestedActions", buildSuggestedActions(errorEvidence, missingColumns));

		return toolData;
	}

	private List<String> safeList(List<String> values) {

		if (values == null || values.isEmpty()) {

			return List.of();
		}

		return values;
	}

	private List<String> extractExistingColumns(List<Map<String, Object>> tableSchema) {

		if (tableSchema == null || tableSchema.isEmpty()) {

			return List.of();
		}

		List<String> existingColumns = new ArrayList<>();

		for (Map<String, Object> column : tableSchema) {

			Object columnName = column.get("columnName");

			if (columnName != null && !String.valueOf(columnName).isBlank()) {

				existingColumns.add(String.valueOf(columnName));
			}
		}

		return existingColumns;
	}

	/**
	 * 将报错日志中引用的字段与数据库真实字段做大小写不敏感比较， 找出 SQL 中引用、但表结构中不存在的字段。
	 */
	private List<String> findMissingColumns(List<String> referencedColumns, List<String> existingColumns) {

		if (referencedColumns == null || referencedColumns.isEmpty()) {

			return List.of();
		}

		Set<String> existingColumnSet = new HashSet<>();

		for (String existingColumn : existingColumns) {

			existingColumnSet.add(existingColumn.toLowerCase(Locale.ROOT));
		}

		List<String> missingColumns = new ArrayList<>();

		Set<String> addedColumns = new HashSet<>();

		for (String referencedColumn : referencedColumns) {

			if (referencedColumn == null || referencedColumn.isBlank()) {

				continue;
			}

			String normalizedColumn = referencedColumn.toLowerCase(Locale.ROOT);

			if (!existingColumnSet.contains(normalizedColumn) && addedColumns.add(normalizedColumn)) {

				missingColumns.add(referencedColumn);
			}
		}

		return missingColumns;
	}

	private String buildDiagnosis(SqlErrorEvidence errorEvidence, String tableName, List<String> missingColumns) {

		if ("UNKNOWN_COLUMN".equals(errorEvidence.getErrorType())) {

			if (missingColumns == null || missingColumns.isEmpty()) {

				return "SQL 引用了数据库表中可能不存在的字段，但未能从报错中确认具体缺失字段。";
			}

			return "SQL 引用了 " + tableName + " 表中不存在的字段：" + String.join(", ", missingColumns);
		}

		return errorEvidence.getSuggestion();
	}

	private List<String> buildSuggestedActions(SqlErrorEvidence errorEvidence, List<String> missingColumns) {

		List<String> suggestedActions = new ArrayList<>();

		if ("UNKNOWN_COLUMN".equals(errorEvidence.getErrorType())) {

			if (missingColumns != null && !missingColumns.isEmpty()) {

				suggestedActions.add(
						"全局搜索字段 " + String.join(", ", missingColumns) + "，定位 Mapper XML、注解 SQL 或 QueryWrapper 中的引用位置。");
			}
			else {
				suggestedActions.add("全局搜索报错中的字段名，确认 SQL 是否引用了不存在的列。");
			}

			suggestedActions.add("确认实体字段、resultMap、表字段三者是否一致。");

			suggestedActions.add("确认当前连接的数据库环境是否执行了最新 DDL。");

			suggestedActions.add("如果业务确实需要该字段，补充数据库 DDL 并同步实体和 Mapper。");

			return suggestedActions;
		}

		suggestedActions.add("根据 errorEvidence 中的 errorType 和 rawMessage 定位 SQL 或 Mapper 配置。");

		suggestedActions.add("结合 tableSchema 确认字段名、类型、可空性和默认值是否符合 SQL 预期。");

		return suggestedActions;
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

	private String getStringArg(Map<String, Object> arguments, String key) {

		if (arguments == null || arguments.get(key) == null) {

			return null;
		}

		return String.valueOf(arguments.get(key));
	}

	private boolean isSafeTableName(String tableName) {

		return tableName.matches(TABLE_NAME_PATTERN);
	}

	private String toJsonSafely(Object object) {
		try {
			return objectMapper.writeValueAsString(object);
		}
		catch (Exception e) {
			/*
			 * 与原有行为保持一致： JSON 序列化不是该诊断工具的核心业务， 序列化异常时尽量保留已有诊断数据。
			 */
			return String.valueOf(object);
		}
	}

}