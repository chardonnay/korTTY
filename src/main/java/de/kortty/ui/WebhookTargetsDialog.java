package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.jobscheduler.JobNotificationFormModel;
import de.kortty.jobscheduler.JobSchedulerService;
import de.kortty.jobscheduler.WebhookFormat;
import de.kortty.jobscheduler.WebhookPayloadFormatter;
import de.kortty.jobscheduler.WebhookSender;
import de.kortty.jobscheduler.WebhookTarget;
import de.kortty.jobscheduler.WebhookTargetSecrets;
import de.kortty.jobscheduler.WebhookTestSend;
import de.kortty.policy.PolicyManager;
import de.kortty.security.MasterPasswordManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;

/**
 * Adds, edits and deletes the webhook targets that job notifications can be sent to, and sends a
 * test notification to one. The URL field is masked and never shows a stored URL: left empty, the
 * stored (master-password-encrypted) URL is kept. The test send runs on the webhook sender's
 * background executor and reports only the receiver's host.
 */
public final class WebhookTargetsDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(WebhookTargetsDialog.class);
    private static final String I18N_PREFIX = "jobscheduler.dialog.webhook.";

    private final KorTTYApplication app;
    private final JobSchedulerService scheduler;
    private final String sampleJobName;
    private final ObservableList<WebhookTarget> targets = FXCollections.observableArrayList();
    private final ListView<WebhookTarget> targetList = new ListView<>(targets);
    private final TextField nameField = new TextField();
    private final ComboBox<WebhookFormat> formatCombo = new ComboBox<>();
    private final PasswordField urlField = new PasswordField();
    private final CheckBox includeSummaryCheck = new CheckBox(text("includeSummary"));
    private final Label includeSummaryWarning = new Label(text("includeSummary.warning"));
    private final CheckBox enabledCheck = new CheckBox(text("enabled"));
    private final Button saveButton = new Button(text("button.save"));
    private final Button deleteButton = new Button(text("button.delete"));
    private final Button testButton = new Button(text("button.sendTest"));
    private final Label statusLabel = new Label();
    private WebhookTarget editing;

    /**
     * @param sampleJobName the job name the test notification shows; the selected job's, or a
     *                      generic one
     */
    public WebhookTargetsDialog(KorTTYApplication app, Window owner, String sampleJobName) {
        this.app = app;
        this.scheduler = app.getJobSchedulerService();
        this.sampleJobName = sampleJobName != null && !sampleJobName.isBlank() ? sampleJobName : text("test.jobName");
        if (owner != null) {
            initOwner(owner);
        }
        initModality(Modality.WINDOW_MODAL);
        setTitle(text("title"));
        setHeaderText(text("header"));
        setResizable(true);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefSize(760, 480);
        getDialogPane().setContent(buildContent());
        reloadTargets(null);
        newTarget();
    }

    private HBox buildContent() {
        targetList.setPrefWidth(220);
        targetList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(WebhookTarget item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : JobNotificationFormModel.label(item)
                    + (item.isEnabled() ? "" : " " + text("disabledSuffix")));
            }
        });
        targetList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                edit(selected);
            }
        });
        Button addButton = new Button(text("button.add"));
        addButton.setOnAction(event -> {
            targetList.getSelectionModel().clearSelection();
            newTarget();
        });
        VBox listBox = new VBox(8, targetList, addButton);
        VBox.setVgrow(targetList, Priority.ALWAYS);

        formatCombo.getItems().setAll(WebhookFormat.values());
        formatCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(WebhookFormat format) {
                return format == null ? "" : text("format." + format.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public WebhookFormat fromString(String value) {
                return null;
            }
        });
        includeSummaryWarning.setWrapText(true);
        includeSummaryWarning.getStyleClass().add("warning-label");
        includeSummaryWarning.visibleProperty().bind(includeSummaryCheck.selectedProperty());
        includeSummaryWarning.managedProperty().bind(includeSummaryWarning.visibleProperty());
        statusLabel.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, fields);
        grid.addRow(0, new Label(text("name")), nameField);
        grid.addRow(1, new Label(text("format")), formatCombo);
        grid.addRow(2, new Label(text("url")), urlField);
        Label urlHint = new Label(text("url.hint"));
        urlHint.setWrapText(true);
        grid.add(urlHint, 1, 3);

        saveButton.setOnAction(event -> saveTarget());
        deleteButton.setOnAction(event -> deleteTarget());
        testButton.setOnAction(event -> sendTest());
        HBox buttons = new HBox(8, saveButton, deleteButton, testButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox editor = new VBox(10, grid, enabledCheck, includeSummaryCheck, includeSummaryWarning, buttons, statusLabel);
        HBox.setHgrow(editor, Priority.ALWAYS);
        HBox content = new HBox(14, listBox, editor);
        content.setPadding(new Insets(12));
        return content;
    }

    private void reloadTargets(String selectId) {
        targets.setAll(scheduler.getWebhookTargets());
        targets.sort((a, b) -> JobNotificationFormModel.label(a).compareToIgnoreCase(JobNotificationFormModel.label(b)));
        if (selectId != null) {
            targets.stream().filter(target -> selectId.equals(target.getId())).findFirst()
                .ifPresent(target -> targetList.getSelectionModel().select(target));
        }
    }

    private void newTarget() {
        edit(new WebhookTarget());
    }

    private void edit(WebhookTarget target) {
        editing = target.copy();
        nameField.setText(editing.getName() != null ? editing.getName() : "");
        formatCombo.getSelectionModel().select(editing.getFormat());
        urlField.clear();
        urlField.setPromptText(editing.hasUrl() ? text("url.storedPrompt") : text("url.prompt"));
        includeSummaryCheck.setSelected(editing.isIncludeSummary());
        enabledCheck.setSelected(editing.isEnabled());
        String id = editing.getId();
        deleteButton.setDisable(scheduler.getWebhookTargets().stream().noneMatch(stored -> stored.getId().equals(id)));
        statusLabel.setText("");
    }

    private void saveTarget() {
        Optional<String> problem = JobNotificationFormModel.validateTarget(nameField.getText(), urlField.getText(),
            editing.hasUrl());
        if (problem.isPresent()) {
            statusLabel.setText(I18n.get(problem.get()));
            return;
        }
        WebhookTarget target = editing.copy();
        target.setName(nameField.getText());
        target.setFormat(formatCombo.getValue());
        target.setIncludeSummary(includeSummaryCheck.isSelected());
        target.setEnabled(enabledCheck.isSelected());
        try {
            if (JobNotificationFormModel.replacesUrl(urlField.getText())) {
                char[] master = VaultUnlockSupport.masterPasswordOrOfferUnlock(
                    dialogWindow(), passwordManager(), I18n.get("jobscheduler.dialog.error.masterPasswordLocked"));
                if (master == null) {
                    return;
                }
                secrets().storeUrl(target, urlField.getText(), master);
            }
            scheduler.saveWebhookTarget(target);
            urlField.clear();
            reloadTargets(target.getId());
            edit(target);
            statusLabel.setText(text("saved"));
        } catch (IllegalArgumentException invalid) {
            // The message is the validator's i18n key and never contains the URL.
            statusLabel.setText(I18n.get(invalid.getMessage()));
        } catch (Exception e) {
            logger.warn("Could not save webhook target {}", target.getId(), e);
            showError(text("error.save"), e.getMessage());
        }
    }

    private void deleteTarget() {
        if (editing == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, text("delete.confirm",
            JobNotificationFormModel.label(editing)), ButtonType.OK, ButtonType.CANCEL);
        DialogThemeHelper.applyTheme(confirm);
        confirm.initOwner(dialogWindow());
        confirm.setHeaderText(null);
        if (confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }
        try {
            scheduler.deleteWebhookTarget(editing.getId());
            reloadTargets(null);
            newTarget();
        } catch (Exception e) {
            logger.warn("Could not delete webhook target {}", editing.getId(), e);
            showError(text("error.delete"), e.getMessage());
        }
    }

    private void sendTest() {
        WebhookSender sender = app.getJobWebhookSender();
        if (sender == null || editing == null) {
            statusLabel.setText(text("test.unavailable"));
            return;
        }
        WebhookTarget target = editing.copy();
        target.setName(nameField.getText());
        target.setFormat(formatCombo.getValue());
        target.setIncludeSummary(includeSummaryCheck.isSelected());
        String typedUrl = urlField.getText();
        char[] master = null;
        if (!JobNotificationFormModel.replacesUrl(typedUrl) && target.hasUrl()) {
            master = VaultUnlockSupport.masterPasswordOrOfferUnlock(
                dialogWindow(), passwordManager(), I18n.get("jobscheduler.dialog.error.masterPasswordLocked"));
            if (master == null) {
                return;
            }
        }
        testButton.setDisable(true);
        statusLabel.setText(text("test.sending"));
        WebhookTestSend.send(target, typedUrl, secrets(), master, PolicyManager.effective(),
                new WebhookPayloadFormatter(), sender, Clock.systemUTC(), sampleJobName, text("test.summary"))
            .thenAccept(outcome -> Platform.runLater(() -> {
                testButton.setDisable(false);
                statusLabel.setText(I18n.get(outcome.messageKey(), outcome.messageArgs()));
            }));
    }

    private WebhookTargetSecrets secrets() {
        return new WebhookTargetSecrets(passwordManager().getEncryptionService());
    }

    private MasterPasswordManager passwordManager() {
        return app.getMasterPasswordManager();
    }

    private Window dialogWindow() {
        return getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : getOwner();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message != null && !message.isBlank() ? message
            : I18n.get("jobscheduler.dialog.error.unknown"));
        alert.showAndWait();
    }

    private static String text(String suffix, Object... args) {
        return I18n.get(I18N_PREFIX + suffix, args);
    }
}
