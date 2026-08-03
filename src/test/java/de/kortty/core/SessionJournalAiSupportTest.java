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
    void resolveProfilePrefersJournalProfileThenDefaultNeverTextRole() {
        de.kortty.model.AiProfile defaultProfile = profile("default-id", "Default");
        de.kortty.model.AiProfile textProfile = profile("text-id", "Text role");
        de.kortty.model.AiProfile journalProfile = profile("journal-id", "Journal");
        de.kortty.model.GlobalSettings settings = new de.kortty.model.GlobalSettings();
        settings.getAiProfiles().addAll(java.util.List.of(defaultProfile, textProfile, journalProfile));
        settings.setDefaultAiProfileId("default-id");
        settings.setTextAiProfileId("text-id");

        // Without a journal profile the DEFAULT profile wins — never the Text-role profile.
        assertThat(SessionJournalAiSupport.resolveProfile(settings)).isSameInstanceAs(defaultProfile);

        settings.setSessionJournalAiProfileId("journal-id");
        assertThat(SessionJournalAiSupport.resolveProfile(settings)).isSameInstanceAs(journalProfile);

        // A stale journal profile id falls back to the default profile.
        settings.setSessionJournalAiProfileId("gone");
        assertThat(SessionJournalAiSupport.resolveProfile(settings)).isSameInstanceAs(defaultProfile);
    }

    private static de.kortty.model.AiProfile profile(String id, String name) {
        de.kortty.model.AiProfile profile = new de.kortty.model.AiProfile();
        profile.setId(id);
        profile.setName(name);
        return profile;
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
