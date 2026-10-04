package de.kortty.ui;

import de.kortty.ui.KeyTypedResidueGuard.Residue;
import de.kortty.ui.SceneShortcutRouter.KeyPress;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * The command palette's chord as the palette and the scene shortcut router see it. Pure functions of
 * a {@link KeyPress} or of a key event's own fields, with an explicit macOS flag, so the rules are
 * unit-tested without the JavaFX toolkit.
 *
 * <p>The chord is MainWindow's {@code COMMAND_PALETTE_ACCELERATOR}, Cmd+Shift+P on macOS and
 * Ctrl+Shift+P elsewhere. It needs Alt up, so AltGr+Shift+P (reported as Ctrl+Alt+Shift+P on
 * Windows) types its character, and plain Ctrl+P stays the shell's previous-history key.
 */
final class PaletteKeys {

    /** What the chord can still type once the router took it: p, P, or U+0010 for Ctrl+P. */
    static final Residue RESIDUE = Residue.ofLetter('P');

    private PaletteKeys() {
    }

    /** Whether {@code press} is {@code chord}. */
    static boolean isChord(@NotNull KeyPress press, @NotNull KeyCombination chord) {
        return press.matches(chord);
    }

    /**
     * The key events the open palette lets through to its window: the chord's KEY_PRESSED only, so
     * the scene shortcut router closes the palette and swallows the chord's KEY_TYPED. Everything
     * else typed while the palette shows stays in the palette (see {@link QuickPickKeyFirewall}).
     */
    static Predicate<KeyEvent> passThrough(@NotNull KeyCombination chord, boolean macOs) {
        Objects.requireNonNull(chord, "chord");
        return event -> event.getEventType() == KeyEvent.KEY_PRESSED
            && isChord(KeyPress.of(event, macOs), chord);
    }
}
