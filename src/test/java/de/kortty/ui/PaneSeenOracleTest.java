package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.testng.annotations.Test;

/**
 * A tab is seen while its window is in front and it is the window's selected tab; pane focus does
 * not matter for terminal notifications. {@link MainWindow} needs a stage, so the walk over the
 * windows is checked with stand-ins and its wiring against the source.
 */
class PaneSeenOracleTest {

    /** A window as the oracle sees it: whether it is in front, and its selected terminal tab. */
    private record Window(boolean foreground, String selectedTab) {
    }

    private static String seen(Window... windows) {
        return PaneSeenOracle.seenTab(Arrays.asList(windows), Window::foreground, Window::selectedTab);
    }

    @Test
    void theSelectedTabOfTheWindowInFrontIsSeen() {
        assertThat(seen(new Window(false, "a"), new Window(true, "b"), new Window(false, "c"))).isEqualTo("b");
    }

    @Test
    void nothingIsSeenWithoutAWindowInFront() {
        assertWithMessage("every window behind another application or minimised")
            .that(seen(new Window(false, "a"), new Window(false, "b"))).isNull();
        assertThat(seen()).isNull();
        assertThat(PaneSeenOracle.seenTab(null, Window::foreground, Window::selectedTab)).isNull();
    }

    @Test
    void aWindowInFrontShowingAnotherKindOfTabSeesNoTerminal() {
        assertThat(seen(new Window(true, null), new Window(false, "a"))).isNull();
    }

    @Test
    void closedWindowsInTheListAreSkipped() {
        List<Window> windows = Arrays.asList(null, new Window(true, "a"));
        assertThat(PaneSeenOracle.seenTab(windows, Window::foreground, Window::selectedTab)).isEqualTo("a");
    }

    @Test
    void theApplicationAndTheCodingAgentsAskTheSameQuestion() throws IOException {
        String oracle = source("PaneSeenOracle.java");
        assertThat(oracle).contains("return seenTab(windows, MainWindow::isForegroundWindow, MainWindow::getActiveTerminalTab);");
        assertThat(oracle).contains("return tab != null && seenTab(MainWindow.getOpenWindows()) == tab;");

        String bridge = source("CodingAgentUiBridge.java");
        assertWithMessage("a coding agent is seen in the same tab, and needs its pane focused as well")
            .that(bridge).contains("TerminalTab active = PaneSeenOracle.seenTab(snapshotWindows());");
        assertThat(bridge).contains("return seenFor(true, true, focused);");
    }

    private static String source(String file) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(Path.of("src/main/java/de/kortty/ui", file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
