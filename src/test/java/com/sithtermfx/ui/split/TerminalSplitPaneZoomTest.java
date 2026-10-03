package com.sithtermfx.ui.split;

import de.kortty.ui.I18n;
import javafx.scene.layout.Region;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * A zoomed pane reparents nodes: its wrapper becomes the split pane's only child while a placeholder
 * keeps its place. Every path that changes the tree, moves the focus to another pane or starts a pane
 * drag must therefore show all panes again first, or it would rebuild the tree around a placeholder
 * or focus a pane that is out of the scene. Proving that live needs a shown window
 * ({@code paneKeyboardSmoke} does), so like {@code TerminalSplitPaneFocusTrackingTest} this test pins
 * the source, line-ending agnostic; the zoom itself is tested on a pure model in {@link PaneZoomTest}.
 * The badge text, the placeholder and the stylesheets are checked directly; no JavaFX toolkit is
 * started.
 */
public class TerminalSplitPaneZoomTest {

    private static final Path SOURCE = Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java");

    @Test
    public void everyChangeToTheTreeShowsThePanesBeforeItRebuildsIt() throws IOException {
        String source = source();

        String split = methodBody(source, "public @Nullable SithTermFxWidget splitWidget(");
        assertWithMessage("a split that failed keeps the zoom; one that goes ahead shows the panes first")
            .that(split.indexOf("unzoom();")).isGreaterThan(split.indexOf("releaseUnattachedWidget(newWidget);"));
        assertThat(split.indexOf("unzoom();")).isLessThan(split.indexOf("rootCell.replaceWidget("));

        String close = methodBody(source, "private void closeSplit(");
        assertThat(close.indexOf("unzoom();")).isAtLeast(0);
        assertThat(close.indexOf("unzoom();")).isLessThan(close.indexOf("rootCell.removeWidget("));

        String move = methodBody(source, "public void moveWidget(");
        assertThat(move.indexOf("unzoom();")).isAtLeast(0);
        assertThat(move.indexOf("unzoom();")).isLessThan(move.indexOf("rootCell.extractWidget("));

        String closeAll = methodBody(source, "public void closeAll(");
        assertThat(closeAll.indexOf("unzoom();")).isLessThan(closeAll.indexOf("rootCell.closeAll();"));

        assertWithMessage("a side dock shows every pane's agent panel")
            .that(methodBody(source, "public void setBottomPanelsDetached(")).contains("unzoom();");
    }

    @Test
    public void movingTheFocusToAnotherPaneShowsThePanesFirst() throws IOException {
        String source = source();

        String focus = methodBody(source, "public void focusWidget(");
        assertWithMessage("the Control API's pane.focus and the Coding Agents panel come through here")
            .that(focus).contains("if (zoomedWidget != null && widget != zoomedWidget) {\n            unzoom();");
        assertThat(focus.indexOf("unzoom();")).isLessThan(focus.indexOf("requestWidgetFocus(widget);"));

        String neighbor = methodBody(source, "public boolean focusNeighbor(");
        assertWithMessage("the panes are measured after they are laid out again")
            .that(neighbor).contains("if (unzoom()) {\n            applyCss();\n            layout();\n        }");
        assertThat(neighbor.indexOf("unzoom()")).isLessThan(neighbor.indexOf("PaneNavigator.neighbor("));

        String next = methodBody(source, "public boolean focusNext(");
        assertThat(next.indexOf("unzoom();")).isAtLeast(0);
        assertThat(next.indexOf("unzoom();")).isLessThan(next.indexOf("PaneNavigator.next("));
    }

    @Test
    public void aPaneDragShowsThePanesToDropOnto() throws IOException {
        String source = source();
        int filter = source.indexOf("addEventFilter(MouseEvent.DRAG_DETECTED, event -> {");
        assertThat(filter).isAtLeast(0);
        String body = source.substring(filter, source.indexOf("});", filter));

        assertThat(body).contains("if (!(event.isShiftDown() && event.isAltDown())) {\n                event.consume();\n"
            + "                return;\n            }\n            unzoom();");
    }

    @Test
    public void theZoomedPaneHasTheBadgeAndNoPaneARing() throws IOException {
        String source = source();
        String decorations = methodBody(source, "private void refreshPaneDecorations(");

        assertThat(decorations).contains("ring.setVisible(several && zoomedWidget == null);");
        assertThat(decorations).contains("refreshZoomBadge(badgeText, hiddenReceivers > 0);");
        assertWithMessage("the badge counts the hidden panes broadcast mode or the input mirror still reaches")
            .that(decorations).contains("hiddenMirrorReceivers(zoomedWidget, panes)");
        String receivers = methodBody(source, "private int hiddenMirrorReceivers(");
        assertWithMessage("the same targets the keys go to, held panes left out")
            .that(receivers).contains("for (SithTermFxWidget target : mirrorTargetsOf(zoomed)) {");
        assertWithMessage("members in other tabs are not hidden panes of this one")
            .that(receivers).contains("if (panes.contains(target)) {");
        assertWithMessage("a new input mirror while zoomed updates the badge")
            .that(methodBody(source, "public void setInputMirror(")).contains("refreshPaneDecorations();");
        assertWithMessage("a screen reader hears that the pane is zoomed")
            .that(decorations).contains("name + \", \" + badgeText");
        assertThat(methodBody(source, "private void refreshZoomBadge("))
            .contains("paneOverlay(zoomedWidget, PaneOverlayLayer.DECORATION)");
        String badge = methodBody(source, "private static @NotNull Label createZoomBadge(");
        assertThat(badge).contains("badge.setMouseTransparent(true);");
        assertThat(badge).contains("badge.setFocusTraversable(false);");
        assertThat(badge).contains("badge.setGraphic(icon);");
        assertWithMessage("switching broadcast mode while zoomed updates the badge")
            .that(methodBody(source, "public void setBroadcastMode(")).contains("refreshPaneDecorations();");
    }

    @Test
    public void unzoomWritesTheDividersAgainAfterTheLayoutButNotIntoAChangedTree() throws IOException {
        String unzoom = methodBody(source(), "public boolean unzoom(");

        assertThat(unzoom).contains("zoomed.restore();");
        assertThat(unzoom).contains("Platform.runLater(() -> Platform.runLater(() -> {");
        assertThat(unzoom).contains("if (rootCell == tree && zoom == null) {\n                zoomed.reapplyDividers();");
        assertThat(unzoom.indexOf("zoom = null;")).isLessThan(unzoom.indexOf("zoomed.restore();"));
    }

    @Test
    public void aProjectSavedWhileZoomedStoresTheDividersWithoutTheZoom() throws IOException {
        assertThat(methodBody(source(), "public double @NotNull [] dividerPositionsOf("))
            .contains("zoom != null ? zoom.savedPositions(control) : null");
        String view = Files.readString(Path.of("src/main/java/de/kortty/ui/TerminalView.java"), StandardCharsets.UTF_8);
        assertThat(view).contains("double[] positions = splitPane.dividerPositionsOf(splitPaneObj);");
        assertThat(view).doesNotContain("splitPaneObj.getDividerPositions()");
    }

    @Test
    public void theBadgeNamesTheHiddenPanesAndThoseThatStillReceiveInput() {
        assertThat(TerminalSplitPane.zoomBadgeText(2, 0)).isEqualTo(I18n.get(TerminalSplitPane.ZOOMED_BADGE_KEY, 2));
        assertThat(TerminalSplitPane.zoomBadgeText(2, 0)).contains("2");
        assertThat(TerminalSplitPane.zoomBadgeText(3, 2))
            .isEqualTo(I18n.get(TerminalSplitPane.ZOOMED_MIRROR_BADGE_KEY, 3, 2));
        assertThat(TerminalSplitPane.zoomBadgeText(3, 2)).contains("3");
        assertThat(TerminalSplitPane.zoomBadgeText(3, 2)).contains("2");
        assertThat(TerminalSplitPane.zoomBadgeText(3, 2)).isNotEqualTo(TerminalSplitPane.zoomBadgeText(3, 0));
    }

    @Test
    public void thePlaceholderTakesNoRoomAndNoFocus() {
        Region placeholder = TerminalSplitPane.createZoomPlaceholder();

        assertThat(placeholder.getMinWidth()).isEqualTo(0.0);
        assertThat(placeholder.getMinHeight()).isEqualTo(0.0);
        assertThat(placeholder.getMaxWidth()).isEqualTo(Double.MAX_VALUE);
        assertThat(placeholder.isFocusTraversable()).isFalse();
        assertThat(placeholder.getChildrenUnmodifiable()).isEmpty();
        assertThat(placeholder.getStyleClass()).contains(TerminalSplitPane.ZOOM_PLACEHOLDER_STYLE_CLASS);
    }

    @Test
    public void bothBaseStylesheetsDrawTheZoomBadgeWithLookedUpColours() throws IOException {
        for (String stylesheet : List.of("terminal.css", "atlantafx-kortty-components.css")) {
            String css = Files.readString(Path.of("src/main/resources/styles", stylesheet), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
            String cell = css.substring(css.indexOf(".kortty-pane-cell {"));
            cell = cell.substring(0, cell.indexOf('}'));
            assertWithMessage(stylesheet).that(cell).contains("-kortty-pane-badge-background:");
            assertWithMessage(stylesheet).that(cell).contains("-kortty-pane-badge-text:");
            assertWithMessage(stylesheet).that(cell).contains("-kortty-pane-badge-mirror-color:");
            assertWithMessage(stylesheet).that(css).contains("." + TerminalSplitPane.ZOOM_BADGE_STYLE_CLASS + " {\n"
                + "    -fx-background-color: -kortty-pane-badge-background;");
            assertWithMessage(stylesheet).that(css).contains("." + TerminalSplitPane.ZOOM_BADGE_STYLE_CLASS + " ."
                + TerminalSplitPane.ZOOM_BADGE_ICON_STYLE_CLASS + " {");
            assertWithMessage("%s: hidden panes that receive input are marked by more than the text", stylesheet)
                .that(css).contains("." + TerminalSplitPane.ZOOM_BADGE_STYLE_CLASS + ":mirroring {");
        }
        assertThat(TerminalSplitPane.MIRRORING.getPseudoClassName()).isEqualTo("mirroring");
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
