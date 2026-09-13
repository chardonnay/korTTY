package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

class AgentRuleTest {

    private static final Pattern NEEDLE_LINE = Pattern.compile("needle");

    private static AgentRule rule(int bottomNonEmptyLines, List<Pattern> anyLine, Pattern regex,
                                  List<String> contains, List<String> notContains, Pattern title, Boolean alt) {
        return new AgentRule("r", CodingAgentState.BLOCKED, 10, bottomNonEmptyLines, anyLine, regex, contains,
            notContains, title, alt);
    }

    private static ScreenSnapshot screen(String text) {
        return ScreenSnapshot.ofText(text, null, false);
    }

    @Test
    void lineAboveTheBottomWindowNeverMatches() {
        ScreenSnapshot snapshot = screen("needle here\nfiller 1\nfiller 2\nfiller 3\n");
        AgentRule windowed = rule(2, List.of(NEEDLE_LINE), null, List.of(), List.of(), null, null);
        AgentRule wholeScreen = rule(0, List.of(NEEDLE_LINE), null, List.of(), List.of(), null, null);

        assertThat(windowed.match(snapshot)).isEmpty();
        assertThat(wholeScreen.match(snapshot)).hasValue("needle here");
    }

    @Test
    void bottomWindowCountsNonEmptyLinesOnly() {
        ScreenSnapshot snapshot = screen("needle here\n\n\n\nfiller 1\n\n\n");
        AgentRule windowed = rule(2, List.of(NEEDLE_LINE), null, List.of(), List.of(), null, null);

        // Blank rows do not consume the window: the two last non-empty rows are "needle here" and "filler 1".
        assertThat(windowed.match(snapshot)).hasValue("needle here");
    }

    @Test
    void anyLineRegexEvidenceIsTheFirstMatchingRegionLine() {
        ScreenSnapshot snapshot = screen("intro\nfirst needle\nsecond needle\nprompt >");
        AgentRule rule = rule(0, List.of(Pattern.compile("nothing"), NEEDLE_LINE), null, List.of(), List.of(),
            null, null);

        assertThat(rule.match(snapshot)).hasValue("first needle");
    }

    @Test
    void anyLineRegexWithoutAnyMatchingLineFails() {
        AgentRule rule = rule(0, List.of(NEEDLE_LINE), null, List.of(), List.of(), null, null);
        assertThat(rule.match(screen("nothing to see\nhere"))).isEmpty();
    }

    @Test
    void regexIsAMultilineDotallFindOverTheJoinedRegion() {
        Pattern spanning = Pattern.compile("^b.*d$", Pattern.MULTILINE | Pattern.DOTALL);
        ScreenSnapshot snapshot = screen("a\nb\nc\nd\ne");
        AgentRule rule = rule(0, List.of(), spanning, List.of(), List.of(), null, null);

        // Evidence is the matched text, which spans lines because of DOTALL.
        assertThat(rule.match(snapshot)).hasValue("b\nc\nd");
        assertThat(rule.match(screen("a\nx\ne"))).isEmpty();
    }

    @Test
    void regexIsRestrictedToTheRegionToo() {
        Pattern top = Pattern.compile("^top$", Pattern.MULTILINE | Pattern.DOTALL);
        ScreenSnapshot snapshot = screen("top\nmiddle\nbottom");
        AgentRule rule = rule(2, List.of(), top, List.of(), List.of(), null, null);

        assertThat(rule.match(snapshot)).isEmpty();
    }

    @Test
    void containsRequiresEverySubstring() {
        ScreenSnapshot snapshot = screen("Do you want to proceed?\n❯ 1. Yes");
        AgentRule all = rule(0, List.of(), null, List.of("proceed", "1. Yes"), List.of(), null, null);
        AgentRule one = rule(0, List.of(), null, List.of("proceed", "2. No"), List.of(), null, null);

        assertThat(all.match(snapshot)).hasValue("r");
        assertThat(one.match(snapshot)).isEmpty();
    }

    @Test
    void notContainsVetoesOnAnySubstring() {
        ScreenSnapshot snapshot = screen("Do you want to proceed?\n✻ Thinking… (esc to interrupt)");
        AgentRule vetoed = rule(0, List.of(), null, List.of("proceed"), List.of("never", "esc to interrupt"), null,
            null);
        AgentRule clean = rule(0, List.of(), null, List.of("proceed"), List.of("never"), null, null);

        assertThat(vetoed.match(snapshot)).isEmpty();
        assertThat(clean.match(snapshot)).hasValue("r");
    }

    @Test
    void titleRegexRequiresAPresentOscTitle() {
        Pattern claude = Pattern.compile("Claude Code");
        AgentRule rule = rule(0, List.of(), null, List.of(), List.of(), claude, null);

        assertThat(rule.match(ScreenSnapshot.ofText("x", null, false))).isEmpty();
        assertThat(rule.match(ScreenSnapshot.ofText("x", "Terminal", false))).isEmpty();
        assertThat(rule.match(ScreenSnapshot.ofText("x", "bash", false))).isEmpty();
        assertThat(rule.match(ScreenSnapshot.ofText("x", "✳ Claude Code", false))).hasValue("✳ Claude Code");
    }

    @Test
    void alternateScreenGateIsOptional() {
        ScreenSnapshot main = ScreenSnapshot.ofText("x", null, false);
        ScreenSnapshot alternate = ScreenSnapshot.ofText("x", null, true);
        AgentRule dontCare = rule(0, List.of(), null, List.of("x"), List.of(), null, null);
        AgentRule mustBeAlternate = rule(0, List.of(), null, List.of("x"), List.of(), null, Boolean.TRUE);
        AgentRule mustBeMain = rule(0, List.of(), null, List.of("x"), List.of(), null, Boolean.FALSE);

        assertThat(dontCare.match(main)).isPresent();
        assertThat(dontCare.match(alternate)).isPresent();
        assertThat(mustBeAlternate.match(main)).isEmpty();
        assertThat(mustBeAlternate.match(alternate)).isPresent();
        assertThat(mustBeMain.match(main)).isPresent();
        assertThat(mustBeMain.match(alternate)).isEmpty();
    }

    @Test
    void evidencePrefersLineThenRegexThenTitleThenRuleId() {
        ScreenSnapshot snapshot = ScreenSnapshot.ofText("needle line\nother", "✳ Claude Code", false);
        Pattern other = Pattern.compile("other", Pattern.MULTILINE | Pattern.DOTALL);
        Pattern title = Pattern.compile("Claude");

        AgentRule line = rule(0, List.of(NEEDLE_LINE), other, List.of(), List.of(), title, null);
        AgentRule regex = rule(0, List.of(), other, List.of(), List.of(), title, null);
        AgentRule titleOnly = rule(0, List.of(), null, List.of(), List.of(), title, null);
        AgentRule idOnly = rule(0, List.of(), null, List.of(), List.of(), null, Boolean.FALSE);

        assertThat(line.match(snapshot)).hasValue("needle line");
        assertThat(regex.match(snapshot)).hasValue("other");
        assertThat(titleOnly.match(snapshot)).hasValue("✳ Claude Code");
        assertThat(idOnly.match(snapshot)).hasValue("r");
    }

    @Test
    void allMatchersAreAnded() {
        ScreenSnapshot snapshot = ScreenSnapshot.ofText("needle line\nother", "✳ Claude Code", true);
        Pattern other = Pattern.compile("other", Pattern.MULTILINE | Pattern.DOTALL);
        Pattern title = Pattern.compile("Claude");
        AgentRule allPass = rule(0, List.of(NEEDLE_LINE), other, List.of("line"), List.of("absent"), title, true);

        assertThat(allPass.match(snapshot)).hasValue("needle line");
        assertThat(rule(0, List.of(Pattern.compile("zzz")), other, List.of("line"), List.of("absent"), title, true)
            .match(snapshot)).isEmpty();
        assertThat(rule(0, List.of(NEEDLE_LINE), Pattern.compile("zzz"), List.of("line"), List.of("absent"), title,
            true).match(snapshot)).isEmpty();
        assertThat(rule(0, List.of(NEEDLE_LINE), other, List.of("zzz"), List.of("absent"), title, true)
            .match(snapshot)).isEmpty();
        assertThat(rule(0, List.of(NEEDLE_LINE), other, List.of("line"), List.of("other"), title, true)
            .match(snapshot)).isEmpty();
        assertThat(rule(0, List.of(NEEDLE_LINE), other, List.of("line"), List.of("absent"), Pattern.compile("Codex"),
            true).match(snapshot)).isEmpty();
        assertThat(rule(0, List.of(NEEDLE_LINE), other, List.of("line"), List.of("absent"), title, false)
            .match(snapshot)).isEmpty();
    }

    @Test
    void nullSnapshotAndEmptyScreenAreHandled() {
        AgentRule rule = rule(0, List.of(NEEDLE_LINE), null, List.of(), List.of(), null, null);
        assertThat(rule.match(null)).isEmpty();
        assertThat(rule.match(ScreenSnapshot.EMPTY)).isEmpty();

        AgentRule matchesEverything = rule(0, List.of(), null, List.of(), List.of(), null, null);
        Optional<String> evidence = matchesEverything.match(ScreenSnapshot.EMPTY);
        assertThat(evidence).hasValue("r");
    }

    @Test
    void listsAreDefensivelyCopiedAndNullSafe() {
        AgentRule rule = new AgentRule("r", CodingAgentState.IDLE, 1, 0, null, null, null, null, null, null);
        assertThat(rule.anyLineRegex()).isEmpty();
        assertThat(rule.contains()).isEmpty();
        assertThat(rule.notContains()).isEmpty();
    }
}
