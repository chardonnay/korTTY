package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class GuideAskPromptSupportTest {

    private static final List<String> ALLOWED = List.of(
        "features/ai-tools.html#running-commands",
        "features/ai-assistant.html");

    @Test
    void userPromptContainsVerbatimSourceLinesAndNumbering() {
        List<GuideDocsRetriever.Excerpt> excerpts = List.of(
            new GuideDocsRetriever.Excerpt("features/ai-tools.html#running-commands",
                "Terminal AI agent & tools", "Running commands", "Body text one", false),
            new GuideDocsRetriever.Excerpt("features/ai-assistant.html",
                "AI assistant", "AI assistant", "Body text two", true));
        String prompt = GuideAskPromptSupport.buildUserPrompt("How do I run the agent?", excerpts);

        assertThat(prompt).contains("Question: How do I run the agent?");
        assertThat(prompt).contains("[1] Page: Terminal AI agent & tools — Section: Running commands");
        assertThat(prompt).contains("Source: features/ai-tools.html#running-commands");
        assertThat(prompt).contains("[2] Page: AI assistant");
        assertThat(prompt).contains("Source: features/ai-assistant.html");
    }

    @Test
    void systemPromptEmbedsLanguageAndNotFoundSentence() {
        String prompt = GuideAskPromptSupport.buildSystemPrompt("German", "Nicht gefunden.");
        assertThat(prompt).contains("Answer in German");
        assertThat(prompt).contains("Nicht gefunden.");
    }

    @Test
    void sanitizeKeepsWhitelistedLinks() {
        String answer = "See [Running commands](features/ai-tools.html#running-commands).";
        assertThat(GuideAskPromptSupport.sanitizeAnswer(answer, ALLOWED)).isEqualTo(answer);
    }

    @Test
    void sanitizeRepairsInventedAnchorsToThePageLink() {
        String answer = "See [agent docs](features/ai-tools.html#made-up-anchor).";
        assertThat(GuideAskPromptSupport.sanitizeAnswer(answer, ALLOWED))
            .isEqualTo("See [agent docs](features/ai-tools.html).");
    }

    @Test
    void sanitizeUnwrapsUnknownAndExternalTargets() {
        assertThat(GuideAskPromptSupport.sanitizeAnswer(
            "See [evil](https://evil.example/page).", ALLOWED))
            .isEqualTo("See evil.");
        assertThat(GuideAskPromptSupport.sanitizeAnswer(
            "See [made up](features/invented.html#x).", ALLOWED))
            .isEqualTo("See made up.");
    }

    @Test
    void renderAnswerHtmlLinkifiesGuideLocationsOnly() {
        String markdown = "Use [Running commands](features/ai-tools.html#running-commands) "
            + "but not [evil](https://evil.example).";
        String html = GuideAskPromptSupport.renderAnswerHtml(
            GuideAskPromptSupport.sanitizeAnswer(markdown, ALLOWED));

        assertThat(html).contains(
            "<a href=\"kortty-guide:features/ai-tools.html#running-commands\">Running commands</a>");
        assertThat(html).doesNotContain("evil.example");
        assertThat(html).doesNotContain("[evil]");
    }

    @Test
    void renderAnswerHtmlKeepsModelHtmlEscaped() {
        String html = GuideAskPromptSupport.renderAnswerHtml(
            "Answer with <script>alert(1)</script> inside.");
        assertThat(html).doesNotContain("<script>alert");
        assertThat(html).contains("&lt;script&gt;");
    }
}
