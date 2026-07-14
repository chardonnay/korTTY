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
    void extractEditableSegmentsUsesFullSnippetContextForTextSelectedInsideComment() {
        String snippet = "echo start\n# Die Sicherung ist abgeschlossen\necho done\n";
        String selectedText = "Sicherung ist abgeschlossen";
        int selectionStart = snippet.indexOf(selectedText);

        List<SnippetAiTextSupport.EditableTextSegment> segments =
            SnippetAiTextSupport.extractEditableSegments(
                snippet,
                selectionStart,
                selectionStart + selectedText.length(),
                "bash");

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).start()).isEqualTo(0);
        assertThat(segments.get(0).end()).isEqualTo(selectedText.length());
        assertThat(segments.get(0).coreText()).isEqualTo(selectedText);
    }

    @Test
    void extractEditableSegmentsStillRejectsCodeOnlySelection() {
        String snippet = "echo \"Sicherung abgeschlossen\"\n";

        List<SnippetAiTextSupport.EditableTextSegment> segments =
            SnippetAiTextSupport.extractEditableSegments(snippet, 0, 4, "bash");

        assertThat(segments).isEmpty();
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

    @Test
    void formatDescriptionAsCommentStartsNextSentenceOnNewCommentLine() {
        String formatted = SnippetAiTextSupport.formatDescriptionAsComment(
            "Creates the backup archive. Sends a result email.",
            "bash",
            "",
            80);

        assertThat(formatted).isEqualTo("""
            # Creates the backup archive.
            # Sends a result email.""");
    }

    @Test
    void formatDescriptionAsCommentRespectsConfiguredTotalLineWidth() {
        String formatted = SnippetAiTextSupport.formatDescriptionAsComment(
            "Creates backup archives with timestamps for selected directories.",
            "bash",
            "",
            40);

        for (String line : formatted.split("\\R")) {
            assertThat(line.length()).isAtMost(40);
            assertThat(line).startsWith("# ");
        }
    }
}
