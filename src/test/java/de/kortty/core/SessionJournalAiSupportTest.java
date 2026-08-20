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

    @Test
    void parsesATranslationReply() {
        assertThat(SessionJournalAiSupport.parseTranslation(
            "{\"translation\":\"Der Dienst l\u00e4uft.\"}")).isEqualTo("Der Dienst l\u00e4uft.");
        assertThat(SessionJournalAiSupport.parseTranslation(
            "```json\n{\"translation\":\"Hallo\"}\n```")).isEqualTo("Hallo");
        assertThat(SessionJournalAiSupport.parseTranslation(
            "<think>reasoning</think>{\"translation\":\"Hallo\"}")).isEqualTo("Hallo");
    }

    @Test
    void acceptsAProseTranslationButRejectsAnEmptyOne() {
        // A model that simply answers with the translated text is right, only differently shaped.
        assertThat(SessionJournalAiSupport.parseTranslation("Hallo Welt")).isEqualTo("Hallo Welt");

        assertThat(SessionJournalAiSupport.parseTranslation(null)).isNull();
        assertThat(SessionJournalAiSupport.parseTranslation("   ")).isNull();
        assertThat(SessionJournalAiSupport.parseTranslation("{\"translation\":\"\"}")).isNull();
        // Valid JSON without the field is a wrong answer, not a differently shaped one.
        assertThat(SessionJournalAiSupport.parseTranslation("{\"summary\":\"nope\"}")).isNull();
    }

    @Test
    void parsesScreenshotAnalysisAndNormalizesTags() {
        SessionJournalAiSupport.ScreenshotAnalysis analysis = SessionJournalAiSupport.parseScreenshotAnalysis(
            "{\"description\":\"Zeigt den nginx-Status.\",\"tags\":[\"Nginx\",\"STATUS\",\"nginx\",\" \"]}");
        assertThat(analysis.description()).isEqualTo("Zeigt den nginx-Status.");
        assertThat(analysis.tags()).containsExactly("nginx", "status").inOrder();
    }

    @Test
    void unwrapsFencedAndThinkPrefixedScreenshotAnalysis() {
        SessionJournalAiSupport.ScreenshotAnalysis fenced = SessionJournalAiSupport.parseScreenshotAnalysis(
            "```json\n{\"description\":\"D.\",\"tags\":[\"a\"]}\n```");
        assertThat(fenced.description()).isEqualTo("D.");
        assertThat(fenced.tags()).containsExactly("a");

        SessionJournalAiSupport.ScreenshotAnalysis thought = SessionJournalAiSupport.parseScreenshotAnalysis(
            "<think>internal chain</think>{\"description\":\"D.\",\"tags\":[]}");
        assertThat(thought.description()).isEqualTo("D.");
        assertThat(thought.tags()).isEmpty();
    }

    @Test
    void screenshotAnalysisFallsBackToProse() {
        SessionJournalAiSupport.ScreenshotAnalysis analysis =
            SessionJournalAiSupport.parseScreenshotAnalysis("The screenshot shows a terminal.");
        assertThat(analysis.description()).isEqualTo("The screenshot shows a terminal.");
        assertThat(analysis.tags()).isEmpty();
    }

    @Test
    void screenshotAnalysisCapsTagCountAndLength() {
        StringBuilder json = new StringBuilder("{\"description\":\"D.\",\"tags\":[");
        for (int i = 0; i < 12; i++) {
            json.append(i > 0 ? "," : "").append("\"tag-").append(i).append("\"");
        }
        json.append(",\"").append("y".repeat(80)).append("\"]}");
        SessionJournalAiSupport.ScreenshotAnalysis analysis =
            SessionJournalAiSupport.parseScreenshotAnalysis(json.toString());
        assertThat(analysis.tags()).hasSize(8);

        SessionJournalAiSupport.ScreenshotAnalysis overlong = SessionJournalAiSupport.parseScreenshotAnalysis(
            "{\"description\":\"D.\",\"tags\":[\"" + "z".repeat(80) + "\"]}");
        assertThat(overlong.tags().get(0).length()).isAtMost(40);
    }

    @Test
    void unusableScreenshotAnalysisIsNull() {
        assertThat(SessionJournalAiSupport.parseScreenshotAnalysis(null)).isNull();
        assertThat(SessionJournalAiSupport.parseScreenshotAnalysis("   ")).isNull();
        assertThat(SessionJournalAiSupport.parseScreenshotAnalysis(
            "{\"description\":\"\",\"tags\":[]}")).isNull();
    }

    @Test
    void parsesSessionSummaryKeywords() {
        SessionJournalAiSupport.SummaryResult parsed = SessionJournalAiSupport.parseSummaryResult(
            "{\"title\":\"T\",\"summary\":\"S.\",\"category\":\"error\","
                + "\"keywords\":[\"result_complex.pl\",\"nginx\",\"result_complex.pl\",\" \"]}");
        assertThat(parsed.keywords()).containsExactly("result_complex.pl", "nginx").inOrder();

        // Window summaries never request keywords — absent field stays an empty list.
        SessionJournalAiSupport.SummaryResult plain = SessionJournalAiSupport.parseSummaryResult(
            "{\"title\":\"T\",\"summary\":\"S.\"}");
        assertThat(plain.keywords()).isEmpty();
    }

    @Test
    void parsesAskAnswerWithSourcesAndTerms() {
        SessionJournalAiSupport.AskAnswer answer = SessionJournalAiSupport.parseAskAnswer(
            "```json\n{\"answer\":\"It failed twice.\",\"sources\":[2,1,2,99],"
                + "\"logSearchTerms\":[\"result_complex.pl\",\" \",\"error\"]}\n```", 5);
        assertThat(answer.answer()).isEqualTo("It failed twice.");
        assertThat(answer.sources()).containsExactly(2, 1).inOrder(); // deduped, 99 out of range
        assertThat(answer.logSearchTerms()).containsExactly("result_complex.pl", "error").inOrder();
    }

    @Test
    void askAnswerProseFallbackKeepsTheText() {
        SessionJournalAiSupport.AskAnswer answer =
            SessionJournalAiSupport.parseAskAnswer("The script never ran.", 3);
        assertThat(answer.answer()).isEqualTo("The script never ran.");
        assertThat(answer.sources()).isEmpty();
        assertThat(answer.logSearchTerms()).isEmpty();
    }

    @Test
    void unusableAskAnswerIsNull() {
        assertThat(SessionJournalAiSupport.parseAskAnswer(null, 3)).isNull();
        assertThat(SessionJournalAiSupport.parseAskAnswer("   ", 3)).isNull();
        assertThat(SessionJournalAiSupport.parseAskAnswer(
            "{\"answer\":\"\",\"sources\":[]}", 3)).isNull();
    }

    @Test
    void askAnswerCapsSearchTerms() {
        SessionJournalAiSupport.AskAnswer answer = SessionJournalAiSupport.parseAskAnswer(
            "{\"answer\":\"a\",\"logSearchTerms\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]}", 0);
        assertThat(answer.logSearchTerms()).hasSize(4);
    }

    @Test
    void parsesSearchTermsLeniently() {
        assertThat(SessionJournalAiSupport.parseSearchTerms(
            "Here you go:\n{\"terms\":[\"result_complex.pl\",\"died at\"]}"))
            .containsExactly("result_complex.pl", "died at").inOrder();
        assertThat(SessionJournalAiSupport.parseSearchTerms("{\"terms\":[]}")).isEmpty();
        // Unparsable ≠ empty: the caller must fall back to deterministic tokens.
        assertThat(SessionJournalAiSupport.parseSearchTerms("no json at all")).isNull();
        assertThat(SessionJournalAiSupport.parseSearchTerms("{\"other\":1}")).isNull();
        assertThat(SessionJournalAiSupport.parseSearchTerms(null)).isNull();
    }
}
