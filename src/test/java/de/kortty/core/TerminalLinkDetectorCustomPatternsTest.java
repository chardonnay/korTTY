package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;

import com.sithtermfx.core.util.CharUtils;
import de.kortty.core.TerminalLinkDetector.Kind;
import de.kortty.core.TerminalLinkDetector.Match;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * The user's own quick-select patterns inside the detector: their matches come out as
 * {@link Kind#CUSTOM} with cell offsets like every other match, and where they overlap a built-in
 * match the longer one wins.
 */
class TerminalLinkDetectorCustomPatternsTest {

    private static final Set<Kind> ALL = EnumSet.allOf(Kind.class);

    private static List<Match> find(String line, String... patterns) {
        return TerminalLinkDetector.find(line, ALL, false, false, QuickSelectPatterns.compile(List.of(patterns)).start());
    }

    private static List<String> described(List<Match> matches) {
        return matches.stream().map(match -> match.kind() + ":" + match.text()).toList();
    }

    @Test
    void aPatternMatchIsReportedAsCustomBesideTheBuiltInOnes() {
        List<Match> matches = find("deploy web01.prod by ops@example.com", "web\\d+\\.prod");

        assertThat(described(matches)).containsExactly("CUSTOM:web01.prod", "EMAIL:ops@example.com").inOrder();
        assertThat(matches.get(0).start()).isEqualTo(7);
        assertThat(matches.get(0).end()).isEqualTo(17);
    }

    @Test
    void aLongerPatternMatchWinsOverTheNumberInsideIt() {
        assertThat(described(find("see JIRA-12345 now", "[A-Z]+-\\d+"))).containsExactly("CUSTOM:JIRA-12345");
    }

    @Test
    void aLongerBuiltInMatchWinsOverThePatternMatchInsideIt() {
        assertThat(described(find("open https://jira.example.com/browse/JIRA-12345 now", "[A-Z]+-\\d+")))
            .containsExactly("URL:https://jira.example.com/browse/JIRA-12345");
    }

    @Test
    void anEquallyLongPatternMatchWinsBecauseTheUserAskedForIt() {
        // The git hash and the pattern cover the same seven characters.
        assertThat(described(find("at c0ffee1 done", "c0ffee\\d"))).containsExactly("CUSTOM:c0ffee1");
    }

    @Test
    void aPatternMatchThatSpansTwoBuiltInMatchesReplacesBothWhenItIsLonger() {
        assertThat(described(find("from 10.0.0.1 to 10.0.0.2 ok", "10\\.0\\.0\\.1 to 10\\.0\\.0\\.2")))
            .containsExactly("CUSTOM:10.0.0.1 to 10.0.0.2");
        // A longer built-in neighbour keeps its place, and so the pattern match is dropped.
        assertThat(described(find("x 2001:db8::1234:5678 y", "1234:5678 y")))
            .containsExactly("IPV6:2001:db8::1234:5678");
    }

    @Test
    void amongThePatternsTheLongerMatchWinsThenTheEarlierPattern() {
        assertThat(described(find("ticket ABC-123-X", "ABC-\\d+", "ABC-\\d+-X"))).containsExactly("CUSTOM:ABC-123-X");
        List<Match> tie = find("host db01 up", "db\\d\\d", "d\\w\\d\\d");
        assertThat(described(tie)).containsExactly("CUSTOM:db01");
    }

    @Test
    void cellOffsetsSkipTheSecondCellOfAWideCharacter() {
        String line = "日" + CharUtils.DWC + " INC42";

        List<Match> matches = find(line, "INC\\d+");

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).text()).isEqualTo("INC42");
        assertThat(matches.get(0).start()).isEqualTo(3);
        assertThat(matches.get(0).end()).isEqualTo(8);
    }

    @Test
    void controlCharactersAndEmptyCellsAreSpacesToAPatternAndAreTrimmedOffItsMatch() {
        String line = "\u0000\u0000id=7‮\u0000";

        List<Match> matches = find(line, ".+");

        assertThat(described(matches)).containsExactly("CUSTOM:id=7");
        assertThat(matches.get(0).start()).isEqualTo(2);
        assertThat(matches.get(0).end()).isEqualTo(6);
    }

    @Test
    void aMatchCutByTheSliceEdgeIsNotOffered() {
        // The logical line goes on after this slice: the token at its end may be longer.
        List<Match> matches = TerminalLinkDetector.find("INC1 INC2", ALL, false, true,
            QuickSelectPatterns.compile(List.of("INC\\d+")).start());

        assertThat(described(matches)).containsExactly("CUSTOM:INC1");
    }

    @Test
    void withoutPatternsOrKindsNothingChanges() {
        assertThat(TerminalLinkDetector.find("INC42 4711", ALL, false, false, null).stream().map(Match::kind).toList())
            .containsExactly(Kind.NUMBER);
        assertThat(TerminalLinkDetector.find("INC42", Set.of(Kind.CUSTOM))).isEmpty();
        // Patterns alone, without a built-in kind, still run.
        assertThat(described(TerminalLinkDetector.find("INC42 4711", Set.of(), false, false,
            QuickSelectPatterns.compile(List.of("INC\\d+")).start()))).containsExactly("CUSTOM:INC42");
    }
}
