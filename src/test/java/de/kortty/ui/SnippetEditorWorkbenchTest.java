package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/** How the snippet editor's content row shares its width between the code and the analysis panel. */
class SnippetEditorWorkbenchTest {

    private static final double PANEL_MIN = 360;
    private static final double EDITOR_MIN = SnippetEditorWorkbench.MIN_EDITOR_WIDTH;

    @Test
    void thePanelGetsItsPreferredWidthWhenThereIsRoom() {
        assertThat(SnippetEditorWorkbench.panelWidth(1500, 740, PANEL_MIN, EDITOR_MIN)).isEqualTo(740.0);
        assertThat(SnippetEditorWorkbench.panelWidth(900, 520, PANEL_MIN, EDITOR_MIN)).isEqualTo(520.0);
    }

    @Test
    void thePanelYieldsSoTheCodeKeepsItsMinimum() {
        // 900 px: the code keeps 280, so a 740 px wish is capped to 620.
        assertThat(SnippetEditorWorkbench.panelWidth(900, 740, PANEL_MIN, EDITOR_MIN)).isEqualTo(900 - EDITOR_MIN);
    }

    @Test
    void thePanelNeverGoesBelowItsMinimum() {
        assertThat(SnippetEditorWorkbench.panelWidth(500, 740, PANEL_MIN, EDITOR_MIN)).isEqualTo(PANEL_MIN);
        assertThat(SnippetEditorWorkbench.panelWidth(100, 740, PANEL_MIN, EDITOR_MIN)).isEqualTo(PANEL_MIN);
        assertThat(SnippetEditorWorkbench.panelWidth(1500, 200, PANEL_MIN, EDITOR_MIN)).isEqualTo(PANEL_MIN);
    }

    @Test
    void anUnknownPreferredWidthFallsBackToTheMinimum() {
        assertThat(SnippetEditorWorkbench.panelWidth(1500, Double.NaN, PANEL_MIN, EDITOR_MIN)).isEqualTo(PANEL_MIN);
        assertThat(SnippetEditorWorkbench.panelWidth(1500, -1, PANEL_MIN, EDITOR_MIN)).isEqualTo(PANEL_MIN);
    }

    @Test
    void theCodeWidthIsWhatTheDividerAndThePanelLeaveOver() {
        double divider = SnippetEditorWorkbench.DIVIDER_WIDTH;
        assertThat(SnippetEditorWorkbench.codeWidth(1540, 740, PANEL_MIN)).isEqualTo(1540 - divider - 740);
        assertThat(SnippetEditorWorkbench.codeWidth(900, 740, PANEL_MIN)).isEqualTo(EDITOR_MIN);
        assertThat(SnippetEditorWorkbench.codeWidth(500, 740, PANEL_MIN)).isEqualTo(500 - divider - PANEL_MIN);
        assertThat(SnippetEditorWorkbench.codeWidth(200, 740, PANEL_MIN)).isEqualTo(0.0);
    }

    @Test
    void theDockedColumnKeepsItsWidthAndYieldsBeforeThePanelMinimum() {
        // Room for everything: the column gets its preferred width.
        assertThat(SnippetEditorWorkbench.dockWidth(2000, PANEL_MIN, 400, 300)).isEqualTo(400.0);
        // Tight: the code and the panel keep their minimums, the column shrinks first ...
        assertThat(SnippetEditorWorkbench.dockWidth(1000, PANEL_MIN, 400, 300))
            .isEqualTo(1000 - PANEL_MIN - EDITOR_MIN);
        // ... but never below its own minimum.
        assertThat(SnippetEditorWorkbench.dockWidth(700, PANEL_MIN, 400, 300)).isEqualTo(300.0);
    }
}
