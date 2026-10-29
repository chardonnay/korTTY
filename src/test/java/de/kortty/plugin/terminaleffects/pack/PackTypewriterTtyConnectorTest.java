package de.kortty.plugin.terminaleffects.pack;

import com.sithtermfx.core.util.PixelSize;
import com.sithtermfx.core.util.TermSize;
import de.kortty.core.SizeRecordingTtyConnector;
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

    @Test
    void resizeForwardsThePixelSize() {
        SizeRecordingTtyConnector base = new SizeRecordingTtyConnector();
        PackTypewriterTtyConnector connector = new PackTypewriterTtyConnector(base, () -> 1.0);

        connector.resize(new TermSize(100, 30), new PixelSize(900, 540));
        connector.resize(new TermSize(80, 24));

        assertThat(base.resizes()).containsExactly("100x30@900x540px", "80x24").inOrder();
    }
}
