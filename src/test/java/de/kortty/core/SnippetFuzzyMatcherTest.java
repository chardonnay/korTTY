package de.kortty.core;

import de.kortty.model.Snippet;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

public class SnippetFuzzyMatcherTest {

    private static Snippet snippet(String name, String... tags) {
        Snippet snippet = new Snippet(name, "echo", "bash");
        snippet.setTagsFromString(String.join(", ", tags));
        return snippet;
    }

    @Test
    public void everyQueryCharacterMustAppearInOrder() {
        assertThat(SnippetFuzzyMatcher.score("dpl", "deploy.sh")).isGreaterThan(0);
        assertThat(SnippetFuzzyMatcher.score("DEPLOY", "deploy.sh")).isGreaterThan(0);
        assertThat(SnippetFuzzyMatcher.score("lpd", "deploy.sh")).isEqualTo(SnippetFuzzyMatcher.NO_MATCH);
        assertThat(SnippetFuzzyMatcher.score("x", "")).isEqualTo(SnippetFuzzyMatcher.NO_MATCH);
        assertThat(SnippetFuzzyMatcher.score("  ", "deploy.sh")).isEqualTo(0);
        assertThat(SnippetFuzzyMatcher.score("de ploy", "deploy.sh")).isGreaterThan(0);
    }

    @Test
    public void prefixesConsecutiveRunsAndWordStartsRankHigher() {
        assertThat(SnippetFuzzyMatcher.score("dep", "deploy.sh"))
            .isGreaterThan(SnippetFuzzyMatcher.score("dep", "old-deploy-helper.sh"));
        assertThat(SnippetFuzzyMatcher.score("bk", "backup-keys.sh"))
            .isGreaterThan(SnippetFuzzyMatcher.score("bk", "bookmark.sh"));
        assertThat(SnippetFuzzyMatcher.score("log", "log.sh"))
            .isGreaterThan(SnippetFuzzyMatcher.score("log", "logrotate-configuration.sh"));
    }

    @Test
    public void namesBeatTagsAndTagsStillMatch() {
        Snippet byName = snippet("docker-clean.sh");
        Snippet byTag = snippet("prune.sh", "docker");
        List<SnippetFuzzyMatcher.Match> ranked =
            SnippetFuzzyMatcher.rank("docker", List.of(byTag, byName, snippet("unrelated.sh")), 10);
        assertThat(ranked.stream().map(SnippetFuzzyMatcher.Match::snippet).toList())
            .containsExactly(byName, byTag).inOrder();
    }

    @Test
    public void aBlankQueryListsEverythingByNameAndTheLimitApplies() {
        Snippet b = snippet("beta.sh");
        Snippet a = snippet("Alpha.sh");
        Snippet c = snippet("gamma.sh");
        assertThat(SnippetFuzzyMatcher.rank("", List.of(b, c, a), 0).stream()
            .map(SnippetFuzzyMatcher.Match::snippet).toList()).containsExactly(a, b, c).inOrder();
        assertThat(SnippetFuzzyMatcher.rank("", List.of(b, c, a), 2)).hasSize(2);
        assertThat(SnippetFuzzyMatcher.rank("a", null, 5)).isEmpty();
        assertThat(SnippetFuzzyMatcher.score("a", (Snippet) null)).isEqualTo(SnippetFuzzyMatcher.NO_MATCH);
    }
}
