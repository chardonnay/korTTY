package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.FileTransferGate;
import de.kortty.policy.PolicyDecision;
import de.kortty.policy.PolicyFeature;
import de.kortty.policy.PolicyFile;
import de.kortty.policy.PolicyIdentity;
import de.kortty.policy.PolicyRule;
import de.kortty.ui.sftp.SftpTransferQueuePaneTestAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * While the organization's policy denies file transfer, every transfer entry point of the SFTP
 * manager is greyed out or rejects the drag up front, instead of refusing only after the click or
 * the drop; with an allowing policy they are usable again. Browsing and remote-only operations stay
 * untouched (D6). The decisions are pure, so this runs without JavaFX.
 */
class SFTPManagerTabTransferPolicyTest {

    private static final PolicyIdentity USER = new PolicyIdentity() {
        @Override
        public String userName() {
            return "u";
        }

        @Override
        public Set<String> osGroups() {
            return Set.of();
        }
    };

    private static EffectivePolicy policy(PolicyDecision fileTransfer) {
        PolicyRule rule = PolicyRule.builder().features(Map.of(PolicyFeature.FILE_TRANSFER, fileTransfer)).build();
        return EffectivePolicy.resolve(new PolicyFile(1, "ACME", Map.of(), List.of(rule),
            List.of(), List.of(), List.of(), List.of()), USER);
    }

    private static boolean allowed(EffectivePolicy policy, FileTransferGate.Route route) {
        return FileTransferGate.check(policy, route).allowed();
    }

    @Test
    void aDenyingPolicyGreysOutUploadDownloadAndRetry() {
        EffectivePolicy deny = policy(PolicyDecision.DENY);

        assertThat(SFTPManagerTab.uploadEnabled(true, true, true, allowed(deny, FileTransferGate.Route.SFTP_UPLOAD)))
            .isFalse();
        assertThat(SFTPManagerTab.downloadEnabled(true, allowed(deny, FileTransferGate.Route.SFTP_DOWNLOAD)))
            .isFalse();
        assertThat(SftpTransferQueuePaneTestAccess.retryEnabled(true, true,
            allowed(deny, FileTransferGate.Route.SFTP_UPLOAD))).isFalse();
    }

    @Test
    void anAllowingPolicyLeavesThemToTheSelection() {
        for (EffectivePolicy allow : List.of(policy(PolicyDecision.ALLOW), EffectivePolicy.unrestricted())) {
            boolean up = allowed(allow, FileTransferGate.Route.SFTP_UPLOAD);
            boolean down = allowed(allow, FileTransferGate.Route.SFTP_DOWNLOAD);
            assertThat(SFTPManagerTab.uploadEnabled(true, true, true, up)).isTrue();
            assertThat(SFTPManagerTab.downloadEnabled(true, down)).isTrue();
            assertThat(SftpTransferQueuePaneTestAccess.retryEnabled(true, true, up)).isTrue();

            assertWithMessage("no selection, no upload").that(SFTPManagerTab.uploadEnabled(false, true, true, up)).isFalse();
            assertWithMessage("an unresolved '~' is no upload target")
                .that(SFTPManagerTab.uploadEnabled(true, true, false, up)).isFalse();
            assertThat(SFTPManagerTab.uploadEnabled(true, false, true, up)).isFalse();
            assertThat(SFTPManagerTab.downloadEnabled(false, down)).isFalse();
            assertThat(SftpTransferQueuePaneTestAccess.retryEnabled(true, false, up)).isFalse();
            assertThat(SftpTransferQueuePaneTestAccess.retryEnabled(false, true, up)).isFalse();
        }
    }

    @Test
    void aDenyingPolicyRejectsTransferDragsOverBothPanels() {
        boolean denied = allowed(policy(PolicyDecision.DENY), FileTransferGate.Route.SFTP_UPLOAD);

        assertWithMessage("files dragged onto the server panel are an upload")
            .that(SFTPManagerTab.remoteDropAccepted(false, true, true, true, denied)).isFalse();
        assertWithMessage("this tab's server rows dragged onto the local panel are a download")
            .that(SFTPManagerTab.localDropAccepted(false, true, true, false, denied)).isFalse();
        assertWithMessage("even when an earlier allowed drag-out had prepared copies")
            .that(SFTPManagerTab.localDropAccepted(false, true, true, true, denied)).isFalse();
        assertWithMessage("files from the desktop onto the local panel are a local copy (D6)")
            .that(SFTPManagerTab.localDropAccepted(false, true, false, true, denied)).isTrue();
    }

    @Test
    void anAllowingPolicyAcceptsTransferDrags() {
        boolean allowed = allowed(policy(PolicyDecision.ALLOW), FileTransferGate.Route.SFTP_UPLOAD);

        assertThat(SFTPManagerTab.remoteDropAccepted(false, true, true, true, allowed)).isTrue();
        assertThat(SFTPManagerTab.localDropAccepted(false, true, true, false, allowed)).isTrue();
        assertThat(SFTPManagerTab.localDropAccepted(false, true, false, true, allowed)).isTrue();

        assertWithMessage("a panel never takes its own rows")
            .that(SFTPManagerTab.remoteDropAccepted(true, true, true, true, allowed)).isFalse();
        assertThat(SFTPManagerTab.localDropAccepted(true, true, false, true, allowed)).isFalse();
        assertWithMessage("nothing to upload without a connection")
            .that(SFTPManagerTab.remoteDropAccepted(false, true, false, true, allowed)).isFalse();
        assertWithMessage("an unresolved '~' is no upload target")
            .that(SFTPManagerTab.remoteDropAccepted(false, true, true, false, allowed)).isFalse();
    }

    @Test
    void theTabWiresTheDecisionsIntoItsControlsAndDragHandlers() throws IOException {
        String tab = Files.readString(Path.of("src/main/java/de/kortty/ui/SFTPManagerTab.java"))
            .replace("\r\n", "\n");
        assertThat(tab).contains("return localDropAccepted(false, isRemoteConnected(), ownRemoteEntries(dragboard).isPresent(),\n"
            + "            dragboard.hasFiles(), fileTransferAllowed());");
        assertThat(tab).contains("return remoteDropAccepted(startedIn(event.getGestureSource(), remoteTable), "
            + "event.getDragboard().hasFiles(),\n            isRemoteConnected(), remotePathResolved, fileTransferAllowed());");
        // Drag-over asks the same decision, so a denied drag never shows the copy cursor.
        assertThat(tab).contains("if (acceptsLocalDrop(event)) {\n            event.acceptTransferModes(TransferMode.COPY);");
        assertThat(tab).contains("if (acceptsRemoteDrop(event)) {\n            event.acceptTransferModes(TransferMode.COPY);");
        // Defence in depth stays: the transfer methods still refuse on their own.
        assertThat(tab).contains("if (refuseTransfer(de.kortty.policy.FileTransferGate.Route.SFTP_UPLOAD)) return;");
        assertThat(tab).contains("if (refuseTransfer(de.kortty.policy.FileTransferGate.Route.SFTP_DOWNLOAD)) return;");
        // Disabled buttons carry the policy's reason as tooltip.
        assertThat(tab).contains("uploadButton.setTooltip(new Tooltip(transferRefusal(de.kortty.policy.FileTransferGate.Route.SFTP_UPLOAD)");
        assertThat(tab).contains("downloadButton.setTooltip(new Tooltip(transferRefusal(de.kortty.policy.FileTransferGate.Route.SFTP_DOWNLOAD)");
    }

    @Test
    void theTransferListGreysOutRetry() throws IOException {
        String pane = Files.readString(Path.of("src/main/java/de/kortty/ui/sftp/SftpTransferQueuePane.java"))
            .replace("\r\n", "\n");
        assertThat(pane).contains("retryButton.setDisable(!retryEnabled(hasQueue, anyRetryable, transferAllowed()));");
        assertThat(pane).contains("retryButton.setTooltip(new Tooltip(refusal));");
        assertThat(pane).contains("if (transferAllowed()) {\n                forSelection(item -> queue.retry(item));");
    }
}
