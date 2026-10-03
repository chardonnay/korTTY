package de.kortty.ui;

import de.kortty.ui.KeyTypedResidueGuard.Residue;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;

/**
 * Configuration &gt; Security &gt; Credentials moved from Cmd/Ctrl+Shift+P to Cmd/Ctrl+Shift+M. The
 * chord goes through the main window's scene shortcut router, so it also works while a terminal has
 * the focus (on Windows and Linux the terminal could take Ctrl+Shift+M as a carriage return) and
 * with the menu bar hidden, and its KEY_TYPED residue never reaches the terminal. Toolkit-free: the
 * router entry is rebuilt here from the same combination, which a source pin keeps in step with
 * MainWindow, and key events are built directly.
 */
class CredentialsShortcutTest {

    private static final Path SOURCE = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final boolean MAC = true;
    private static final boolean PC = false;

    /** MainWindow.CREDENTIALS_ACCELERATOR; {@link #mainWindowRoutesTheCredentialsChord()} pins the two together. */
    private static final KeyCombination CREDENTIALS =
        new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);

    @Test
    void mainWindowRoutesTheCredentialsChord() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(Pattern.compile("static final KeyCombination CREDENTIALS_ACCELERATOR\\s*=\\s*"
                + "new KeyCodeCombination\\(\\s*KeyCode\\.M,\\s*KeyCombination\\.SHORTCUT_DOWN,\\s*"
                + "KeyCombination\\.SHIFT_DOWN\\s*\\)").matcher(source).find()).isTrue();
        assertThat(source).contains("manageCredentials.setAccelerator(CREDENTIALS_ACCELERATOR);");

        int routerStart = source.indexOf("private SceneShortcutRouter createSceneShortcutRouter() {");
        assertThat(routerStart).isAtLeast(0);
        int routerEnd = source.indexOf("return router;", routerStart);
        assertThat(routerEnd).isGreaterThan(routerStart);
        Matcher entry = Pattern.compile("\\.consume\\(press -> press\\.matches\\(CREDENTIALS_ACCELERATOR\\), "
                + "SceneShortcutRouter\\.ALWAYS,\\s*\\(\\) -> Platform\\.runLater\\(this::showCredentialManagement\\), "
                + "Residue\\.ofLetter\\('M'\\)\\)")
            .matcher(source);
        assertThat(entry.find(routerStart)).isTrue();
        assertThat(entry.start()).isLessThan(routerEnd);
    }

    @Test
    void ctrlShiftMOpensCredentialsOnWindowsAndLinuxAndItsCarriageReturnIsSwallowed() {
        List<String> opened = new ArrayList<>();
        SceneShortcutRouter router = credentialsRouter(PC, opened);

        KeyEvent chord = keyPressed(KeyCode.M, "\r", true, true, false, false);
        router.onKeyPressed(chord);
        KeyEvent residue = keyTyped("\r");
        router.onKeyTyped(residue);
        // The next Enter is a real one and reaches the shell.
        KeyEvent nextEnter = keyTyped("\r");
        router.onKeyTyped(nextEnter);

        assertThat(opened).containsExactly("credentials");
        assertThat(chord.isConsumed()).isTrue();
        assertThat(residue.isConsumed()).isTrue();
        assertThat(nextEnter.isConsumed()).isFalse();
    }

    @Test
    void cmdShiftMOpensCredentialsOnMacOsAndLeavesNoLetterBehind() {
        List<String> opened = new ArrayList<>();
        SceneShortcutRouter router = credentialsRouter(MAC, opened);

        KeyEvent chord = keyPressed(KeyCode.M, "m", true, false, false, true);
        router.onKeyPressed(chord);
        KeyEvent residue = keyTyped("M");
        router.onKeyTyped(residue);

        assertThat(opened).containsExactly("credentials");
        assertThat(chord.isConsumed()).isTrue();
        assertThat(residue.isConsumed()).isTrue();
    }

    @Test
    void neighbouringChordsAreNotCredentials() {
        // Ctrl+M is Manage Connections (and Enter for the shell), Ctrl+Alt+Shift+M is AltGr+Shift+M
        // on Windows, and Ctrl+Shift+P is the old chord.
        assertNotCredentials(PC, keyPressed(KeyCode.M, "\r", false, true, false, false), "\r");
        assertNotCredentials(PC, keyPressed(KeyCode.M, "\r", true, true, true, false), "\r");
        assertNotCredentials(PC, keyPressed(KeyCode.P, "\u0010", true, true, false, false), "\u0010");
        // On macOS: the physical Ctrl key, Cmd without Shift (minimise) and the old Cmd+Shift+P.
        assertNotCredentials(MAC, keyPressed(KeyCode.M, "\r", true, true, false, false), "\r");
        assertNotCredentials(MAC, keyPressed(KeyCode.M, "m", false, false, false, true), "m");
        assertNotCredentials(MAC, keyPressed(KeyCode.P, "p", true, false, false, true), "P");
    }

    private static void assertNotCredentials(boolean macOs, KeyEvent press, String typedCharacter) {
        List<String> opened = new ArrayList<>();
        SceneShortcutRouter router = credentialsRouter(macOs, opened);

        router.onKeyPressed(press);
        KeyEvent typed = keyTyped(typedCharacter);
        router.onKeyTyped(typed);

        assertThat(opened).isEmpty();
        assertThat(press.isConsumed()).isFalse();
        assertThat(typed.isConsumed()).isFalse();
    }

    /** The router entry MainWindow registers, with the opening recorded instead of performed. */
    private static SceneShortcutRouter credentialsRouter(boolean macOs, List<String> opened) {
        return new SceneShortcutRouter(macOs)
            .consume(press -> press.matches(CREDENTIALS), SceneShortcutRouter.ALWAYS,
                () -> opened.add("credentials"), Residue.ofLetter('M'));
    }

    private static KeyEvent keyPressed(KeyCode code, String text, boolean shift, boolean ctrl, boolean alt,
                                       boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, text, code, shift, ctrl, alt, meta);
    }

    private static KeyEvent keyTyped(String character) {
        return new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, false, false, false, false);
    }
}
