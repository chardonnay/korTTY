package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalAiSupportTest {

    @Test
    void parsesPlainJsonReply() {
        SessionJournalAiSupport.SummaryResult result = SessionJournalAiSupport.parseSummaryResult(
            "{\"title\":\"Deploy\",\"summary\":\"Deployed nginx config.\",\"category\":\"important\"}");
        assertThat(result).isNotNull();
        assertThat(result.title()).isEqualTo("Deploy");
        assertThat(result.summary()).isEqualTo("Deployed nginx config.");
        assertThat(result.category()).isEqualTo("important");
    }

    @Test
    void parsesFencedJsonReply() {
        SessionJournalAiSupport.SummaryResult result = SessionJournalAiSupport.parseSummaryResult(
            "```json\n{\"title\":\"T\",\"summary\":\"S.\",\"category\":\"none\"}\n```");
        assertThat(result).isNotNull();
        assertThat(result.summary()).isEqualTo("S.");
    }

    @Test
    void stripsThinkBlocksBeforeParsing() {
        SessionJournalAiSupport.SummaryResult result = SessionJournalAiSupport.parseSummaryResult(
            "<think>pondering deeply</think>{\"title\":\"T\",\"summary\":\"S.\",\"category\":\"info\"}");
        assertThat(result).isNotNull();
        assertThat(result.summary()).isEqualTo("S.");
        assertThat(result.category()).isEqualTo("info");
    }

    @Test
    void fallsBackToPlainTextForUnparsableReplies() {
        SessionJournalAiSupport.SummaryResult result = SessionJournalAiSupport.parseSummaryResult(
            "The user listed files. Nothing failed.");
        assertThat(result).isNotNull();
        assertThat(result.summary()).isEqualTo("The user listed files. Nothing failed.");
        assertThat(result.title()).isEqualTo("The user listed files");
        assertThat(result.category()).isNull();
    }

    @Test
    void returnsNullForBlankReplies() {
        assertThat(SessionJournalAiSupport.parseSummaryResult(null)).isNull();
        assertThat(SessionJournalAiSupport.parseSummaryResult("   ")).isNull();
    }

    @Test
    void normalizeTitleStripsMarkupAndCapsLength() {
        assertThat(SessionJournalAiSupport.normalizeTitle("  `My   #Title*` \"quoted\"  ", "fb", 80))
            .isEqualTo("My Title quoted");
        assertThat(SessionJournalAiSupport.normalizeTitle(null, "fallback", 80)).isEqualTo("fallback");
        assertThat(SessionJournalAiSupport.normalizeTitle("   ", "fallback", 80)).isEqualTo("fallback");
        String longTitle = "x".repeat(200);
        assertThat(SessionJournalAiSupport.normalizeTitle(longTitle, "fb", 80).length()).isAtMost(80);
    }
}
