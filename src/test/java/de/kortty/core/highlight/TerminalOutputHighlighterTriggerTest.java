package de.kortty.core.highlight;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.core.highlight.HeadlessTerminalSession.ManualScheduler;
import de.kortty.core.highlight.TerminalOutputHighlighter.LineMatch;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The highlight engine's triggers against a real SithTermFX buffer and emulator, without JavaFX: a rule
 * with an action fires for output that arrives after the baseline, once per new hit on a line, never for
 * output that was already there (a restored screen, a set switch, a resize) and never in the alternate
 * screen.
 */
class TerminalOutputHighlighterTriggerTest {

    private ManualScheduler scheduler;

    private final AtomicBoolean alternateAllowed = new AtomicBoolean();

    private final List<LineMatch> fired = new ArrayList<>();

    private TerminalOutputHighlighter highlighter;

    @BeforeMethod
    void setUp() {
        scheduler = new ManualScheduler();
        alternateAllowed.set(false);
        fired.clear();
    }

    @AfterMethod
    void tearDown() {
        if (highlighter != null) {
            highlighter.close();
            highlighter = null;
        }
    }

    /** Attaches without running a pass: the pane's first pass is up to each test. */
    private TerminalOutputHighlighter attach(HeadlessTerminalSession session, CompiledHighlightSet set) {
        highlighter = new TerminalOutputHighlighter(session.buffer, set, () -> { }, () -> { }, () -> false,
            alternateAllowed::get, scheduler, fired::addAll);
        return highlighter;
    }

    /** Attaches and runs the pass the application schedules at once, while the new pane is still empty. */
    private TerminalOutputHighlighter attachEmpty(HeadlessTerminalSession session, CompiledHighlightSet set) {
        attach(session, set);
        highlighter.runPassNow();
        return highlighter;
    }

    private static HighlightRule notify(String pattern) {
        HighlightRule rule = new HighlightRule(pattern, false);
        rule.setIgnoreCase(false);
        rule.setForeground("ansi:1");
        rule.setAction(HighlightRule.Action.NOTIFY);
        rule.setName("Errors");
        return rule;
    }

    private static CompiledHighlightSet set(HighlightRule... rules) {
        return CompiledHighlightSet.compile(new HighlightRuleSet("test.set", "Test", List.of(rules)));
    }

    private static CompiledHighlightSet errors() {
        return set(notify("ERROR"));
    }

    private static int settle(TerminalOutputHighlighter highlighter) {
        for (int passes = 1; passes <= 1_000; passes++) {
            if (!highlighter.runPassNow().backlog()) {
                return passes;
            }
        }
        throw new AssertionError("highlighter never settled");
    }

    private List<String> firedLabels() {
        List<String> labels = new ArrayList<>();
        for (LineMatch match : fired) {
            labels.add(match.rule().label());
        }
        return labels;
    }

    @Test
    void newOutputFiresOnceAndAnUnchangedLineNeverAgain() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        attachEmpty(session, errors());

        session.println("ERROR disk full");
        highlighter.runPassNow();

        assertThat(firedLabels()).containsExactly("Errors");
        assertWithMessage("the matched text only travels when the rule asks for it")
            .that(fired.getFirst().matchedText()).isNull();
        fired.clear();
        highlighter.runPassNow();
        session.println("fine");
        highlighter.runPassNow();
        assertThat(fired).isEmpty();
    }

    @Test
    void aProgressLineThatKeepsItsMatchFiresOnce() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        attachEmpty(session, errors());

        session.print("ERROR count: 1");
        highlighter.runPassNow();
        session.terminal.carriageReturn();
        session.print("ERROR count: 2");
        highlighter.runPassNow();
        session.terminal.carriageReturn();
        session.print("ERROR count: 3");
        highlighter.runPassNow();

        assertThat(fired).hasSize(1);
    }

    @Test
    void aLineWrittenInPiecesFiresWhenTheMatchIsComplete() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        attachEmpty(session, errors());

        session.print("ERR");
        highlighter.runPassNow();
        assertThat(fired).isEmpty();

        session.print("OR: disk full");
        highlighter.runPassNow();
        assertThat(fired).hasSize(1);
    }

    @Test
    void aLineThatIsClearedAndWrittenAgainFiresAgain() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        attachEmpty(session, errors());
        session.print("ERROR first");
        highlighter.runPassNow();
        assertThat(fired).hasSize(1);

        session.terminal.carriageReturn();
        session.terminal.eraseInLine(2);
        highlighter.runPassNow();
        session.print("ERROR second");
        highlighter.runPassNow();

        assertThat(fired).hasSize(2);
    }

    @Test
    void whatThePaneHeldWhenTheHighlighterWasAttachedNeverFires() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        session.println("ERROR from before");
        attach(session, errors());

        settle(highlighter);

        assertThat(fired).isEmpty();
        session.println("ERROR after");
        highlighter.runPassNow();
        assertThat(fired).hasSize(1);
    }

    @Test
    void restoredOutputWrittenBeforeTheBaselineNeverFiresEvenFromTheHistory() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        attachEmpty(session, errors());
        // A project's saved screen, written locally and taller than the pane: part of it scrolls into the
        // history before the first pass.
        for (int i = 0; i < 12; i++) {
            session.println("ERROR restored " + i);
        }
        assertThat(session.historyCount()).isGreaterThan(0);

        highlighter.markBaseline();
        settle(highlighter);

        assertWithMessage("old output, written before markBaseline(), must not fire").that(fired).isEmpty();
        session.println("ERROR live");
        settle(highlighter);
        assertThat(fired).hasSize(1);
    }

    @Test
    void aBaselineRowThatAProgramWritesAnewFires() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        session.print("ERROR old");
        attach(session, errors());

        // Redrawn before the first pass ever looked at the row: its text no longer is the baseline's.
        session.terminal.carriageReturn();
        session.terminal.eraseInLine(2);
        session.print("ERROR redrawn in place");
        highlighter.runPassNow();

        assertThat(fired).hasSize(1);
    }

    @Test
    void anUnchangedBaselineRowKeepsItsHitSoARewriteWithTheSameHitStaysQuiet() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        session.print("ERROR old");
        attach(session, errors());
        highlighter.runPassNow();

        session.terminal.carriageReturn();
        session.print("ERROR old, still the same first hit");
        highlighter.runPassNow();

        assertThat(fired).isEmpty();
    }

    @Test
    void outputThatScrolledIntoTheHistoryUncheckedStillFiresOnce() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        attachEmpty(session, errors());
        session.println("ERROR early in a burst");
        for (int i = 0; i < 20; i++) {
            session.println("line " + i);
        }

        settle(highlighter);

        assertThat(fired).hasSize(1);
    }

    @Test
    void aRuleFiresAtMostOncePerPassHowManyLinesMatch() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 10);
        attachEmpty(session, errors());
        session.println("ERROR one");
        session.println("ERROR two");
        session.println("ERROR three");

        highlighter.runPassNow();
        highlighter.runPassNow();

        assertThat(fired).hasSize(1);
    }

    @Test
    void switchingToASetWithTriggersOnlyColorsWhatIsThere() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        attach(session, CompiledHighlightSet.NONE);
        session.println("ERROR printed while no set was on");

        highlighter.setRuleSet(errors());
        settle(highlighter);

        assertWithMessage("a sweep never fires").that(fired).isEmpty();
        assertThat(HeadlessTerminalSession.isHighlighted(session.line(0), 0)).isTrue();
        session.println("ERROR new");
        highlighter.runPassNow();
        assertThat(fired).hasSize(1);
    }

    @Test
    void aResizeRebuildsLinesWithoutFiringAgain() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        attachEmpty(session, errors());
        for (int i = 0; i < 8; i++) {
            session.println("ERROR " + i + " and some more text so that a narrower pane wraps it");
        }
        settle(highlighter);
        assertThat(fired).hasSize(1);
        fired.clear();

        session.resize(40, 5);
        settle(highlighter);
        session.resize(40, 9);
        settle(highlighter);
        session.resize(80, 3);
        settle(highlighter);

        assertWithMessage("the same output in rebuilt or moved lines is no new output").that(fired).isEmpty();
        session.println("ERROR after the resize");
        settle(highlighter);
        assertThat(fired).hasSize(1);
    }

    @Test
    void theAlternateScreenNeverFiresEvenWhenItIsHighlighted() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        alternateAllowed.set(true);
        attach(session, errors());
        highlighter.runPassNow();
        session.terminal.useAlternateBuffer(true);

        session.print("ERROR in a full-screen program");
        highlighter.runPassNow();

        assertThat(HeadlessTerminalSession.isHighlighted(session.line(0), 0)).isTrue();
        assertThat(fired).isEmpty();
    }

    @Test
    void theMatchedTextTravelsOnlyForARuleThatAsksForIt() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        HighlightRule withText = new HighlightRule("disk \\w+", true);
        withText.setAction(HighlightRule.Action.NOTIFY);
        withText.setNotifyWithText(true);
        withText.setUnderline(true);
        attach(session, set(notify("ERROR"), withText));

        session.println("ERROR: disk full on /var");
        highlighter.runPassNow();

        assertThat(fired).hasSize(2);
        assertWithMessage("matches arrive in rule order").that(fired.get(0).rule().index()).isEqualTo(0);
        assertThat(fired.get(0).matchedText()).isNull();
        assertThat(fired.get(1).matchedText()).isEqualTo("disk full");
        assertWithMessage("a rule without a name is called by its pattern")
            .that(fired.get(1).rule().label()).isEqualTo("disk \\w+");
    }

    @Test
    void aTriggerWithoutALookClaimsNoCharactersAndStillFires() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        HighlightRule quiet = new HighlightRule("ERROR", false);
        quiet.setAction(HighlightRule.Action.NOTIFY);
        quiet.setIgnoreCase(false);
        HighlightRule colored = new HighlightRule("ERROR", false);
        colored.setIgnoreCase(false);
        colored.setBold(true);
        attach(session, set(quiet, colored));

        session.println("ERROR disk full");
        highlighter.runPassNow();

        assertThat(fired).hasSize(1);
        assertWithMessage("the rule below the trigger still colors the word")
            .that(HeadlessTerminalSession.isHighlighted(session.line(0), 0)).isTrue();
    }

    @Test
    void aHighlighterWithoutASinkTracksNothing() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        highlighter = new TerminalOutputHighlighter(session.buffer, errors(), () -> { }, () -> { }, () -> false,
            alternateAllowed::get, scheduler);
        highlighter.markBaseline();
        session.println("ERROR disk full");

        highlighter.runPassNow();

        assertThat(fired).isEmpty();
        assertThat(HeadlessTerminalSession.isHighlighted(session.line(0), 0)).isTrue();
    }

    @Test
    void hitKeysTellPlaceAndTextApartAndAreNeverZero() {
        assertThat(TerminalOutputHighlighter.hitKey(0, "ERROR")).isNotEqualTo(0L);
        assertThat(TerminalOutputHighlighter.hitKey(0, "ERROR"))
            .isEqualTo(TerminalOutputHighlighter.hitKey(0, "ERROR"));
        assertThat(TerminalOutputHighlighter.hitKey(0, "ERROR"))
            .isNotEqualTo(TerminalOutputHighlighter.hitKey(4, "ERROR"));
        assertThat(TerminalOutputHighlighter.hitKey(0, "ERROR"))
            .isNotEqualTo(TerminalOutputHighlighter.hitKey(0, "FATAL"));
    }
}
