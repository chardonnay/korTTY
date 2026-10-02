package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * {@link RestoredHistoryReplayTest} pins what the replay writes; this pins where it is called from.
 * A project's saved screen used to be typed into the remote shell as a heredoc, half a second after
 * connecting. Both halves of the fix are wiring that no headless test can reach, because
 * {@code TerminalView} needs a running JavaFX toolkit and a live connector: the saved text must be
 * queued before the connect thread starts, and written into the emulator before the emulator starts
 * reading the connection — never through the connector, and never into the session journal.
 */
class RestoredHistoryWiringTest {

    private static final Path UI_ROOT = Path.of("src/main/java/de/kortty/ui");

    @Test
    void theRemoteHeredocRestoreIsGone() throws IOException {
        for (String file : new String[] {"TerminalView.java", "MainWindow.java"}) {
            String source = source(file);
            assertWithMessage(file + " still builds the remote heredoc").that(source).doesNotContain("/tmp/.kortty_hist_");
            assertWithMessage(file + " still builds the remote heredoc").that(source).doesNotContain("<<H");
            assertWithMessage(file + " still calls the removed restoreHistory(...)")
                .that(source).doesNotContain(".restoreHistory(");
        }
        assertThat(source("TerminalView.java")).doesNotContain("public void restoreHistory(");
    }

    @Test
    void theReplayRunsBeforeTheEmulatorStartsReadingTheConnection() throws IOException {
        String connect = region(source("TerminalView.java"), "public void connect() {", "connectThread.start();");

        int replay = connect.indexOf("replayPendingRestoredHistory(terminalWidget);");
        int attach = connect.indexOf("terminalWidget.setTtyConnector(");
        int start = connect.indexOf("terminalWidget.start();");
        assertWithMessage("connect() no longer replays the restored block").that(replay).isAtLeast(0);
        assertThat(attach).isAtLeast(0);
        assertThat(start).isAtLeast(0);
        assertWithMessage("the restored block must be written before the connector is attached")
            .that(replay).isLessThan(attach);
        assertWithMessage("the restored block must be written before the emulator starts")
            .that(replay).isLessThan(start);
    }

    @Test
    void theReplayWritesNeitherToTheConnectorNorToTheJournal() throws IOException {
        String body = region(source("TerminalView.java"),
            "private void replayPendingRestoredHistory(", "\n    }\n");

        assertThat(body).contains("RestoredHistoryReplay.replay(widget.getTerminal()");
        assertThat(body).doesNotContain("ttyConnector");
        assertThat(body).doesNotContain("getTtyConnector");
        assertThat(body).doesNotContain("Journal");

        String replay = source("RestoredHistoryReplay.java");
        assertThat(replay).doesNotContain("import com.sithtermfx.core.TtyConnector");
        assertThat(replay).doesNotContain("getTtyConnector");
    }

    @Test
    void theSavedScreenIsQueuedBeforeTheConnectThreadStarts() throws IOException {
        String open = region(source("MainWindow.java"),
            "java.time.LocalDateTime historySavedAt,", "return terminalTab;");

        int queue = open.indexOf("setPendingRestoredHistory(historyToRestore, historySavedAt);");
        int connect = open.indexOf("connectThread.start();");
        assertWithMessage("openConnectionAndReturnTab no longer queues the saved screen").that(queue).isAtLeast(0);
        assertThat(connect).isAtLeast(0);
        assertThat(queue).isLessThan(connect);
        assertWithMessage("the fixed-delay restore thread is back").that(open).doesNotContain("Thread.sleep(500)");
    }

    private static String source(String file) throws IOException {
        return Files.readString(UI_ROOT.resolve(file), StandardCharsets.UTF_8);
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
