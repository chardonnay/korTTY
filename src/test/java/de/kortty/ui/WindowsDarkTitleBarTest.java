package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

public class WindowsDarkTitleBarTest {

    @Test
    void zeroMeansDarkAppMode() {
        assertThat(WindowsDarkTitleBar.isDarkAppMode(0)).isTrue();
    }

    @Test
    void lightOrMissingValueMeansLight() {
        assertThat(WindowsDarkTitleBar.isDarkAppMode(1)).isFalse();
        assertThat(WindowsDarkTitleBar.isDarkAppMode(null)).isFalse();
    }
}
