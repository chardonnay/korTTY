package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Every way of closing an SFTP Manager tab asks about running transfers first. A live tab needs a
 * shown window and a server, so this pins the sources, line-ending agnostic; the decision itself
 * ({@code needsCloseConfirmation}) is tested in {@code SftpTransferQueueHostTest}.
 */
class SFTPManagerTabCloseGuardWiringTest {

    private static final Path SFTP_TAB = Path.of("src/main/java/de/kortty/ui/SFTPManagerTab.java");
    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final Path GUARDS = Path.of("src/main/java/de/kortty/ui/HostedCloseGuards.java");

    @Test
    void theTabIsAHostedCloseGuardAndItsCloseButtonAsksBeforeCleaningUp() throws IOException {
        String tab = read(SFTP_TAB);
        assertThat(tab).contains("public class SFTPManagerTab extends Tab implements HostedCloseGuard {");
        String closeRequest = tab.substring(tab.indexOf("setOnCloseRequest(event -> {"));
        int ask = closeRequest.indexOf("if (!confirmHostedClose()) {\n                event.consume();");
        int cleanup = closeRequest.indexOf("cleanup();");
        assertWithMessage("a veto consumes the close request before anything is closed").that(ask).isAtLeast(0);
        assertThat(ask).isLessThan(cleanup);
        assertWithMessage("the idle auto-close never fires under running transfers")
            .that(tab).contains("if (transferQueueHost != null && transferQueueHost.needsCloseConfirmation()) {");
        assertWithMessage("closing the tab cancels the transfers and a running drag-out download")
            .that(methodBody(tab, "void cleanup() {")).contains("cancelDragOut();");
        assertThat(methodBody(tab, "void cleanup() {")).contains("transferQueueHost.close();");
    }

    @Test
    void everyMainWindowClosePathDispatchesOnHostedCloseGuard() throws IOException {
        String guards = read(GUARDS);
        assertThat(methodBody(guards, "static boolean confirmTab(Tab tab) {"))
            .contains("if (tab instanceof HostedCloseGuard guard) {\n            return guard.confirmHostedClose();");
        assertThat(methodBody(guards, "static boolean needsCloseConfirmation(Tab tab) {"))
            .contains("tab instanceof HostedCloseGuard guard && guard.needsCloseConfirmation()");

        String main = read(MAIN_WINDOW);
        // Close Tab / Cmd+W on one tab, and several tabs at once (Close Others, To the Right).
        assertThat(methodBody(main, "private static boolean confirmUserClose(Tab tab) {"))
            .contains("return HostedCloseGuards.confirmTab(tab);");
        assertThat(methodBody(main, "private boolean confirmUserCloseAll(List<Tab> targets) {"))
            .contains("HostedCloseGuards.confirmTabs(targets,");
        // Window close, quit (through confirmClose), Close All Tabs and opening a project.
        assertThat(methodBody(main, "private boolean confirmHostedTabsClose() {"))
            .contains("HostedCloseGuards.confirmTabs(tabPane.getTabs(),");
        assertThat(methodBody(main, "private boolean confirmSnippetEditorsClose(boolean includeUnownedEditors) {"))
            .contains("if (!confirmHostedTabsClose()) {");
        assertThat(methodBody(main, "private boolean confirmClose() {"))
            .contains("return confirmSnippetEditorsClose(");
        assertThat(methodBody(main, "private void openProjectFile(Path path) {"))
            .contains("if (!confirmHostedTabsClose()) {");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code signature} to the end of its method (the next line that closes at depth 4). */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("missing: " + signature).that(start).isAtLeast(0);
        int end = source.indexOf("\n    }\n", start);
        return source.substring(start, end < 0 ? source.length() : end);
    }
}
