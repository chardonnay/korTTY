package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Every way a terminal tab reaches a server must honour the enterprise server policy like a single
 * open: a group opened from Quick Connect, Duplicate, and the tab's own (re)connect loop, which is
 * the only gate left when the connection editor changed a saved connection in place after the tab
 * opened. The decision itself is covered by {@code ServerAccessPolicyTest}; this pins the wiring in
 * the MainWindow, TerminalView and TerminalTab sources, because those need a live stage and
 * connector.
 */
class TabServerPolicyGateTest {

    private static final Path UI_ROOT = Path.of("src/main/java/de/kortty/ui");

    @Test
    void everyTerminalTabMainWindowBuildsComesFromAGatedPath() throws IOException {
        String main = source("MainWindow.java");
        assertWithMessage("a new path that builds a TerminalTab needs its own server-policy gate")
            .that(count(main, "new TerminalTab(")).isEqualTo(3);

        String open = methodBody(main, "String restoredWorkingDirectory) {");
        assertThat(open.indexOf("ServerAccessPolicy.firstBlockedTarget(connection)"))
            .isLessThan(open.indexOf("new TerminalTab("));
        assertThat(count(methodBody(main, "private void openGroupConnections("), "new TerminalTab(")).isEqualTo(1);
        assertThat(count(methodBody(main, "private void createDuplicateTab("), "new TerminalTab(")).isEqualTo(1);
    }

    @Test
    void aGroupSkipsBlockedMembersBeforeReadingPasswordsOrCountingUsage() throws IOException {
        String group = methodBody(source("MainWindow.java"), "private void openGroupConnections(");

        int partition = group.indexOf("ServerAccessPolicy.partition(groupConnections)");
        assertWithMessage("openGroupConnections partitions the group by the server policy")
            .that(partition).isAtLeast(0);
        int dialog = group.indexOf("PolicyUiSupport.showBlockedServerDialog(policyPartition.blockedTargetList())");
        assertThat(dialog).isGreaterThan(partition);
        int allBlocked = group.indexOf("if (policyPartition.allowed().isEmpty())");
        assertThat(allBlocked).isGreaterThan(dialog);

        // Only the allowed members reach the vault, the usage count and a tab.
        int loop = group.indexOf("for (ServerConnection conn : policyPartition.allowed())");
        assertThat(loop).isGreaterThan(allBlocked);
        assertThat(group).doesNotContain("for (ServerConnection conn : groupConnections)");
        assertThat(group.indexOf("GroupOpenSupport.decide(conn, this::getConnectionPassword)")).isGreaterThan(loop);
        assertThat(group.indexOf("incrementUsageCount()")).isGreaterThan(loop);
        assertThat(group.indexOf("new TerminalTab(")).isGreaterThan(loop);

        // The status bar counts the tabs that opened, not the group's size.
        assertThat(group).contains("I18n.get(\"status.groupOpened\", groupName, opened)");
    }

    @Test
    void duplicateRechecksTheConnectionBeforeAnyPasswordPrompt() throws IOException {
        String main = source("MainWindow.java");
        String duplicate = methodBody(main, "private void duplicateTab(");

        // Duplicate signs in like the Connection Manager: the shared resolver checks the server
        // policy before it asks anything (ConnectionAuthResolverTest), and only a READY result
        // reaches createDuplicateTab.
        int resolve = duplicate.indexOf("resolveConnectionAuthInteractively(sourceTab.getConnection())");
        assertWithMessage("duplicateTab signs in through the shared resolver").that(resolve).isAtLeast(0);
        int notReady = duplicate.indexOf("if (!auth.isReady())", resolve);
        int refuse = duplicate.indexOf("return;", notReady);
        assertThat(notReady).isGreaterThan(resolve);
        assertThat(refuse).isGreaterThan(notReady);
        assertThat(duplicate.indexOf("createDuplicateTab(")).isGreaterThan(refuse);
        assertWithMessage("no password dialog of its own").that(duplicate).doesNotContain("new Dialog<");
        assertThat(duplicate).doesNotContain("getConnectionPassword(");
        assertThat(duplicate).contains("auth.temporaryKey()");

        // createDuplicateTab is reached through that gate only.
        int callsInDuplicate = count(duplicate, "createDuplicateTab(");
        int declarations = count(main, "private void createDuplicateTab(");
        assertThat(count(main, "createDuplicateTab(")).isEqualTo(callsInDuplicate + declarations);
    }

    @Test
    void theSharedSignInShowsThePolicyMessageForABlockedTarget() throws IOException {
        String main = source("MainWindow.java");
        String interactive = methodBody(main,
            "private ConnectionAuthResolver.Resolution resolveConnectionAuthInteractively(");
        int resolve = interactive.indexOf("connectionAuthResolver().resolve(connection, true)");
        assertThat(resolve).isAtLeast(0);
        int blocked = interactive.indexOf("if (auth.status() == ConnectionAuthResolver.Status.BLOCKED)", resolve);
        assertThat(blocked).isGreaterThan(resolve);
        assertThat(interactive.indexOf("PolicyUiSupport.showBlockedServerDialog(auth.blockedTarget())"))
            .isGreaterThan(blocked);

        // The application's policy seam is the central server policy.
        String seams = methodBody(source("ConnectionAuthResolver.java"), "static Seams forApplication(KorTTYApplication app) {");
        assertThat(seams).contains("ServerAccessPolicy::firstBlockedTarget");
    }

    @Test
    void theConnectionManagerSignsInBeforeItOpensATab() throws IOException {
        String main = source("MainWindow.java");
        String manager = methodBody(main, "private void showConnectionManager() {");
        assertThat(manager).contains("connectSavedConnection(connection, false, tab -> { })");
        assertWithMessage("the Connection Manager has no sign-in steps of its own")
            .that(manager).doesNotContain("openConnection(");
        assertThat(manager).doesNotContain("new Dialog<");
        assertThat(manager).doesNotContain("getConnectionPassword(");

        String connect = methodBody(main, "private ConnectionAuthResolver.Status connectSavedConnection(");
        int resolve = connect.indexOf("resolveConnectionAuthInteractively(connection)");
        int notReady = connect.indexOf("if (!auth.isReady())", resolve);
        int open = connect.indexOf("openConnectionAndReturnTab(", notReady);
        assertThat(resolve).isAtLeast(0);
        assertThat(notReady).isGreaterThan(resolve);
        assertThat(open).isGreaterThan(notReady);
        // The resolved key, password and effect reach the tab; usage and onOpened follow a real tab.
        String openCall = connect.substring(open, connect.indexOf(");", open));
        assertThat(openCall).contains("auth.password()");
        assertThat(openCall).contains("auth.temporaryKey()");
        assertThat(openCall).contains("resolved.getTerminalEffectPluginId()");
        int noTab = connect.indexOf("if (tab == null)", open);
        assertThat(noTab).isGreaterThan(open);
        assertThat(connect.indexOf("recordConnectionUsage(resolved)")).isGreaterThan(noTab);
        assertThat(connect.indexOf("onOpened.accept(tab)")).isGreaterThan(noTab);
    }

    @Test
    void everyConnectAttemptRechecksThePolicyAndStopsRetryingWhenBlocked() throws IOException {
        String connect = methodBody(source("TerminalView.java"), "public void connect() {");

        int loop = connect.indexOf("while (attempt < retryCount");
        int gate = connect.indexOf("ServerAccessPolicy.firstBlockedTarget(connection)");
        int connector = connect.indexOf("createConnectorForConnection(connection, password)");
        assertWithMessage("the connect loop asks the server policy").that(gate).isAtLeast(0);
        assertWithMessage("checked on every attempt, inside the retry loop").that(gate).isGreaterThan(loop);
        assertWithMessage("checked before anything is contacted").that(gate).isLessThan(connector);
        int refuse = connect.indexOf("throw new de.kortty.policy.PolicyRestrictionException(", gate);
        assertThat(refuse).isGreaterThan(gate);
        assertThat(refuse).isLessThan(connector);
        assertThat(connect.substring(gate, refuse)).doesNotContain("ttyConnector =");

        // The refusal is permanent, like a configuration refusal: the loop stops, the terminal says
        // why, and auto-reconnect sees a permanent failure.
        int handler = connect.indexOf("catch (de.kortty.policy.PolicyRestrictionException e)");
        assertWithMessage("the policy refusal needs its own catch, not the retrying one")
            .that(handler).isGreaterThan(connector);
        int generic = connect.indexOf("catch (Exception e)", handler);
        assertThat(generic).isGreaterThan(handler);
        assertWithMessage("the catch after the policy refusal is the retrying one")
            .that(connect.substring(generic)).contains("Thread.sleep(1000);");
        String refusal = connect.substring(handler, generic);
        assertThat(refusal).contains("configurationRefused = true;");
        assertThat(refusal).contains("showMessage(e.getMessage());");
        assertThat(refusal).contains("PolicyUiSupport.managedByOrganizationText()");
        assertThat(connect).contains("while (attempt < retryCount && !connected && !authenticationFailed && !configurationRefused)");
        assertThat(connect).contains("lastConnectFailurePermanent = authenticationFailed || configurationRefused;");
    }

    @Test
    void aPermanentFailureNeverArmsAutoReconnect() throws IOException {
        String schedule = methodBody(source("TerminalTab.java"), "private void maybeScheduleAutoReconnect() {");
        assertThat(schedule).contains(
            "if (!terminalView.wasConnectionLost() || terminalView.isLastConnectFailurePermanent()) {");
        int disarm = schedule.indexOf("if (terminalView.isLastConnectFailurePermanent()) {");
        assertThat(disarm).isAtLeast(0);
        assertThat(schedule.indexOf("autoReconnectActive = false;", disarm)).isGreaterThan(disarm);
    }

    @Test
    void theTerminalRefusalNamesTheBlockedTarget() {
        assertThat(I18n.get("policy.server.blocked.message", "vault.acme.com:22")).contains("vault.acme.com:22");
    }

    private static String source(String fileName) throws IOException {
        return Files.readString(UI_ROOT.resolve(fileName), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    /** The body of the method declared with {@code declaration}, up to its matching brace. */
    private static String methodBody(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertWithMessage("declaration %s", declaration).that(start).isAtLeast(0);
        assertWithMessage("declaration %s is ambiguous", declaration)
            .that(source.indexOf(declaration, start + 1)).isEqualTo(-1);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = endOfLiteral(source, i, c);
            } else if (source.startsWith("//", i)) {
                i = source.indexOf('\n', i);
            } else if (source.startsWith("/*", i)) {
                i = source.indexOf("*/", i) + 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open + 1, i);
            }
        }
        throw new AssertionError("unterminated method " + declaration);
    }

    /** Index of the quote that closes the string or char literal opened at {@code start}. */
    private static int endOfLiteral(String source, int start, char quote) {
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        throw new AssertionError("unterminated literal at " + start);
    }
}
