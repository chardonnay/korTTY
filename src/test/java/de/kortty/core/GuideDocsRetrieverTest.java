package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Retrieval-quality gate for the guide AI docs search, running against the real bundled index.
 * The canonical questions mirror the documented feature examples; if these fail after a docs
 * rebuild, retrieval quality regressed (or the ai-tools page moved).
 */
class GuideDocsRetrieverTest {

    private static final int BUDGET = 16_000;
    private static final int MAX_EXCERPTS = 12;

    @Test
    void canonicalGermanQuestionFindsAiToolsPage() {
        GuideDocsRetriever.RetrievalResult result = GuideDocsRetriever.retrieve(
            GuideSearchIndex.load("de"), GuideSearchIndex.load("en"),
            "Wie führe ich den KI-Agenten im Terminalfenster aus?", BUDGET, MAX_EXCERPTS);
        assertTopExcerptsContainPage(result, "features/ai-tools.html", 3);
    }

    @Test
    void canonicalEnglishQuestionFindsAiToolsPage() {
        GuideDocsRetriever.RetrievalResult result = GuideDocsRetriever.retrieve(
            GuideSearchIndex.load("en"), GuideSearchIndex.load("de"),
            "How do I run the AI agent in the terminal window?", BUDGET, MAX_EXCERPTS);
        assertTopExcerptsContainPage(result, "features/ai-tools.html", 3);
    }

    @Test
    void englishQuestionAgainstGermanIndexStillFindsAiTools() {
        // No fallback index: forces the ki<->ai synonym bridge on the DE corpus alone.
        GuideDocsRetriever.RetrievalResult result = GuideDocsRetriever.retrieve(
            GuideSearchIndex.load("de"), null,
            "How do I run the AI agent in the terminal window?", BUDGET, MAX_EXCERPTS);
        assertTopExcerptsContainPage(result, "features/ai-tools.html", 5);
    }

    @Test
    void offTopicQuestionRetrievesNothing() {
        GuideDocsRetriever.RetrievalResult result = GuideDocsRetriever.retrieve(
            GuideSearchIndex.load("de"), GuideSearchIndex.load("en"),
            "Wie backe ich einen Kuchen mit Schokolade?", BUDGET, MAX_EXCERPTS);
        assertWithMessage("off-topic top score below confidence threshold; got "
            + describe(result))
            .that(result.topScore()).isLessThan(GuideDocsRetriever.MIN_CONFIDENT_SCORE);
    }

    @Test
    void budgetAndExcerptCapAreRespected() {
        GuideDocsRetriever.RetrievalResult result = GuideDocsRetriever.retrieve(
            GuideSearchIndex.load("en"), null,
            "How do I run the AI agent in the terminal window?", 8_000, 7);
        assertThat(result.excerpts().size()).isAtMost(7);
        int totalChars = result.excerpts().stream()
            .mapToInt(excerpt -> excerpt.text().length()).sum();
        // Merging joins adjacent sections but each merged excerpt is re-truncated.
        assertThat(totalChars).isAtMost(8_000 + GuideDocsRetriever.MAX_EXCERPT_CHARS);
        for (GuideDocsRetriever.Excerpt excerpt : result.excerpts()) {
            assertThat(excerpt.text().length())
                .isAtMost(GuideDocsRetriever.MAX_EXCERPT_CHARS + 2);
        }
    }

    @Test
    void fallbackLanguageCitationsDropTheLocalizedAnchor() {
        // English-only question phrasing against the German index with EN fallback available:
        // any excerpt taken from the fallback corpus must cite the page path only.
        GuideDocsRetriever.RetrievalResult result = GuideDocsRetriever.retrieve(
            GuideSearchIndex.load("de"), GuideSearchIndex.load("en"),
            "keyboard shortcuts split screen recording", BUDGET, MAX_EXCERPTS);
        for (GuideDocsRetriever.Excerpt excerpt : result.excerpts()) {
            if (excerpt.fromFallbackLanguage()) {
                assertWithMessage("fallback citation " + excerpt.location())
                    .that(excerpt.location()).doesNotContain("#");
            }
        }
    }

    @Test
    void germanCompoundsDecomposeAgainstTheCorpusVocabulary() {
        Set<String> vocabulary = Set.of("terminal", "fenster", "verbindung");
        assertThat(GuideDocsRetriever.compoundParts("terminalfenster", vocabulary))
            .containsExactly("terminal", "fenster");
        assertThat(GuideDocsRetriever.compoundParts("kuchenrezept", vocabulary)).isEmpty();
    }

    @Test
    void foldingAndStemmingNormalizeGermanAndEnglishForms() {
        assertThat(GuideDocsRetriever.fold("Ausführen")).isEqualTo("ausfuhren");
        assertThat(GuideDocsRetriever.stem("ausfuhren")).isEqualTo("ausfuhr");
        assertThat(GuideDocsRetriever.stem("agenten")).isEqualTo("agent");
        assertThat(GuideDocsRetriever.stem("einstellungen")).isEqualTo("einstell");
        // Double-consonant collapse keeps "running" and "runs" on the same stem.
        assertThat(GuideDocsRetriever.stem("running")).isEqualTo("run");
        assertThat(GuideDocsRetriever.stem("runs")).isEqualTo("run");
    }

    @Test
    void singularAndPluralLandOnTheSameStem() {
        // The bounded second stemming pass reunites '-n'-final nouns whose plural strips only
        // the "s" ("sessions" -> "session") with the singular ("session" -> "sessio").
        assertThat(GuideDocsRetriever.stem("sessions"))
            .isEqualTo(GuideDocsRetriever.stem("session"));
        assertThat(GuideDocsRetriever.stem("screens"))
            .isEqualTo(GuideDocsRetriever.stem("screen"));
        assertThat(GuideDocsRetriever.stem("connections"))
            .isEqualTo(GuideDocsRetriever.stem("connection"));
        assertThat(GuideDocsRetriever.stem("aktionen"))
            .isEqualTo(GuideDocsRetriever.stem("aktion"));
    }

    @Test
    void pluralPhrasedQuestionScoresLikeTheSingularOne() {
        GuideDocsRetriever.RetrievalResult singular = GuideDocsRetriever.retrieve(
            GuideSearchIndex.load("en"), null, "How do I split the screen?", BUDGET, MAX_EXCERPTS);
        GuideDocsRetriever.RetrievalResult plural = GuideDocsRetriever.retrieve(
            GuideSearchIndex.load("en"), null, "How do I split screens?", BUDGET, MAX_EXCERPTS);
        assertWithMessage("plural query keeps most of the singular score; singular="
            + singular.topScore() + " plural=" + plural.topScore())
            .that(plural.topScore()).isAtLeast(singular.topScore() * 0.6);
    }

    @Test
    void truncationCutsAtASentenceBoundary() {
        String sentence = "This is a fairly long sentence about korTTY features. ";
        String text = sentence.repeat(80); // ~4400 chars
        String truncated = GuideDocsRetriever.truncateAtSentence(text);
        assertThat(truncated.length()).isAtMost(GuideDocsRetriever.MAX_EXCERPT_CHARS + 2);
        assertThat(truncated).endsWith(". …");
    }

    private static void assertTopExcerptsContainPage(GuideDocsRetriever.RetrievalResult result,
                                                     String pagePath, int topN) {
        assertWithMessage("excerpts present").that(result.excerpts()).isNotEmpty();
        List<GuideDocsRetriever.Excerpt> top =
            result.excerpts().subList(0, Math.min(topN, result.excerpts().size()));
        boolean found = top.stream().anyMatch(excerpt -> excerpt.location().startsWith(pagePath));
        assertWithMessage(pagePath + " in top " + topN + "; got " + describe(result))
            .that(found).isTrue();
    }

    private static String describe(GuideDocsRetriever.RetrievalResult result) {
        return "topScore=" + result.topScore() + " locations="
            + result.excerpts().stream().map(GuideDocsRetriever.Excerpt::location).toList();
    }
}
