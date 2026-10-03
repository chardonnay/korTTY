package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * {@code TerminalOutputHighlighterTest} pins what the highlight engine does; this pins where the
 * application hooks it in, which no headless test reaches because {@code TerminalView} needs a running
 * JavaFX toolkit: one highlighter per pane, attached with the pane and released with it, a new split
 * inheriting its parent's choice, and one service created at startup and stopped at shutdown.
 */
class TerminalHighlighterWiringTest {

    private static final Path MAIN = Path.of("src/main/java/de/kortty");

    @Test
    void everyPaneGetsAHighlighterWhenItIsConfigured() throws IOException {
        String setup = region(source("ui/TerminalView.java"),
            "private void setupWidgetEventHandlers(SithTermFxWidget widget) {", "\n    }\n");

        assertThat(setup).contains("attachTerminalHighlighter(widget);");
        assertWithMessage("attach after the coding agent monitor, like every per-pane listener")
            .that(setup.indexOf("attachTerminalHighlighter(widget);"))
            .isGreaterThan(setup.indexOf("attachCodingAgentMonitor(widget);"));
    }

    @Test
    void attachingNeverTouchesTheSplitPaneAndNeverBreaksWidgetCreation() throws IOException {
        String attach = region(source("ui/TerminalView.java"),
            "private void attachTerminalHighlighter(SithTermFxWidget widget) {", "\n    }\n");

        assertWithMessage("the first widget is configured before splitPane is assigned")
            .that(attach).doesNotContain("splitPane");
        assertThat(attach).contains("catch (RuntimeException e)");
        assertThat(attach).contains("service.attach(");
    }

    @Test
    void aClosedPaneAndAClosedTabReleaseTheirHighlighters() throws IOException {
        String view = source("ui/TerminalView.java");
        assertThat(region(view, "private void releasePaneState(SithTermFxWidget widget) {", "\n    }\n"))
            .contains("releaseTerminalHighlighter(widget);");
        assertThat(region(view, "public void cleanup() {", "\n    }\n"))
            .contains("releaseAllTerminalHighlighters();");
        String release = region(view, "private void releaseTerminalHighlighter(SithTermFxWidget widget) {", "\n    }\n");
        assertThat(release).contains("paneHighlightOverride.remove(widget);");
        assertThat(release).contains("service.detach(highlighter);");
    }

    @Test
    void aNewSplitInheritsItsParentsChoice() throws IOException {
        String view = source("ui/TerminalView.java");
        String hook = region(view, "splitPane.setOnWidgetSplitCreated(", "});");

        assertThat(hook).contains("inheritHighlightOnSplit(widget, request);");
        assertThat(hook).contains("inheritEffectOnSplit(widget, request);");
        assertThat(region(view, "private void inheritHighlightOnSplit(", "\n    }\n"))
            .contains("setPaneHighlightOverride(newWidget, choice);");
    }

    @Test
    void aRestyleTakesARecordingFrameOnlyForARecordedPane() throws IOException {
        String body = region(source("ui/TerminalView.java"),
            "private void recordRestyledTerminalRecordingSnapshot(", "\n    }\n");

        assertThat(body).contains("terminalRecordingModelListeners.containsKey(widget)");
        assertThat(body).contains("recordTerminalRecordingSnapshot(widget);");
    }

    @Test
    void theServiceIsCreatedLoadedAndStoppedWithTheApplication() throws IOException {
        String app = source("KorTTYApplication.java");

        assertThat(app).contains("new de.kortty.core.highlight.TerminalHighlightService(");
        assertThat(region(app, "public void start(Stage primaryStage) {", "terminalHighlightService.reload("))
            .contains("globalSettingsManager.load();");
        assertThat(region(app, "private synchronized void performShutdown() {", "\n    }\n"))
            .contains("terminalHighlightService::stop");
    }

    private static String source(String file) throws IOException {
        // A Windows checkout has CRLF line endings; the markers above are written with \n.
        return Files.readString(MAIN.resolve(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code startMarker} up to and including the next {@code endMarker}. */
    private static String region(String source, String startMarker, String endMarker) {
        int from = source.indexOf(startMarker);
        assertWithMessage("marker not found: " + startMarker).that(from).isAtLeast(0);
        int to = source.indexOf(endMarker, from + startMarker.length());
        assertWithMessage("end marker not found after " + startMarker).that(to).isAtLeast(0);
        return source.substring(from, to + endMarker.length());
    }
}
