package de.kortty.ui;

import de.kortty.core.TerminalAgentService;
import de.kortty.model.TerminalAgentModels;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Shared blocking-approval dialog for both swarm entry points ({@link SwarmAgentTab} and the
 * "ask all open terminals" broadcast in {@link AiResultTab}): a batch-approval confirmation with
 * owner/toFront hardening (an ownerless alert can open behind the main window or unfocused on
 * macOS) and a 500ms cancellation poll instead of an unbounded wait, so Stop / tab-close aborts a
 * pending approval instead of leaving the agent blocked forever.
 */
final class SwarmApprovalDialogSupport {

    private SwarmApprovalDialogSupport() {
    }

    static TerminalAgentService.ApprovalDecision requestBlocking(
        TerminalAgentModels.Approval approval,
        String serverLabel,
        Window owner,
        BooleanSupplier isCancelled) {

        CompletableFuture<TerminalAgentService.ApprovalDecision> future = new CompletableFuture<>();
        AtomicReference<Alert> openAlert = new AtomicReference<>();
        Runnable show = () -> {
            try {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle(I18n.get("ai.swarm.approve.title"));
                alert.setHeaderText(null);
                StringBuilder commands = new StringBuilder();
                if (approval != null && approval.commands() != null) {
                    for (TerminalAgentModels.PlannedCommand command : approval.commands()) {
                        if (command != null && command.command() != null) {
                            commands.append(command.command()).append('\n');
                        }
                    }
                }
                alert.setContentText(I18n.get("ai.swarm.approve.message", serverLabel, commands.toString().trim()));
                ButtonType approveAll = new ButtonType(I18n.get("ai.swarm.approve.approveAll"), ButtonBar.ButtonData.OK_DONE);
                ButtonType cancel = new ButtonType(I18n.get("ai.swarm.approve.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(approveAll, cancel);
                if (owner != null) {
                    alert.initOwner(owner);
                }
                alert.setOnShown(shownEvent -> {
                    if (alert.getDialogPane().getScene() != null
                        && alert.getDialogPane().getScene().getWindow() instanceof javafx.stage.Stage stage) {
                        stage.toFront();
                        stage.requestFocus();
                    }
                });
                openAlert.set(alert);
                Optional<ButtonType> choice = alert.showAndWait();
                future.complete(choice.isPresent() && choice.get() == approveAll
                    ? TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS
                    : TerminalAgentService.ApprovalDecision.CANCEL);
            } catch (Exception ex) {
                // a broken dialog must never leave the agent/broadcast blocked forever
                future.complete(TerminalAgentService.ApprovalDecision.CANCEL);
            } finally {
                openAlert.set(null);
            }
        };
        if (Platform.isFxApplicationThread()) {
            show.run();
        } else {
            Platform.runLater(show);
        }
        try {
            while (true) {
                try {
                    return future.get(500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException stillWaiting) {
                    if (isCancelled != null && isCancelled.getAsBoolean()) {
                        Platform.runLater(() -> {
                            Alert alert = openAlert.get();
                            if (alert != null && alert.isShowing()) {
                                alert.close();
                            }
                        });
                        return TerminalAgentService.ApprovalDecision.CANCEL;
                    }
                }
            }
        } catch (Exception e) {
            return TerminalAgentService.ApprovalDecision.CANCEL;
        }
    }
}
