package com.sithtermfx.ui.split;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * {@code refreshSplitCloseButtons()} prunes {@code widgetCloseButtons} and
 * {@code widgetOverlayHosts} to the panes in {@code rootCell}, then shows the close buttons only
 * while there is more than one pane. The leaf {@code SplitCell} constructor used to call it right
 * after registering its own pane, before the cell had joined the tree, so every pane lost both
 * entries at once: {@link TerminalSplitPane#getWidgetOverlayHost} answered null, and no close button
 * was ever hidden, leaving an "x" on single-pane tabs that emptied the tab when clicked.
 *
 * <p>The refresh therefore belongs to whoever just put the cell into {@code rootCell}. Proving it
 * live needs a JavaFX toolkit (the {@code terminalSplitCloseButtonSmoke} task does), so like
 * {@code TerminalSplitPaneFocusTrackingTest} this test reads the source (line-ending agnostic).
 */
public class TerminalSplitPaneCloseButtonTest {

    private static final Path SOURCE = Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java");

    private static final String REFRESH_CALL = "refreshSplitCloseButtons();";

    /** The constructor that builds the first pane refreshes once that pane is the root, before any lambda. */
    private static final Pattern CONSTRUCTOR_REFRESHES_AFTER_ROOT = Pattern.compile(
        "this\\.rootCell\\s*=\\s*createInitialCell\\(\\);[^{}]*?refreshSplitCloseButtons\\(\\);");

    @Test
    public void leafCellDoesNotRefreshBeforeJoiningTheTree() throws IOException {
        String source = read();
        String leafConstructor = methodBody(source, "SplitCell(@NotNull SithTermFxWidget widget)");

        assertWithMessage("the leaf SplitCell constructor must exist").that(leafConstructor).isNotNull();
        assertWithMessage("the leaf SplitCell constructor still registers the pane's close button")
            .that(leafConstructor).contains("widgetCloseButtons.put(widget, closeButton)");
        assertWithMessage("the leaf SplitCell constructor still registers the pane's overlay host")
            .that(leafConstructor).contains("widgetOverlayHosts.put(widget, wrapper)");
        assertWithMessage("the leaf SplitCell constructor must not prune against a tree it has not joined")
            .that(stripComments(leafConstructor)).doesNotContain(REFRESH_CALL);
    }

    @Test
    public void constructorRefreshesOnceTheFirstPaneIsTheRoot() throws IOException {
        assertWithMessage("the constructor must refresh the close buttons after rootCell = createInitialCell()")
            .that(CONSTRUCTOR_REFRESHES_AFTER_ROOT.matcher(read()).find()).isTrue();
    }

    @Test
    public void splitAndCloseRefreshAfterReplacingTheRoot() throws IOException {
        String source = read();
        for (String signature : new String[] {
            "public @Nullable SithTermFxWidget splitWidget(", "private void closeSplit("}) {
            String body = methodBody(source, signature);
            assertWithMessage(signature + " must exist").that(body).isNotNull();
            int rootAssigned = body.indexOf("rootCell = replacement;");
            int refreshed = body.lastIndexOf(REFRESH_CALL);
            assertWithMessage(signature + " must replace rootCell").that(rootAssigned).isAtLeast(0);
            assertWithMessage(signature + " must refresh the close buttons after replacing rootCell")
                .that(refreshed).isGreaterThan(rootAssigned);
        }
    }

    /** A split of a pane outside the tree must stop before building a pane that can never join it. */
    @Test
    public void splitOfAPaneOutsideTheTreeBuildsNothing() throws IOException {
        String body = methodBody(read(), "public @Nullable SithTermFxWidget splitWidget(");

        assertThat(body).isNotNull();
        int guard = body.indexOf("if (!getAllWidgets().contains(widget))");
        assertWithMessage("splitWidget must check that the pane belongs to the tree").that(guard).isAtLeast(0);
        assertWithMessage("the tree check must come before the new pane is built")
            .that(guard).isLessThan(body.indexOf("createWidget("));
    }

    /** Both ways a built pane can fail to join the tree release it and drop its map entries. */
    @Test
    public void unattachedPanesAreReleased() throws IOException {
        String source = read();
        String splitWidget = methodBody(source, "public @Nullable SithTermFxWidget splitWidget(");
        String release = methodBody(source, "private void releaseUnattachedWidget(");

        assertThat(splitWidget).isNotNull();
        assertWithMessage("releaseUnattachedWidget must exist").that(release).isNotNull();
        assertThat(release).contains("notifyWidgetClosed(widget)");
        assertThat(release).contains("forgetWidget(widget)");
        int releases = 0;
        for (int at = splitWidget.indexOf("releaseUnattachedWidget(newWidget)"); at >= 0;
             at = splitWidget.indexOf("releaseUnattachedWidget(newWidget)", at + 1)) {
            releases++;
        }
        assertWithMessage("splitWidget must release the pane on a failed connect and on a failed insert")
            .that(releases).isEqualTo(2);
    }

    private static String read() throws IOException {
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    private static String stripComments(String code) {
        return code.replaceAll("(?m)//.*$", "").replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /** The text from {@code signature} to the brace closing its body, or null when it is absent. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            return null;
        }
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
        return null;
    }
}
