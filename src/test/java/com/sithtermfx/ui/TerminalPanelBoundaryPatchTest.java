package com.sithtermfx.ui;

import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalTextBuffer;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * Regression coverage for the hyperlink hit-test boundary: the row just below the screen is not a
 * cell. korTTY carried this as a pinned patch until it shipped upstream in SithTermFX 1.2.3; the
 * test now pins the released behaviour against a regressing upgrade.
 */
class TerminalPanelBoundaryPatchTest {

    @Test
    void rejectsFirstRowBelowTerminalWhileKeepingLastScreenRowValid() {
        TerminalTextBuffer buffer = new TerminalTextBuffer(80, 24, new StyleState());

        assertThat(TerminalPanel.isCellInsideTextBuffer(new Cell(23, 79), buffer)).isTrue();
        assertThat(TerminalPanel.isCellInsideTextBuffer(new Cell(24, 0), buffer)).isFalse();
    }
}
