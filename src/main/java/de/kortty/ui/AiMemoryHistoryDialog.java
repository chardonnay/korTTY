package de.kortty.ui;

import de.kortty.memory.MemoryScope;
import de.kortty.memory.MemoryStore;
import de.kortty.memory.MemoryStore.HistoryEntry;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * The saved versions of one memory file. The selected version is compared with the current file; restoring it asks for a
 * confirmation and then hands the version's text to {@code onRestore}.
 */
final class AiMemoryHistoryDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(AiMemoryHistoryDialog.class);
    /** The time of a version, in the application language's format. */
    private static DateTimeFormatter stamp() {
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(AiSkillStatisticsFormat.locale())
            .withZone(ZoneId.systemDefault());
    }

    private final MemoryStore store;
    private final MemoryScope scope;
    private final String currentText;
    private final boolean restorable;
    private final ListView<HistoryEntry> versions = new ListView<>();
    private final MonacoDiffPane diffPane = new MonacoDiffPane();
    private final Button restoreButton = new Button(I18n.get("settings.ai.memory.history.restore"));
    private final Label status = new Label();
    private boolean disposed;

    AiMemoryHistoryDialog(Window owner, MemoryStore store, MemoryScope scope, String currentText, boolean restorable,
                          Consumer<String> onRestore) {
        this.store = store;
        this.scope = scope;
        this.currentText = currentText;
        this.restorable = restorable;
        setTitle(I18n.get("settings.ai.memory.history.title"));
        setResizable(true);
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }
        versions.setPrefWidth(300);
        versions.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(HistoryEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(empty || entry == null ? null
                    : stamp().format(entry.takenAt()) + "  ·  " + I18n.get("settings.ai.memory.history.size", entry.size()));
            }
        });
        versions.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> showVersion(now));
        restoreButton.setDisable(true);
        restoreButton.setOnAction(event -> restore(onRestore));
        status.setWrapText(true);
        status.setStyle(MutedTextStyle.HINT);
        HBox.setHgrow(status, Priority.ALWAYS);
        Label header = new Label(I18n.get("settings.ai.memory.history.header"));
        header.setWrapText(true);
        HBox body = new HBox(12, versions, diffPane);
        HBox.setHgrow(diffPane, Priority.ALWAYS);
        VBox.setVgrow(body, Priority.ALWAYS);
        HBox footer = new HBox(10, status, restoreButton);
        VBox root = new VBox(10, header, body, footer);
        root.setPadding(new Insets(14));
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(new ButtonType(I18n.get("dialog.close"), ButtonBar.ButtonData.CANCEL_CLOSE));
        getDialogPane().setPrefSize(1040, 640);
        setResultConverter(button -> null);
        setOnHidden(event -> {
            disposed = true;
            diffPane.dispose();
        });
        load();
    }

    private void load() {
        CompletableFuture.supplyAsync(() -> {
            try {
                return store.history(scope);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }).whenComplete((entries, error) -> Platform.runLater(() -> {
            if (disposed) {
                return;
            }
            if (error != null) {
                logger.debug("The memory history could not be listed");
                status.setText(AiMemoryMessages.describe(error));
                return;
            }
            versions.setItems(FXCollections.observableArrayList(entries));
            status.setText(entries.isEmpty() ? I18n.get("settings.ai.memory.history.none") : "");
        }));
    }

    private void showVersion(HistoryEntry entry) {
        restoreButton.setDisable(entry == null || !restorable);
        if (entry == null) {
            return;
        }
        // Two selections read in turn may finish out of order; only the version still selected is compared.
        readVersion(entry, text -> {
            if (entry == versions.getSelectionModel().getSelectedItem()) {
                diffPane.setComparison(currentText, text, "markdown", "markdown");
            }
        });
    }

    private void restore(Consumer<String> onRestore) {
        HistoryEntry entry = versions.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.ownedBy(confirm, getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
        confirm.setTitle(I18n.get("settings.ai.memory.history.restoreConfirm.title"));
        confirm.setHeaderText(I18n.get("settings.ai.memory.history.restoreConfirm.header"));
        confirm.setContentText(I18n.get("settings.ai.memory.history.restoreConfirm.content"));
        DialogThemeHelper.applyTheme(confirm);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        readVersion(entry, text -> {
            onRestore.accept(text);
            close();
        });
    }

    /** Reads one version off the FX thread and hands its text back on the FX thread. */
    private void readVersion(HistoryEntry entry, Consumer<String> onText) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return store.readHistory(entry);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }).whenComplete((text, error) -> Platform.runLater(() -> {
            if (disposed) {
                return;
            }
            if (error != null) {
                status.setText(AiMemoryMessages.describe(error));
                return;
            }
            onText.accept(text);
        }));
    }
}
