package com.sithtermfx.ui.split;

import com.sithtermfx.ui.split.TerminalSplitPane.PaneOverlayLayer;
import de.kortty.ui.I18n;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The per-pane overlay layers korTTY draws over a terminal (the hover underline of a link and quick
 * select's labels, the focus ring, the drop zones of a pane move) must never take a click, a hover or
 * layout space from the terminal, must stay above the terminal and the effect overlays in their fixed
 * order whatever was added to the pane's wrapper later, and must cover the whole pane. Toolkit-free:
 * layout panes can be built and resized without a running JavaFX toolkit.
 */
public class TerminalSplitPaneOverlayLayerTest {

    @Test
    public void aLayerIsUnmanagedMouseTransparentAndNotFocusable() {
        for (PaneOverlayLayer kind : PaneOverlayLayer.values()) {
            Pane layer = TerminalSplitPane.createOverlayLayer(kind);

            assertThat(layer.isManaged()).isFalse();
            assertThat(layer.isMouseTransparent()).isTrue();
            assertThat(layer.isPickOnBounds()).isFalse();
            assertThat(layer.isFocusTraversable()).isFalse();
            assertThat(layer.getChildren()).isEmpty();
            assertThat(layer.getViewOrder()).isEqualTo(kind.viewOrder());
        }
    }

    @Test
    public void layersAreDrawnAboveEverythingAtTheDefaultViewOrderInDeclarationOrder() {
        double previous = 0;
        for (PaneOverlayLayer layer : PaneOverlayLayer.values()) {
            // JavaFX draws a lower view order on top; the terminal and effect overlays keep 0.
            assertThat(layer.viewOrder()).isLessThan(previous);
            previous = layer.viewOrder();
        }
        assertWithMessage("links, then the focus ring and badges, then the drop zones of a pane move on top")
            .that(List.of(PaneOverlayLayer.values()))
            .containsExactly(PaneOverlayLayer.LINKS, PaneOverlayLayer.DECORATION, PaneOverlayLayer.DROP_ZONES)
            .inOrder();
    }

    @Test
    public void aLayerIsAddedOnceAndFollowsTheSizeOfThePane() {
        StackPane wrapper = new StackPane();
        wrapper.resize(640, 480);

        Pane links = TerminalSplitPane.overlayLayerOf(wrapper, PaneOverlayLayer.LINKS);
        Pane decoration = TerminalSplitPane.overlayLayerOf(wrapper, PaneOverlayLayer.DECORATION);

        assertThat(TerminalSplitPane.overlayLayerOf(wrapper, PaneOverlayLayer.LINKS)).isSameInstanceAs(links);
        assertThat(wrapper.getChildren()).containsExactly(links, decoration).inOrder();
        assertThat(decoration.getWidth()).isEqualTo(640);
        assertThat(decoration.getHeight()).isEqualTo(480);

        wrapper.resize(300, 200);
        assertThat(links.getWidth()).isEqualTo(300);
        assertThat(decoration.getHeight()).isEqualTo(200);
        assertWithMessage("an unmanaged layer never asks for room").that(wrapper.prefWidth(-1)).isEqualTo(0);
    }

    @Test
    public void theFocusRingFillsItsLayerWithoutTakingInputOrLayout() {
        StackPane wrapper = new StackPane();
        wrapper.resize(500, 300);
        Pane decoration = TerminalSplitPane.overlayLayerOf(wrapper, PaneOverlayLayer.DECORATION);

        assertThat(TerminalSplitPane.findFocusRing(decoration)).isNull();
        Region ring = TerminalSplitPane.focusRingOf(decoration);

        assertThat(TerminalSplitPane.focusRingOf(decoration)).isSameInstanceAs(ring);
        assertThat(TerminalSplitPane.findFocusRing(decoration)).isSameInstanceAs(ring);
        assertThat(decoration.getChildren()).containsExactly(ring);
        assertThat(ring.getStyleClass()).contains(TerminalSplitPane.FOCUS_RING_STYLE_CLASS);
        assertThat(ring.isManaged()).isFalse();
        assertThat(ring.isMouseTransparent()).isTrue();
        assertThat(ring.isFocusTraversable()).isFalse();
        assertWithMessage("padding would shrink the terminal on every focus change")
            .that(ring.getPadding().getTop() + ring.getPadding().getLeft()).isEqualTo(0.0);
        assertThat(ring.getWidth()).isEqualTo(500);
        assertThat(ring.getHeight()).isEqualTo(300);

        wrapper.resize(800, 250);
        assertThat(ring.getWidth()).isEqualTo(800);
        assertThat(ring.getHeight()).isEqualTo(250);
    }

    @Test
    public void onlyASplitTabNamesItsPanes() {
        assertThat(TerminalSplitPane.paneAccessibleName(0, 1)).isNull();
        assertThat(TerminalSplitPane.paneAccessibleName(1, 3))
            .isEqualTo(I18n.get(TerminalSplitPane.PANE_ACCESSIBLE_NAME_KEY, 2, 3));
        assertThat(TerminalSplitPane.paneAccessibleName(1, 3)).contains("2");
        assertThat(TerminalSplitPane.paneAccessibleName(1, 3)).contains("3");
    }

    @Test
    public void bothBaseStylesheetsDrawTheRingFromTheCellsFocusState() throws IOException {
        for (String stylesheet : List.of("terminal.css", "atlantafx-kortty-components.css")) {
            String css = Files.readString(Path.of("src/main/resources/styles", stylesheet), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
            assertWithMessage(stylesheet).that(css).contains(".kortty-pane-cell {\n    -kortty-pane-focus-color:");
            assertWithMessage(stylesheet).that(css).contains("-kortty-pane-last-focus-color:");
            int lastFocused = css.indexOf(".kortty-pane-cell:last-focused .kortty-pane-focus-ring {");
            int focusWithin = css.indexOf(".kortty-pane-cell:focus-within .kortty-pane-focus-ring {");
            assertWithMessage(stylesheet).that(lastFocused).isAtLeast(0);
            assertWithMessage("%s: equal specificity, so the real focus must come later and win", stylesheet)
                .that(focusWithin).isGreaterThan(lastFocused);
            String ring = css.substring(css.indexOf(".kortty-pane-focus-ring {"));
            ring = ring.substring(0, ring.indexOf('}'));
            assertWithMessage("%s: the ring must not pad the pane", stylesheet).that(ring).doesNotContain("-fx-padding");
        }
    }

    @Test
    public void theSplitPaneMarksItsCellsAndRefreshesTheRingAfterEveryChange() throws IOException {
        String source = Files.readString(
            Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java"), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");

        assertThat(source).contains("static final String PANE_CELL_STYLE_CLASS = \"kortty-pane-cell\";");
        assertThat(source).contains("static final String FOCUS_RING_STYLE_CLASS = \"kortty-pane-focus-ring\";");
        assertThat(source).contains("PseudoClass.getPseudoClass(\"last-focused\")");
        assertThat(source).contains("wrapper.getStyleClass().add(PANE_CELL_STYLE_CLASS);");
        assertWithMessage("the last-focused mark follows every change of the focused pane")
            .that(methodBody(source, "private void setFocusedWidgetInternal(")).contains("refreshLastFocusedMarks();");
        assertWithMessage("split and close refresh the ring and the names")
            .that(methodBody(source, "private void refreshSplitCloseButtons(")).contains("refreshPaneDecorations();");
        assertWithMessage("a moved pane gets its new number")
            .that(methodBody(source, "public void moveWidget(")).contains("refreshPaneDecorations();");
        assertWithMessage("the drop zones of a pane move live in their own layer")
            .that(source).contains("splitPane.paneOverlay(targetWidget, PaneOverlayLayer.DROP_ZONES)");
    }

    /** The text from {@code signature} to the brace closing its body. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("unbalanced method: " + signature);
    }
}
