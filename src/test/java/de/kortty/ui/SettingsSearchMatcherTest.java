package de.kortty.ui;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SettingsSearchMatcherTest {

    private static SettingsSearchMatcher.Candidate<String> option(String label, String context) {
        return new SettingsSearchMatcher.Candidate<>(label, "", context, false, label);
    }

    private static SettingsSearchMatcher.Candidate<String> page(String title) {
        return new SettingsSearchMatcher.Candidate<>(title, "", title, true, "page:" + title);
    }

    private static List<String> labels(List<SettingsSearchMatcher.Match<String>> matches) {
        return matches.stream().map(match -> match.candidate().label()).toList();
    }

    @Test
    void ignoresCaseAccentsAndUmlautSpellings() {
        List<SettingsSearchMatcher.Candidate<String>> candidates = List.of(option("Schriftgröße", "Schriftart"));
        assertThat(labels(SettingsSearchMatcher.search(candidates, "SCHRIFTGRÖSSE"))).containsExactly("Schriftgröße");
        assertThat(labels(SettingsSearchMatcher.search(candidates, "schriftgroesse"))).containsExactly("Schriftgröße");
        assertThat(labels(SettingsSearchMatcher.search(candidates, "schriftgrosse"))).containsExactly("Schriftgröße");
        assertThat(SettingsSearchMatcher.normalize("Café: Größe…")).isEqualTo("cafe grosse");
    }

    @Test
    void everyWordMustMatchLabelTooltipOrPage() {
        List<SettingsSearchMatcher.Candidate<String>> candidates = List.of(
            option("Scrollback lines", "Terminal"),
            option("Scrollback lines", "Logging"),
            new SettingsSearchMatcher.Candidate<>("Bell", "Plays a sound on BEL", "Messaging", false, "bell"));
        assertThat(SettingsSearchMatcher.search(candidates, "terminal scroll")).hasSize(1);
        assertThat(SettingsSearchMatcher.search(candidates, "terminal scroll").getFirst().candidate().context())
            .isEqualTo("Terminal");
        assertThat(labels(SettingsSearchMatcher.search(candidates, "sound"))).containsExactly("Bell");
        assertThat(SettingsSearchMatcher.search(candidates, "scroll nothing")).isEmpty();
        assertThat(SettingsSearchMatcher.search(candidates, "terminal")).isEmpty();
    }

    @Test
    void prefixHitsRankAboveInnerHitsAndPagesAboveOptions() {
        List<SettingsSearchMatcher.Candidate<String>> candidates = List.of(
            option("Use the system proxy for updates", "Updates"),
            option("Proxy host", "Translation"),
            page("Proxy"));
        assertThat(labels(SettingsSearchMatcher.search(candidates, "proxy")))
            .containsExactly("Proxy", "Proxy host", "Use the system proxy for updates").inOrder();
    }

    @Test
    void equalScoresKeepDialogOrderAndDuplicatesCollapse() {
        List<SettingsSearchMatcher.Candidate<String>> candidates = List.of(
            page("Terminal"),
            option("Terminal", "Terminal"),
            option("Font A", "Font"),
            option("Font B", "Font"),
            option("Font A", "Font"));
        assertThat(labels(SettingsSearchMatcher.search(candidates, "terminal"))).containsExactly("Terminal");
        assertThat(SettingsSearchMatcher.search(candidates, "terminal").getFirst().candidate().tab()).isTrue();
        assertThat(labels(SettingsSearchMatcher.search(candidates, "font"))).containsExactly("Font A", "Font B").inOrder();
    }

    @Test
    void tooShortQueriesFindNothingAndResultsAreCapped() {
        List<SettingsSearchMatcher.Candidate<String>> candidates = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            candidates.add(option("Option " + i, "Page"));
        }
        assertThat(SettingsSearchMatcher.search(candidates, "o")).isEmpty();
        assertThat(SettingsSearchMatcher.search(candidates, "  ")).isEmpty();
        assertThat(SettingsSearchMatcher.search(null, "option")).isEmpty();
        assertThat(SettingsSearchMatcher.search(candidates, "option")).hasSize(SettingsSearchMatcher.MAX_RESULTS);
    }

    @Test
    void displayTextDropsFormColonsAndLineBreaks() {
        assertThat(SettingsSearchBar.displayText("  Font size:  ")).isEqualTo("Font size");
        assertThat(SettingsSearchBar.displayText("Two\nlines")).isEqualTo("Two lines");
        assertThat(SettingsSearchBar.displayText(null)).isEmpty();
    }
}
