package de.kortty.ui;

import de.kortty.control.McpWriteConsent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The modal that asks the user before an MCP client types into a pane.
 *
 * <p>{@link #ask} is called on a control worker thread. It posts the modal with
 * {@link Platform#runLater} and waits for the answer with a deadline; it never blocks the JavaFX
 * thread, and called on it, it refuses at once. When the deadline passes the modal is closed and the
 * answer is a denial. Deny is the default button, so Enter or Escape never allows anything.
 *
 * <p>The client name is labelled as reported by the client, because nothing proves it. The text is
 * shown exactly, with every control and invisible character made visible
 * ({@link McpWriteConsent#visible}).
 */
public final class McpWriteConsentDialog implements McpWriteConsent.Prompter {

    private static final Logger logger = LoggerFactory.getLogger(McpWriteConsentDialog.class);

    private static final int TEXT_ROWS = 8;

    @Override
    public McpWriteConsent.Decision ask(McpWriteConsent.Request request, long timeoutMillis)
            throws TimeoutException, InterruptedException {
        if (Platform.isFxApplicationThread()) {
            // A modal awaited here would freeze the whole UI; nobody can answer, so nobody allows.
            logger.warn("An MCP write consent was requested on the JavaFX thread; denied");
            return McpWriteConsent.Decision.DENY;
        }
        CompletableFuture<McpWriteConsent.Decision> answer = new CompletableFuture<>();
        AtomicReference<Alert> shown = new AtomicReference<>();
        // Throws IllegalStateException without a toolkit; McpWriteConsent counts that as no prompt.
        Platform.runLater(() -> {
            if (answer.isDone()) {
                return;
            }
            try {
                Alert alert = build(request);
                shown.set(alert);
                Optional<ButtonType> pressed = alert.showAndWait();
                answer.complete(decisionFor(alert, pressed.orElse(null)));
            } catch (RuntimeException e) {
                answer.completeExceptionally(e);
            }
        });
        try {
            return answer.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            answer.complete(McpWriteConsent.Decision.DENY);
            Platform.runLater(() -> {
                Alert alert = shown.get();
                if (alert != null && alert.isShowing()) {
                    alert.setResult(denyButton(alert));
                    alert.close();
                }
            });
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime
                : new IllegalStateException("The consent prompt failed", cause);
        }
    }

    private static Alert build(McpWriteConsent.Request request) {
        ButtonType deny = new ButtonType(I18n.get("controlApi.mcpConsent.deny"),
            ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType once = new ButtonType(I18n.get("controlApi.mcpConsent.allowOnce"),
            ButtonBar.ButtonData.OK_DONE);
        List<ButtonType> buttons = new ArrayList<>(List.of(deny, once));
        ButtonType session = null;
        if (request.sessionOffered()) {
            session = new ButtonType(I18n.get("controlApi.mcpConsent.allowSession"),
                ButtonBar.ButtonData.OTHER);
            buttons.add(session);
        }
        Alert alert = new Alert(Alert.AlertType.WARNING, "", buttons.toArray(ButtonType[]::new));
        DialogThemeHelper.applyTheme(alert);
        alert.initModality(Modality.APPLICATION_MODAL);
        alert.setTitle(I18n.get("controlApi.mcpConsent.title"));
        alert.setHeaderText(I18n.get("controlApi.mcpConsent.header"));
        alert.getDialogPane().setContent(content(request));
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        alert.getDialogPane().setUserData(new Buttons(deny, once, session));
        // Deny is the safe answer: Enter must never allow a write.
        for (ButtonType type : buttons) {
            if (alert.getDialogPane().lookupButton(type) instanceof Button button) {
                button.setDefaultButton(type == deny);
            }
        }
        alert.setOnShown(event -> {
            if (alert.getDialogPane().getScene().getWindow() instanceof Stage stage) {
                stage.toFront();
            }
        });
        return alert;
    }

    private static VBox content(McpWriteConsent.Request request) {
        GridPane facts = new GridPane();
        facts.setHgap(10);
        facts.setVgap(6);
        addRow(facts, 0, I18n.get("controlApi.mcpConsent.client"), request.client());
        addRow(facts, 1, I18n.get("controlApi.mcpConsent.pane"), request.paneLabel());
        addRow(facts, 2, I18n.get("controlApi.mcpConsent.action"), I18n.get(actionKey(request.verb())));
        addRow(facts, 3, I18n.get("controlApi.mcpConsent.submits"),
            I18n.get(request.submits() ? "controlApi.mcpConsent.submits.yes" : "controlApi.mcpConsent.submits.no"));

        Label textLabel = new Label(I18n.get("controlApi.mcpConsent.text"));
        TextArea text = new TextArea(McpWriteConsent.visible(request.text()));
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefRowCount(TEXT_ROWS);
        text.setStyle("-fx-font-family: monospace;");
        VBox.setVgrow(text, Priority.ALWAYS);

        Label warning = new Label(I18n.get("controlApi.mcpConsent.warning"));
        warning.setWrapText(true);

        VBox box = new VBox(8, facts, textLabel, text, warning);
        box.setPadding(new Insets(4, 0, 0, 0));
        box.setPrefWidth(560);
        return box;
    }

    private static void addRow(GridPane grid, int row, String name, String value) {
        Label nameLabel = new Label(name);
        nameLabel.setMinWidth(Region.USE_PREF_SIZE);
        Label valueLabel = new Label(value);
        valueLabel.setWrapText(true);
        grid.add(nameLabel, 0, row);
        grid.add(valueLabel, 1, row);
    }

    /** The i18n key describing what the write does. */
    static String actionKey(String verb) {
        return switch (verb == null ? "" : verb) {
            case "pane.run" -> "controlApi.mcpConsent.action.run";
            case "pane.send_keys" -> "controlApi.mcpConsent.action.sendKeys";
            default -> "controlApi.mcpConsent.action.sendText";
        };
    }

    /** The three buttons of one prompt, kept on the pane so the answer can be mapped back. */
    private record Buttons(ButtonType deny, ButtonType once, ButtonType session) {
    }

    private static ButtonType denyButton(Alert alert) {
        return ((Buttons) alert.getDialogPane().getUserData()).deny();
    }

    private static McpWriteConsent.Decision decisionFor(Alert alert, ButtonType pressed) {
        Buttons buttons = (Buttons) alert.getDialogPane().getUserData();
        if (pressed == null || pressed == buttons.deny()) {
            return McpWriteConsent.Decision.DENY;
        }
        if (pressed == buttons.once()) {
            return McpWriteConsent.Decision.ALLOW_ONCE;
        }
        if (buttons.session() != null && pressed == buttons.session()) {
            return McpWriteConsent.Decision.ALLOW_PANE_FOR_SESSION;
        }
        return McpWriteConsent.Decision.DENY;
    }
}
