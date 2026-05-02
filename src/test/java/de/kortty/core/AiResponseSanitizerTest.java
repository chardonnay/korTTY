package de.kortty.core;

import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;


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

        assertThat(sanitized).isEqualTo("Visible intro\n\nVisible result");
    }

    @Test
    void sanitizeForDisplayKeepsNormalTextUntouched() {
        String sanitized = AiResponseSanitizer.sanitizeForDisplay("Normal answer");

        assertThat(sanitized).isEqualTo("Normal answer");
    }
}
