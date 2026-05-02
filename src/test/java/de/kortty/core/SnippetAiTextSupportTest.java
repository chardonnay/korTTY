package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;
import static com.google.common.truth.Truth.assertThat;


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

        assertThat(segments.size()).isEqualTo(2);
        assertThat(segments.get(0).coreText()).isEqualTo("backup log filez");
        assertThat(segments.get(1).coreText()).isEqualTo("Backup completd");
    }

    @Test
    void applyReplacementsPreservesOuterWhitespace() {
        String updated = SnippetAiTextSupport.applyReplacements(
            "#  backup log filez  ",
            List.of(new SnippetAiTextSupport.EditableTextSegment(1, 21, "  backup log filez  ", SnippetAiTextSupport.SegmentType.COMMENT)),
            List.of("backup log files"));

        assertThat(updated).isEqualTo("#  backup log files  ");
    }

    @Test
    void formatDescriptionAsCommentUsesExpectedCommentSyntax() {
        String formatted = SnippetAiTextSupport.formatDescriptionAsComment(
            "Compresses all log files.",
            "python",
            "    ");

        assertThat(formatted.startsWith("    # ")).isTrue();
        assertThat(formatted.contains("Compresses all log files.")).isTrue();
    }
}
