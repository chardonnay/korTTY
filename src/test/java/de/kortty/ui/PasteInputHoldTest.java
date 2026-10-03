package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.paste.PastePacer;
import de.kortty.paste.PasteTarget;
import java.util.ArrayList;
import java.util.List;
import javafx.event.EventType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * A pane that is pacing a paste takes no keys: they are consumed before the terminal sees them, Esc
 * stops the paste, and Cmd shortcuts still work. Real key events, no JavaFX toolkit.
 */
class PasteInputHoldTest {

    /** Keeps the scheduled lines until the test runs them. */
    private static final class QueuedScheduler implements PastePacer.Scheduler {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public PastePacer.Cancellable schedule(Runnable task, long delayMs) {
            tasks.add(task);
            return () -> tasks.remove(task);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove(0).run();
            }
        }
    }

    private static final class Pane implements PasteTarget {
        final List<String> sent = new ArrayList<>();
        final Object session = new Object();

        @Override
        public Object key() {
            return this;
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
            return "console-01";
        }
    }

    private QueuedScheduler scheduler;

    private PastePacer pacer;

    private PasteInputHold hold;

    /** TestNG shares one instance between the tests, so each test starts from a fresh pacer. */
    @BeforeMethod
    void freshPacer() {
        scheduler = new QueuedScheduler();
        pacer = new PastePacer(scheduler);
        hold = new PasteInputHold(pacer);
    }

    private static KeyEvent pressed(KeyCode code) {
        return key(KeyEvent.KEY_PRESSED, "", code, false);
    }

    private static KeyEvent typed(String character) {
        return key(KeyEvent.KEY_TYPED, character, KeyCode.UNDEFINED, false);
    }

    private static KeyEvent key(EventType<KeyEvent> type, String character, KeyCode code, boolean meta) {
        return new KeyEvent(type, character, "", code, false, false, false, meta);
    }

    /** Starts a paced paste of three lines into {@code pane}; its first line is out at once. */
    private void pace(Pane pane) {
        pacer.send(pane, "one\rtwo\rthree", 200);
        assertThat(pacer.isPacing(pane)).isTrue();
    }

    @Test
    void keysPassWhileNoPasteIsPaced() {
        Pane pane = new Pane();
        KeyEvent press = pressed(KeyCode.A);
        KeyEvent type = typed("a");

        assertThat(hold.filter(pane, press)).isFalse();
        assertThat(hold.filter(pane, type)).isFalse();

        assertThat(press.isConsumed()).isFalse();
        assertThat(type.isConsumed()).isFalse();
        assertThat(hold.isIdle()).isTrue();
    }

    @Test
    void whileAPasteIsPacedThePaneTakesNoKeys() {
        Pane pane = new Pane();
        pace(pane);

        for (KeyCode code : new KeyCode[] {KeyCode.A, KeyCode.ENTER, KeyCode.UP, KeyCode.TAB, KeyCode.C}) {
            KeyEvent press = pressed(code);
            assertThat(hold.filter(pane, press)).isTrue();
            assertThat(press.isConsumed()).isTrue();
        }
        KeyEvent ctrlC = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, false, true, false, false);
        assertThat(hold.filter(pane, ctrlC)).isTrue();
        KeyEvent type = typed("x");
        assertThat(hold.filter(pane, type)).isTrue();
        assertThat(type.isConsumed()).isTrue();

        // The paste itself goes on.
        assertThat(pacer.isPacing(pane)).isTrue();
        scheduler.runAll();
        assertThat(pane.sent).containsExactly("one\r", "two\r", "three").inOrder();
    }

    @Test
    void escStopsThePasteAndIsNotSentToThePane() {
        Pane pane = new Pane();
        pace(pane);

        KeyEvent esc = pressed(KeyCode.ESCAPE);
        assertThat(hold.filter(pane, esc)).isTrue();

        assertThat(esc.isConsumed()).isTrue();
        assertThat(pacer.isPacing(pane)).isFalse();
        scheduler.runAll();
        assertThat(pane.sent).containsExactly("one\r");
        // The character of the Esc press is held too, although the paste is over now.
        KeyEvent escTyped = typed("\u001b");
        assertThat(hold.filter(pane, escTyped)).isTrue();
        assertThat(escTyped.isConsumed()).isTrue();
    }

    @Test
    void cmdShortcutsStillWork() {
        Pane pane = new Pane();
        pace(pane);

        KeyEvent cmdW = key(KeyEvent.KEY_PRESSED, "", KeyCode.W, true);
        KeyEvent cmdWTyped = key(KeyEvent.KEY_TYPED, "w", KeyCode.UNDEFINED, true);
        assertThat(hold.filter(pane, cmdW)).isFalse();
        assertThat(hold.filter(pane, cmdWTyped)).isFalse();

        assertThat(cmdW.isConsumed()).isFalse();
        assertThat(cmdWTyped.isConsumed()).isFalse();
        assertThat(PasteInputHold.action(cmdW)).isEqualTo(PasteInputHold.Action.PASS);
        assertThat(pacer.isPacing(pane)).isTrue();
    }

    @Test
    void theActionOfAKeyPress() {
        assertThat(PasteInputHold.action(pressed(KeyCode.ESCAPE))).isEqualTo(PasteInputHold.Action.CANCEL);
        assertThat(PasteInputHold.action(pressed(KeyCode.A))).isEqualTo(PasteInputHold.Action.HOLD);
        assertThat(PasteInputHold.action(pressed(KeyCode.ENTER))).isEqualTo(PasteInputHold.Action.HOLD);
        assertThat(PasteInputHold.action(key(KeyEvent.KEY_PRESSED, "", KeyCode.V, true)))
            .isEqualTo(PasteInputHold.Action.PASS);
    }

    @Test
    void aKeyPressedDuringThePasteNeverTypesItsCharacterAfterwards() {
        Pane pane = new Pane();
        pace(pane);
        assertThat(hold.filter(pane, pressed(KeyCode.X))).isTrue();

        // The last line goes out between the key press and its character.
        scheduler.runAll();
        assertThat(pacer.isPacing(pane)).isFalse();
        assertThat(hold.isIdle()).isFalse();

        KeyEvent residue = typed("x");
        assertThat(hold.filter(pane, residue)).isTrue();
        assertThat(residue.isConsumed()).isTrue();
        assertThat(hold.isIdle()).isTrue();

        // The next key is the user's again.
        KeyEvent nextPress = pressed(KeyCode.Y);
        KeyEvent nextType = typed("y");
        assertThat(hold.filter(pane, nextPress)).isFalse();
        assertThat(hold.filter(pane, nextType)).isFalse();
    }

    @Test
    void aHeldKeyWithoutCharacterHoldsNothingAfterTheNextPress() {
        Pane pane = new Pane();
        pace(pane);
        assertThat(hold.filter(pane, pressed(KeyCode.UP))).isTrue();
        scheduler.runAll();

        // The arrow key had no character; the next key press clears what was left of it.
        assertThat(hold.filter(pane, pressed(KeyCode.A))).isFalse();
        assertThat(hold.filter(pane, typed("a"))).isFalse();
    }

    @Test
    void keyReleasesPass() {
        Pane pane = new Pane();
        pace(pane);

        KeyEvent release = key(KeyEvent.KEY_RELEASED, "", KeyCode.A, false);
        assertThat(hold.filter(pane, release)).isFalse();
        assertThat(release.isConsumed()).isFalse();
    }

    @Test
    void anotherPaneKeepsItsKeys() {
        Pane pacing = new Pane();
        Pane other = new Pane();
        pace(pacing);

        KeyEvent press = pressed(KeyCode.A);
        KeyEvent type = typed("a");
        assertThat(hold.filter(other, press)).isFalse();
        assertThat(hold.filter(other, type)).isFalse();
        assertThat(press.isConsumed()).isFalse();

        KeyEvent esc = pressed(KeyCode.ESCAPE);
        assertThat(hold.filter(other, esc)).isFalse();
        assertThat(pacer.isPacing(pacing)).isTrue();
    }

    @Test
    void keysForNoPaneOrAlreadyConsumedPass() {
        Pane pane = new Pane();
        pace(pane);

        assertThat(hold.filter(null, pressed(KeyCode.A))).isFalse();
        KeyEvent consumed = pressed(KeyCode.ESCAPE);
        consumed.consume();
        assertThat(hold.filter(pane, consumed)).isFalse();
        assertThat(pacer.isPacing(pane)).isTrue();
        assertThat(hold.filter(pane, null)).isFalse();
    }

    @Test
    void aClosedPaneIsForgotten() {
        Pane pane = new Pane();
        pace(pane);
        hold.filter(pane, pressed(KeyCode.A));
        pacer.cancel(pane);

        hold.release(pane);

        assertThat(hold.isIdle()).isTrue();
        hold.release(null);
    }
}
