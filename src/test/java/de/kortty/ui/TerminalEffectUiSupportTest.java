package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

public class TerminalEffectUiSupportTest {

    @Test
    void sliderValueForAnimationSpeedCapsAtSliderMaximum() {
        assertThat(TerminalEffectUiSupport.sliderValueForAnimationSpeed(99.0)).isEqualTo(10.0);
    }

    @Test
    void parsesTwoDigitAnimationSpeedInput() {
        assertThat(TerminalEffectUiSupport.parseAnimationSpeedInput("99")).isEqualTo(99.0);
    }

    @Test
    void rejectsZeroAnimationSpeedInput() {
        assertThat(TerminalEffectUiSupport.parseAnimationSpeedInput("0")).isNull();
    }

    @Test
    void formatsAnimationSpeedInputAsWholeNumber() {
        assertThat(TerminalEffectUiSupport.formatAnimationSpeedInput(12.0)).isEqualTo("12");
    }
}
