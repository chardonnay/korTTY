package de.kortty.plugin.terminaleffects.mother;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

public class MotherPacedTtyConnectorTest {

    @Test
    void delayMillisScalesWithAnimationSpeed() {
        assertThat(MotherPacedTtyConnector.delayMillis(
                TerminalOutputPacer.DelayMode.CHARACTER,
                1.0)).isEqualTo(14L);
        assertThat(MotherPacedTtyConnector.delayMillis(
                TerminalOutputPacer.DelayMode.CHARACTER,
                2.0)).isEqualTo(7L);
        assertThat(MotherPacedTtyConnector.delayMillis(
                TerminalOutputPacer.DelayMode.CHARACTER,
                10.0)).isEqualTo(1L);
        assertThat(MotherPacedTtyConnector.delayMillis(
                TerminalOutputPacer.DelayMode.LINE_START,
                99.0)).isEqualTo(1L);
    }

    @Test
    void delayMillisClampsBelowMinimumSpeed() {
        assertThat(MotherPacedTtyConnector.delayMillis(
                TerminalOutputPacer.DelayMode.CHARACTER,
                0.5)).isEqualTo(14L);
    }

    @Test
    void delayMillisUsesDefaultSpeedForInvalidValues() {
        assertThat(MotherPacedTtyConnector.delayMillis(
                TerminalOutputPacer.DelayMode.CHARACTER,
                Double.NaN)).isEqualTo(14L);
        assertThat(MotherPacedTtyConnector.delayMillis(
                TerminalOutputPacer.DelayMode.CHARACTER,
                0.0)).isEqualTo(14L);
    }

    @Test
    void delayMillisLeavesImmediateSegmentsImmediate() {
        assertThat(MotherPacedTtyConnector.delayMillis(
                TerminalOutputPacer.DelayMode.NONE,
                1.0)).isEqualTo(0L);
    }

    @Test
    void containsVisibleOutputAcceptsPrintableText() {
        assertThat(MotherPacedTtyConnector.containsVisibleOutput("MOTHER", 6)).isTrue();
        assertThat(MotherPacedTtyConnector.containsVisibleOutput("   ", 3)).isTrue();
    }

    @Test
    void containsVisibleOutputIgnoresTerminalControls() {
        assertThat(MotherPacedTtyConnector.containsVisibleOutput("\u001B[32m", 5)).isFalse();
        assertThat(MotherPacedTtyConnector.containsVisibleOutput("\r\n\t", 3)).isFalse();
    }
}
