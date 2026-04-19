package de.kortty.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnippetAiTextSupportTest {

    @Test
    void extractEditableSegmentsFindsCommentsAndStringsButNotCodeTokens() {
        List<SnippetAiTextSupport.EditableTextSegment> segments = SnippetAiTextSupport.extractEditableSegments(
            """
            # backup log filez
            echo "Backup completd"
            for file in *.log; do gzip "$file"; done
            """,
            "bash");

        assertEquals(2, segments.size());
        assertEquals("backup log filez", segments.get(0).coreText());
        assertEquals("Backup completd", segments.get(1).coreText());
    }

    @Test
    void applyReplacementsPreservesOuterWhitespace() {
        String updated = SnippetAiTextSupport.applyReplacements(
            "#  backup log filez  ",
            List.of(new SnippetAiTextSupport.EditableTextSegment(1, 21, "  backup log filez  ", SnippetAiTextSupport.SegmentType.COMMENT)),
            List.of("backup log files"));

        assertEquals("#  backup log files  ", updated);
    }

    @Test
    void formatDescriptionAsCommentUsesExpectedCommentSyntax() {
        String formatted = SnippetAiTextSupport.formatDescriptionAsComment(
            "Compresses all log files.",
            "python",
            "    ");

        assertTrue(formatted.startsWith("    # "));
        assertTrue(formatted.contains("Compresses all log files."));
    }
}
