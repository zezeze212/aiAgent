package com.example.agent.tool;

import com.example.agent.sql.SqlErrorEvidence;
import com.example.agent.sql.SqlErrorEvidenceExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            return "缺少参数 log，无法分析 SQL 报错。";
        }

        if (tableName == null || tableName.isBlank()) {
            return "缺少参数 tableName，无法结合表结构分析 SQL 报错。";
        }

        tableName = tableName.trim();

        if (!isSafeTableName(tableName)) {
            return "表名格式不合法，只允许字母、数字和下划线，tableName=" + tableName;
        }

        SqlErrorEvidence evidence = sqlErrorEvidenceExtractor.extract(log);

        List<Map<String, Object>> columns = queryTableColumns(tableName);

        if (columns.isEmpty()) {
            return "未查询到表结构，无法结合表结构分析。tableName=" + tableName;
        }


        Map<String, Object> toolData = new LinkedHashMap<>();
        toolData.put("errorEvidence", evidence);
        toolData.put("tableName", tableName);
        toolData.put("tableSchema", columns);
        return toJsonSafely(toolData);
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