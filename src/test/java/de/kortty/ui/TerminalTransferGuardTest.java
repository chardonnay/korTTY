package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.FileTransferGate;
import de.kortty.policy.PolicyDecision;
import de.kortty.policy.PolicyFeature;
import de.kortty.policy.PolicyFile;
import de.kortty.policy.PolicyIdentity;
import de.kortty.policy.PolicyRule;

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
    void aFileTransferDenyRefusesTerminalDropAndSnippetCopy() {
        EffectivePolicy deny = policy(PolicyDecision.DENY);
        for (TerminalTransferGuard.Transfer transfer : TerminalTransferGuard.Transfer.values()) {
            assertWithMessage(transfer.name()).that(TerminalTransferGuard.policyRefusal(transfer, deny)).isPresent();
            assertThat(TerminalTransferGuard.policyRefusal(transfer, deny).get()).contains("ACME");
        }
        assertThat(TerminalTransferGuard.Transfer.DROP.route()).isEqualTo(FileTransferGate.Route.TERMINAL_DROP);
        assertThat(TerminalTransferGuard.Transfer.SNIPPET_COPY.route())
            .isEqualTo(FileTransferGate.Route.SNIPPET_COPY_TO_TERMINAL);
    }

    @Test
    void anAllowingPolicyLetsTerminalDropAndSnippetCopyThrough() {
        for (EffectivePolicy allowing : List.of(policy(PolicyDecision.ALLOW), EffectivePolicy.unrestricted())) {
            for (TerminalTransferGuard.Transfer transfer : TerminalTransferGuard.Transfer.values()) {
                assertThat(TerminalTransferGuard.policyRefusal(transfer, allowing)).isEmpty();
            }
        }
    }

    @Test
    void aDeniedDropIsRejectedWhileDraggingAndADeniedSnippetCopyIsGreyedOut() throws IOException {
        String view = source("src/main/java/de/kortty/ui/TerminalView.java");
        String over = method(view, "private boolean handleFileDragOver(");
        int gate = over.indexOf("TerminalTransferGuard.allowedByPolicy(TerminalTransferGuard.Transfer.DROP)");
        assertThat(gate).isAtLeast(0);
        assertThat(gate).isLessThan(over.indexOf("event.acceptTransferModes(TransferMode.COPY)"));
        assertThat(method(view, "private boolean handleFileDragDropped("))
            .contains("TerminalTransferGuard.allowedByPolicy(TerminalTransferGuard.Transfer.DROP)");
        String start = method(view, "private void startDroppedFileCopy(");
        assertThat(start.indexOf("TerminalTransferGuard.policyRefusal(")).isLessThan(start.indexOf("copyDroppedFilesToServer("));

        String transfer = source("src/main/java/de/kortty/ui/SnippetTerminalTransfer.java");
        assertThat(method(transfer, "static boolean supports("))
            .contains("TerminalTransferGuard.allowedByPolicy(TerminalTransferGuard.Transfer.SNIPPET_COPY)");
        String snippetStart = method(transfer, "static void start(");
        assertThat(snippetStart.indexOf("TerminalTransferGuard.policyRefusal(")).isLessThan(snippetStart.indexOf("new Thread("));
        // Both snippet menus grey their "copy to terminal" items through supports().
        assertThat(source("src/main/java/de/kortty/ui/SnippetLibraryPane.java"))
            .contains("return tab != null && SnippetTerminalTransfer.supports(tab);");
        assertThat(source("src/main/java/de/kortty/ui/SnippetFolderTreePane.java"))
            .contains("copyToTerminal.setDisable(!folder || !actions.canCopyToTerminal());");
    }

    private static EffectivePolicy policy(PolicyDecision fileTransfer) {
        PolicyIdentity user = new PolicyIdentity() {
            @Override
            public String userName() {
                return "u";
            }

            @Override
            public Set<String> osGroups() {
                return Set.of();
            }
        };
        PolicyRule rule = PolicyRule.builder().features(Map.of(PolicyFeature.FILE_TRANSFER, fileTransfer)).build();
        return EffectivePolicy.resolve(new PolicyFile(1, "ACME", Map.of(), List.of(rule),
            List.of(), List.of(), List.of(), List.of()), user);
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
