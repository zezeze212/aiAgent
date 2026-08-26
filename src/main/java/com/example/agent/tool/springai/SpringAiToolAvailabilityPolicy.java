package com.example.agent.tool.springai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根据原始用户问题决定本次请求可以向模型暴露哪些诊断工具。
 *
 * 这是一层确定性边界，不依赖模型遵守 Prompt：缺少必要输入时，相关工具不会进入本次请求的 Tool Schema。
 */
public final class SpringAiToolAvailabilityPolicy {

    private static final Pattern SQL_TABLE_REFERENCE = Pattern.compile(
            "(?iu)\\b(?:from|join|update|into|table|describe|desc)\\s+[`\"]?(?:[a-z][a-z0-9_$]*[`\"]?\\s*\\.\\s*)?[`\"]?([a-z][a-z0-9_$]*)[`\"]?");

    private static final Pattern TABLE_NAME_AFTER_MARKER = Pattern.compile(
            "(?iu)(?:表名|相关表)\\s*(?:是|为|=|:|：)?\\s*[`\"]?([a-z][a-z0-9_$]*)[`\"]?");

    private static final Pattern TABLE_NAME_BEFORE_MARKER = Pattern.compile(
            "(?iu)(?<![a-z0-9_$])([a-z][a-z0-9_$]*)(?![a-z0-9_$])\\s*(?:表(?:的|结构|字段|[，。,:：、]|\\s|$)|的真实(?:表)?结构)");

    private static final Pattern JAVA_EXCEPTION_TYPE = Pattern.compile(
            "(?u)(?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*(?:Exception|Error)\\b");

    private static final Pattern JAVA_STACK_FRAME = Pattern.compile(
            "(?u)\\bat\\s+(?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*\\([^)]*\\.java:\\d+\\)");

    private SpringAiToolAvailabilityPolicy() {
    }

    /**
     * 返回本次请求允许暴露的工具名，顺序与注册顺序保持一致。
     */
    public static List<String> selectToolNames(String userMessage) {
        List<String> toolNames = new ArrayList<>();

        if (!extractExplicitTableNames(userMessage).isEmpty()) {
            toolNames.add("getTableSchema");
            toolNames.add("analyzeSqlErrorWithSchema");
        }

        if (hasCompleteJavaStackTrace(userMessage)) {
            toolNames.add("analyzeStackTrace");
        }

        return List.copyOf(toolNames);
    }

    /**
     * 校验模型提交的 tableName 是否确实来自原始用户问题中的表名证据。
     */
    public static boolean hasExplicitTableNameEvidence(String userMessage, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }

        String normalizedTableName = normalizeIdentifier(tableName);

        return extractExplicitTableNames(userMessage).contains(normalizedTableName);
    }

    static Set<String> extractExplicitTableNames(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Set.of();
        }

        Set<String> tableNames = new LinkedHashSet<>();

        collectMatches(SQL_TABLE_REFERENCE, userMessage, tableNames);
        collectMatches(TABLE_NAME_AFTER_MARKER, userMessage, tableNames);
        collectMatches(TABLE_NAME_BEFORE_MARKER, userMessage, tableNames);

        return Set.copyOf(tableNames);
    }

    static boolean hasCompleteJavaStackTrace(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }

        return JAVA_EXCEPTION_TYPE.matcher(userMessage).find() && JAVA_STACK_FRAME.matcher(userMessage).find();
    }

    private static void collectMatches(Pattern pattern, String text, Set<String> tableNames) {
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            tableNames.add(normalizeIdentifier(matcher.group(1)));
        }
    }

    private static String normalizeIdentifier(String identifier) {
        String normalized = identifier == null ? "" : identifier.trim();

        if (normalized.length() >= 2 && ((normalized.startsWith("`") && normalized.endsWith("`"))
                || (normalized.startsWith("\"") && normalized.endsWith("\"")))) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }

        return normalized.toLowerCase(Locale.ROOT);
    }

}
