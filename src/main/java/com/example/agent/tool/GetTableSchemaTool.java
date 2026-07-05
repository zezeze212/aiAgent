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

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "getTableSchema";
    }

    @Override
    public String description() {
        return "查询当前 MySQL 数据库中某张表的字段结构。适用于用户询问表有哪些字段、字段类型、字段注释、表结构、SQL 报错中字段是否存在等场景。";
    }

    @Override
    public String parameterSchema() {
        return """
                {
                  "tableName": "需要查询结构的数据库表名，例如 agent_run_log"
                }
                """;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String tableName = getStringArg(arguments, "tableName");

        if (tableName == null || tableName.isBlank()) {
            return "缺少参数 tableName，无法查询表结构。";
        }

        tableName = tableName.trim();

        if (!isSafeTableName(tableName)) {
            return "表名格式不合法，只允许字母、数字和下划线，tableName=" + tableName;
        }

        List<Map<String, Object>> columns = queryTableColumns(tableName);

        if (columns.isEmpty()) {
            return buildTableNotFoundMessage(tableName);
        }

        try {
            return objectMapper.writeValueAsString(columns);
        } catch (Exception e) {
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

    private String buildTableNotFoundMessage(String inputTableName) {
        List<String> allTableNames = queryAllTableNames();

        List<String> similarTables = allTableNames.stream()
                .map(tableName -> new TableSimilarity(
                        tableName,
                        calculateDistance(inputTableName, tableName)
                ))
                .filter(item -> item.distance <= Math.max(2, inputTableName.length() / 3))
                .sorted(Comparator.comparingInt(item -> item.distance))
                .limit(5)
                .map(item -> item.tableName)
                .toList();

        if (similarTables.isEmpty()) {
            return "未查询到表结构，请确认表名是否正确，tableName=" + inputTableName;
        }

        return "未查询到表结构，tableName=" + inputTableName
                + "。你是不是想查询：" + String.join("、", similarTables);
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
        return tableName.matches("^[a-zA-Z0-9_]+$");
    }

    private String getStringArg(Map<String, Object> arguments, String key) {
        if (arguments == null || arguments.get(key) == null) {
            return null;
        }
        return String.valueOf(arguments.get(key));
    }

    /**
     * todo 学习一下这个、计算两个字符串的编辑距离。
     * 距离越小，说明两个表名越相似。
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

                dp[i][j] = Math.min(
                        Math.min(
                                dp[i - 1][j] + 1,
                                dp[i][j - 1] + 1
                        ),
                        dp[i - 1][j - 1] + cost
                );
            }
        }

        return dp[a.length()][b.length()];
    }

    private record TableSimilarity(String tableName, int distance) {
    }
}