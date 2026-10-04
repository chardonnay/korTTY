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
 * opened only for the primary connection, and never by a split pane. Tunnels saved in the
 * connection editor reach the open tabs, so switching them off there stops them.
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
        String tab = source("TerminalTab.java");
        assertWithMessage("a tab that closes on its own must release the same resources as its close button")
            .that(methodBody(tab, "private void closeTabSilently() {"))
            .contains("releaseResources();");
        assertThat(methodBody(tab, "void releaseResources() {")).contains("terminalView.cleanup();");
    }

    @Test
    void splitPanesNeverOpenTunnels() throws IOException {
        String view = source("TerminalView.java");

        for (String splitPath : new String[] {
                "private @Nullable TtyConnector doCreateSameServerConnection(PaneOrigin origin) {",
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
        String view = source("TerminalView.java");
        assertWithMessage("a connect opens the tunnels on the primary connector only")
            .that(methodBody(view, "private void startTunnelsAfterConnect(")).contains("tunnelHostIsCurrent(connector)");
        assertThat(methodBody(view, "private void startTunnelsAfterConnect(")).contains("openTunnels(connector);");
        String start = methodBody(view, "private void openTunnels(");

        assertThat(start).contains("SshTunnelManager.enabledTunnels(connection)");
        assertThat(start).contains("portForwardingAllowedByPolicy()");
        // The decision itself is SshTunnelApprovalGate's, pinned by SshTunnelApprovalGateTest.
        assertThat(start).contains("tunnelApprovals.allows(");
        assertWithMessage("the forwards open off the FX thread")
            .that(start).contains("attachTunnelsInBackground(");
        assertThat(start.indexOf("tunnelApprovals.allows(")).isLessThan(start.indexOf("attachTunnelsInBackground("));
    }

    @Test
    void savedTunnelChangesReachEveryOpenTab() throws IOException {
        // The connection editor's "Enable SSH tunnels" switch would be a lie if unticking it left
        // running tunnels open: a save in the connection manager applies the tunnels to every tab.
        String mainWindow = source("MainWindow.java");
        assertThat(methodBody(mainWindow, "private void refreshAllTerminalTabsConnectionSettings() {"))
            .contains("applyTunnelSettingsToOpenTabs();");
        String applyAll = methodBody(mainWindow, "private static void applyTunnelSettingsToOpenTabs() {");
        assertWithMessage("every window, not only the one that opened the connection manager")
            .that(applyAll).contains("openWindows");
        assertThat(applyAll).contains(".applyTunnelSettings();");
    }

    @Test
    void applyingSavedTunnelsStopsSwitchedOffOnesAndReopensOnlyAChangedSet() throws IOException {
        String apply = methodBody(source("TerminalView.java"), "public void applyTunnelSettings() {");

        int read = apply.indexOf("SshTunnelManager.enabledTunnels(connection)");
        int clear = apply.indexOf("tunnelManager.clear();");
        int unchanged = apply.indexOf("SshTunnelManager.sameForwards(tunnels, evaluatedTunnels)");
        int reopen = apply.indexOf("openTunnels(host);");
        assertThat(read).isAtLeast(0);
        assertWithMessage("no enabled tunnel left: stop and forget them").that(clear).isGreaterThan(read);
        assertWithMessage("an unchanged set keeps running untouched").that(unchanged).isGreaterThan(clear);
        assertWithMessage("a changed set goes through the same confirmation as a connect")
            .that(reopen).isGreaterThan(unchanged);
        assertThat(apply.indexOf("tunnelManager.stop();")).isLessThan(reopen);
    }

    @Test
    void savedTunnelsNeverOpenDuringALoginOrNextToAPendingConnectStart() throws IOException {
        String view = source("TerminalView.java");

        // The connector is assigned before its login, and its session is open while it is still
        // authenticating: only a connected primary may carry tunnels applied from a save.
        assertThat(methodBody(view, "private TtyConnector tunnelHostForSavedSettings() {"))
            .contains("sshPrimary.isConnected()");

        // A connect that just succeeded has queued startTunnelsAfterConnect, which reads the saved
        // tunnels itself; a save in between must not attach (or ask) a second time.
        String connect = methodBody(view, "public void connect() {");
        int mark = connect.indexOf("pendingTunnelStartConnector = tunnelHostConnector;");
        assertThat(mark).isAtLeast(0);
        assertThat(mark).isLessThan(connect.indexOf("startTunnelsAfterConnect(tunnelHostConnector)"));
        assertThat(methodBody(view, "private void startTunnelsAfterConnect(")).contains("pendingTunnelStartConnector = null;");

        String apply = methodBody(view, "public void applyTunnelSettings() {");
        int clear = apply.indexOf("tunnelManager.clear();");
        int skip = apply.indexOf("pendingTunnelStartConnector == primary");
        assertWithMessage("switched-off tunnels leave the status bar even while the tab is disconnected")
            .that(apply.indexOf("primary == null")).isGreaterThan(clear);
        assertThat(skip).isGreaterThan(clear);
        assertThat(skip).isLessThan(apply.indexOf("openTunnels(host);"));
    }

    @Test
    void theConnectionEditorStoresTunnelsOnlyWhenSaved() throws IOException {
        String editor = source("ConnectionEditDialog.java");
        String tunnelsTab = methodBody(editor, "private Tab createTunnelsTab() {");

        assertThat(tunnelsTab).contains("TunnelEditSupport.workingCopies(connection.getSshTunnels())");
        assertWithMessage("add, edit, remove and the switch work on copies; Cancel must leave the connection alone")
            .that(tunnelsTab).doesNotContain("connection.getSshTunnels().");
        assertThat(tunnelsTab).contains("TunnelEditSupport.setAllEnabled(editedTunnels, enableTunnelsCheck.isSelected())");
        assertThat(editor).contains("TunnelEditSupport.writeBack(connection, editedTunnels);");
    }

    @Test
    void typeAheadCanNeverApproveTheTunnels() throws IOException {
        // The question pops up right after login, while the user may still be typing into the
        // terminal: Enter or Space must decline, and opening needs a deliberate click.
        String confirm = methodBody(source("TerminalView.java"), "private boolean confirmTunnelSet(");

        assertThat(confirm).contains("openButton.setDefaultButton(false);");
        assertThat(confirm).contains("skipButton.setDefaultButton(true);");
        assertThat(confirm).contains("skipButton.requestFocus()");
    }
}
