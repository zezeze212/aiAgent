package com.example.agent.validator;

import com.example.agent.dto.ToolDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolDecisionValidatorTest {

    private final ToolDecisionValidator validator =
            new ToolDecisionValidator();

    @Test
    void shouldNormalizeValidToolDecision() {
        ToolDecision decision = new ToolDecision();
        decision.setNeedTool(true);
        decision.setToolName("  analyzeSqlErrorWithSchema  ");
        decision.setArguments(null);
        decision.setDirectAnswer("这段内容应该被清空");

        ToolDecision result =
                validator.validateAndNormalize(decision);

        assertSame(decision, result);
        assertEquals(
                "analyzeSqlErrorWithSchema",
                result.getToolName()
        );
        assertNotNull(result.getArguments());
        assertTrue(result.getArguments().isEmpty());
        assertEquals("", result.getDirectAnswer());
    }

    @Test
    void shouldNormalizeValidDirectAnswerDecision() {
        ToolDecision decision = new ToolDecision();
        decision.setNeedTool(false);
        decision.setToolName("oldTool");
        decision.setArguments(null);
        decision.setDirectAnswer("这是直接回答");

        ToolDecision result =
                validator.validateAndNormalize(decision);

        assertEquals("", result.getToolName());
        assertNotNull(result.getArguments());
        assertTrue(result.getArguments().isEmpty());
        assertEquals("这是直接回答", result.getDirectAnswer());
    }

    @Test
    void shouldRejectDecisionWhenNeedToolIsNull() {
        ToolDecision decision = new ToolDecision();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateAndNormalize(decision)
        );

        assertEquals("needTool不能为空", exception.getMessage());
    }

    @Test
    void shouldRejectToolDecisionWhenToolNameIsBlank() {
        ToolDecision decision = new ToolDecision();
        decision.setNeedTool(true);
        decision.setToolName("  ");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateAndNormalize(decision)
        );

        assertEquals("toolName不能为空", exception.getMessage());
    }

    @Test
    void shouldRejectDirectDecisionWhenAnswerIsBlank() {
        ToolDecision decision = new ToolDecision();
        decision.setNeedTool(false);
        decision.setDirectAnswer("");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateAndNormalize(decision)
        );

        assertEquals("directAnswer不能为空", exception.getMessage());
    }
}