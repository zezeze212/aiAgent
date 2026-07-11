package com.example.agent.tool;

import com.example.agent.dto.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryTest {

    @Test
    void shouldReturnFailedResultWhenToolThrowsException() {
        AgentTool tool = mock(AgentTool.class);

        when(tool.name()).thenReturn("failingTool");

        when(tool.execute(anyMap()))
                .thenThrow(new IllegalArgumentException("参数错误"));

        ToolRegistry registry = new ToolRegistry(List.of(tool));

        ToolExecutionResult result = registry.executeWithResult(
                "failingTool",
                Map.of()
        );

        assertFalse(result.getSuccess());
        assertEquals("参数错误", result.getErrorMessage());
        assertEquals("工具执行失败：参数错误", result.getResult());
    }


    @Test
    void shouldReturnFailedResultWhenToolNotFound() {
        ToolRegistry registry = new ToolRegistry(List.of());

        ToolExecutionResult result = registry.executeWithResult("missingTool", Map.of());

        assertFalse(result.getSuccess());
        assertEquals("未知工具：missingTool", result.getErrorMessage());
        assertEquals("未知工具：missingTool", result.getResult());
    }

    @Test
    void shouldReturnSuccessResultWhenToolExecutesNormally() {
        AgentTool tool = mock(AgentTool.class);

        when(tool.name()).thenReturn("successTool");

        when(tool.execute(anyMap())).thenReturn("执行成功");

        ToolRegistry registry = new ToolRegistry(List.of(tool));

        ToolExecutionResult result = registry.executeWithResult("successTool", Map.of());

        assertTrue(result.getSuccess());
        assertEquals("successTool", result.getToolName());
        assertEquals("执行成功", result.getResult());
        assertNull(result.getErrorMessage());
    }

}