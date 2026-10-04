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
 * The markers of a pane whose typing goes to other panes: an amber outline and a badge in the pane's
 * DECORATION layer ("Multi-exec" for a member of the input mirror, "Broadcast" in a tab whose
 * broadcast mode reaches another pane), its accessible name, and the input mirror redrawing them when
 * its members change. The outline is checked on real regions; the badge is a Label, which needs the
 * JavaFX toolkit, so its wiring is pinned in the source, line-ending agnostic. No toolkit is started.
 */
public class TerminalSplitPaneMirrorMarkerTest {

    private static final Path SOURCE = Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java");

    @Test
    public void aMemberIsMarkedMultiExecAndABroadcastingPaneBroadcastAndOtherPanesNothing() {
        assertThat(TerminalSplitPane.mirrorBadgeText(true, false)).isEqualTo(I18n.get(TerminalSplitPane.MULTI_EXEC_BADGE_KEY));
        assertWithMessage("multi-exec reaches other tabs too, so it names the badge")
            .that(TerminalSplitPane.mirrorBadgeText(true, true)).isEqualTo(I18n.get(TerminalSplitPane.MULTI_EXEC_BADGE_KEY));
        assertThat(TerminalSplitPane.mirrorBadgeText(false, true)).isEqualTo(I18n.get(TerminalSplitPane.BROADCAST_BADGE_KEY));
        assertThat(TerminalSplitPane.mirrorBadgeText(false, false)).isNull();
    }

    @Test
    public void aScreenReaderHearsTheBadgeAfterThePanesName() {
        assertThat(TerminalSplitPane.joinAccessibleText("Pane 2 of 3", null)).isEqualTo("Pane 2 of 3");
        assertThat(TerminalSplitPane.joinAccessibleText("Pane 2 of 3", "Multi-exec")).isEqualTo("Pane 2 of 3, Multi-exec");
        assertWithMessage("a single pane has no name but still says it takes part")
            .that(TerminalSplitPane.joinAccessibleText(null, "Multi-exec")).isEqualTo("Multi-exec");
        assertThat(TerminalSplitPane.joinAccessibleText(null, null)).isNull();
    }

    @Test
    public void theOutlineFillsTheLayerBesideTheRingWithoutTakingInputOrLayout() {
        StackPane wrapper = new StackPane();
        wrapper.resize(500, 300);
        Pane decoration = TerminalSplitPane.overlayLayerOf(wrapper, PaneOverlayLayer.DECORATION);
        Region ring = TerminalSplitPane.focusRingOf(decoration);

        assertThat(TerminalSplitPane.findMirrorOutline(decoration)).isNull();
        Region outline = TerminalSplitPane.mirrorOutlineOf(decoration);

        assertThat(TerminalSplitPane.mirrorOutlineOf(decoration)).isSameInstanceAs(outline);
        assertThat(TerminalSplitPane.findMirrorOutline(decoration)).isSameInstanceAs(outline);
        assertWithMessage("the ring and the outline are two regions").that(outline).isNotSameInstanceAs(ring);
        assertThat(TerminalSplitPane.findFocusRing(decoration)).isSameInstanceAs(ring);
        assertThat(decoration.getChildren()).containsExactly(ring, outline).inOrder();
        assertThat(outline.getStyleClass()).contains(TerminalSplitPane.MIRROR_OUTLINE_STYLE_CLASS);
        assertThat(outline.isManaged()).isFalse();
        assertThat(outline.isMouseTransparent()).isTrue();
        assertThat(outline.isFocusTraversable()).isFalse();
        assertWithMessage("padding would shrink the terminal when a pane joins")
            .that(outline.getPadding().getTop() + outline.getPadding().getLeft()).isEqualTo(0.0);
        assertThat(outline.getWidth()).isEqualTo(500);
        assertThat(outline.getHeight()).isEqualTo(300);

        wrapper.resize(800, 250);
        assertThat(outline.getWidth()).isEqualTo(800);
        assertThat(outline.getHeight()).isEqualTo(250);
    }

    @Test
    public void everyPaneIsMarkedFromItsMembershipAndTheTabsBroadcastModeAfterTheZoomBadge() throws IOException {
        String decorations = methodBody(source(), "private void refreshPaneDecorations(");

        assertThat(decorations).contains("String mirrorText = mirrorBadgeText(isMirrorMember(pane), broadcastMode && several);");
        assertWithMessage("the badge comes last, after the pane's name and the connection it runs")
            .that(decorations).contains("panel.getCanvas().setAccessibleText(\n"
                + "                    joinAccessibleText(joinAccessibleText(text, connectionText), mirrorText));");
        assertWithMessage("a zoomed pane's mirror badge sits left of the zoom badge, so the zoom badge comes first")
            .that(decorations.indexOf("refreshMirrorMarker(panes.get(i), mirrorTexts.get(i));"))
            .isGreaterThan(decorations.indexOf("refreshZoomBadge(badgeText, hiddenReceivers > 0);"));
        assertWithMessage("the input mirror redraws the markers when its members change")
            .that(methodBody(source(), "public void refreshMirrorMarkers(")).contains("refreshPaneDecorations();");

        String marker = methodBody(source(), "private void refreshMirrorMarker(");
        assertThat(marker).contains("outline.setVisible(text != null);");
        assertThat(marker).contains("zoomed.layoutXProperty().subtract(MIRROR_BADGE_GAP)");
        assertThat(marker).contains("decoration.widthProperty().subtract(ZOOM_BADGE_RIGHT_INSET)");
        String badge = methodBody(source(), "private static @NotNull Label createMirrorBadge(");
        assertThat(badge).contains("badge.setMouseTransparent(true);");
        assertThat(badge).contains("badge.setFocusTraversable(false);");
        assertWithMessage("an icon, so the badge is not text and colour alone").that(badge).contains("badge.setGraphic(icon);");
    }

    @Test
    public void aHeldMemberIsAConnectedPaneOfThisSplitPaneTheGuardHoldsBack() throws IOException {
        assertThat(methodBody(source(), "public boolean isHeldMirrorTarget(")).contains(
            "return holdsWidget(widget) && isConnected(widget) && !acceptsMirroredInput(widget);");
    }

    @Test
    public void theMultiExecToggleSitsBelowBroadcastModeInExtras() throws IOException {
        String extras = methodBody(source(), "private @NotNull Menu createExtrasSubmenu(");
        int broadcast = extras.indexOf("extrasMenu.getItems().addAll(splitMenu, fontMenu, new SeparatorMenuItem(), broadcastToggle);");
        int mirror = extras.indexOf("mirrorMenuItemsFactory.apply(widget)");
        assertThat(broadcast).isAtLeast(0);
        assertThat(mirror).isGreaterThan(broadcast);
        assertWithMessage("a failing factory leaves the rest of the menu alone")
            .that(extras.substring(mirror)).contains("catch (RuntimeException e)");
    }

    @Test
    public void bothBaseStylesheetsDrawTheOutlineTheBadgeAndTheStatusChip() throws IOException {
        for (String stylesheet : List.of("terminal.css", "atlantafx-kortty-components.css")) {
            String css = Files.readString(Path.of("src/main/resources/styles", stylesheet), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
            assertWithMessage(stylesheet).that(css).contains("." + TerminalSplitPane.MIRROR_OUTLINE_STYLE_CLASS + " {\n"
                + "    -fx-border-color: -kortty-pane-badge-mirror-color;");
            assertWithMessage("%s: the outline sits just inside the focus ring", stylesheet)
                .that(css).contains("-fx-border-insets: 2;");
            assertWithMessage(stylesheet).that(css).contains("." + TerminalSplitPane.MIRROR_BADGE_STYLE_CLASS + " {\n"
                + "    -fx-background-color: -kortty-pane-badge-background;");
            assertWithMessage(stylesheet).that(css).contains("." + TerminalSplitPane.MIRROR_BADGE_STYLE_CLASS + " ."
                + TerminalSplitPane.MIRROR_BADGE_ICON_STYLE_CLASS + " {");
            assertWithMessage(stylesheet).that(css).contains(".status-bar .kortty-multi-exec-status {");
            assertWithMessage(stylesheet).that(css).contains(".tab-mirror-marker,");
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
