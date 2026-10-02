package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The SSH tunnel lifecycle of a terminal tab lives in {@link TerminalView}, which cannot be
 * constructed without a JavaFX stage. Its order of operations is what makes the tunnels work, and
 * a missing call compiles fine, so the wiring is pinned against the source here: the tunnels are
 * stopped before every session they run on is replaced or closed on purpose, closed with the tab,
 * opened only for the primary connection, and never by a split pane.
 */
class TerminalViewTunnelWiringTest {

    private static final Path UI_ROOT = Path.of("src/main/java/de/kortty/ui");

    private static String source(String fileName) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(UI_ROOT.resolve(fileName), StandardCharsets.UTF_8).replace("\r\n", "\n");
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
        throw new AssertionError("unbalanced braces in " + signature);
    }

    @Test
    void everyConnectAttemptStopsTheTunnelsBeforeItReplacesTheSession() throws IOException {
        String connect = methodBody(source("TerminalView.java"), "public void connect() {");
        int stop = connect.indexOf("tunnelManager.stop();");
        int closePrevious = connect.indexOf("ttyConnector.close();");

        assertThat(stop).isAtLeast(0);
        assertWithMessage("a reconnect must release the ports before the old session closes, or its"
                + " close would count as the owner going away")
            .that(stop).isLessThan(closePrevious);
        assertWithMessage("the tunnels start after a successful primary connect, off the retry path")
            .that(connect).contains("startTunnelsAfterConnect(");
    }

    @Test
    void aManualDisconnectStopsTheTunnelsFirst() throws IOException {
        String disconnect = methodBody(source("TerminalView.java"), "public void disconnectOnly() {");

        assertThat(disconnect.indexOf("tunnelManager.stop();")).isAtLeast(0);
        assertThat(disconnect.indexOf("tunnelManager.stop();")).isLessThan(disconnect.indexOf("ttyConnector.close();"));
    }

    @Test
    void closingTheTabClosesTheTunnels() throws IOException {
        String view = source("TerminalView.java");
        String cleanup = methodBody(view, "public void cleanup() {");
        String closeTunnels = methodBody(view, "public void closeTunnels() {");

        assertThat(cleanup).contains("closeTunnels();");
        assertThat(closeTunnels).contains("tunnelManager.close();");
        assertWithMessage("a tab that closes on its own must release the same resources as its close button")
            .that(methodBody(source("TerminalTab.java"), "private void closeTabSilently() {"))
            .contains("terminalView.cleanup();");
    }

    @Test
    void splitPanesNeverOpenTunnels() throws IOException {
        String view = source("TerminalView.java");

        for (String splitPath : new String[] {
                "private @Nullable TtyConnector doCreateSameServerConnection() {",
                "private @Nullable TtyConnector doCreateNewConnectionForSplit() {",
                "private TtyConnector createConnectorForConnection("}) {
            String body = methodBody(view, splitPath);
            assertWithMessage(splitPath + " must leave the tunnels to the tab")
                .that(body).doesNotContain("tunnelManager");
            assertThat(body).doesNotContain("startTunnelsAfterConnect");
        }
    }

    @Test
    void theTunnelsAreAskedAboutOnceAndCopiedOnTheFxThread() throws IOException {
        String start = methodBody(source("TerminalView.java"), "private void startTunnelsAfterConnect(");

        assertThat(start).contains("SshTunnelManager.enabledTunnels(connection)");
        assertThat(start).contains("portForwardingAllowedByPolicy()");
        assertThat(start).contains("tunnelSetApproved(");
        assertWithMessage("the forwards open off the FX thread")
            .that(start).contains("attachTunnelsInBackground(");
        assertThat(start.indexOf("tunnelSetApproved(")).isLessThan(start.indexOf("attachTunnelsInBackground("));
    }
}
