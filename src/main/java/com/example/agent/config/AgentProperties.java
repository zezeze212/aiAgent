package com.example.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /**
     * Agent 单次请求允许的最大执行时间，默认 120 秒。
     */
    private long maxExecutionTimeMs = 120_000L;
}
