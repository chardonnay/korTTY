package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The foreign-session gate on terminal drag-and-drop and on snippet copy to the terminal directory,
 * plus source pins that both paths evaluate it on the FX thread before their worker starts and use a
 * leased SFTP channel instead of an unclosed one.
 */
class TerminalTransferGuardTest {

    private static final Path BUNDLES = Path.of("src/main/resources/i18n");
    private static final List<String> LOCALES = List.of("", "_de", "_es", "_fr", "_hr", "_it", "_nl", "_pt");

    @Test
    void aForeignVerdictRefusesDropAndSnippetCopy() {
        assertThat(TerminalTransferGuard.refusalKey(TerminalTransferGuard.Transfer.DROP, true))
            .hasValue("terminal.dragDrop.foreignSession");
        assertThat(TerminalTransferGuard.refusalKey(TerminalTransferGuard.Transfer.SNIPPET_COPY, true))
            .hasValue("snippets.transfer.foreignSession");
    }

    @Test
    void aNativeVerdictAllowsDropAndSnippetCopy() {
        for (TerminalTransferGuard.Transfer transfer : TerminalTransferGuard.Transfer.values()) {
            assertThat(TerminalTransferGuard.refusalKey(transfer, false)).isEmpty();
        }
    }

    @Test
    void everyNewMessageIsTranslated() throws IOException {
        List<String> keys = List.of("terminal.dragDrop.foreignSession", "snippets.transfer.foreignSession",
            "terminal.dragDrop.skippedLinks", "terminal.sftp.sessionUnavailable");
        for (String locale : LOCALES) {
            Properties bundle = new Properties();
            bundle.load(new StringReader(Files.readString(
                BUNDLES.resolve("messages" + locale + ".properties"), StandardCharsets.UTF_8)));
            for (String key : keys) {
                assertWithMessage("messages%s: %s", locale, key).that(bundle.getProperty(key)).isNotEmpty();
            }
            assertWithMessage("messages%s: placeholder", locale)
                .that(bundle.getProperty("terminal.dragDrop.skippedLinks")).contains("{0}");
        }
    }

    @Test
    void theDropChecksTheSessionOfThePaneUnderThePointerBeforeCopying() throws IOException {
        String view = source("src/main/java/de/kortty/ui/TerminalView.java");
        String start = method(view, "private void startDroppedFileCopy(");
        assertThat(start).contains("splitPane.focusWidget(pane)");
        assertThat(start).contains("isForeignSessionActive(createTerminalAgentRunContext(pane))");
        assertThat(start).contains("TerminalTransferGuard.Transfer.DROP");
        assertThat(start.indexOf("refusal.isPresent()")).isLessThan(start.indexOf("copyDroppedFilesToServer("));
        assertThat(method(view, "private boolean handleFileDragDropped(")).contains("fileDropPane(event)");
    }

    @Test
    void theSnippetCopyChecksTheSessionBeforeItsWorkerStarts() throws IOException {
        String transfer = source("src/main/java/de/kortty/ui/SnippetTerminalTransfer.java");
        String start = method(transfer, "static void start(");
        int gate = start.indexOf("TerminalTransferGuard.Transfer.SNIPPET_COPY");
        assertThat(gate).isAtLeast(0);
        assertThat(gate).isLessThan(start.indexOf("new Thread("));
        assertThat(start).contains("view.isForeignSessionActive(context)");
    }

    @Test
    void terminalTransfersUseALeasedChannel() throws IOException {
        for (String path : List.of("src/main/java/de/kortty/ui/TerminalView.java",
                "src/main/java/de/kortty/ui/SnippetTerminalTransfer.java")) {
            String code = source(path);
            assertWithMessage(path).that(code).doesNotContain("SftpClientFactory");
            assertWithMessage(path).that(code).contains("TerminalSftpLease.open(");
        }
        assertThat(method(source("src/main/java/de/kortty/ui/TerminalView.java"),
            "private void copyDroppedFilesToServer(")).contains("try (TerminalSftpLease lease = TerminalSftpLease.open(");
    }

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code signature} to the next method declaration at class indentation. */
    private static String method(String code, String signature) {
        int start = code.indexOf(signature);
        assertWithMessage("method %s", signature).that(start).isAtLeast(0);
        int end = code.indexOf("\n    }\n", start);
        return code.substring(start, end < 0 ? code.length() : end);
    }
}
