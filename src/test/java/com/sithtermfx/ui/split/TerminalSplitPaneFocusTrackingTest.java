package com.sithtermfx.ui.split;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Edit &gt; Find, Copy/Paste, the AI run context and the recording scope act on
 * {@link TerminalSplitPane#getFocusedWidget()}. That field was only written on a primary click on
 * the pane and from a focus listener on the pane's preferred focusable node, whose
 * {@code focused} property stays false while the terminal canvas inside it holds the keyboard
 * focus. Focus that reached another pane any other way (a middle click, the window regaining focus
 * after a split's connect dialog, a focus request from code) left the commands on the old pane
 * while the keystrokes went to the new one.
 *
 * <p>Two things keep it true and are pinned here: every write goes through
 * {@code setFocusedWidgetInternal}, and each pane follows its canvas's {@code focused} property.
 * Proving the latter live needs a shown, focused window, so like
 * {@code TerminalWidgetReflectionGuardTest} this test reads the source (line-ending agnostic).
 */
public class TerminalSplitPaneFocusTrackingTest {

    private static final Path SOURCE = Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java");

    /** An assignment to the focused-widget field (a comparison has a second '='). */
    private static final Pattern FIELD_WRITE = Pattern.compile("\\bfocusedWidget\\s*=(?!=)");

    /** A canvas focus listener whose body moves the split pane's focused widget to the pane. */
    private static final Pattern CANVAS_FOCUS_FOLLOWED = Pattern.compile(
        "getCanvas\\(\\)\\s*\\.\\s*focusedProperty\\(\\)\\s*\\.\\s*addListener\\([^;]*?setFocusedWidgetInternal\\(widget\\)");

    @Test
    public void onlySetFocusedWidgetInternalWritesTheField() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String setter = methodBody(source, "private void setFocusedWidgetInternal(");

        List<String> writesOutsideSetter = new ArrayList<>();
        int writesInSetter = 0;
        Matcher matcher = FIELD_WRITE.matcher(source);
        while (matcher.find()) {
            if (setter != null && isWithin(source, setter, matcher.start())) {
                writesInSetter++;
            } else {
                writesOutsideSetter.add("TerminalSplitPane.java:" + lineOf(source, matcher.start()));
            }
        }

        assertWithMessage("setFocusedWidgetInternal must exist").that(setter).isNotNull();
        assertWithMessage("direct writes to focusedWidget bypass setFocusedWidgetInternal")
            .that(writesOutsideSetter).isEmpty();
        assertThat(writesInSetter).isEqualTo(1);
    }

    @Test
    public void eachPaneFollowsItsCanvasFocus() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String setupWidget = methodBody(source, "private void setupWidget(");

        assertWithMessage("setupWidget must exist").that(setupWidget).isNotNull();
        assertWithMessage("setupWidget must move the focused widget when the pane's canvas gains focus")
            .that(CANVAS_FOCUS_FOLLOWED.matcher(setupWidget).find()).isTrue();
    }

    @Test
    public void programmaticFocusAndClicksGoThroughTheSetter() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertThat(methodBody(source, "public void focusWidget(")).contains("setFocusedWidgetInternal(widget)");
        assertThat(methodBody(source, "private void setupContextMenu(")).contains("setFocusedWidgetInternal(widget)");
    }

    /** The rule itself is pinned by TerminalSplitPaneCloseFocusTest; this pins that closing a pane uses it. */
    @Test
    public void closingAPaneKeepsTheFocusedPaneWhenItSurvives() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertThat(methodBody(source, "private void closeSplit("))
            .contains("setFocusedWidgetInternal(paneFocusedAfterClose(focusedWidget, widget, getAllWidgets()))");
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

    private static boolean isWithin(String source, String body, int offset) {
        int start = source.indexOf(body);
        return offset >= start && offset < start + body.length();
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
