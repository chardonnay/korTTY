package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Opening a project sorts every saved tab without asking (see {@link TabRestoreTriage}), opens what is
 * ready and lists the rest in one restore bar: no modal dialog per tab, no silent skip of local shells,
 * Connect… asks once per connection with the policy first, and a vault unlock opens the tabs waiting
 * for it in every window.
 */
class DeferredProjectTabsWiringTest {

    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");

    @Test
    void everySavedTabIsSortedWithoutAskingBeforeItOpens() throws IOException {
        String window = read();
        String restoreWindow = methodBody(window,
            "private void restoreWindowState(WindowState windowState, Project project, boolean moveWindow) {");
        assertThat(restoreWindow).contains("restoreOrDeferSavedTab(sessionState, index, project, restore);");
        assertThat(restoreWindow).doesNotContain("restoreSavedTab(sessionState, index, project, restore);");
        assertWithMessage("an earlier project's waiting tabs go with its tabs")
            .that(restoreWindow.indexOf("restoreAttention.clear();"))
            .isGreaterThan(restoreWindow.indexOf("closeAllTabs();"));
        assertThat(restoreWindow.indexOf("refreshRestoreAttentionBar();"))
            .isGreaterThan(restoreWindow.indexOf("restore.syncTabsDone();"));

        String defer = methodBody(window, "private void restoreOrDeferSavedTab(");
        assertThat(defer).contains("TabRestoreTriage.Outcome outcome = classifySavedTab(sessionState);");
        assertThat(defer).contains("restoreSavedTab(sessionState, index, project, restore, outcome.auth());");
        assertThat(defer).contains("restoreAttention.add(new RestoreAttention.Item<>(");
        assertWithMessage("auto-reconnect off still skips connection tabs")
            .that(defer).contains("!project.isAutoReconnect() && TabRestoreTriage.opensOverConnection(sessionState)");
        for (String dialog : new String[] {"showAndWait", "new Alert", "resolve(", "getConnectionPassword("}) {
            assertWithMessage("sorting a saved tab must not ask: " + dialog).that(defer).doesNotContain(dialog);
        }

        String classify = methodBody(window, "private TabRestoreTriage.Outcome classifySavedTab(SessionState sessionState) {");
        assertThat(classify).contains("TabRestoreTriage.classify(sessionState, this::savedConnectionOf, connectionAuthResolver(),");
        assertThat(methodBody(window, "private ServerConnection savedConnectionOf(SessionState sessionState) {"))
            .contains("SftpSessionRestoreSupport.findConnection(");
    }

    @Test
    void aRestoredTabOpensWithTheSignInItWasSortedWithNeverWithAnUnaskedPassword() throws IOException {
        String restoreTab = methodBody(read(), "private void restoreSavedTab(");

        assertThat(restoreTab).doesNotContain("getConnectionPassword(");
        assertThat(restoreTab).doesNotContain("isKeyAuth");
        assertThat(restoreTab).contains("ServerConnection connection = remote ? auth.connection() : null;");
        assertThat(restoreTab).contains("de.kortty.model.TemporarySSHKey temporaryKey = remote ? auth.temporaryKey() : null;");
        assertWithMessage("the terminal tab gets the valid temporary key, so openConnectionAndReturnTab never renews it")
            .that(restoreTab).contains("                        temporaryKey,\n                        sessionState.getTerminalEffectPluginId(),");
        assertThat(restoreTab).contains("new SFTPManagerTab(app, connection, password, temporaryKey,");
        assertThat(count(restoreTab, "openOwnedSftpSession(connection, password, temporaryKey)")).isEqualTo(2);
    }

    @Test
    void connectAsksOncePerConnectionWithThePolicyFirstAndNoDialogForABlockedServer() throws IOException {
        String window = read();
        String connect = methodBody(window, "private boolean connectDeferredTab(RestoreAttention.Item<DeferredTab> item) {");

        int remove = connect.indexOf("if (!restoreAttention.remove(item)) {");
        int resolve = connect.indexOf("connectionAuthResolver().resolve(savedConnectionOf(tab.state()), true);");
        assertWithMessage("taken out of the list before any question, so an unlock retry cannot open it twice")
            .that(remove).isAtLeast(0);
        assertThat(resolve).isGreaterThan(remove);
        assertWithMessage("a blocked server moves in the list instead of a policy dialog")
            .that(connect).doesNotContain("showBlockedServerDialog");
        assertThat(connect).doesNotContain("resolveConnectionAuthInteractively");
        assertThat(connect).contains("restoreAttention.sameConnection(item);");
        assertThat(connect).contains("return !outcome.classification().waitsForUser();");

        String walk = methodBody(window, "private void connectDeferredTabs(RestoreAttention.Item<DeferredTab> only) {");
        assertThat(walk).contains("if (connectingDeferredTabs) {");
        assertThat(walk).contains("restoreAttention.nextToConnect()");
        assertThat(walk).contains("if (!connectDeferredTab(next.get())) {");
    }

    @Test
    void aVaultUnlockOpensTheTabsWaitingForItInEveryWindow() throws IOException {
        String window = read();

        assertThat(methodBody(window, "public void onVaultUnlocked() {")).contains("retryTabsWaitingForVault();");
        String retry = methodBody(window, "private void retryTabsWaitingForVault() {");
        assertThat(retry).contains("restoreAttention.waiting(TabRestoreTriage.Classification.NEEDS_UNLOCK)");
        assertThat(retry).contains("classifySavedTab(item.tab().state())");
        assertThat(retry).doesNotContain("resolve(");

        String unlock = methodBody(window, "private void unlockVaultForDeferredTabs() {");
        assertThat(unlock).contains("VaultUnlockSupport.unlock(stage, app.getMasterPasswordManager());");

        String open = methodBody(window, "private void openDeferredTab(DeferredTab tab, ConnectionAuthResolver.Resolution auth) {");
        assertWithMessage("a tab of a project that another project replaced never opens")
            .that(open).contains("if (tab.restore() != activeRestore) {");
    }

    @Test
    void theRestoreBarSitsAboveTheStatusLineAndGoesWithTheProject() throws IOException {
        String window = read();

        String install = methodBody(window, "private void installRestoreAttentionBar() {");
        assertThat(install).contains("statusBar.getChildren().add(0, restoreAttentionBar);");
        assertThat(window).contains("statusBar = new VBox(statusRow);\n        installRestoreAttentionBar();");
        assertThat(methodBody(window, "static void restoreProject(Project project, MainWindow firstWindow) {"))
            .contains("firstWindow.clearRestoreAttention();");
        assertThat(methodBody(window, "private void reopenDeferredTabs(Runnable reopen) {"))
            .contains("organizeTabsByGroup();");
    }

    private static String read() throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(MAIN_WINDOW, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
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
        throw new AssertionError("unbalanced braces after " + signature);
    }
}
