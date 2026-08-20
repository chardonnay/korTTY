package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class TextRelevanceScorerTest {

    private static final List<TextRelevanceScorer.Doc> DOCS = List.of(
        new TextRelevanceScorer.Doc("deploy", "Deploy Tuesday",
            "perl result_complex.pl died at line 42 nginx restart failed"),
        new TextRelevanceScorer.Doc("quiet", "Quiet maintenance",
            "routine apt update, nothing notable, all services healthy"),
        new TextRelevanceScorer.Doc("nginx", "Nginx tuning",
            "nginx worker_connections raised, reload succeeded"));

    @Test
    void ranksTheDocumentContainingTheIdentifierFirst() {
        List<TextRelevanceScorer.Scored> scored = TextRelevanceScorer.score(
            DOCS, "result_complex.pl error", 10);
        assertThat(scored.get(0).id()).isEqualTo("deploy");
    }

    @Test
    void titleMatchesBoostTheScore() {
        List<TextRelevanceScorer.Scored> scored = TextRelevanceScorer.score(
            DOCS, "nginx tuning", 10);
        assertThat(scored.get(0).id()).isEqualTo("nginx");
    }

    @Test
    void limitCapsAndOrdersDescending() {
        List<TextRelevanceScorer.Scored> scored = TextRelevanceScorer.score(DOCS, "nginx", 1);
        assertThat(scored).hasSize(1);
        List<TextRelevanceScorer.Scored> all = TextRelevanceScorer.score(DOCS, "nginx", 10);
        for (int i = 1; i < all.size(); i++) {
            assertThat(all.get(i - 1).score()).isAtLeast(all.get(i).score());
        }
    }

    @Test
    void unmatchedQueryReturnsNothing() {
        assertThat(TextRelevanceScorer.score(DOCS, "zeppelin", 10)).isEmpty();
        assertThat(TextRelevanceScorer.score(DOCS, "  ", 10)).isEmpty();
        assertThat(TextRelevanceScorer.score(List.of(), "nginx", 10)).isEmpty();
    }

    @Test
    void germanQuestionMatchesThroughSharedStemming() {
        // "Verbindungen" and "Verbindung" must land on the same stem (guide-retriever tokenizer).
        List<TextRelevanceScorer.Doc> docs = List.of(
            new TextRelevanceScorer.Doc("a", "Server", "die verbindung wurde getrennt"),
            new TextRelevanceScorer.Doc("b", "Server", "alles ruhig"));
        List<TextRelevanceScorer.Scored> scored =
            TextRelevanceScorer.score(docs, "getrennte Verbindungen", 10);
        assertThat(scored).isNotEmpty();
        assertThat(scored.get(0).id()).isEqualTo("a");
    }
}
