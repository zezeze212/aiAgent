package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class CurrentTimeTool implements AgentTool {

    @Override
    public String name() {
        return "getCurrentTime";
    }

    @Override
    public String description() {
        return "获取当前北京时间。适用于用户询问当前时间、现在几点、今天日期等问题。";
    }

    @Override
    public String parameterSchema() {
        return "{}";
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}