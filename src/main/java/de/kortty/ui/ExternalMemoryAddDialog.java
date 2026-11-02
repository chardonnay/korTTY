package de.kortty.ui;

import de.kortty.memory.external.ExternalMemoryEntry;
import de.kortty.memory.external.ExternalMemoryReader;
import de.kortty.memory.external.ExternalMemoryService;
import de.kortty.memory.external.ExternalMemoryTool;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Shows the full text of a reference memory file, with its path, tool and scope, before the user approves it. Nothing is
 * approved without the button; the approval reads the file again and refuses a text that changed after the review.
 *
 * <p>The same dialog approves a changed file again: the tool and scope stay as they were, and the new text is shown.</p>
 */
final class ExternalMemoryAddDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(ExternalMemoryAddDialog.class);
    private static final String ERROR_STYLE = "-fx-text-fill: #d9534f;";

    private final ExternalMemoryService service;
    private final ExternalMemoryService.Review review;
    private final ExternalMemoryEntry reapproveTarget;
    private final ComboBox<ExternalMemoryTool> toolCombo = new ComboBox<>();
    private final ComboBox<AiMemoryPane.ScopeChoice> scopeCombo = new ComboBox<>();
    private final Label problemLabel = new Label();
    private final Label errorLabel = new Label();
    private final ButtonType approveType;
    private ExternalMemoryEntry result;

    private ExternalMemoryAddDialog(Window owner, ExternalMemoryService service, ExternalMemoryService.Review review,
                                    ExternalMemoryEntry reapproveTarget, ExternalMemoryTool tool,
                                    List<AiMemoryPane.ScopeChoice> scopes, AiMemoryPane.ScopeChoice scope) {
        this.service = service;
        this.review = review;
        this.reapproveTarget = reapproveTarget;
        setTitle(I18n.get("settings.ai.memory.external.dialog.title"));
        setResizable(true);
        initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            initOwner(owner);
        }
        boolean reapproving = reapproveTarget != null;
        approveType = new ButtonType(I18n.get(reapproving
            ? "settings.ai.memory.external.dialog.reapprove" : "settings.ai.memory.external.dialog.approve"),
            ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(approveType, ButtonType.CANCEL);

        Label header = new Label(I18n.get("settings.ai.memory.external.dialog.header"));
        header.setWrapText(true);
        header.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        Label readOnly = hint(I18n.get("settings.ai.memory.external.dialog.readOnlyNote"));

        toolCombo.getItems().setAll(ExternalMemoryTool.values());
        toolCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ExternalMemoryTool value) {
                return value == null ? "" : ExternalMemoryTexts.toolLabel(value);
            }

            @Override
            public ExternalMemoryTool fromString(String text) {
                return null;
            }
        });
        toolCombo.getSelectionModel().select(tool);
        toolCombo.setDisable(reapproving);
        scopeCombo.getItems().setAll(scopes);
        scopeCombo.getSelectionModel().select(scope);
        scopeCombo.setDisable(reapproving);

        GridPane grid = AiManagerSections.formGrid();
        AiManagerSections.addRow(grid, "settings.ai.memory.external.dialog.path", pathLabel(review.file().toString()));
        AiManagerSections.addRow(grid, "settings.ai.memory.external.dialog.tool", toolCombo);
        AiManagerSections.addRow(grid, "settings.ai.memory.external.dialog.scope", scopeCombo);

        VBox notes = new VBox(6, readOnly);
        review.repository().ifPresent(repository -> notes.getChildren().add(
            warning(I18n.get("settings.ai.memory.external.dialog.repo", repository.toString()))));
        if (review.document() != null && review.document().removedInvisible() > 0) {
            notes.getChildren().add(hint(I18n.get("settings.ai.memory.external.dialog.removed",
                review.document().removedInvisible())));
        }
        if (review.document() != null && review.document().truncated()) {
            notes.getChildren().add(hint(I18n.get("settings.ai.memory.external.dialog.truncated")));
        }
        problemLabel.setWrapText(true);
        problemLabel.setStyle(ERROR_STYLE);
        problemLabel.setVisible(false);
        problemLabel.setManaged(false);
        errorLabel.setWrapText(true);
        errorLabel.setStyle(ERROR_STYLE);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        review.problem().ifPresent(problem -> showProblem(problemText(problem)));

        TextArea text = new TextArea(textToReview(review.document()));
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefRowCount(16);
        VBox.setVgrow(text, Priority.ALWAYS);

        VBox root = new VBox(10, header, grid, notes, problemLabel, errorLabel, text);
        root.setPadding(new Insets(14));
        VBox.setVgrow(text, Priority.ALWAYS);
        getDialogPane().setContent(root);
        getDialogPane().setPrefSize(860, 640);
        setResultConverter(button -> null);

        Button approve = (Button) getDialogPane().lookupButton(approveType);
        approve.setDisable(review.problem().isPresent());
        approve.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            approveInBackground(approve);
        });
    }

    /**
     * Shows the review of a new file and approves it on the user's confirmation.
     *
     * @return the new entry, or empty when the user cancelled
     */
    static Optional<ExternalMemoryEntry> approve(Window owner, ExternalMemoryService service,
                                                 ExternalMemoryService.Review review, ExternalMemoryTool tool,
                                                 List<AiMemoryPane.ScopeChoice> scopes,
                                                 AiMemoryPane.ScopeChoice scope) {
        ExternalMemoryAddDialog dialog = new ExternalMemoryAddDialog(owner, service, review, null, tool, scopes, scope);
        dialog.showAndWait();
        return Optional.ofNullable(dialog.result);
    }

    /**
     * Shows the review of a changed file and approves its new text, keeping the entry's tool and scope.
     *
     * @return the updated entry, or empty when the user cancelled
     */
    static Optional<ExternalMemoryEntry> reapprove(Window owner, ExternalMemoryService service, ExternalMemoryEntry entry,
                                                   ExternalMemoryService.Review review,
                                                   List<AiMemoryPane.ScopeChoice> scopes) {
        AiMemoryPane.ScopeChoice scope = scopes.stream()
            .filter(choice -> Objects.equals(choice.connectionId(), entry.connectionId()))
            .findFirst()
            .orElse(scopes.get(0));
        ExternalMemoryAddDialog dialog = new ExternalMemoryAddDialog(owner, service, review, entry, entry.tool(),
            scopes, scope);
        dialog.showAndWait();
        return Optional.ofNullable(dialog.result);
    }

    private void approveInBackground(Button approve) {
        approve.setDisable(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        ExternalMemoryTool tool = toolCombo.getValue();
        AiMemoryPane.ScopeChoice scope = scopeCombo.getValue();
        String connectionId = scope == null ? null : scope.connectionId();
        CompletableFuture.supplyAsync(() -> {
            try {
                return reapproveTarget == null
                    ? service.approve(review, tool, connectionId)
                    : service.reapprove(reapproveTarget.id(), review).orElse(null);
            } catch (IOException | RuntimeException e) {
                throw new CompletionException(e);
            }
        }).whenComplete((entry, error) -> Platform.runLater(() -> {
            if (error != null) {
                showFailure(error);
                approve.setDisable(false);
                return;
            }
            result = entry;
            close();
        }));
    }

    private void showFailure(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof ExternalMemoryService.RefusedException refused) {
            errorLabel.setText(problemText(refused.problem()));
        } else {
            logger.warn("The memory file could not be approved: {}", cause.getClass().getSimpleName());
            errorLabel.setText(I18n.get("settings.ai.memory.external.error", cause.getClass().getSimpleName()));
        }
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void showProblem(String text) {
        problemLabel.setText(text);
        problemLabel.setVisible(true);
        problemLabel.setManaged(true);
    }

    private String problemText(ExternalMemoryService.Problem problem) {
        if (problem == ExternalMemoryService.Problem.SECRET && review.secret().isPresent()) {
            var finding = review.secret().get();
            return I18n.get("settings.ai.memory.secret.refused", finding.line(),
                AiMemoryMessages.categoryLabel(finding.category()));
        }
        return ExternalMemoryTexts.problemText(problem);
    }

    /** A path has no spaces to wrap at: a zero-width space after each separator lets long paths break there. */
    private static Label pathLabel(String path) {
        Label label = new Label(path.replace("/", "/\u200B").replace("\\", "\\\u200B"));
        label.setWrapText(true);
        label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return label;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        label.setStyle(MutedTextStyle.HINT);
        return label;
    }

    private static Label warning(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: #b7791f;");
        return label;
    }

    /**
     * The text the approval shows: the text a request sends, so the user reviews exactly what is sent. Front matter that is
     * not sent is not shown either; a binary or too large file shows nothing, as nothing is sent.
     */
    static String textToReview(ExternalMemoryReader.Document document) {
        return document == null ? "" : document.promptText();
    }
}
