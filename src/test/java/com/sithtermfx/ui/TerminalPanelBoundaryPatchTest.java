package com.sithtermfx.ui;

import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalTextBuffer;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/** Regression coverage for korTTY's pinned SithTermFX 1.2.1 boundary patch. */
class TerminalPanelBoundaryPatchTest {

    @Test
    void rejectsFirstRowBelowTerminalWhileKeepingLastScreenRowValid() {
        TerminalTextBuffer buffer = new TerminalTextBuffer(80, 24, new StyleState());

        assertThat(TerminalPanel.isCellInsideTextBuffer(new Cell(23, 79), buffer)).isTrue();
        assertThat(TerminalPanel.isCellInsideTextBuffer(new Cell(24, 0), buffer)).isFalse();
    }
}
