package de.kortty.ui;

import de.kortty.ui.KeyTypedResidueGuard.Residue;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * The KEY_TYPED residue guard of the scene shortcut router: it swallows the character half of a
 * consumed chord exactly once, and never the next real character, so a chord is neither typed into
 * the terminal nor mirrored to the other panes by broadcast mode.
 */
class KeyTypedResidueGuardTest {

    @Test
    void anArmedPressSwallowsOneMatchingResidue() {
        KeyTypedResidueGuard guard = new KeyTypedResidueGuard();
        guard.arm(Residue.ofLetter('L'));

        assertThat(guard.swallow("\f")).isTrue();
        // One-shot: the same character typed again is real input.
        assertThat(guard.isArmed()).isFalse();
        assertThat(guard.swallow("\f")).isFalse();
    }

    @Test
    void everyKeyPressedResetsTheGuard() {
        KeyTypedResidueGuard guard = new KeyTypedResidueGuard();
        guard.arm(Residue.ofLetter('L'));

        guard.reset();

        assertThat(guard.isArmed()).isFalse();
        assertThat(guard.swallow("l")).isFalse();
    }

    @Test
    void withNoKeyTypedTheNextRealCharacterPasses() {
        KeyTypedResidueGuard guard = new KeyTypedResidueGuard();
        // Ctrl+= on Windows: the chord is consumed but the platform delivers no KEY_TYPED.
        guard.arm(Residue.anyCharacter());

        // The user's next key press: the router resets the guard before it looks at any chord.
        guard.reset();

        assertThat(guard.swallow("a")).isFalse();
    }

    @Test
    void aNonMatchingCharacterPasses() {
        KeyTypedResidueGuard guard = new KeyTypedResidueGuard();
        guard.arm(Residue.ofLetter('L'));

        assertThat(guard.swallow("x")).isFalse();
        // Only the first KEY_TYPED after the chord can be its residue.
        assertThat(guard.isArmed()).isFalse();
        assertThat(guard.swallow("l")).isFalse();
    }

    @Test
    void anUnarmedGuardSwallowsNothing() {
        KeyTypedResidueGuard guard = new KeyTypedResidueGuard();

        assertThat(guard.swallow("+")).isFalse();
        assertThat(guard.swallow(null)).isFalse();
    }

    @Test
    void letterResidueIsBothCasesAndTheControlCharacter() {
        Residue residue = Residue.ofLetter('p');

        assertThat(residue.characters()).containsExactly("p", "P", "\u0010");
        assertThat(Residue.ofLetter('F').characters()).containsExactly("f", "F", "\u0006");
        assertThat(residue.matches("q")).isFalse();
        assertThat(residue.matches(null)).isFalse();
        assertThrows(IllegalArgumentException.class, () -> Residue.ofLetter('1'));
    }

    @Test
    void anyCharacterAndNoneResidues() {
        assertThat(Residue.anyCharacter().matches("+")).isTrue();
        assertThat(Residue.anyCharacter().matches("")).isTrue();
        assertThat(Residue.anyCharacter().matches("\u0000")).isTrue();
        assertThat(Residue.NONE.matches("")).isFalse();
        assertThat(Residue.NONE.matches("\t")).isFalse();
        assertThat(Residue.of("\t", "\t").characters()).containsExactly("\t");
    }

    @Test
    void armingWithNoneSwallowsNothingButStillDisarms() {
        KeyTypedResidueGuard guard = new KeyTypedResidueGuard();
        guard.arm(Residue.NONE);

        assertThat(guard.swallow("x")).isFalse();
        assertThat(guard.isArmed()).isFalse();
    }
}
