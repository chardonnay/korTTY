package com.sithtermfx.ui.split;

import com.sithtermfx.ui.split.TerminalSplitPane.PaneOverlayLayer;
import javafx.scene.layout.Pane;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * The per-pane overlay layers korTTY draws over a terminal (today the hover underline of a link)
 * must never take a click, a hover or layout space from the terminal, and must stay above the
 * terminal and the effect overlays whatever was added to the pane's wrapper later. Toolkit-free:
 * a layout pane can be built without a running JavaFX toolkit.
 */
public class TerminalSplitPaneOverlayLayerTest {

    @Test
    public void aLayerIsUnmanagedMouseTransparentAndNotFocusable() {
        Pane layer = TerminalSplitPane.createOverlayLayer(PaneOverlayLayer.LINKS);

        assertThat(layer.isManaged()).isFalse();
        assertThat(layer.isMouseTransparent()).isTrue();
        assertThat(layer.isPickOnBounds()).isFalse();
        assertThat(layer.isFocusTraversable()).isFalse();
        assertThat(layer.getChildren()).isEmpty();
        assertThat(layer.getViewOrder()).isEqualTo(PaneOverlayLayer.LINKS.viewOrder());
    }

    @Test
    public void layersAreDrawnAboveEverythingAtTheDefaultViewOrderInDeclarationOrder() {
        double previous = 0;
        for (PaneOverlayLayer layer : PaneOverlayLayer.values()) {
            // JavaFX draws a lower view order on top; the terminal and effect overlays keep 0.
            assertThat(layer.viewOrder()).isLessThan(previous);
            previous = layer.viewOrder();
        }
    }
}
