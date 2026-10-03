package de.kortty.core;

import de.kortty.core.FuzzyMatcher.Field;
import de.kortty.core.FuzzyMatcher.Ranked;
import de.kortty.model.Snippet;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

public class FuzzyMatcherTest {

    private record Item(String title, String detail) {
    }

    private static final Comparator<Item> BY_TITLE = Comparator.comparing(Item::title, String.CASE_INSENSITIVE_ORDER);

    private static List<Field> titleAndDetail(Item item) {
        return List.of(new Field(item.title(), 3), new Field(item.detail(), 1));
    }

    private static List<Item> items(List<Ranked<Item>> ranked) {
        return ranked.stream().map(Ranked::item).toList();
    }

    /** Scores captured from SnippetFuzzyMatcher before the extraction; both matchers must keep them. */
    @DataProvider(name = "pinnedScores")
    public Object[][] pinnedScores() {
        return new Object[][] {
            {"dpl", "deploy.sh", 35},
            {"DEPLOY", "deploy.sh", 69},
            {"lpd", "deploy.sh", FuzzyMatcher.NO_MATCH},
            {"x", "", FuzzyMatcher.NO_MATCH},
            {"x", null, FuzzyMatcher.NO_MATCH},
            {"  ", "deploy.sh", 0},
            {null, "deploy.sh", 0},
            {"", "", 0},
            {"de ploy", "deploy.sh", 69},
            {"dep", "deploy.sh", 50},
            {"dep", "old-deploy-helper.sh", 39},
            {"bk", "backup-keys.sh", 31},
            {"bk", "bookmark.sh", 28},
            {"log", "log.sh", 51},
            {"log", "logrotate-configuration.sh", 46},
            {"docker", "docker-clean.sh", 67},
            {"docker", "docker", 69},
            {"jl", "View › Live Journal › Dock Left", 23},
            {"journalleft", "View › Live Journal › Dock Left", 79},
            {"aa", "banana", 21},
            {"ss", "ssh-tunnel", 43},
            {"Üb", "über-skript", 43},
            {"tun", "SSH Tunnel", 42},
            {"rs", "restart-service.sh", 30},
        };
    }

    @Test(dataProvider = "pinnedScores")
    public void textScoresAreUnchangedFromTheSnippetMatcher(String query, String text, int expected) {
        assertThat(FuzzyMatcher.score(query, text)).isEqualTo(expected);
        assertThat(SnippetFuzzyMatcher.score(query, text)).isEqualTo(expected);
    }

    /** Name/tag scores captured from SnippetFuzzyMatcher before the extraction: name x3, best field wins. */
    @DataProvider(name = "pinnedSnippetScores")
    public Object[][] pinnedSnippetScores() {
        return new Object[][] {
            {"docker", "docker-clean.sh", "", 201},
            {"docker", "prune.sh", "docker", 69},
            {"dc", "docker-clean.sh", "dc, cleanup", 93},
            {"prod", "deploy.sh", "production, prod", 57},
            {"", "anything.sh", "", 0},
            {"zzz", "a.sh", "b", FuzzyMatcher.NO_MATCH},
            {"clean", "prune.sh", "docker, cleanup", 63},
            {"log", "rotate.sh", "logs, log", 51},
        };
    }

    @Test(dataProvider = "pinnedSnippetScores")
    public void snippetScoresAreTheBestWeightedNameOrTagField(String query, String name, String tags, int expected) {
        Snippet snippet = new Snippet(name, "echo", "bash");
        snippet.setTagsFromString(tags);
        List<Field> fields = new ArrayList<>();
        fields.add(new Field(name, 3));
        snippet.getTags().forEach(tag -> fields.add(new Field(tag, 1)));

        assertThat(SnippetFuzzyMatcher.score(query, snippet)).isEqualTo(expected);
        assertThat(FuzzyMatcher.bestScore(query, fields)).isEqualTo(expected);
    }

    @Test
    public void aWeightedTitleMatchBeatsTheSameDetailMatchAndDetailMatchesStillCount() {
        Item byDetail = new Item("Close Tab", "docker");
        Item byTitle = new Item("docker", "Close Tab");
        Item unrelated = new Item("Split Right", "Terminal");

        List<Ranked<Item>> ranked = FuzzyMatcher.rank("docker", List.of(byDetail, unrelated, byTitle),
            FuzzyMatcherTest::titleAndDetail, BY_TITLE, 0);

        int exact = FuzzyMatcher.score("docker", "docker");
        assertThat(items(ranked)).containsExactly(byTitle, byDetail).inOrder();
        assertThat(ranked.get(0).score()).isEqualTo(exact * 3);
        assertThat(ranked.get(1).score()).isEqualTo(exact);
    }

    @Test
    public void theWeightCanLiftAWeakerTitleMatchAboveAStrongerDetailMatch() {
        Item weakTitle = new Item("logrotate-configuration.sh", "Snippets");
        Item strongDetail = new Item("Rotate", "log.sh");
        assertThat(FuzzyMatcher.score("log", "log.sh"))
            .isGreaterThan(FuzzyMatcher.score("log", "logrotate-configuration.sh"));

        assertThat(items(FuzzyMatcher.rank("log", List.of(strongDetail, weakTitle),
            FuzzyMatcherTest::titleAndDetail, BY_TITLE, 0))).containsExactly(weakTitle, strongDetail).inOrder();
    }

    @Test
    public void aBlankQueryReturnsEveryItemInTieOrder() {
        Item beta = new Item("beta", null);
        Item alpha = new Item("Alpha", "first");
        Item gamma = new Item("gamma", "third");
        List<Item> input = Arrays.asList(beta, null, gamma, alpha);

        List<Ranked<Item>> byTitle = FuzzyMatcher.rank("  ", input, FuzzyMatcherTest::titleAndDetail, BY_TITLE, 0);
        assertThat(items(byTitle)).containsExactly(alpha, beta, gamma).inOrder();
        assertThat(byTitle.stream().map(Ranked::score).distinct().toList()).containsExactly(0);

        assertThat(items(FuzzyMatcher.rank(null, input, FuzzyMatcherTest::titleAndDetail, null, 0)))
            .containsExactly(beta, gamma, alpha).inOrder();
        assertThat(items(FuzzyMatcher.rank("", input, item -> List.of(), null, 0)))
            .containsExactly(beta, gamma, alpha).inOrder();
    }

    @Test
    public void equalScoresWithoutATieBreakKeepTheInputOrder() {
        Item first = new Item("deploy", "one");
        Item second = new Item("deploy", "two");
        assertThat(items(FuzzyMatcher.rank("dep", List.of(second, first), FuzzyMatcherTest::titleAndDetail, null, 0)))
            .containsExactly(second, first).inOrder();
    }

    @Test
    public void theLimitKeepsTheBestMatchesAndZeroOrLessMeansNoLimit() {
        Item best = new Item("deploy", "");
        Item middle = new Item("deploy-staging", "");
        Item last = new Item("old-deploy-helper", "");
        List<Item> input = List.of(last, best, middle);

        assertThat(items(FuzzyMatcher.rank("dep", input, FuzzyMatcherTest::titleAndDetail, BY_TITLE, 2)))
            .containsExactly(best, middle).inOrder();
        assertThat(FuzzyMatcher.rank("dep", input, FuzzyMatcherTest::titleAndDetail, BY_TITLE, 0)).hasSize(3);
        assertThat(FuzzyMatcher.rank("dep", input, FuzzyMatcherTest::titleAndDetail, BY_TITLE, -1)).hasSize(3);
        assertThat(FuzzyMatcher.rank("dep", input, FuzzyMatcherTest::titleAndDetail, BY_TITLE, 5)).hasSize(3);
    }

    @Test
    public void itemsWithoutAMatchingFieldAreLeftOut() {
        Item match = new Item("ssh-tunnel", null);
        Item noMatch = new Item("deploy", "staging");
        Item noText = new Item(null, null);

        assertThat(items(FuzzyMatcher.rank("tun", List.of(noMatch, match, noText),
            FuzzyMatcherTest::titleAndDetail, null, 0))).containsExactly(match);
        assertThat(FuzzyMatcher.rank("tun", null, FuzzyMatcherTest::titleAndDetail, null, 0)).isEmpty();
        assertThat(FuzzyMatcher.rank("tun", List.of(match), item -> null, null, 0)).isEmpty();
        assertThat(FuzzyMatcher.bestScore("tun", null)).isEqualTo(FuzzyMatcher.NO_MATCH);
        assertThat(FuzzyMatcher.bestScore("tun", List.of())).isEqualTo(FuzzyMatcher.NO_MATCH);
        assertThat(FuzzyMatcher.bestScore("tun", Arrays.asList(null, new Field("tunnel", 2))))
            .isEqualTo(FuzzyMatcher.score("tun", "tunnel") * 2);
    }

    @Test
    public void aFieldWeightMustBeAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> new Field("x", 0));
        assertThrows(IllegalArgumentException.class, () -> new Field("x", -3));
        assertThat(new Field("x", 1).weight()).isEqualTo(1);
    }
}
