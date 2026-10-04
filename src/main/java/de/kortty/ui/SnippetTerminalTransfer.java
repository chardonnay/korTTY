package de.kortty.ui;

import de.kortty.core.LocalShellTtyConnector;
import de.kortty.core.ObservableTtyConnector;
import de.kortty.core.RemotePathSupport;
import de.kortty.core.SnippetFolderLayout;
import de.kortty.core.SnippetTreeTransferService;
import de.kortty.core.SshTtyConnector;
import de.kortty.core.sftp.TerminalSftpLease;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Copies snippets as files — a single script, a selection or a whole folder tree — into the
 * working directory of a terminal tab: over the session's SFTP channel for SSH, on disk for a
 * local shell. Every file gets its executable bit from the snippet ({@code 0755} / {@code 0644}).
 * The target directory and existing files are resolved off the FX thread, the user confirms
 * (overwrite, skip existing or cancel), then a cancellable progress dialog runs the copy.
 * The copy is refused up front while the pane runs as another user or host (after {@code su} or a
 * nested {@code ssh}), see {@link TerminalTransferGuard}.
 */
final class SnippetTerminalTransfer {

    private static final Logger logger = LoggerFactory.getLogger(SnippetTerminalTransfer.class);
    private static final int MAX_LISTED_CONFLICTS = 12;

    private SnippetTerminalTransfer() {
    }

    /**
     * Whether the tab's focused pane can take the files now: an SSH session the organization's
     * file-transfer policy lets korTTY copy to, or a local shell (Telnet/serial cannot receive files).
     * The snippet menus grey out their "copy to terminal" items with it, so a denied copy is visible
     * before it is chosen.
     */
    static boolean supports(TerminalTab tab) {
        if (tab == null || tab.getTerminalView() == null) {
            return false;
        }
        SshTtyConnector ssh = tab.getTerminalView().getActiveSshConnector();
        if (ssh != null) {
            return ssh.isConnected() && ssh.getSession() != null
                && TerminalTransferGuard.allowedByPolicy(TerminalTransferGuard.Transfer.SNIPPET_COPY);
        }
        ObservableTtyConnector connector = tab.getTerminalView().getActiveAgentConnector();
        return connector instanceof LocalShellTtyConnector local && local.isConnected();
    }

    /**
     * The policy's refusal of a copy into the tab's focused pane, or empty. Only a copy to a server
     * is a file transfer; a local shell's folder is on this computer.
     */
    static Optional<String> policyRefusal(TerminalTab tab) {
        if (tab == null || tab.getTerminalView() == null || tab.getTerminalView().getActiveSshConnector() == null) {
            return Optional.empty();
        }
        return TerminalTransferGuard.policyRefusal(TerminalTransferGuard.Transfer.SNIPPET_COPY,
            de.kortty.policy.PolicyManager.effective());
    }

    /**
     * One prepared copy: where it goes and which files are already there. A remote plan owns the SFTP
     * channel leased from the terminal's session and releases it in {@link #close()}.
     */
    private record Plan(String targetDirectory, List<String> conflicts, TerminalSftpLease lease, Path localDirectory) {
        SftpClient sftp() {
            return lease != null ? lease.client() : null;
        }

        void close() {
            if (lease != null) {
                lease.close();
            }
        }
    }

    static void start(Window owner, TerminalTab tab, SnippetFolderLayout layout, String label, Runnable onFinished) {
        TerminalView view = tab.getTerminalView();
        SshTtyConnector ssh = view.getActiveSshConnector();
        if (ssh != null) {
            Optional<String> denied = TerminalTransferGuard.policyRefusal(
                TerminalTransferGuard.Transfer.SNIPPET_COPY, de.kortty.policy.PolicyManager.effective());
            if (denied.isPresent()) {
                logger.info("Snippets not copied to the terminal directory: file transfer is disabled by policy");
                showError(owner, denied.get());
                return;
            }
        }
        TerminalView.TerminalAgentRunContext context = view.captureTerminalAgentRunContext();
        // FX thread: the verdict reads the screen; only the plain result reaches the worker.
        Optional<String> refusal = TerminalTransferGuard.refusalKey(
            TerminalTransferGuard.Transfer.SNIPPET_COPY, view.isForeignSessionActive(context));
        if (refusal.isPresent()) {
            logger.info("Snippets not copied to the terminal directory: a different session is active in the pane");
            showError(owner, I18n.get(refusal.get()));
            return;
        }
        String promptDirectory = context != null ? context.workingDirectory() : null;
        ObservableTtyConnector local = ssh == null ? view.getActiveAgentConnector() : null;

        Thread resolver = new Thread(() -> {
            Plan plan = null;
            try {
                plan = ssh != null ? prepareRemote(ssh, promptDirectory, layout)
                    : prepareLocal((LocalShellTtyConnector) local, promptDirectory, layout);
                Plan ready = plan;
                Platform.runLater(() -> confirmAndRun(owner, ready, layout, label, onFinished));
            } catch (Exception e) {
                if (plan != null) {
                    plan.close();
                }
                logger.warn("Could not prepare copying snippets to the terminal directory", e);
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                Platform.runLater(() -> showError(owner, I18n.get("snippets.transfer.failed", message)));
            }
        }, "SnippetTransferPrepare");
        resolver.setDaemon(true);
        resolver.start();
    }

    private static Plan prepareRemote(SshTtyConnector ssh, String promptDirectory, SnippetFolderLayout layout)
            throws Exception {
        TerminalSftpLease lease = TerminalSftpLease.open(ssh.getSession());
        try {
            SftpClient sftp = lease.client();
            String tracked = ssh.getCurrentRemoteDirectory();
            String target;
            if (tracked != null && tracked.trim().startsWith("/")) {
                target = tracked.trim();
            } else if (promptDirectory != null && promptDirectory.startsWith("/")) {
                target = promptDirectory;
            } else {
                String start = RemotePathSupport.needsSftpStartDirectory(tracked)
                    ? RemotePathSupport.sftpStartDirectory(sftp) : null;
                target = RemotePathSupport.resolveTargetDirectory(tracked, start);
            }
            List<String> conflicts = SnippetTreeTransferService.existingRemote(sftp, target, layout);
            return new Plan(target, conflicts, lease, null);
        } catch (Exception e) {
            lease.close();
            throw e;
        }
    }

    private static Plan prepareLocal(LocalShellTtyConnector local, String promptDirectory, SnippetFolderLayout layout)
            throws Exception {
        if (local == null) {
            throw new IllegalStateException(I18n.get("snippets.transfer.unsupported"));
        }
        String directory = local.refreshCurrentWorkingDirectory(); // blocking: we are off the FX thread
        if (directory == null || directory.isBlank()) {
            directory = promptDirectory != null ? promptDirectory : local.getCurrentWorkingDirectory();
        }
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException(I18n.get("snippets.transfer.noDirectory"));
        }
        Path path = Path.of(directory);
        return new Plan(path.toString(), SnippetTreeTransferService.existingLocal(path, layout), null, path);
    }

    private static void confirmAndRun(Window owner, Plan plan, SnippetFolderLayout layout, String label,
                                      Runnable onFinished) {
        int files = layout.entries().size();
        ButtonType copy = new ButtonType(I18n.get("snippets.transfer.copy"), ButtonBar.ButtonData.OK_DONE);
        ButtonType overwrite = new ButtonType(I18n.get("snippets.transfer.overwrite"), ButtonBar.ButtonData.OK_DONE);
        ButtonType skip = new ButtonType(I18n.get("snippets.transfer.skipExisting"), ButtonBar.ButtonData.OTHER);
        Alert confirm;
        if (plan.conflicts().isEmpty()) {
            confirm = new Alert(Alert.AlertType.CONFIRMATION,
                I18n.get("snippets.transfer.confirm", label, files, plan.targetDirectory()), copy, ButtonType.CANCEL);
        } else {
            StringBuilder listed = new StringBuilder();
            plan.conflicts().stream().limit(MAX_LISTED_CONFLICTS).forEach(path -> listed.append("• ").append(path).append('\n'));
            if (plan.conflicts().size() > MAX_LISTED_CONFLICTS) {
                listed.append(I18n.get("snippets.transfer.moreConflicts", plan.conflicts().size() - MAX_LISTED_CONFLICTS));
            }
            confirm = new Alert(Alert.AlertType.WARNING,
                I18n.get("snippets.transfer.conflicts", plan.conflicts().size(), plan.targetDirectory()) + "\n\n" + listed,
                overwrite, skip, ButtonType.CANCEL);
        }
        confirm.setTitle(I18n.get("snippets.transfer.title"));
        confirm.setHeaderText(null);
        confirm.initOwner(owner);
        confirm.getDialogPane().setMinWidth(480);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() == ButtonType.CANCEL) {
            plan.close();
            return;
        }
        SnippetTreeTransferService.ConflictPolicy policy = answer.get() == skip
            ? SnippetTreeTransferService.ConflictPolicy.SKIP : SnippetTreeTransferService.ConflictPolicy.OVERWRITE;
        run(owner, plan, layout, policy, onFinished);
    }

    private static void run(Window owner, Plan plan, SnippetFolderLayout layout,
                            SnippetTreeTransferService.ConflictPolicy policy, Runnable onFinished) {
        AtomicBoolean cancelled = new AtomicBoolean();
        ProgressBar progress = new ProgressBar(0);
        progress.setPrefWidth(360);
        Label target = new Label(I18n.get("terminal.dragDrop.target", plan.targetDirectory()));
        target.setWrapText(true);
        Label status = new Label(I18n.get("terminal.dragDrop.count", 0, layout.entries().size()));
        Label current = new Label("");
        current.setStyle("-fx-font-size: 0.8462em; -fx-opacity: 0.75;");
        VBox content = new VBox(8, target, status, current, progress);
        content.setPadding(new Insets(14));

        ThemeAwareDialog<Void> dialog = new ThemeAwareDialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.get("snippets.transfer.title"));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        dialog.setOnCloseRequest(event -> cancelled.set(true));

        Thread worker = new Thread(() -> {
            try {
                SnippetTreeTransferService.ProgressListener listener = (done, total, path) -> Platform.runLater(() -> {
                    status.setText(I18n.get("terminal.dragDrop.count", done, total));
                    current.setText(path);
                    progress.setProgress(total == 0 ? 1 : (double) done / total);
                });
                SnippetTreeTransferService.Result result = plan.sftp() != null
                    ? SnippetTreeTransferService.uploadRemote(plan.sftp(), plan.targetDirectory(), layout, policy,
                        listener, cancelled::get)
                    : SnippetTreeTransferService.writeLocal(plan.localDirectory(), layout, policy,
                        listener, cancelled::get);
                logger.info("Copied {} snippet files to {} (skipped {}, cancelled {})",
                    result.written().size(), result.targetDirectory(), result.skipped().size(), result.cancelled());
                Platform.runLater(() -> {
                    status.setText(result.cancelled()
                        ? I18n.get("snippets.transfer.cancelled", result.written().size())
                        : I18n.get("snippets.transfer.done", result.written().size(), result.skipped().size()));
                    progress.setProgress(1);
                    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
                    if (!result.cancelled() && onFinished != null) {
                        onFinished.run();
                    }
                });
            } catch (Exception e) {
                logger.warn("Copying snippets to the terminal directory failed", e);
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                Platform.runLater(() -> {
                    status.setText(I18n.get("snippets.transfer.failed", message));
                    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
                });
            } finally {
                plan.close();
            }
        }, "SnippetTransfer");
        worker.setDaemon(true);
        worker.start();
        dialog.show();
    }

    private static void showError(Window owner, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        alert.setHeaderText(null);
        TextArea text = new TextArea(message);
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefRowCount(4);
        alert.getDialogPane().setContent(text);
        alert.showAndWait();
    }
}
