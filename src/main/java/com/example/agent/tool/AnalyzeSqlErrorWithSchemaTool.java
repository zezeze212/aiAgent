package com.example.agent.tool;

import com.example.agent.sql.SqlErrorEvidence;
import com.example.agent.sql.SqlErrorEvidenceExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class AnalyzeSqlErrorWithSchemaTool implements AgentTool {

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    private final SqlErrorEvidenceExtractor sqlErrorEvidenceExtractor;

    @Override
    public String name() {
        return "analyzeSqlErrorWithSchema";
    }

    @Override
    public String description() {
        return "提取 SQL/MyBatis 报错中的结构化错误证据，并查询相关数据库表结构。适用于 Unknown column、字段不存在、字段映射错误、SQL字段与真实表结构不一致等需要真实数据库信息的场景。";
    }

    @Override
    public String parameterSchema() {
        return """
                {
                  "log": "用户提供的 SQL、MyBatis 或 Java 报错日志",
                  "tableName": "相关数据库表名，例如 agent_run_log"
                }
                """;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String log = getStringArg(arguments, "log");
        String tableName = getStringArg(arguments, "tableName");

        if (log == null || log.isBlank()) {
            throw new IllegalArgumentException(
                    "缺少参数 log，无法分析 SQL 报错。"
            );
        }

        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException(
                    "缺少参数 tableName，无法结合表结构分析 SQL 报错。"
            );
        }

        tableName = tableName.trim();

        if (!isSafeTableName(tableName)) {
            throw new IllegalArgumentException(
                    "表名格式不合法，只允许字母、数字和下划线，tableName=" + tableName
            );
        }

        SqlErrorEvidence evidence = sqlErrorEvidenceExtractor.extract(log);

        List<Map<String, Object>> columns = queryTableColumns(tableName);

        if (columns.isEmpty()) {
            return "未查询到表结构，无法结合表结构分析。tableName=" + tableName;
        }

        Map<String, Object> toolData = buildToolData(evidence, tableName, columns);
        return toJsonSafely(toolData);
    }

    private Map<String, Object> buildToolData(SqlErrorEvidence errorEvidence, String tableName, List<Map<String, Object>> tableSchema) {
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

            return "SQL 引用了 "
                    + tableName
                    + " 表中不存在的字段："
                    + String.join(", ", missingColumns);
        }

        return errorEvidence.getSuggestion();
    }

    private List<String> buildSuggestedActions(SqlErrorEvidence errorEvidence, List<String> missingColumns) {
        List<String> suggestedActions = new ArrayList<>();

        if ("UNKNOWN_COLUMN".equals(errorEvidence.getErrorType())) {
            if (missingColumns != null && !missingColumns.isEmpty()) {
                suggestedActions.add(
                        "全局搜索字段 "
                                + String.join(", ", missingColumns)
                                + "，定位 Mapper XML、注解 SQL 或 QueryWrapper 中的引用位置。"
                );
            } else {
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
        return tableName.matches("^[a-zA-Z0-9_]+$");
    }

    private String toJsonSafely(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            return String.valueOf(object);
        }
    }
}