package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * A link to a file opens the file as text in the Snippet Editor, and only behind the same guards as
 * "Open in Snippet Editor": the enterprise policy, a connected SSH or local-shell session, the
 * foreign-session check, regular UTF-8 files, and a 10 MB cap on both sides. The wiring needs a
 * JavaFX toolkit and a session, so it is pinned in the source (CRLF-safe, whitespace-insensitive).
 */
public class TerminalPathOpenWiringTest {

    @Test
    public void onlyAPolicyThatLoadsFilesIntoTheSnippetEditorInstallsThePathHandler() throws IOException {
        String mainWindow = source("src/main/java/de/kortty/ui/MainWindow.java");

        int guard = mainWindow.indexOf("if (policy.loadIntoSnippetEditor() != de.kortty.policy.LoadIntoEditorMode.DENY) {");
        int install = mainWindow.indexOf("terminalTab.getTerminalView().setTerminalPathOpenHandler((runContext, link) -> "
            + "openTerminalPathInSnippetEditor(terminalTab, runContext, link));");
        assertThat(guard).isAtLeast(0);
        assertThat(install).isGreaterThan(guard);
        assertThat(install).isLessThan(mainWindow.indexOf("if (policy.aiAgentAllowed()) {", guard));
    }

    @Test
    public void aLinkedFileIsCappedOnBothSidesGuardedAndReadOnlyForOsc8Links() throws IOException {
        String mainWindow = source("src/main/java/de/kortty/ui/MainWindow.java");
        int method = mainWindow.indexOf("private void openTerminalPathInSnippetEditor(");
        String body = mainWindow.substring(method, mainWindow.indexOf("private sealed interface TerminalFileRequest", method));

        assertThat(body).contains("== de.kortty.policy.LoadIntoEditorMode.DENY) { return; }");
        assertThat(body).contains("boolean foreignSession = terminalTab.getTerminalView().isForeignSessionActive(runContext);");
        assertThat(body).contains("terminalTab, runContext, request, foreignSession, "
            + "MAX_LOCAL_TEXT_FILE_LOAD_BYTES, MAX_LOCAL_TEXT_FILE_LOAD_BYTES);");
        assertThat(body).contains("boolean readOnly = link.fromOsc8();");
        assertThat(mainWindow).contains("private static final long MAX_LOCAL_TEXT_FILE_LOAD_BYTES = 10L * 1024 * 1024;");
        // A file that grows during the transfer still stops at the cap, over SFTP and from disk.
        assertThat(mainWindow).contains("return readAtMost(input, maxBytes, remotePath);");
        assertThat(mainWindow).contains("bytes = readAtMost(input, maxBytes, filePath.toString());");
        // Opened as text into the editor, never with another program.
        assertThat(NoHyperlinkFilterGuardTest.codeOnly(body)).doesNotContain("Desktop");
        assertThat(NoHyperlinkFilterGuardTest.codeOnly(body)).doesNotContain("showDocument");
    }

    @Test
    public void anOsc8FileLinkLocksTheFileActionsWithItsOwnReason() throws IOException {
        String mainWindow = source("src/main/java/de/kortty/ui/MainWindow.java");
        String dialog = source("src/main/java/de/kortty/ui/SnippetEditDialog.java");

        assertThat(mainWindow).contains("boolean remoteWriteAllowed = !fromOsc8Link && "
            + "de.kortty.policy.PolicyManager.effective().loadIntoSnippetEditor() == de.kortty.policy.LoadIntoEditorMode.ALLOW;");
        assertThat(mainWindow).contains("fromOsc8Link ? null : draft -> overwriteTerminalLocalTextFile(filePath, draft),");
        assertThat(mainWindow).contains("fromOsc8Link ? I18n.get(\"terminal.links.fileReadOnly\") : null");
        // A locked button stays locked when the form becomes valid.
        assertThat(dialog).contains(
            "overwriteFileButton.setDisable(disable || externalFileActionConfig.overwriteAction() == null);");
        assertThat(dialog).contains("saveFileAsButton.setDisable(disable || externalFileActionConfig.saveAsAction() == null);");
    }

    @Test
    public void aLinkedFileThatFailsToOpenIsNoErrorAndKeepsItsPathOutOfTheLog() throws IOException {
        String mainWindow = source("src/main/java/de/kortty/ui/MainWindow.java");
        int task = mainWindow.indexOf("private void runTerminalTextFileLoadTask(");
        String failed = mainWindow.substring(task, mainWindow.indexOf("thread.start();", task));
        int method = mainWindow.indexOf(
            "private static void logLinkedTerminalFileFailure(String path, @Nullable Throwable failure) {");
        assertThat(method).isAtLeast(0);
        String beforeDebug = mainWindow.substring(method, mainWindow.indexOf("logger.debug(", method));

        // A Cmd/Ctrl+click on any printed path can name a missing file: no ERROR, so no error telemetry.
        assertThat(failed).contains("if (request instanceof LinkedTerminalFile) { "
            + "logLinkedTerminalFileFailure(selectedFileName, failure); } else {");
        assertThat(beforeDebug).contains("if (failure instanceof TerminalTextFileLoadException loadFailure) { "
            + "logger.info(\"Could not open the file a terminal link names: {}\", loadFailure.reason()); }");
        // The path is logged at DEBUG only.
        assertThat(NoHyperlinkFilterGuardTest.codeOnly(beforeDebug.substring(beforeDebug.indexOf('{'))))
            .doesNotContain("path");
        assertThat(mainWindow.substring(method)).contains(
            "logger.debug(\"Linked terminal file that failed to open: '{}'\", path, failure);");
    }

    @Test
    public void onlySshAndLocalShellPanesFindPathsAndKeepFileLinks() throws IOException {
        String view = source("src/main/java/de/kortty/ui/TerminalView.java");

        assertThat(view).contains("private boolean opensFileLinks(SithTermFxWidget widget) { "
            + "if (terminalPathOpenHandler == null) { return false; } "
            + "TtyConnector connector = unwrapTerminalEffectConnector(widget.getTtyConnector()); "
            + "return connector instanceof SshTtyConnector || connector instanceof LocalShellTtyConnector; }");
        // The handler is read on the emulator thread when an OSC 8 file: link arrives.
        assertThat(view).contains("private volatile TerminalPathOpenHandler terminalPathOpenHandler;");
        // Every pane's file links go through the handler, which checks the host once more.
        assertThat(view).contains("korttyWidget.setFileLinkHandler(new TerminalFileLinkHandler() {");
        assertThat(view).contains(
            "return opensFileLinks(widget) && (link.host() == null || link.hostAccepted(fileLinkHosts(widget)));");
        assertThat(view).contains("if (handler != null && accepts(link)) { "
            + "handler.handle(createTerminalAgentRunContext(widget), link); }");
    }

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
