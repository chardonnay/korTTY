package de.kortty.ui;

import de.kortty.ui.KeyTypedResidueGuard.Residue;
import de.kortty.ui.SceneShortcutRouter.KeyPress;
import javafx.scene.input.KeyCharacterCombination;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * The main window's scene shortcut router: ordered first-match routing, observers of presses and
 * releases that do not consume, the residue swallow of consumed chords, and the guard reset at the top of every
 * KEY_PRESSED (zoom used to reset its flag only after the menu-bar and fullscreen chords had
 * returned). Toolkit-free: key events are built directly and {@link KeyPress#matches} never asks the
 * toolkit for the platform's shortcut key.
 */
class SceneShortcutRouterTest {

    private static final KeyCombination SHORTCUT_SHIFT_L =
        new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCombination SHORTCUT_V = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);

    @Test
    void shortcutIsCmdOnMacOsAndCtrlElsewhere() {
        assertThat(press(KeyCode.L, "", true, false, false, true, true).matches(SHORTCUT_SHIFT_L)).isTrue();
        assertThat(press(KeyCode.L, "", true, true, false, false, true).matches(SHORTCUT_SHIFT_L)).isFalse();
        assertThat(press(KeyCode.L, "", true, true, false, false, false).matches(SHORTCUT_SHIFT_L)).isTrue();
        assertThat(press(KeyCode.L, "", true, false, false, true, false).matches(SHORTCUT_SHIFT_L)).isFalse();
    }

    @Test
    void modifiersTheCombinationLeavesUpMustBeUp() {
        // Cmd+Ctrl+Shift+L on macOS, Ctrl+Alt+Shift+L (AltGr+Shift+L on Windows) elsewhere.
        assertThat(press(KeyCode.L, "", true, true, false, true, true).matches(SHORTCUT_SHIFT_L)).isFalse();
        assertThat(press(KeyCode.L, "", true, true, true, false, false).matches(SHORTCUT_SHIFT_L)).isFalse();
        // Shift missing, or another key.
        assertThat(press(KeyCode.L, "", false, true, false, false, false).matches(SHORTCUT_SHIFT_L)).isFalse();
        assertThat(press(KeyCode.K, "", true, true, false, false, false).matches(SHORTCUT_SHIFT_L)).isFalse();
        assertThat(press(KeyCode.V, "", false, true, false, false, false).matches(SHORTCUT_V)).isTrue();
        assertThat(press(KeyCode.V, "", true, true, false, false, false).matches(SHORTCUT_V)).isFalse();
    }

    @Test
    void explicitControlAndAnyModifiersFollowKeyCombination() {
        KeyCombination ctrlTab = new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN);
        KeyCombination shortcutAnyShiftT =
            new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_ANY);

        // CONTROL_DOWN means the Ctrl key on macOS too, and then Cmd must be up.
        assertThat(press(KeyCode.TAB, "", false, true, false, false, true).matches(ctrlTab)).isTrue();
        assertThat(press(KeyCode.TAB, "", false, true, false, true, true).matches(ctrlTab)).isFalse();
        assertThat(press(KeyCode.T, "", true, false, false, true, true).matches(shortcutAnyShiftT)).isTrue();
        assertThat(press(KeyCode.T, "", false, false, false, true, true).matches(shortcutAnyShiftT)).isTrue();
    }

    @Test
    void onlyKeyCodeCombinationsCanBeRouted() {
        KeyCombination byCharacter = new KeyCharacterCombination("+", KeyCombination.SHORTCUT_DOWN);

        assertThrows(IllegalArgumentException.class,
            () -> press(KeyCode.PLUS, "+", false, true, false, false, false).matches(byCharacter));
    }

    @Test
    void theFirstMatchingEntryWinsAndConsumes() {
        List<String> ran = new ArrayList<>();
        SceneShortcutRouter router = new SceneShortcutRouter(false)
            .consume(p -> p.code() == KeyCode.A, SceneShortcutRouter.ALWAYS, () -> ran.add("first"), Residue.NONE)
            .consume(p -> p.code() == KeyCode.A, SceneShortcutRouter.ALWAYS, () -> ran.add("second"), Residue.NONE);

        KeyEvent event = keyPressed(KeyCode.A, "a", false, true, false, false);
        router.onKeyPressed(event);

        assertThat(ran).containsExactly("first");
        assertThat(event.isConsumed()).isTrue();
    }

    @Test
    void anEntryOutOfScopeLetsTheKeyThrough() {
        List<String> ran = new ArrayList<>();
        SceneShortcutRouter router = new SceneShortcutRouter(false)
            .consume(p -> p.code() == KeyCode.A, () -> false, () -> ran.add("out of scope"), Residue.anyCharacter())
            .consume(p -> p.code() == KeyCode.B, SceneShortcutRouter.ALWAYS, () -> ran.add("other key"), Residue.NONE);

        KeyEvent event = keyPressed(KeyCode.A, "a", false, true, false, false);
        router.onKeyPressed(event);
        KeyEvent typed = keyTyped("a");
        router.onKeyTyped(typed);

        assertThat(ran).isEmpty();
        assertThat(event.isConsumed()).isFalse();
        assertThat(typed.isConsumed()).isFalse();
    }

    @Test
    void anObserverRunsWithoutConsumingAndLaterEntriesStillRun() {
        List<String> ran = new ArrayList<>();
        SceneShortcutRouter router = new SceneShortcutRouter(true)
            .observe(p -> p.matches(SHORTCUT_V), SceneShortcutRouter.ALWAYS, () -> ran.add("observer"))
            .consume(p -> p.code() == KeyCode.V, SceneShortcutRouter.ALWAYS, () -> ran.add("consumer"), Residue.NONE);

        KeyEvent event = keyPressed(KeyCode.V, "v", false, false, false, true);
        router.onKeyPressed(event);

        assertThat(ran).containsExactly("observer", "consumer").inOrder();
        assertThat(event.isConsumed()).isTrue();

        SceneShortcutRouter observerOnly = new SceneShortcutRouter(true)
            .observe(p -> p.matches(SHORTCUT_V), SceneShortcutRouter.ALWAYS, () -> ran.add("observer"));
        KeyEvent paste = keyPressed(KeyCode.V, "v", false, false, false, true);
        observerOnly.onKeyPressed(paste);
        KeyEvent typed = keyTyped("v");
        observerOnly.onKeyTyped(typed);

        assertThat(paste.isConsumed()).isFalse();
        assertThat(typed.isConsumed()).isFalse();
    }

    @Test
    void aConsumedChordsResidueIsSwallowedOnce() {
        SceneShortcutRouter router = new SceneShortcutRouter(false)
            .consume(p -> p.matches(SHORTCUT_SHIFT_L), SceneShortcutRouter.ALWAYS, () -> { }, Residue.ofLetter('L'));

        router.onKeyPressed(keyPressed(KeyCode.L, "\f", true, true, false, false));
        KeyEvent residue = keyTyped("\f");
        router.onKeyTyped(residue);
        KeyEvent next = keyTyped("\f");
        router.onKeyTyped(next);

        assertThat(residue.isConsumed()).isTrue();
        assertThat(next.isConsumed()).isFalse();
    }

    @Test
    void aPendingZoomResidueDoesNotSurviveTheMenuBarChord() {
        // The old zoom flag was reset only after the menu-bar and fullscreen chords had returned, so
        // it survived them and swallowed whatever KEY_TYPED came next.
        SceneShortcutRouter router = new SceneShortcutRouter(false)
            .consume(p -> p.matches(SHORTCUT_SHIFT_L), SceneShortcutRouter.ALWAYS, () -> { }, Residue.ofLetter('L'))
            .consume(SceneShortcutKeys::isZoomIn, SceneShortcutRouter.ALWAYS, () -> { }, SceneShortcutKeys.ZOOM_RESIDUE);

        // Ctrl+= zooms but its platform delivers no KEY_TYPED.
        router.onKeyPressed(keyPressed(KeyCode.EQUALS, "=", false, true, false, false));
        // Ctrl+Shift+L arms its own letter residue in place of the zoom's any-character one, so a
        // KEY_TYPED that is not L, l or U+000C passes.
        router.onKeyPressed(keyPressed(KeyCode.L, "\f", true, true, false, false));
        KeyEvent typed = keyTyped("x");
        router.onKeyTyped(typed);

        assertThat(typed.isConsumed()).isFalse();
    }

    @Test
    void aKeyNoEntryClaimsDisarmsAChordWithoutKeyTyped() {
        SceneShortcutRouter router = new SceneShortcutRouter(false)
            .consume(SceneShortcutKeys::isZoomIn, SceneShortcutRouter.ALWAYS, () -> { }, SceneShortcutKeys.ZOOM_RESIDUE);

        router.onKeyPressed(keyPressed(KeyCode.EQUALS, "=", false, true, false, false));
        KeyEvent plainKey = keyPressed(KeyCode.A, "a", false, false, false, false);
        router.onKeyPressed(plainKey);
        KeyEvent typed = keyTyped("a");
        router.onKeyTyped(typed);

        assertThat(plainKey.isConsumed()).isFalse();
        assertThat(typed.isConsumed()).isFalse();
    }

    @Test
    void releaseObserversRunWhileTheirScopeHoldsAndNeverConsume() {
        List<String> ran = new ArrayList<>();
        boolean[] cycling = {true};
        SceneShortcutRouter router = new SceneShortcutRouter(false)
            .consume(press -> press.code() == KeyCode.TAB && press.ctrl(), SceneShortcutRouter.ALWAYS,
                () -> ran.add("tab"), Residue.of("\t"))
            .observeRelease(release -> release.code() == KeyCode.CONTROL, () -> cycling[0], () -> ran.add("first"))
            .observeRelease(release -> release.code() == KeyCode.CONTROL, SceneShortcutRouter.ALWAYS,
                () -> ran.add("second"));

        KeyEvent ctrlReleased = keyReleased(KeyCode.CONTROL, false);
        router.onKeyReleased(ctrlReleased);
        assertThat(ran).containsExactly("first", "second").inOrder();
        assertThat(ctrlReleased.isConsumed()).isFalse();

        ran.clear();
        cycling[0] = false;
        router.onKeyReleased(keyReleased(KeyCode.CONTROL, false));
        router.onKeyReleased(keyReleased(KeyCode.SHIFT, true));
        assertThat(ran).containsExactly("second");

        // A release between a chord and its KEY_TYPED leaves the residue guard armed.
        router.onKeyPressed(keyPressed(KeyCode.TAB, "\t", false, true, false, false));
        router.onKeyReleased(keyReleased(KeyCode.TAB, true));
        KeyEvent residue = keyTyped("\t");
        router.onKeyTyped(residue);
        assertThat(residue.isConsumed()).isTrue();
    }

    @Test
    void mainWindowRegistersItsSceneKeyFiltersOnlyThroughTheRouter() throws IOException {
        String source = Files.readString(Path.of("src/main/java/de/kortty/ui/MainWindow.java"), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");

        assertThat(source).contains("createSceneShortcutRouter().install(scene);");
        assertThat(Pattern.compile(
                "(?:scene|getScene\\(\\))\\s*\\.addEventFilter\\(\\s*(?:javafx\\.scene\\.input\\.)?KeyEvent\\.")
            .matcher(source).find()).isFalse();
    }

    private static KeyPress press(KeyCode code, String text, boolean shift, boolean ctrl, boolean alt,
                                  boolean meta, boolean macOs) {
        return new KeyPress(code, text, KeyEvent.CHAR_UNDEFINED, shift, ctrl, alt, meta, macOs);
    }

    private static KeyEvent keyPressed(KeyCode code, String text, boolean shift, boolean ctrl, boolean alt,
                                       boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, text, code, shift, ctrl, alt, meta);
    }

    private static KeyEvent keyReleased(KeyCode code, boolean ctrl) {
        return new KeyEvent(KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, "", code, false, ctrl, false, false);
    }

    private static KeyEvent keyTyped(String character) {
        return new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, false, false, false, false);
    }
}
