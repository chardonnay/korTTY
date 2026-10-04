package de.kortty.ui;

import de.kortty.core.KeymapOverrides.Resolution;
import de.kortty.ui.KeyTypedResidueGuard.Residue;
import de.kortty.ui.SceneShortcutRouter.KeyPress;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One rebindable chord of the main window's scene shortcut router: the action it belongs to (the
 * i18n key of the menu item that shows it), its default (MainWindow's KeyCodeCombination constant)
 * and the chord in effect, which follows the user's shortcut overrides ({@link #bind(Resolution)}).
 * The router asks {@link #matches} and {@link #residue} on every key press, so a new binding takes
 * effect at once. FX thread only.
 *
 * <p>The default chord swallows the residue declared for it. A chord the user chose swallows
 * whatever the KEY_TYPED right after it carries ({@link Residue#anyCharacter()}): its character
 * depends on the key, the layout and the modifiers, and only that one KEY_TYPED can be swallowed, so
 * no later character is lost.
 */
final class RoutedChord {

    private final String actionId;
    private final KeyCombination defaultChord;
    private final Residue defaultResidue;
    private @Nullable KeyCombination chord;

    /**
     * @param actionId       the action's id, the i18n key of its menu item
     * @param defaultChord   the chord without an override, a KeyCodeCombination as the router needs
     * @param defaultResidue what the default chord's KEY_TYPED can still carry
     */
    RoutedChord(@NotNull String actionId, @NotNull KeyCombination defaultChord, @NotNull Residue defaultResidue) {
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        if (!(defaultChord instanceof KeyCodeCombination)) {
            throw new IllegalArgumentException("Only KeyCodeCombination chords can be routed: " + defaultChord);
        }
        this.defaultChord = defaultChord;
        this.defaultResidue = Objects.requireNonNull(defaultResidue, "defaultResidue");
        this.chord = defaultChord;
    }

    @NotNull String actionId() {
        return actionId;
    }

    @NotNull KeyCombination defaultChord() {
        return defaultChord;
    }

    /** The chord in effect; {@code null} when the user removed the action's shortcut. */
    @Nullable KeyCombination chord() {
        return chord;
    }

    /** Whether {@code press} is the chord in effect; never while the action has no shortcut. */
    boolean matches(@NotNull KeyPress press) {
        KeyCombination current = chord;
        return current != null && press.matches(current);
    }

    /** What the chord in effect can still type once the router took it. */
    @NotNull Residue residue() {
        KeyCombination current = chord;
        return current == null || current.equals(defaultChord) ? defaultResidue : Residue.anyCharacter();
    }

    /** Puts the action on {@code chord}, or leaves it without a shortcut when {@code chord} is {@code null}. */
    void bind(@Nullable KeyCombination chord) {
        if (chord != null && !(chord instanceof KeyCodeCombination)) {
            throw new IllegalArgumentException("Only KeyCodeCombination chords can be routed: " + chord);
        }
        this.chord = chord != null && chord.equals(defaultChord) ? defaultChord : chord;
    }

    /**
     * Puts the action on its chord in {@code resolution}; on its default when the resolution does
     * not know the action (its menu item is missing).
     */
    void bind(@NotNull Resolution resolution) {
        bind(resolution.knows(actionId) ? KeymapSupport.combinationOf(resolution.chord(actionId)) : defaultChord);
    }
}
