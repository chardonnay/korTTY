package de.kortty.paste;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

class PasteDecisionTest {

    private static final String ESC = "\u001b";

    private static final String ONE_LINE = "ls -la";

    private static final String TRAILING_NEWLINE = "ls -la\n";

    private static final String MULTI_LINE = "cd /tmp\r\nls -la";

    private static final String CONTROL = "sleep 9\u0003";

    private static final String BIDI = "cat \u202etxt.sh";

    private static final String ESCAPE_SEQUENCE = "printf '" + ESC + "[31m'";

    /** 6 KiB on one line: larger than the 5 KiB default. */
    private static final String LARGE = "x".repeat(6 * 1024);

    private static PasteDecision decision(PasteWarningMode mode, int largeWarningKiB) {
        return new PasteDecision(new PasteProtectionSettings(mode, largeWarningKiB));
    }

    /** A paste and what it holds. */
    private record Sample(String name, String text, boolean lineBreak, boolean controls, boolean large) {
    }

    @Test
    void theMatrixOfModesBracketingAndPastes() {
        List<Sample> samples = List.of(
            new Sample("one line", ONE_LINE, false, false, false),
            new Sample("trailing newline", TRAILING_NEWLINE, true, false, false),
            new Sample("multi-line", MULTI_LINE, true, false, false),
            new Sample("control characters", CONTROL, false, true, false),
            new Sample("bidi control", BIDI, false, true, false),
            new Sample("escape sequence", ESCAPE_SEQUENCE, false, true, false),
            new Sample("large", LARGE, false, false, true),
            new Sample("large multi-line with controls", LARGE + "\n" + CONTROL, true, true, true));
        for (PasteWarningMode mode : PasteWarningMode.values()) {
            PasteDecision rules = decision(mode, 5);
            for (boolean bracketed : new boolean[] {false, true}) {
                for (Sample sample : samples) {
                    Set<PasteReason> expected = EnumSet.noneOf(PasteReason.class);
                    if (sample.lineBreak() && (mode == PasteWarningMode.ALWAYS
                            || (mode == PasteWarningMode.UNLESS_BRACKETED && !bracketed))) {
                        expected.add(PasteReason.MULTI_LINE);
                    }
                    if (sample.controls() && mode != PasteWarningMode.OFF) {
                        expected.add(PasteReason.CONTROL_CHARACTERS);
                    }
                    if (sample.large()) {
                        expected.add(PasteReason.LARGE);
                    }
                    assertWithMessage("%s, %s, %s", mode, bracketed ? "bracketed" : "not bracketed", sample.name())
                        .that(rules.reasons(sample.text(), bracketed))
                        .containsExactlyElementsIn(expected)
                        .inOrder();
                }
            }
        }
    }

    @Test
    void aSingleLineNeverAsks() {
        for (PasteWarningMode mode : PasteWarningMode.values()) {
            for (boolean bracketed : new boolean[] {false, true}) {
                assertThat(decision(mode, 5).reasons(ONE_LINE, bracketed)).isEmpty();
            }
        }
    }

    @Test
    void unlessBracketedAsksAboutLineBreaksOnlyWhenThePaneDoesNotBracket() {
        PasteDecision rules = decision(PasteWarningMode.UNLESS_BRACKETED, 5);
        assertThat(rules.reasons(TRAILING_NEWLINE, false)).containsExactly(PasteReason.MULTI_LINE);
        assertThat(rules.reasons(MULTI_LINE, false)).containsExactly(PasteReason.MULTI_LINE);
        assertThat(rules.reasons("a\rb", false)).containsExactly(PasteReason.MULTI_LINE);
        assertThat(rules.reasons(TRAILING_NEWLINE, true)).isEmpty();
        assertThat(rules.reasons(MULTI_LINE, true)).isEmpty();
    }

    @Test
    void alwaysAsksAboutLineBreaksEvenWhenThePaneBrackets() {
        PasteDecision rules = decision(PasteWarningMode.ALWAYS, 5);
        assertThat(rules.reasons(TRAILING_NEWLINE, true)).containsExactly(PasteReason.MULTI_LINE);
        assertThat(rules.reasons(MULTI_LINE, true)).containsExactly(PasteReason.MULTI_LINE);
        assertThat(rules.reasons(MULTI_LINE, false)).containsExactly(PasteReason.MULTI_LINE);
    }

    @Test
    void controlCharactersAskBracketedOrNotUnlessTheModeIsOff() {
        for (PasteWarningMode mode : List.of(PasteWarningMode.UNLESS_BRACKETED, PasteWarningMode.ALWAYS)) {
            for (boolean bracketed : new boolean[] {false, true}) {
                for (String text : List.of(CONTROL, BIDI, ESCAPE_SEQUENCE, "x\u007f", "x\u0085")) {
                    assertWithMessage("%s %s %s", mode, bracketed, text)
                        .that(decision(mode, 5).reasons(text, bracketed))
                        .containsExactly(PasteReason.CONTROL_CHARACTERS);
                }
            }
        }
        assertThat(decision(PasteWarningMode.OFF, 5).reasons(CONTROL, false)).isEmpty();
        assertThat(decision(PasteWarningMode.OFF, 5).reasons(BIDI, true)).isEmpty();
    }

    @Test
    void anEmbeddedBracketMarkerAsksAsAControlCharacter() {
        String breakout = "echo safe" + ESC + "[201~";
        assertThat(decision(PasteWarningMode.UNLESS_BRACKETED, 5).reasons(breakout, true))
            .containsExactly(PasteReason.CONTROL_CHARACTERS);
    }

    @Test
    void tabsAndZeroWidthJoinersDoNotAsk() {
        PasteDecision rules = decision(PasteWarningMode.ALWAYS, 5);
        assertThat(rules.reasons("a\tb", false)).isEmpty();
        assertThat(rules.reasons("\ud83d\udc68\u200d\ud83d\udc69", false)).isEmpty();
    }

    @Test
    void theSizeCheckIgnoresModeAndBracketing() {
        for (PasteWarningMode mode : PasteWarningMode.values()) {
            for (boolean bracketed : new boolean[] {false, true}) {
                assertWithMessage("%s %s", mode, bracketed).that(decision(mode, 5).reasons(LARGE, bracketed))
                    .containsExactly(PasteReason.LARGE);
            }
        }
    }

    @Test
    void theThresholdIsExclusiveAndCountedInUtf8() {
        PasteDecision rules = decision(PasteWarningMode.OFF, 5);
        assertThat(rules.reasons("x".repeat(5 * 1024), false)).isEmpty();
        assertThat(rules.reasons("x".repeat(5 * 1024 + 1), false)).containsExactly(PasteReason.LARGE);
        // 2,561 umlauts are 5,122 bytes: large, although shorter than 5 KiB in characters.
        assertThat(rules.reasons("\u00e4".repeat(2_561), false)).containsExactly(PasteReason.LARGE);
        assertThat(rules.reasons("\u00e4".repeat(2_560), false)).isEmpty();
    }

    @Test
    void aZeroThresholdTurnsTheSizeCheckOff() {
        for (PasteWarningMode mode : PasteWarningMode.values()) {
            assertThat(decision(mode, 0).reasons(LARGE, false)).doesNotContain(PasteReason.LARGE);
        }
        assertThat(decision(PasteWarningMode.OFF, 0).reasons("x".repeat(20 * 1024 * 1024), false)).isEmpty();
    }

    @Test
    void everyReasonAtOnceComesInTheEnumsOrder() {
        String all = LARGE + "\n" + CONTROL;
        assertThat(decision(PasteWarningMode.UNLESS_BRACKETED, 5).reasons(all, false))
            .containsExactly(PasteReason.MULTI_LINE, PasteReason.CONTROL_CHARACTERS, PasteReason.LARGE)
            .inOrder();
        assertThat(decision(PasteWarningMode.UNLESS_BRACKETED, 5).reasons(all, true))
            .containsExactly(PasteReason.CONTROL_CHARACTERS, PasteReason.LARGE)
            .inOrder();
    }

    @Test
    void disabledProtectionNeverAsks() {
        PasteDecision rules = new PasteDecision(PasteProtectionSettings.DISABLED);
        for (String text : List.of(ONE_LINE, TRAILING_NEWLINE, MULTI_LINE, CONTROL, BIDI, LARGE)) {
            assertThat(rules.reasons(text, false)).isEmpty();
            assertThat(rules.reasons(text, true)).isEmpty();
        }
    }

    @Test
    void nullAndEmptyTextNeverAsk() {
        PasteDecision rules = decision(PasteWarningMode.ALWAYS, 1);
        assertThat(rules.reasons(null, false)).isEmpty();
        assertThat(rules.reasons("", false)).isEmpty();
    }

    @Test
    void missingSettingsMeanTheDefaults() {
        PasteDecision rules = new PasteDecision(null);
        assertThat(rules.settings()).isEqualTo(PasteProtectionSettings.DEFAULTS);
        assertThat(rules.reasons(MULTI_LINE, false)).containsExactly(PasteReason.MULTI_LINE);
        assertThat(rules.reasons(MULTI_LINE, true)).isEmpty();
    }

    @Test
    void aMeasuredPasteGivesTheSameReasons() {
        PasteDecision rules = decision(PasteWarningMode.UNLESS_BRACKETED, 5);
        for (String text : List.of(ONE_LINE, TRAILING_NEWLINE, MULTI_LINE, CONTROL, BIDI, LARGE)) {
            for (boolean bracketed : new boolean[] {false, true}) {
                assertThat(rules.reasonsFor(PasteInspection.of(text), bracketed))
                    .isEqualTo(rules.reasons(text, bracketed));
            }
        }
    }

    @Test
    void theReasonsCannotBeChanged() {
        Set<PasteReason> reasons = decision(PasteWarningMode.ALWAYS, 5).reasons(MULTI_LINE, false);
        try {
            reasons.add(PasteReason.LARGE);
            throw new AssertionError("the reasons were modifiable");
        } catch (UnsupportedOperationException expected) {
            assertThat(reasons).containsExactly(PasteReason.MULTI_LINE);
        }
    }

    @Test
    void theGuardAsksWhenTheDecisionSaysSo() {
        List<PasteConfirmationRequest> asked = new ArrayList<>();
        PasteGuard guard = new PasteGuard(() -> decision(PasteWarningMode.UNLESS_BRACKETED, 5),
            (request, answer) -> asked.add(request));
        RecordingTarget target = new RecordingTarget();
        guard.paste(target, ONE_LINE, PasteSource.CLIPBOARD);
        assertThat(asked).isEmpty();
        assertThat(target.sent).containsExactly(ONE_LINE);

        guard.paste(target, MULTI_LINE, PasteSource.CLIPBOARD);
        assertThat(asked).hasSize(1);
        assertThat(asked.get(0).reasons()).containsExactly(PasteReason.MULTI_LINE);
        assertThat(target.sent).containsExactly(ONE_LINE);
    }

    /** A pane that records what it receives. */
    private static final class RecordingTarget implements PasteTarget {
        final Object key = new Object();
        final Object session = new Object();
        final List<String> sent = new ArrayList<>();

        @Override
        public Object key() {
            return key;
        }

        @Override
        public boolean bracketedPasteMode() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }

        @Override
        public Object session() {
            return session;
        }

        @Override
        public void send(String payload) {
            sent.add(payload);
        }

        @Override
        public String label() {
            return "demo";
        }
    }
}
