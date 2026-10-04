package de.kortty.ui;

import de.kortty.ui.SplitOrientationChooser.SplitSide;
import javafx.geometry.Orientation;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Split Pane (Cmd/Ctrl+Shift+O) puts the new pane to the right of a wide pane and below a tall or
 * narrow one, so repeated splits of the focused pane stay roughly square: a cell is about twice as
 * tall as wide, so twice as many columns as rows is square on screen, and a pane needs 40 columns
 * before it is split to the right. Pure, no JavaFX toolkit.
 */
class SplitOrientationChooserTest {

    private static final Path TERMINAL_VIEW = Path.of("src/main/java/de/kortty/ui/TerminalView.java");

    @Test
    void aWidePaneIsSplitToTheRight() {
        assertThat(SplitOrientationChooser.choose(80, 24)).isEqualTo(SplitSide.RIGHT);
        assertThat(SplitOrientationChooser.choose(230, 60)).isEqualTo(SplitSide.RIGHT);
        assertWithMessage("exactly square on screen counts as wide")
            .that(SplitOrientationChooser.choose(100, 50)).isEqualTo(SplitSide.RIGHT);
    }

    @Test
    void aTallPaneIsSplitDown() {
        assertThat(SplitOrientationChooser.choose(99, 50)).isEqualTo(SplitSide.DOWN);
        assertThat(SplitOrientationChooser.choose(115, 60)).isEqualTo(SplitSide.DOWN);
        assertThat(SplitOrientationChooser.choose(60, 80)).isEqualTo(SplitSide.DOWN);
    }

    @Test
    void aNarrowPaneIsSplitDownHoweverFewRowsItHas() {
        // Wider than tall, but each half of a split to the right would get fewer than 20 columns.
        assertThat(SplitOrientationChooser.choose(39, 10)).isEqualTo(SplitSide.DOWN);
        assertThat(SplitOrientationChooser.choose(30, 2)).isEqualTo(SplitSide.DOWN);
        assertThat(SplitOrientationChooser.choose(SplitOrientationChooser.MIN_COLUMNS_TO_SPLIT_RIGHT, 20))
            .isEqualTo(SplitSide.RIGHT);
        assertThat(SplitOrientationChooser.choose(SplitOrientationChooser.MIN_COLUMNS_TO_SPLIT_RIGHT - 1, 1))
            .isEqualTo(SplitSide.DOWN);
    }

    @Test
    void anUnknownSizeIsSplitDown() {
        assertThat(SplitOrientationChooser.choose(0, 0)).isEqualTo(SplitSide.DOWN);
        assertThat(SplitOrientationChooser.choose(120, 0)).isEqualTo(SplitSide.DOWN);
        assertThat(SplitOrientationChooser.choose(-1, 24)).isEqualTo(SplitSide.DOWN);
        assertThat(SplitOrientationChooser.choose(Integer.MAX_VALUE, Integer.MAX_VALUE)).isEqualTo(SplitSide.DOWN);
    }

    @Test
    void repeatedSplitsAlternateBetweenRightAndDown() {
        int columns = 160;
        int rows = 50;
        StringBuilder sides = new StringBuilder();
        for (int split = 0; split < 4; split++) {
            SplitSide side = SplitOrientationChooser.choose(columns, rows);
            sides.append(side == SplitSide.RIGHT ? 'R' : 'D');
            if (side == SplitSide.RIGHT) {
                columns /= 2;
            } else {
                rows /= 2;
            }
        }
        assertThat(sides.toString()).isEqualTo("RDRD");
    }

    @Test
    void eachSideIsTheSplitPaneOrientationOfTheContextMenu() {
        // Split Right (same server) splits HORIZONTAL, Split Down (same server) VERTICAL.
        assertThat(SplitSide.RIGHT.orientation()).isEqualTo(Orientation.HORIZONTAL);
        assertThat(SplitSide.DOWN.orientation()).isEqualTo(Orientation.VERTICAL);
    }

    @Test
    void terminalViewSplitsTheFocusedPaneOnItsOwnServerAndFocusesTheNewPane() throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        String source = Files.readString(TERMINAL_VIEW, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String split = methodBody(source, "Optional<SithTermFxWidget> splitFocusedPane(");
        assertThat(split).contains("SithTermFxWidget focused = getFocusedWidget();");
        assertWithMessage("the factory path, so 'same server' resolves the pane's own origin")
            .that(split).contains("splitPane.splitWidget(focused, SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL,\n"
                + "            chosen.orientation(), null);");
        assertThat(split).contains("splitPane.focusWidget(created);");
        assertThat(methodBody(source, "private static SplitOrientationChooser.SplitSide automaticSplitSide("))
            .contains("SplitOrientationChooser.choose(buffer.getWidth(), buffer.getHeight())");

        String close = methodBody(source, "boolean closeFocusedPane() {");
        assertWithMessage("closePane refuses the tab's last pane")
            .that(close).contains("if (!closePane(getFocusedWidget())) {");
        assertThat(close).contains("splitPane.focusWidget(next);");
    }

    /** The text of the method whose declaration contains {@code signature}, up to its closing brace. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced method: " + signature);
    }
}
