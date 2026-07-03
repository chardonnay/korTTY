package de.kortty.plugin.terminaleffects.pack;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

public class PackTypewriterTtyConnectorTest {

    @Test
    void delayMillisUsesTypewriterRhythm() {
        assertThat(PackTypewriterTtyConnector.delayMillis(
                PackOutputPacer.DelayMode.CHARACTER, 1.0)).isEqualTo(28L);
        assertThat(PackTypewriterTtyConnector.delayMillis(
                PackOutputPacer.DelayMode.PUNCTUATION, 1.0)).isEqualTo(120L);
        assertThat(PackTypewriterTtyConnector.delayMillis(
                PackOutputPacer.DelayMode.LINE_BREAK, 1.0)).isEqualTo(300L);
        assertThat(PackTypewriterTtyConnector.delayMillis(
                PackOutputPacer.DelayMode.NONE, 1.0)).isEqualTo(0L);
    }

    @Test
    void delayMillisScalesWithAnimationSpeed() {
        assertThat(PackTypewriterTtyConnector.delayMillis(
                PackOutputPacer.DelayMode.CHARACTER, 2.0)).isEqualTo(14L);
        assertThat(PackTypewriterTtyConnector.delayMillis(
                PackOutputPacer.DelayMode.LINE_BREAK, 10.0)).isEqualTo(30L);
        assertThat(PackTypewriterTtyConnector.delayMillis(
                PackOutputPacer.DelayMode.CHARACTER, 99.0)).isEqualTo(1L);
    }

    @Test
    void delayMillisClampsInvalidSpeeds() {
        assertThat(PackTypewriterTtyConnector.delayMillis(
                PackOutputPacer.DelayMode.CHARACTER, 0.5)).isEqualTo(28L);
        assertThat(PackTypewriterTtyConnector.delayMillis(
                PackOutputPacer.DelayMode.CHARACTER, 0.0)).isEqualTo(28L);
        assertThat(PackTypewriterTtyConnector.delayMillis(
                PackOutputPacer.DelayMode.CHARACTER, Double.NaN)).isEqualTo(28L);
    }
}
