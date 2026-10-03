package de.kortty.ui;

import de.kortty.ui.KeyTypedResidueGuard.Residue;
import de.kortty.ui.PaneNavigator.PaneDirection;
import de.kortty.ui.SceneShortcutRouter.KeyPress;
import javafx.scene.input.KeyCombination;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The keyboard shortcuts that act on the split panes of the selected terminal tab: Cmd+Option with
 * an arrow key on macOS, Ctrl+Alt with an arrow key on Windows and Linux, to move the focus to the
 * pane on that side, and Cmd/Ctrl+Shift+O to split the focused pane. The chords are MainWindow's
 * KeyCodeCombination constants (also shown on the <i>View → Panes</i> items); this class maps a key
 * press to the action it stands for and says when a pane shortcut applies. Pure, so it is
 * unit-tested without the JavaFX toolkit.
 *
 * <p>Moving the focus applies only while the tab has two or more panes. With one pane the key goes
 * to the shell, which gets Ctrl+Alt with an arrow key the way xterm sends it. Splitting applies to
 * every terminal tab; on Windows and Linux the shell no longer gets Ctrl+Shift+O, which xterm sends
 * as Ctrl+O anyway.
 */
final class PaneShortcuts {

    /** What a pane shortcut does. */
    enum PaneAction {
        FOCUS_LEFT(PaneDirection.LEFT, Residue.NONE),
        FOCUS_RIGHT(PaneDirection.RIGHT, Residue.NONE),
        FOCUS_UP(PaneDirection.UP, Residue.NONE),
        FOCUS_DOWN(PaneDirection.DOWN, Residue.NONE),
        /**
         * Splits the focused pane on that pane's own server, to the right or below. Its KEY_TYPED
         * can still carry the O, or the U+000F that Ctrl turns it into, so that is swallowed.
         */
        SPLIT(null, Residue.ofLetter('O'));

        private final @Nullable PaneDirection direction;
        private final Residue residue;

        PaneAction(@Nullable PaneDirection direction, @NotNull Residue residue) {
            this.direction = direction;
            this.residue = residue;
        }

        /** The direction the focus moves in; {@code null} for {@link #SPLIT}, which moves no focus. */
        @Nullable PaneDirection direction() {
            return direction;
        }

        /**
         * What the chord's KEY_TYPED can still type once the router took its KEY_PRESSED: nothing
         * for the arrow keys, the letter for the split chord.
         */
        @NotNull Residue residue() {
            return residue;
        }

        /** The focus action for {@code direction}. */
        static @NotNull PaneAction focus(@NotNull PaneDirection direction) {
            for (PaneAction action : values()) {
                if (action.direction == direction) {
                    return action;
                }
            }
            throw new IllegalArgumentException("No focus action for " + direction);
        }
    }

    private final Map<PaneAction, KeyCombination> chords;

    /** @param chords the chord of every action; each must be a KeyCodeCombination, as the router needs */
    PaneShortcuts(@NotNull Map<PaneAction, ? extends KeyCombination> chords) {
        EnumMap<PaneAction, KeyCombination> copy = new EnumMap<>(PaneAction.class);
        for (PaneAction action : PaneAction.values()) {
            copy.put(action, Objects.requireNonNull(chords.get(action), () -> "No chord for " + action));
        }
        this.chords = copy;
    }

    /** The chord of {@code action}. */
    @NotNull KeyCombination chordOf(@NotNull PaneAction action) {
        return chords.get(action);
    }

    /** The pane action {@code press} is the chord of, if any. */
    @NotNull Optional<PaneAction> match(@NotNull KeyPress press) {
        for (Map.Entry<PaneAction, KeyCombination> entry : chords.entrySet()) {
            if (press.matches(entry.getValue())) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /** Whether {@code press} is the chord of exactly {@code action}. */
    boolean isChordOf(@NotNull KeyPress press, @Nullable PaneAction action) {
        return action != null && match(press).orElse(null) == action;
    }

    /**
     * Whether a pane shortcut acts in a tab with {@code paneCount} panes: splitting needs a pane to
     * split, moving the focus a second pane. Otherwise the key is left to the terminal.
     */
    static boolean applies(@NotNull PaneAction action, int paneCount) {
        return action == PaneAction.SPLIT ? paneCount >= 1 : paneCount >= 2;
    }
}
