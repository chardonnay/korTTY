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
    void pathLikeStringsStayExcludedAfterRegexReplacement() {
        List<SnippetAiTextSupport.EditableTextSegment> segments =
            SnippetAiTextSupport.extractEditableSegments(
                "cp \"~/backups/logs/archive\" \"/usr/local/share\"\necho \"Backup done\"\n",
                "bash");

        // Both path literals are code-only; only the human-readable text remains editable.
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).coreText()).isEqualTo("Backup done");
    }

    @Test
    void adversarialPathLikeStringCompletesQuickly() {
        // The former regex (overlapping '/' classes with nested quantifiers) showed catastrophic
        // backtracking on this shape: many valid segments with a final rejected character.
        String hostile = "\"" + "aaaa/".repeat(1600) + "!" + "\"";
        long start = System.nanoTime();

        SnippetAiTextSupport.extractEditableSegments("echo " + hostile + "\n", "bash");

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMillis).isLessThan(2_000L);
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
