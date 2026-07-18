package com.example.agent.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentJsonHelper {

    private final ObjectMapper objectMapper;

    public Object parseJsonIfPossible(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }

        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception e) {
            return value;
        }
    }

    public String serializeForStorage(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof String text) {
            return text;
        }

        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Agent JSON 序列化失败", e);
            return String.valueOf(value);
        }
    }
}