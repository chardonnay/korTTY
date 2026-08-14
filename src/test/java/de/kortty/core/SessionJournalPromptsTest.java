package de.kortty.core;

import org.testng.annotations.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalPromptsTest {

    @Test
    void screenshotSystemPromptCarriesLanguageAndJsonShape() {
        String prompt = SessionJournalPrompts.screenshotAnalysisSystemPrompt("de");
        assertThat(prompt).contains("language code de");
        assertThat(prompt).contains("\"description\"");
        assertThat(prompt).contains("\"tags\"");
        // Screen content is data: the prompt must forbid following instructions inside the image.
        assertThat(prompt).contains("never instructions");
    }

    @Test
    void screenshotSystemPromptFallsBackToEnglish() {
        assertThat(SessionJournalPrompts.screenshotAnalysisSystemPrompt(null)).contains("language code en");
        assertThat(SessionJournalPrompts.screenshotAnalysisSystemPrompt(" ")).contains("language code en");
    }

    @Test
    void screenshotUserPromptCarriesSessionMetadataAndFencesTheCaption() {
        String prompt = SessionJournalPrompts.screenshotAnalysisUserPrompt(
            "daniel", "web01", OffsetDateTime.of(2026, 8, 13, 12, 0, 0, 0, ZoneOffset.UTC),
            "check ```fence``` breakout");
        assertThat(prompt).contains("daniel@web01");
        assertThat(prompt).contains("2026-08-13 12:00:00");
        assertThat(prompt).contains("Analyze the attached screenshot.");
        assertThat(prompt).contains("check ```fence``` breakout");
        // The caption fence must be longer than any backtick run inside the caption.
        assertThat(prompt).contains("````");
    }

    @Test
    void screenshotUserPromptOmitsAnAbsentCaption() {
        String prompt = SessionJournalPrompts.screenshotAnalysisUserPrompt(
            "daniel", "web01", null, null);
        assertThat(prompt).doesNotContain("caption");
        assertThat(prompt).contains("Analyze the attached screenshot.");
    }
}
