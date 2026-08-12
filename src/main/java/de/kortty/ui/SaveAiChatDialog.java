package de.kortty.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * Dialog for saving or renaming an AI chat with optional asynchronous title generation.
 */
public class SaveAiChatDialog extends ThemeAwareDialog<String> {

    @FunctionalInterface
    public interface SuggestedTitleProvider {
        String generate() throws Exception;
    }

    private final TextField titleField;
    private final Label statusLabel;
    private Task<String> suggestionTask;
    private boolean programmaticUpdate;
    private boolean userEdited;

    public SaveAiChatDialog(
        Window owner,
        String dialogTitle,
        String headerText,
        String initialTitle,
        String fallbackTitle,
        SuggestedTitleProvider suggestedTitleProvider) {
        setTitle(dialogTitle);
        setHeaderText(headerText);
        if (owner != null) {
            initOwner(owner);
        }
        initModality(Modality.WINDOW_MODAL);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        titleField = new TextField(initialTitle != null ? initialTitle : "");
        titleField.setPromptText(I18n.get("ai.result.save.label"));
        titleField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (!programmaticUpdate) {
                userEdited = true;
            }
            Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
            if (okButton != null) {
                okButton.setDisable(newValue == null || newValue.trim().isEmpty());
            }
        });

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");

        VBox content = new VBox(10, new Label(I18n.get("ai.result.save.label")), titleField, statusLabel);
        content.setPadding(new Insets(6, 0, 0, 0));
        getDialogPane().setContent(content);

        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(titleField.getText() == null || titleField.getText().trim().isEmpty());

        setResultConverter(buttonType -> buttonType == ButtonType.OK ? titleField.getText().trim() : null);
        setOnHidden(event -> cancelSuggestionTask());

        if (suggestedTitleProvider != null) {
            beginTitleGeneration(suggestedTitleProvider, fallbackTitle);
        } else if (fallbackTitle != null && titleField.getText().trim().isEmpty()) {
            applySuggestedTitle(fallbackTitle);
        }

        Platform.runLater(() -> {
            titleField.requestFocus();
            titleField.selectAll();
        });
    }

    private void beginTitleGeneration(SuggestedTitleProvider provider, String fallbackTitle) {
        statusLabel.setText(I18n.get("ai.result.save.generating"));
        suggestionTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                return provider.generate();
            }
        };
        suggestionTask.setOnSucceeded(event -> {
            String suggestion = suggestionTask.getValue();
            if (!userEdited && suggestion != null && !suggestion.isBlank()) {
                applySuggestedTitle(suggestion);
            } else if (!userEdited && (titleField.getText() == null || titleField.getText().trim().isEmpty()) && fallbackTitle != null) {
                applySuggestedTitle(fallbackTitle);
            }
            statusLabel.setText(I18n.get("ai.result.save.generated"));
        });
        suggestionTask.setOnFailed(event -> {
            if (!userEdited && (titleField.getText() == null || titleField.getText().trim().isEmpty()) && fallbackTitle != null) {
                applySuggestedTitle(fallbackTitle);
            }
            statusLabel.setText(I18n.get("ai.result.save.generateFailed"));
        });
        Thread thread = new Thread(suggestionTask, "ai-chat-title-suggestion");
        thread.setDaemon(true);
        thread.start();
    }

    private void applySuggestedTitle(String title) {
        programmaticUpdate = true;
        try {
            titleField.setText(title != null ? title.trim() : "");
        } finally {
            programmaticUpdate = false;
        }
    }

    private void cancelSuggestionTask() {
        if (suggestionTask != null) {
            suggestionTask.cancel(true);
        }
    }
}
