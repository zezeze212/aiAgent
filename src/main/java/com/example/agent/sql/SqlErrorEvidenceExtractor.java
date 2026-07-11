package com.example.agent.sql;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SqlErrorEvidenceExtractor {

    public SqlErrorEvidence extract(String log) {
        SqlErrorEvidence evidence = new SqlErrorEvidence();
        evidence.setRawMessage(log);

        if (log == null || log.isBlank()) {
            evidence.setErrorType("UNKNOWN");
            evidence.setSuggestion("报错日志为空，无法提取 SQL 错误证据。");
            return evidence;
        }

        if (tryExtractUnknownColumn(log, evidence)) {
            return evidence;
        }

        if (tryExtractTableNotFound(log, evidence)) {
            return evidence;
        }

        if (tryExtractDuplicateKey(log, evidence)) {
            return evidence;
        }

        if (tryExtractDataTooLong(log, evidence)) {
            return evidence;
        }

        if (tryExtractSqlSyntax(log, evidence)) {
            return evidence;
        }

        if (tryExtractMyBatisParameterNotFound(log, evidence)) {
            return evidence;
        }

        evidence.setErrorType("UNKNOWN");
        evidence.setSuggestion("未匹配到明确的 SQL 错误类型，需要结合完整 SQL、Mapper XML 和上下文继续分析。");
        return evidence;
    }

    private boolean tryExtractUnknownColumn(String log, SqlErrorEvidence evidence) {
        Pattern pattern = Pattern.compile("Unknown column\\s+'([^']+)'", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(log);

        if (!matcher.find()) {
            return false;
        }

        evidence.setErrorType("UNKNOWN_COLUMN");
        evidence.getColumns().add(normalizeIdentifier(matcher.group(1)));
        evidence.setSuggestion("SQL 中引用了数据库表中可能不存在的字段。");
        return true;
    }

    private boolean tryExtractTableNotFound(String log, SqlErrorEvidence evidence) {
        Pattern pattern = Pattern.compile("Table\\s+'([^']+)'\\s+doesn't exist", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(log);

        if (!matcher.find()) {
            return false;
        }

        evidence.setErrorType("TABLE_NOT_FOUND");
        evidence.getTables().add(normalizeIdentifier(matcher.group(1)));
        evidence.setSuggestion("SQL 访问的表不存在，可能是表名写错、库环境不对或未执行建表脚本。");
        return true;
    }

    private boolean tryExtractDuplicateKey(String log, SqlErrorEvidence evidence) {
        Pattern pattern = Pattern.compile("Duplicate entry\\s+'([^']+)'\\s+for key\\s+'([^']+)'", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(log);

        if (!matcher.find()) {
            return false;
        }

        evidence.setErrorType("DUPLICATE_KEY");
        evidence.setDuplicateValue(matcher.group(1));
        evidence.getKeys().add(matcher.group(2));
        evidence.setSuggestion("唯一索引或主键冲突，需要检查是否重复插入或幂等逻辑缺失。");
        return true;
    }

    private boolean tryExtractDataTooLong(String log, SqlErrorEvidence evidence) {
        Pattern pattern = Pattern.compile("Data too long for column\\s+'([^']+)'", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(log);

        if (!matcher.find()) {
            return false;
        }

        evidence.setErrorType("DATA_TOO_LONG");
        evidence.getColumns().add(normalizeIdentifier(matcher.group(1)));
        evidence.setSuggestion("写入字段内容长度超过数据库字段定义长度。");
        return true;
    }

    private boolean tryExtractSqlSyntax(String log, SqlErrorEvidence evidence) {
        Pattern pattern = Pattern.compile("You have an error in your SQL syntax.*near\\s+'([^']+)'", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(log);

        if (!matcher.find()) {
            return false;
        }

        evidence.setErrorType("SQL_SYNTAX");
        evidence.setNearSql(matcher.group(1));
        evidence.setSuggestion("SQL 语法错误，需要检查 near 附近的 SQL 片段。");
        return true;
    }

    private boolean tryExtractMyBatisParameterNotFound(String log, SqlErrorEvidence evidence) {
        Pattern pattern = Pattern.compile("Parameter\\s+'([^']+)'\\s+not found", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(log);

        if (!matcher.find()) {
            return false;
        }

        evidence.setErrorType("PARAM_NOT_FOUND");
        evidence.getParameters().add(matcher.group(1));
        evidence.setSuggestion("MyBatis XML 中使用的参数名和 Mapper 方法参数名不一致，可能需要加 @Param 或修改占位符。");
        return true;
    }

    private String normalizeIdentifier(String value) {
        if (value == null) {
            return null;
        }

        String result = value.trim().replace("`", "");

        int dotIndex = result.lastIndexOf(".");
        if (dotIndex >= 0 && dotIndex < result.length() - 1) {
            result = result.substring(dotIndex + 1);
        }

        return result;
    }
}