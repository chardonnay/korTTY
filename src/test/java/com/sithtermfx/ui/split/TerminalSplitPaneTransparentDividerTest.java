package com.sithtermfx.ui.split;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * The dividers between split panes in a see-through window: the designs draw them half
 * see-through over the split control's background, which is transparent there, so they showed as
 * a clear gap to the desktop. While transparent they get the host's fill at full opacity; otherwise
 * the design's divider stays untouched. No toolkit is started.
 */
public class TerminalSplitPaneTransparentDividerTest {

    @Test
    public void aTransparentBackgroundFillsTheDividerAtFullOpacity() {
        assertThat(TerminalSplitPane.transparentDividerStyle(true, "rgba(54,54,54,0.500)"))
            .isEqualTo("-fx-background-color: rgba(54,54,54,0.500); -fx-opacity: 1;");
    }

    @Test
    public void anOpaqueBackgroundOrNoColorKeepsTheDesignsDivider() {
        assertThat(TerminalSplitPane.transparentDividerStyle(false, "rgba(54,54,54,0.500)")).isNull();
        assertThat(TerminalSplitPane.transparentDividerStyle(true, null)).isNull();
        assertThat(TerminalSplitPane.transparentDividerStyle(true, " ")).isNull();
    }
}
