package de.kortty.plugin.terminaleffects;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

public class TerminalEffectAnimationSpeedTest {

    @Test
    void normalizeAllowsTwoDigitSpeedValues() {
        assertThat(TerminalEffectAnimationSpeed.normalize(99.0)).isEqualTo(99.0);
    }

    @Test
    void normalizeClampsAboveTwoDigitSpeedValues() {
        assertThat(TerminalEffectAnimationSpeed.normalize(120.0)).isEqualTo(99.0);
    }
}
