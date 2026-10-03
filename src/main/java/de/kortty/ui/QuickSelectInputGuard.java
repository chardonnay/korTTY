package de.kortty.ui;

import de.kortty.ui.KeyTypedResidueGuard.Residue;
import javafx.scene.input.KeyCode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps the keys of a quick-select session out of the terminal: nothing typed while quick select
 * runs reaches the shell, and neither does what its last key leaves behind.
 *
 * <p>Quick select consumes its KEY_PRESSED events in a filter on the split pane, before any pane,
 * broadcast mode's mirror included, sees them. That alone is not enough: the paired KEY_TYPED still
 * carries the key's character (on Windows and Linux always, on macOS for keys without Cmd), and an
 * input method can still deliver text. So while a session runs every KEY_TYPED and every input-method
 * event is swallowed, and after the key that ends it the guard swallows that key's KEY_TYPED once,
 * through the same {@link KeyTypedResidueGuard} the window's shortcut router uses, and any
 * input-method text until the next KEY_PRESSED, along with the rest of a composition begun during
 * the session.
 *
 * <p>The guard is cleared at the top of every KEY_PRESSED that reaches the split pane, so a key
 * without a KEY_TYPED never costs the next real character. Toolkit-free, so it is unit-tested headless.
 */
final class QuickSelectInputGuard {

    private final KeyTypedResidueGuard residue = new KeyTypedResidueGuard();
    private boolean sessionKeyPending;
    private boolean inputMethodComposing;

    /** At the top of every KEY_PRESSED that reaches the split pane, before quick select looks at it. */
    void keyPressed() {
        residue.reset();
        sessionKeyPending = false;
    }

    /**
     * Quick select consumed this KEY_PRESSED. A key that types a character arms the residue guard for
     * whatever its KEY_TYPED carries, which depends on the layout (a letter key on a Cyrillic layout).
     */
    void consumed(@NotNull KeyCode code) {
        sessionKeyPending = true;
        if (!code.isModifierKey()) {
            residue.arm(Residue.anyCharacter());
        }
    }

    /**
     * For every KEY_TYPED that reaches the split pane.
     *
     * @return whether it must be consumed
     */
    boolean swallowTyped(boolean sessionActive, @Nullable String character) {
        boolean residueOfSessionKey = residue.swallow(character);
        return sessionActive || residueOfSessionKey;
    }

    /**
     * For every input-method event that reaches the split pane.
     *
     * @param composing whether the event still carries uncommitted (composed) text
     * @return whether it must be consumed
     */
    boolean swallowInputMethod(boolean sessionActive, boolean composing) {
        if (sessionActive || sessionKeyPending || inputMethodComposing) {
            inputMethodComposing = composing;
            return true;
        }
        return false;
    }
}
