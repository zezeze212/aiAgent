package com.example.agent.tool;

import com.example.agent.service.AiChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

// @Component
@RequiredArgsConstructor
public class AnalyzeMapperXmlTool implements AgentTool {

    private final AiChatService aiChatService;

    @Override
    public String name() {
        return "analyzeMapperXml";
    }

    @Override
    public String description() {
        return "分析 MyBatis Mapper XML 写法问题，重点检查 resultMap 的 column/property 映射、#{参数} 绑定、parameterType、resultType、动态 SQL 的 if/test/foreach 条件等。适用于怀疑 Mapper XML 写法错误、参数名不匹配、实体属性映射错误的场景。若问题主要是数据库字段不存在或表结构不一致，应优先使用 analyzeSqlErrorWithSchema。";
    }

    @Override
    public String parameterSchema() {
        return """
                {
                  "log": "用户提供的 MyBatis、SQL 或 Java 报错日志",
                  "mapperXml": "用户提供的 Mapper XML 内容"
                }
                """;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        if (arguments == null) {
            return "参数不能为空，请提供 log 和 mapperXml。";
        }

        String log = getString(arguments, "log");
        String mapperXml = getString(arguments, "mapperXml");

        if (log == null || log.trim().isEmpty()) {
            return "log 不能为空，请提供 MyBatis、SQL 或 Java 报错日志。";
        }

        if (mapperXml == null || mapperXml.trim().isEmpty()) {
            return "mapperXml 不能为空，请提供 Mapper XML 内容。";
        }

        return aiChatService.analyzeMapperXml(log, mapperXml);
    }


    private String getString(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : String.valueOf(value);
    }
}