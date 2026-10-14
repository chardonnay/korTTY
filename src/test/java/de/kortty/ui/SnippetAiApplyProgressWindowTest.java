package de.kortty.ui;

import javafx.geometry.Rectangle2D;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class SnippetAiApplyProgressWindowTest {

    private static final Rectangle2D SCREEN = new Rectangle2D(0, 25, 1920, 1055);

    @Test
    void opensRightOfTheEditorWhenThatFitsOnTheScreen() {
        Rectangle2D placed = SnippetAiApplyProgressWindow.besideOwner(100, 80, 1200, 900, 400, SCREEN);
        assertThat(placed.getMinX()).isEqualTo(100 + 1200 + SnippetAiApplyProgressWindow.GAP);
        assertThat(placed.getMinY()).isEqualTo(80.0);
        assertThat(placed.getHeight()).isEqualTo(900.0);
    }

    @Test
    void staysOnTheScreenBesideAWideEditor() {
        // No room right of the editor: the window sits at the screen's right edge, over the editor.
        Rectangle2D placed = SnippetAiApplyProgressWindow.besideOwner(0, 25, 1900, 1100, 400, SCREEN);
        assertThat(placed.getMaxX()).isEqualTo(SCREEN.getMaxX());
        assertThat(placed.getMaxY()).isAtMost(SCREEN.getMaxY());
    }

    @Test
    void anUnknownScreenPlacesItRightOfTheEditor() {
        Rectangle2D placed = SnippetAiApplyProgressWindow.besideOwner(10, 20, 800, 600, 400, null);
        assertThat(placed.getMinX()).isEqualTo(10 + 800 + SnippetAiApplyProgressWindow.GAP);
        assertThat(placed.getHeight()).isEqualTo(600.0);
    }
}
