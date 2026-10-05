package de.kortty.ui;

import de.kortty.core.OpenSshKnownHostsParser.KnownHostsFile;
import de.kortty.core.SshHostKeyTrustManager.KnownHostsConflict;
import de.kortty.core.SshHostKeyTrustManager.KnownHostsImportResult;
import de.kortty.core.SshHostKeyTrustManager.TrustedHostKey;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static com.google.common.truth.Truth.assertThat;

class KnownHostsDialogImportSummaryTest {

    private static final TrustedHostKey ADDED = new TrustedHostKey(
        "web.example.com", 22, "ssh-ed25519", "SHA256:Added", "2026-01-02T03:04:05Z");
    private static final TrustedHostKey REVOKED = new TrustedHostKey(
        "old.example.com", 2200, "ssh-rsa", "SHA256:Revoked", "2026-01-02T03:04:05Z");
    private static final KnownHostsConflict CONFLICT = new KnownHostsConflict(
        "db.example.com", 2222, "ssh-rsa", "SHA256:Trusted", "ecdsa-sha2-nistp256", "SHA256:FromFile", 7);

    @Test
    void cleanImportShowsOnlyTheThreeMainCounts() {
        KnownHostsImportResult result = new KnownHostsImportResult(
            file(0, 0, 0, 0, 0, List.of()), List.of(ADDED), 3, List.of(), 0, 0, List.of(), false);

        String summary = KnownHostsDialog.summaryText(result, false);

        assertThat(summary.split("\n")).asList().containsExactly(
            I18n.get("ssh.knownHosts.import.summary.added", 1),
            I18n.get("ssh.knownHosts.import.summary.alreadyTrusted", 3),
            I18n.get("ssh.knownHosts.import.summary.conflicts", 0)).inOrder();
        assertThat(KnownHostsDialog.detailsText(result)).isEmpty();
    }

    @Test
    void reportsEverySkippedKindConflictsAndRevokedTrustedKeys() {
        KnownHostsImportResult result = new KnownHostsImportResult(
            file(2, 1, 1, 4, 5, List.of(18, 19)), List.of(ADDED), 0, List.of(CONFLICT), 2, 3, List.of(REVOKED), true);

        String summary = KnownHostsDialog.summaryText(result, false);

        assertThat(summary).contains(I18n.get("ssh.knownHosts.import.summary.conflicts", 1));
        assertThat(summary).contains(I18n.get("ssh.knownHosts.import.summary.additionalKeys", 2));
        assertThat(summary).contains(I18n.get("ssh.knownHosts.import.summary.hashed", 2));
        assertThat(summary).contains(I18n.get("ssh.knownHosts.import.summary.revoked", 1));
        assertThat(summary).contains(I18n.get("ssh.knownHosts.import.summary.revokedEntries", 3));
        assertThat(summary).contains(I18n.get("ssh.knownHosts.import.summary.certAuthority", 1));
        assertThat(summary).contains(I18n.get("ssh.knownHosts.import.summary.patterns", 4));
        assertThat(summary).contains(I18n.get("ssh.knownHosts.import.summary.unsupported", 5));
        assertThat(summary).contains(I18n.get("ssh.knownHosts.import.summary.malformed", 2, "18, 19"));
        assertThat(summary).contains(I18n.get("ssh.knownHosts.import.summary.trustedRevoked", 1));
        assertThat(summary).contains(I18n.get("ssh.knownHosts.import.summary.conflictHint"));
        assertThat(summary).doesNotContain(I18n.get("ssh.knownHosts.import.summary.conflictHintLocked"));

        String locked = KnownHostsDialog.summaryText(result, true);
        assertThat(locked).contains(I18n.get("ssh.knownHosts.import.summary.conflictHintLocked"));
        assertThat(locked).doesNotContain(I18n.get("ssh.knownHosts.import.summary.conflictHint"));

        String details = KnownHostsDialog.detailsText(result);
        assertThat(details).contains("db.example.com:2222");
        assertThat(details).contains("SHA256:Trusted");
        assertThat(details).contains("SHA256:FromFile");
        assertThat(details).contains("7");
        assertThat(details).contains("old.example.com:2200");
        assertThat(details).contains("SHA256:Revoked");
    }

    @Test
    void longMalformedLineListsAreShortened() {
        List<Integer> numbers = IntStream.rangeClosed(1, 25).boxed().toList();

        String text = KnownHostsDialog.lineNumbers(numbers);

        assertThat(text).startsWith("1, 2, 3");
        assertThat(text).contains("20");
        assertThat(text).doesNotContain("21");
        assertThat(text).endsWith(", …");
        assertThat(KnownHostsDialog.lineNumbers(List.of(4))).isEqualTo("4");
    }

    @Test
    void importWorkRunsOffTheFxThread() throws Exception {
        String source = Files.readString(
            Path.of("src/main/java/de/kortty/ui/KnownHostsDialog.java"), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");

        assertThat(source).contains("new Thread(() -> {");
        assertThat(source).contains("importButton.setMnemonicParsing(false);");
        assertThat(source).contains("OpenSshKnownHostsParser.read(file), true)");
        assertThat(source).contains("OpenSshKnownHostsParser.read(file), false)");
        assertThat(source).contains("Platform.runLater(() -> {");
        assertThat(source).contains("((Button) confirm.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);");
    }

    private static KnownHostsFile file(int hashed, int revoked, int certAuthority, int patterns, int unsupported,
                                       List<Integer> malformed) {
        return new KnownHostsFile(List.of(), Set.of(), hashed, revoked, certAuthority, patterns, unsupported, malformed);
    }
}
