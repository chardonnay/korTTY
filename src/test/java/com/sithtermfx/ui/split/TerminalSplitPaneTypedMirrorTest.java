package com.sithtermfx.ui.split;

import com.sithtermfx.ui.split.BroadcastTargets.TypedMirror;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The character of a key's KEY_TYPED reaches the panes that mirror the pane it was typed in (broadcast
 * mode, multi-exec) only when that pane sends the key to its own program too. On Windows and Linux the
 * KEY_TYPED of a Ctrl+Shift chord still carries the control character Ctrl turns the letter into, so
 * without this rule copying with Ctrl+Shift+C sent {@code U+0003} and interrupted the program in every
 * other pane, and pasting with Ctrl+Shift+V sent {@code U+0016}, after which their shells inserted the
 * next key literally. The decision is the pure {@link TypedMirror}; its wiring into the split pane's
 * key filters is read from the source (line-ending agnostic), because a live split pane needs a JavaFX
 * toolkit.
 */
public class TerminalSplitPaneTypedMirrorTest {

    private static final Path SOURCE = Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java");

    @Test
    public void copyAndPasteKeysMirrorNothing() {
        // Ctrl+Shift+C with a selection copies, Ctrl+Shift+V pastes: SithTermFX consumes the key
        // without sending it, so its KEY_TYPED goes nowhere, whatever it carries.
        TypedMirror copy = TypedMirror.ofPress(true).afterTerminal(true);
        assertThat(copy).isEqualTo(TypedMirror.NONE);
        assertThat(copy.mirrors('\u0003')).isFalse();
        assertThat(copy.mirrors('\u0016')).isFalse();
        assertThat(copy.mirrors('C')).isFalse();
    }

    @Test
    public void aControlKeyTheTerminalSendsIsMirrored() {
        // Ctrl+C, Ctrl+D, Ctrl+R: the terminal sends the control character from the KEY_PRESSED and
        // consumes it; the KEY_TYPED character stands for what it sent.
        TypedMirror ctrlC = TypedMirror.ofPress(false).afterTerminal(true);
        assertThat(ctrlC).isEqualTo(TypedMirror.ALL);
        assertThat(ctrlC.mirrors('\u0003')).isTrue();
        assertThat(ctrlC.mirrors('\u0004')).isTrue();
    }

    @Test
    public void aKeyTheTerminalLeftAloneMirrorsOnlyAPrintableCharacter() {
        // A letter: the terminal sends it from the KEY_TYPED, so it is mirrored.
        TypedMirror letter = TypedMirror.ofPress(false).afterTerminal(false);
        assertThat(letter).isEqualTo(TypedMirror.PRINTABLE_ONLY);
        assertThat(letter.mirrors('a')).isTrue();
        assertThat(letter.mirrors('é')).isTrue();
        assertThat(letter.mirrors('@')).isTrue();
        // A menu shortcut such as Ctrl+Shift+D: the terminal never sends the control character its
        // KEY_TYPED carries, so no other pane gets it (U+0004 would end their shells).
        assertThat(letter.mirrors('\u0004')).isFalse();
        assertThat(letter.mirrors('\u0014')).isFalse();
    }

    @Test
    public void aKeyTheTerminalNeverSawMirrorsOnlyAPrintableCharacter() {
        // The decision a KEY_TYPED gets whose KEY_PRESSED stopped before the pane, such as a chord
        // the scene took or one korTTY's terminal view consumed.
        assertThat(TypedMirror.PRINTABLE_ONLY.mirrors('x')).isTrue();
        assertThat(TypedMirror.PRINTABLE_ONLY.mirrors('\u0003')).isFalse();
        assertThat(TypedMirror.NONE.afterTerminal(true)).isEqualTo(TypedMirror.NONE);
        assertThat(TypedMirror.NONE.afterTerminal(false)).isEqualTo(TypedMirror.NONE);
        assertThat(TypedMirror.ALL.afterTerminal(false)).isEqualTo(TypedMirror.ALL);
    }

    @Test
    public void everyPressDecidesWhatItsTypedCharacterMayMirror() throws IOException {
        String source = source();
        String route = member(source,
            "private void routeKeyPressed(@NotNull SithTermFxWidget widget, @NotNull KeyEvent event) {");
        assertWithMessage("every press starts from the safe decision, before any early return")
            .that(route.indexOf("typedMirror = BroadcastTargets.TypedMirror.PRINTABLE_ONLY;"))
            .isLessThan(route.indexOf("if (event.isConsumed()) {"));
        assertThat(route).contains("typedMirrorPane = widget;");
        assertWithMessage("copy and paste run in the pane only")
            .that(route).contains("typedMirror = BroadcastTargets.TypedMirror.ofPress(runsPaneAction(widget, event));");
        assertWithMessage("Enter, Backspace and Esc are mirrored from the press, never a second time")
            .that(route.indexOf("typedMirror = BroadcastTargets.TypedMirror.NONE;"))
            .isLessThan(route.indexOf("broadcastToOthers(widget, sequence);"));

        String noted = member(source,
            "private void noteTerminalHandledKey(@NotNull SithTermFxWidget widget, @NotNull KeyEvent event) {");
        assertThat(noted).contains("typedMirror = typedMirror.afterTerminal(event.isConsumed());");
        assertWithMessage("the canvas filter runs after SithTermFX's own, which the widget added when it was built")
            .that(source).contains(
                "panel.getCanvas().addEventFilter(KeyEvent.KEY_PRESSED, event -> noteTerminalHandledKey(widget, event));");
    }

    @Test
    public void theTypedFilterMirrorsOnlyWhatThePaneSendsAndUsesTheDecisionUp() throws IOException {
        String source = source();
        String typed = source.substring(source.indexOf("widgetPane.addEventFilter(KeyEvent.KEY_TYPED, event -> {\n"));
        typed = typed.substring(0, typed.indexOf("\n        });\n"));
        assertWithMessage("the decision is used up by every KEY_TYPED, also one that is not mirrored")
            .that(typed.indexOf("mirrorsTypedCharacter(widget, character.charAt(0))"))
            .isLessThan(typed.indexOf("if (!isMirroring(widget)) return;"));
        assertThat(typed.indexOf("if (!typedByPane) return;")).isLessThan(typed.indexOf("broadcastToOthers(widget, character);"));

        String decision = member(source,
            "private boolean mirrorsTypedCharacter(@NotNull SithTermFxWidget widget, char character) {");
        assertThat(decision).contains("typedMirrorPane == widget");
        assertThat(decision).contains("typedMirrorPane = null;");
        assertThat(decision).contains("return decision.mirrors(character);");
    }

    private static String source() throws IOException {
        return Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The member that starts with {@code signature}, up to the line closing it. */
    private static String member(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("not found: " + signature).that(start).isAtLeast(0);
        int end = source.indexOf("\n    }\n", start);
        return end < 0 ? source.substring(start) : source.substring(start, end);
    }
}
