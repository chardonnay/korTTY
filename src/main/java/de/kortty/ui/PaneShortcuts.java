package de.kortty.ui;

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
 * pane on that side. The chords are MainWindow's KeyCodeCombination constants (also shown on the
 * <i>View → Panes</i> items); this class maps a key press to the action it stands for and says when
 * a pane shortcut applies. Pure, so it is unit-tested without the JavaFX toolkit.
 *
 * <p>A pane shortcut applies only while the tab has two or more panes. With one pane the key goes
 * to the shell, which gets Ctrl+Alt with an arrow key the way xterm sends it.
 */
final class PaneShortcuts {

    /** What a pane shortcut does. */
    enum PaneAction {
        FOCUS_LEFT(PaneDirection.LEFT),
        FOCUS_RIGHT(PaneDirection.RIGHT),
        FOCUS_UP(PaneDirection.UP),
        FOCUS_DOWN(PaneDirection.DOWN);

        private final PaneDirection direction;

        PaneAction(PaneDirection direction) {
            this.direction = direction;
        }

        /** The direction the focus moves in. */
        @NotNull PaneDirection direction() {
            return direction;
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
     * Whether a pane shortcut acts in a tab with {@code paneCount} panes: moving the focus needs a
     * second pane. Otherwise the key is left to the terminal.
     */
    static boolean applies(@NotNull PaneAction action, int paneCount) {
        return paneCount >= 2;
    }
}
