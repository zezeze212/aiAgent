package com.example.agent.tool;

import com.example.agent.service.AiChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AnalyzeSqlErrorWithSchemaTool implements AgentTool {

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    private final AiChatService aiChatService;

    @Override
    public String name() {
        return "analyzeSqlErrorWithSchema";
    }

    @Override
    public String description() {
        return "结合 SQL/MyBatis 报错日志和数据库表结构进行排障分析。适用于 Unknown column、字段不存在、字段映射错误、SQL 查询字段和表结构不一致等问题。";
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

        List<Map<String, Object>> columns = queryTableColumns(tableName);

        if (columns.isEmpty()) {
            return "未查询到表结构，无法结合表结构分析。tableName=" + tableName;
        }

        String schemaJson = toJsonSafely(columns);

        String prompt = """
                你是一个 Java 后端排障助手，请结合 SQL/MyBatis 报错日志和数据库表结构，给出简洁明确的排障结论。
                
                输出格式固定为：
                
                【结论】
                用 1～2 句话说明根因。
                
                【字段校验】
                明确说明报错字段是否存在于表结构中。
                
                【可能位置】
                简要列出最可能出问题的位置，例如 mapper.xml、实体类字段、@TableField、手写 SQL、resultMap。
                
                【修复建议】
                给出 2～3 条最直接的修复方式。
                
                要求：
                - 回答控制在 300 字以内
                - 不要重复粘贴完整表结构
                - 不要输出过长解释
                - 不要编造表中不存在的字段
                - 如果报错字段不在表结构里，要明确指出
                
                【报错日志】
                %s
                
                【表名】
                %s
                
                【表结构 JSON】
                %s
                """.formatted(log, tableName, schemaJson);

        return aiChatService.chat(prompt);
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