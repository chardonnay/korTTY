package de.kortty.ui;

import javafx.scene.input.KeyCode;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * Nothing quick select's keys type reaches a shell: while it runs every typed character and every
 * input-method event is swallowed, and after the key that ends it the same one-shot
 * {@link KeyTypedResidueGuard} the window's shortcut router uses swallows that key's typed character
 * once, whatever the layout makes of it. The guard is cleared by the next key press, so a key
 * without a typed character never costs the next real one.
 */
public class QuickSelectInputGuardTest {

    @Test
    public void whileQuickSelectRunsEveryTypedCharacterIsSwallowed() {
        QuickSelectInputGuard guard = new QuickSelectInputGuard();

        guard.keyPressed();
        guard.consumed(KeyCode.S);

        assertThat(guard.swallowTyped(true, "s")).isTrue();
        // A second KEY_TYPED without its own press still never reaches the shell while it runs.
        assertThat(guard.swallowTyped(true, "x")).isTrue();
    }

    @Test
    public void theTypedCharacterOfTheKeyThatEndedQuickSelectIsSwallowedOnce() {
        QuickSelectInputGuard guard = new QuickSelectInputGuard();

        guard.keyPressed();
        guard.consumed(KeyCode.A);

        // On a Cyrillic layout the A key types "ф": the residue is whatever the key types.
        assertThat(guard.swallowTyped(false, "ф")).isTrue();
        assertThat(guard.swallowTyped(false, "ф")).isFalse();
    }

    @Test
    public void aPressQuickSelectDidNotConsumeLetsItsCharacterThrough() {
        QuickSelectInputGuard guard = new QuickSelectInputGuard();

        guard.keyPressed();

        assertThat(guard.swallowTyped(false, "a")).isFalse();
    }

    @Test
    public void aConsumedKeyWithoutTypedCharacterDoesNotEatTheNextRealOne() {
        QuickSelectInputGuard guard = new QuickSelectInputGuard();

        // An arrow key cancels quick select but delivers no KEY_TYPED.
        guard.keyPressed();
        guard.consumed(KeyCode.UP);
        // The next key reaches the shell normally.
        guard.keyPressed();

        assertThat(guard.swallowTyped(false, "x")).isFalse();
    }

    @Test
    public void aModifierKeyArmsNothing() {
        QuickSelectInputGuard guard = new QuickSelectInputGuard();

        guard.keyPressed();
        guard.consumed(KeyCode.SHIFT);

        assertThat(guard.swallowTyped(false, "x")).isFalse();
    }

    @Test
    public void inputMethodTextIsSwallowedWhileQuickSelectRunsAndRightAfterItsLastKey() {
        QuickSelectInputGuard guard = new QuickSelectInputGuard();

        assertThat(guard.swallowInputMethod(true, false)).isTrue();

        // The label key ended quick select; the input method commits that key's text afterwards.
        guard.keyPressed();
        guard.consumed(KeyCode.A);
        assertThat(guard.swallowInputMethod(false, false)).isTrue();

        // The next key belongs to the shell again.
        guard.keyPressed();
        assertThat(guard.swallowInputMethod(false, false)).isFalse();
    }

    @Test
    public void aCompositionBegunDuringQuickSelectIsSwallowedUntilItEnds() {
        QuickSelectInputGuard guard = new QuickSelectInputGuard();

        // Composing started while quick select ran.
        assertThat(guard.swallowInputMethod(true, true)).isTrue();
        // Escape ended quick select; the input method still holds the composition.
        guard.keyPressed();
        guard.consumed(KeyCode.ESCAPE);
        guard.keyPressed();
        assertThat(guard.swallowInputMethod(false, true)).isTrue();
        // Its commit is swallowed too, and then input reaches the shell again.
        assertThat(guard.swallowInputMethod(false, false)).isTrue();
        assertThat(guard.swallowInputMethod(false, true)).isFalse();
    }
}
