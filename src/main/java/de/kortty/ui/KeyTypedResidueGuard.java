package de.kortty.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Swallows the KEY_TYPED "residue" of a shortcut chord whose KEY_PRESSED was consumed.
 *
 * <p>Consuming a chord's KEY_PRESSED does not stop the paired KEY_TYPED: depending on the platform
 * and layout it still carries the key's character ({@code +} after Ctrl+Plus on Linux), a control
 * character ({@code U+000C} after Ctrl+Shift+L on Windows) or nothing at all. Left alone, that
 * character is typed into the focused terminal, and broadcast mode mirrors it to every other pane.
 *
 * <p>The guard is one-shot and cannot go stale: {@link #reset()} runs at the top of every
 * KEY_PRESSED, so a chord that produced no KEY_TYPED never eats the next real character, and
 * {@link #swallow(String)} decides on the first KEY_TYPED only, swallowing it when it is one of the
 * armed chord's residue characters. Free of the JavaFX toolkit, so it is unit-tested headless.
 */
final class KeyTypedResidueGuard {

    private @Nullable Residue armed;

    /** Disarms the guard. Called for every KEY_PRESSED before any chord can arm it again. */
    void reset() {
        armed = null;
    }

    /** Arms the guard for the residue of a chord whose KEY_PRESSED was just consumed. */
    void arm(@NotNull Residue residue) {
        armed = residue;
    }

    boolean isArmed() {
        return armed != null;
    }

    /**
     * Called for every KEY_TYPED. Returns true when {@code character} is the armed chord's residue
     * and the event must be consumed. Either way the guard is disarmed: only the first KEY_TYPED
     * after the chord can be its residue.
     */
    boolean swallow(@Nullable String character) {
        Residue residue = armed;
        armed = null;
        return residue != null && residue.matches(character);
    }

    /** The characters a chord can still deliver as its KEY_TYPED half after its KEY_PRESSED was consumed. */
    record Residue(boolean matchesAny, @NotNull Set<String> characters) {

        /** For chords that type nothing, such as F12. */
        static final Residue NONE = new Residue(false, Set.of());

        Residue {
            characters = Set.copyOf(characters);
        }

        /** Exactly these characters. */
        static Residue of(String... characters) {
            return new Residue(false, Set.copyOf(List.of(characters)));
        }

        /**
         * A letter chord such as Ctrl+Shift+L: the letter in either case, or the control character
         * Ctrl turns it into ({@code U+000C} for L).
         */
        static Residue ofLetter(char letter) {
            char upper = Character.toUpperCase(letter);
            if (upper < 'A' || upper > 'Z') {
                throw new IllegalArgumentException("Not an ASCII letter: " + letter);
            }
            return of(String.valueOf(Character.toLowerCase(upper)), String.valueOf(upper),
                String.valueOf((char) (upper - 'A' + 1)));
        }

        /**
         * Whatever the chord's KEY_TYPED carries. For keys whose character depends on the layout
         * and the modifier, such as the zoom keys ({@code à} on the AZERTY 0 key, AltGr's
         * {@code ~} on the German plus key).
         */
        static Residue anyCharacter() {
            return new Residue(true, Set.of());
        }

        boolean matches(@Nullable String character) {
            if (matchesAny) {
                return true;
            }
            return character != null && characters.contains(character);
        }
    }
}
