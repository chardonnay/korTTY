package com.sithtermfx.ui.split;

import com.sithtermfx.ui.split.TerminalSplitPane.PaneOverlayLayer;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The frame of a pane that runs another connection than its tab, in that connection's tab color: a
 * region in the pane's DECORATION layer below the focus ring and the mirror outline, which move
 * inside it while it shows, and a screen-reader text after the pane's name. The regions are checked
 * for real; the wiring into the split pane, which needs live terminal widgets, is pinned in the
 * source, line-ending agnostic. No toolkit is started.
 */
public class TerminalSplitPaneConnectionFrameTest {

    private static final Path SOURCE = Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java");

    @Test
    public void theFrameFillsThePaneBelowTheRingAndTheOutlineWithoutTakingInputOrLayout() {
        StackPane wrapper = new StackPane();
        wrapper.resize(500, 300);
        Pane decoration = TerminalSplitPane.overlayLayerOf(wrapper, PaneOverlayLayer.DECORATION);
        Region ring = TerminalSplitPane.focusRingOf(decoration);
        Region outline = TerminalSplitPane.mirrorOutlineOf(decoration);

        assertThat(TerminalSplitPane.findConnectionFrame(decoration)).isNull();
        Region frame = TerminalSplitPane.connectionFrameOf(decoration);

        assertThat(TerminalSplitPane.connectionFrameOf(decoration)).isSameInstanceAs(frame);
        assertThat(TerminalSplitPane.findConnectionFrame(decoration)).isSameInstanceAs(frame);
        assertWithMessage("the ring and the outline are drawn over the frame")
            .that(decoration.getChildren()).containsExactly(frame, ring, outline).inOrder();
        assertThat(frame.getStyleClass()).contains(TerminalSplitPane.CONNECTION_FRAME_STYLE_CLASS);
        assertThat(frame.isManaged()).isFalse();
        assertWithMessage("the pane's close button and the terminal keep every click")
            .that(frame.isMouseTransparent()).isTrue();
        assertThat(frame.isFocusTraversable()).isFalse();
        assertWithMessage("padding would shrink the terminal when the frame shows")
            .that(frame.getPadding().getTop() + frame.getPadding().getLeft()).isEqualTo(0.0);
        assertThat(frame.getWidth()).isEqualTo(500);
        assertThat(frame.getHeight()).isEqualTo(300);

        wrapper.resize(640, 200);
        assertThat(frame.getWidth()).isEqualTo(640);
        assertThat(frame.getHeight()).isEqualTo(200);
    }

    @Test
    public void theFrameIsThreePixelsOfTheConnectionsColorAndHidesWithoutOne() {
        Pane decoration = TerminalSplitPane.overlayLayerOf(new StackPane(), PaneOverlayLayer.DECORATION);
        Region frame = TerminalSplitPane.connectionFrameOf(decoration);

        TerminalSplitPane.showConnectionFrame(frame, Color.web("#D32F2F"));
        assertThat(frame.isVisible()).isTrue();
        BorderStroke stroke = frame.getBorder().getStrokes().get(0);
        assertThat(stroke.getTopStroke()).isEqualTo(Color.web("#D32F2F"));
        assertThat(stroke.getWidths().getTop()).isEqualTo(TerminalSplitPane.CONNECTION_FRAME_WIDTH);
        assertThat(stroke.getWidths().getLeft()).isEqualTo(TerminalSplitPane.CONNECTION_FRAME_WIDTH);
        assertWithMessage("as wide as the frame around a colored tab")
            .that(TerminalSplitPane.CONNECTION_FRAME_WIDTH).isEqualTo(3.0);
        assertWithMessage("drawn over the pane's edge, not inside a margin")
            .that(stroke.getInsets().getTop()).isEqualTo(0.0);

        TerminalSplitPane.showConnectionFrame(frame, null);
        assertThat(frame.isVisible()).isFalse();
        assertThat(frame.getBorder()).isNull();
    }

    @Test
    public void screenReadersHearTheConnectionAfterThePanesNameAndBeforeItsBadge() {
        String connection = "Connection db-prod, tab color red (#D32F2F)";

        assertThat(TerminalSplitPane.joinAccessibleText(
                TerminalSplitPane.joinAccessibleText("Pane 2 of 3", connection), "Multi-exec"))
            .isEqualTo("Pane 2 of 3, " + connection + ", Multi-exec");
        assertWithMessage("a single pane has no name but still says which connection it runs")
            .that(TerminalSplitPane.joinAccessibleText(TerminalSplitPane.joinAccessibleText(null, connection), null))
            .isEqualTo(connection);
    }

    @Test
    public void everyPaneShowsItsMarkWhenTheDecorationsAreRefreshed() throws IOException {
        String decorations = methodBody(source(), "private void refreshPaneDecorations(");
        assertThat(decorations).contains("PaneConnectionMark connectionMark = paneConnectionMarks.get(pane);");
        assertThat(decorations).contains(
            "refreshConnectionFrame(pane, connectionMark != null ? connectionMark.frameColor() : null);");
        assertThat(decorations).contains(
            "joinAccessibleText(joinAccessibleText(text, connectionText), mirrorText));");

        String frame = methodBody(source(), "private void refreshConnectionFrame(");
        assertWithMessage("the ring and the outline move inside the frame while it shows")
            .that(frame).contains("wrapper.pseudoClassStateChanged(CONNECTION_FRAMED, color != null);");
        assertWithMessage("a pane without a frame gets no layer for it")
            .that(frame).contains(": existingPaneOverlay(pane, PaneOverlayLayer.DECORATION);");
        assertThat(frame).contains("showConnectionFrame(frame, color);");

        String set = methodBody(source(), "public void setPaneConnectionMarks(");
        assertThat(set).contains("if (next.equals(paneConnectionMarks)) {");
        assertThat(set).contains("refreshPaneDecorations();");
        assertWithMessage("a closed pane's mark goes with it")
            .that(methodBody(source(), "private void forgetWidget(")).contains("paneConnectionMarks.remove(widget);");
        assertThat(methodBody(source(), "public void closeAll(")).contains("paneConnectionMarks.clear();");
    }

    @Test
    public void bothBaseStylesheetsMoveTheRingAndTheOutlineInsideTheFrame() throws IOException {
        for (String stylesheet : List.of("terminal.css", "atlantafx-kortty-components.css")) {
            String css = Files.readString(Path.of("src/main/resources/styles", stylesheet), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
            assertWithMessage(stylesheet).that(css).contains(".kortty-pane-cell:connection-framed ."
                + "kortty-pane-focus-ring {\n    -fx-border-insets: 3;\n}");
            assertWithMessage(stylesheet).that(css).contains(".kortty-pane-cell:connection-framed ."
                + TerminalSplitPane.MIRROR_OUTLINE_STYLE_CLASS + " {\n    -fx-border-insets: 5;\n}");
        }
    }

    private static String source() throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");
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
