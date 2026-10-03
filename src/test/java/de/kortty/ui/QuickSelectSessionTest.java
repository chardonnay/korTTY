package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import de.kortty.core.QuickSelectLabels;
import de.kortty.core.TerminalLinkDetector.Kind;
import de.kortty.ui.QuickSelectScreen.Hit;
import de.kortty.ui.QuickSelectSession.Action;
import de.kortty.ui.QuickSelectSession.Outcome;
import de.kortty.ui.QuickSelectSession.Target;
import de.kortty.ui.SceneShortcutRouter.KeyPress;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Quick select's key handling, toolkit-free: labels go bottom row first, a label copies and Shift
 * with it opens, letters narrow and Backspace widens again, modifier keys and the starting chord
 * change nothing, and Escape and any other key cancel. The decision uses key codes and the Shift
 * key only, so Caps Lock and the keyboard layout's characters never matter.
 */
public class QuickSelectSessionTest {

    private static final KeyCombination TRIGGER =
        new KeyCodeCombination(KeyCode.SPACE, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);

    /** Three matches on three rows: the bottom one gets "a", the top one "d". */
    private static final List<Hit> THREE = List.of(
        hit(Kind.URL, "https://example.com", 0, 0),
        hit(Kind.IPV4, "10.0.0.1", 0, 1),
        hit(Kind.NUMBER, "4711", 0, 2));

    @Test
    public void theBottomRowGetsTheFirstLabelsAndARowGoesLeftToRight() {
        QuickSelectSession session = QuickSelectSession.of(List.of(
            hit(Kind.URL, "https://top.example", 0, 0),
            hit(Kind.NUMBER, "1234", 20, 3),
            hit(Kind.NUMBER, "5678", 2, 3)), QuickSelectLabels.DEFAULT_ALPHABET, TRIGGER);

        assertThat(session.targets().stream().map(t -> t.label() + "=" + t.text()).toList())
            .containsExactly("a=5678", "s=1234", "d=https://top.example").inOrder();
    }

    @Test
    public void theSameTextShownTwiceSharesOneLabel() {
        QuickSelectSession session = QuickSelectSession.of(List.of(
            hit(Kind.IPV4, "10.0.0.1", 0, 0),
            hit(Kind.NUMBER, "4711", 0, 1),
            hit(Kind.IPV4, "10.0.0.1", 0, 2)), QuickSelectLabels.DEFAULT_ALPHABET, TRIGGER);

        List<Target> targets = session.targets();
        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).label()).isEqualTo("a");
        assertThat(targets.get(0).text()).isEqualTo("10.0.0.1");
        assertThat(targets.get(0).hits()).hasSize(2);
        assertThat(targets.get(1).label()).isEqualTo("s");
    }

    @Test
    public void aLabelCopiesAndShiftWithItOpens() {
        Outcome copy = session().onKey(press(KeyCode.S, false));
        assertThat(copy.action()).isEqualTo(Action.COPY);
        assertThat(copy.target().text()).isEqualTo("10.0.0.1");

        Outcome open = session().onKey(press(KeyCode.D, true));
        assertThat(open.action()).isEqualTo(Action.OPEN);
        assertThat(open.target().text()).isEqualTo("https://example.com");
    }

    @Test
    public void capsLockNeverTurnsACopyIntoAnOpen() {
        QuickSelectSession session = session();

        // Caps Lock itself changes nothing; with it on, a letter still arrives without Shift.
        assertThat(session.onKey(press(KeyCode.CAPS, false)).action()).isEqualTo(Action.IGNORE);
        KeyPress upperCaseA = new KeyPress(KeyCode.A, "A", KeyEvent.CHAR_UNDEFINED, false, false, false, false, false);

        assertThat(session.onKey(upperCaseA).action()).isEqualTo(Action.COPY);
    }

    @Test
    public void modifierKeysOnTheirOwnAndTheStartingChordChangeNothing() {
        QuickSelectSession session = session();

        for (KeyCode modifier : List.of(KeyCode.SHIFT, KeyCode.CONTROL, KeyCode.ALT, KeyCode.META, KeyCode.COMMAND,
                KeyCode.ALT_GRAPH, KeyCode.WINDOWS)) {
            assertThat(session.onKey(press(modifier, false)).action()).isEqualTo(Action.IGNORE);
        }
        // Held down, the chord repeats: Ctrl+Shift+Space on Windows and Linux, Cmd+Shift+Space on macOS.
        assertThat(session.onKey(new KeyPress(KeyCode.SPACE, " ", KeyEvent.CHAR_UNDEFINED, true, true, false, false,
            false)).action()).isEqualTo(Action.IGNORE);
        assertThat(session.onKey(new KeyPress(KeyCode.SPACE, " ", KeyEvent.CHAR_UNDEFINED, true, false, false, true,
            true)).action()).isEqualTo(Action.IGNORE);
        assertThat(session.prefix()).isEmpty();
        assertThat(session.targets()).hasSize(3);
    }

    @Test
    public void escapeAndEveryOtherKeyCancel() {
        for (KeyCode key : List.of(KeyCode.ESCAPE, KeyCode.SPACE, KeyCode.ENTER, KeyCode.DIGIT1, KeyCode.TAB,
                KeyCode.UP, KeyCode.F5, KeyCode.PERIOD)) {
            assertThat(session().onKey(press(key, false)).action()).isEqualTo(Action.CANCEL);
        }
        // A letter with Ctrl, Alt or Cmd is a shortcut, not a label (AltGr is reported as Ctrl+Alt).
        assertThat(session().onKey(new KeyPress(KeyCode.A, "a", KeyEvent.CHAR_UNDEFINED, false, true, false, false,
            false)).action()).isEqualTo(Action.CANCEL);
        assertThat(session().onKey(new KeyPress(KeyCode.A, "a", KeyEvent.CHAR_UNDEFINED, false, true, true, false,
            false)).action()).isEqualTo(Action.CANCEL);
        assertThat(session().onKey(new KeyPress(KeyCode.A, "a", KeyEvent.CHAR_UNDEFINED, false, false, false, true,
            true)).action()).isEqualTo(Action.CANCEL);
        // The starting chord without its Shift is another key.
        assertThat(session().onKey(new KeyPress(KeyCode.SPACE, " ", KeyEvent.CHAR_UNDEFINED, false, true, false,
            false, false)).action()).isEqualTo(Action.CANCEL);
    }

    @Test
    public void aLetterNoLabelStartsWithIsIgnored() {
        QuickSelectSession session = session();

        Outcome outcome = session.onKey(press(KeyCode.Z, false));

        assertThat(outcome.action()).isEqualTo(Action.CONTINUE);
        assertThat(session.prefix()).isEmpty();
        assertThat(session.onKey(press(KeyCode.A, false)).action()).isEqualTo(Action.COPY);
    }

    @Test
    public void twoLetterLabelsNarrowOnTheFirstLetterAndBackspaceWidensAgain() {
        List<Hit> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(hit(Kind.NUMBER, String.valueOf(1000 + i), 0, i));
        }
        QuickSelectSession session = QuickSelectSession.of(many, QuickSelectLabels.DEFAULT_ALPHABET, TRIGGER);
        Target aa = session.targets().get(0);
        Target sa = session.targets().get(26);
        assertThat(aa.label()).isEqualTo("aa");
        assertThat(sa.label()).isEqualTo("sa");

        assertThat(session.onKey(press(KeyCode.S, false)).action()).isEqualTo(Action.CONTINUE);
        assertThat(session.prefix()).isEqualTo("s");
        assertThat(session.shows(sa)).isTrue();
        assertThat(session.shows(aa)).isFalse();

        assertThat(session.onKey(press(KeyCode.BACK_SPACE, false)).action()).isEqualTo(Action.CONTINUE);
        assertThat(session.prefix()).isEmpty();
        assertThat(session.shows(aa)).isTrue();
        // Backspace with nothing typed changes nothing and keeps quick select open.
        assertThat(session.onKey(press(KeyCode.BACK_SPACE, false)).action()).isEqualTo(Action.CONTINUE);

        assertThat(session.onKey(press(KeyCode.S, false)).action()).isEqualTo(Action.CONTINUE);
        Outcome copy = session.onKey(press(KeyCode.A, false));
        assertThat(copy.action()).isEqualTo(Action.COPY);
        assertThat(copy.target()).isEqualTo(sa);

        // Shift on the last letter decides; Shift on the first only narrows.
        QuickSelectSession again = QuickSelectSession.of(many, QuickSelectLabels.DEFAULT_ALPHABET, TRIGGER);
        assertThat(again.onKey(press(KeyCode.A, true)).action()).isEqualTo(Action.CONTINUE);
        assertThat(again.onKey(press(KeyCode.S, true)).action()).isEqualTo(Action.OPEN);
    }

    @Test
    public void matchesThePrintedOverAreDroppedAndNothingLeftEndsTheSession() {
        QuickSelectSession session = session();

        assertThat(session.retainHits(hit -> !hit.text().equals("4711"))).isTrue();
        assertThat(session.targets().stream().map(Target::label).toList()).containsExactly("s", "d").inOrder();
        // The dropped label no longer picks anything; the others keep theirs.
        assertThat(session.onKey(press(KeyCode.A, false)).action()).isEqualTo(Action.CONTINUE);
        assertThat(session.onKey(press(KeyCode.D, false)).target().text()).isEqualTo("https://example.com");

        assertThat(session().retainHits(hit -> false)).isFalse();
    }

    @Test
    public void aTargetKeepsThePlacesThatStillShowIt() {
        QuickSelectSession session = QuickSelectSession.of(List.of(
            hit(Kind.IPV4, "10.0.0.1", 0, 0),
            hit(Kind.IPV4, "10.0.0.1", 0, 2)), QuickSelectLabels.DEFAULT_ALPHABET, TRIGGER);

        assertThat(session.retainHits(hit -> hit.start().y == 0)).isTrue();

        assertThat(session.targets()).hasSize(1);
        assertThat(session.targets().get(0).hits()).containsExactly(hit(Kind.IPV4, "10.0.0.1", 0, 0));
    }

    private static QuickSelectSession session() {
        return QuickSelectSession.of(THREE, QuickSelectLabels.DEFAULT_ALPHABET, TRIGGER);
    }

    private static Hit hit(Kind kind, String text, int column, int line) {
        return new Hit(kind, text, new Point(column, line), new Point(column + text.length() - 1, line));
    }

    private static KeyPress press(KeyCode code, boolean shift) {
        String text = code.isLetterKey() ? code.getChar().toLowerCase() : "";
        return new KeyPress(code, text, KeyEvent.CHAR_UNDEFINED, shift, false, false, false, false);
    }
}
