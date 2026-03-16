package de.kortty.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiResponseSanitizerTest {

    @Test
    void sanitizeForDisplayRemovesThinkBlocks() {
        String sanitized = AiResponseSanitizer.sanitizeForDisplay("""
            Visible intro
            <think>
            internal chain of thought
            </think>

            Visible result
            """);

        assertEquals("Visible intro\n\nVisible result", sanitized);
    }

    @Test
    void sanitizeForDisplayKeepsNormalTextUntouched() {
        String sanitized = AiResponseSanitizer.sanitizeForDisplay("Normal answer");

        assertEquals("Normal answer", sanitized);
    }
}
