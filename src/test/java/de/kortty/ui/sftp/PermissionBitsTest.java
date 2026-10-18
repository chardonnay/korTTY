package de.kortty.ui.sftp;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

class PermissionBitsTest {

    @Test
    void octalBecomesTheNineBitsInLsOrder() {
        boolean[] bits = PermissionBits.parseOctal("751").orElseThrow();

        assertThat(bits).asList().containsExactly(
            true, true, true,     // owner rwx
            true, false, true,    // group r-x
            false, false, true)   // others --x
            .inOrder();
        assertThat(PermissionBits.toSymbolic(bits)).isEqualTo("rwxr-x--x");
        assertThat(PermissionBits.toOctal(bits)).isEqualTo("751");
    }

    @Test
    void everyModeSurvivesTheRoundTrip() {
        for (int mode = 0; mode < 512; mode++) {
            String octal = String.format("%03o", mode);
            assertThat(PermissionBits.toOctal(PermissionBits.parseOctal(octal).orElseThrow())).isEqualTo(octal);
        }
    }

    @Test
    void onlyThreeOctalDigitsAreAccepted() {
        assertThat(PermissionBits.parseOctal(" 644 ")).isPresent();
        assertThat(PermissionBits.parseOctal("")).isEmpty();
        assertThat(PermissionBits.parseOctal(null)).isEmpty();
        assertThat(PermissionBits.parseOctal("75")).isEmpty();
        // Four digits (setuid and friends) never reach chmod through the dialog either.
        assertThat(PermissionBits.parseOctal("4755")).isEmpty();
        assertThat(PermissionBits.parseOctal("758")).isEmpty();
        assertThat(PermissionBits.parseOctal("rwx")).isEmpty();
    }

    @Test
    void symbolicAndOctalOfNothingAndEverything() {
        assertThat(PermissionBits.toSymbolic(new boolean[9])).isEqualTo("---------");
        assertThat(PermissionBits.toOctal(new boolean[9])).isEqualTo("000");
        assertThat(PermissionBits.toSymbolic(PermissionBits.parseOctal("777").orElseThrow())).isEqualTo("rwxrwxrwx");
    }

    @Test
    void anythingButNineBitsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PermissionBits.toOctal(new boolean[8]));
        assertThrows(IllegalArgumentException.class, () -> PermissionBits.toSymbolic(null));
    }
}
