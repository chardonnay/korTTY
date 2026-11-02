package de.kortty.ui;

import de.kortty.memory.MemoryCleanupService;
import de.kortty.memory.MemoryScope;
import de.kortty.memory.MemoryStore;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The korTTY entries archived for one memory file. The list shows the entries of that scope, newest first. The selected
 * entries can go back into the file or be deleted for good, each after a confirmation that names them. Viewing works in every
 * mode; a read-only or disabled memory disables both actions and says why.
 */
final class AiMemoryArchiveViewDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(AiMemoryArchiveViewDialog.class);
    private static final String ERROR_STYLE = "-fx-text-fill: #d9534f;";
    private static final int PREVIEW_LINES = 12;
    private static final int PREVIEW_CHARS = 120;

    /** Work that reads or writes the archive, run off the FX thread. */
    @FunctionalInterface
    private interface Io<T> {

        T run() throws IOException;
    }

    private final MemoryStore store;
    private final MemoryCleanupService cleanup = new MemoryCleanupService();
    private final MemoryScope scope;
    private final String targetLabel;
    private final boolean writable;
    private final String readOnlyReason;
    private final int budgetTokens;
    private final Runnable onChanged;
    private final ListView<AiMemoryArchiveRows.Row> list = new ListView<>();
    private final Button restoreButton = new Button(I18n.get("settings.ai.memory.archive.restore"));
    private final Button deleteButton = new Button(I18n.get("settings.ai.memory.archive.delete"));
    private final Label status = new Label();
    private boolean disposed;

    /**
     * @param targetLabel    the memory file the restore goes into, for the confirmation, such as {@code Global (MEMORY.md)}
     * @param writable       whether the memory may be changed now; false disables restore and delete
     * @param readOnlyReason the reason shown on the disabled actions while {@code writable} is false
     * @param budgetTokens   the budget the soft limit of the file is taken from
     * @param onChanged      runs on the FX thread after a restore, so the Memory tab can show the file again
     */
    AiMemoryArchiveViewDialog(Window owner, MemoryStore store, MemoryScope scope, String targetLabel, boolean writable,
                              String readOnlyReason, int budgetTokens, Runnable onChanged) {
        this.store = store;
        this.scope = scope;
        this.targetLabel = targetLabel;
        this.writable = writable;
        this.readOnlyReason = readOnlyReason;
        this.budgetTokens = budgetTokens;
        this.onChanged = onChanged;
        setTitle(I18n.get("settings.ai.memory.archive.title"));
        setResizable(true);
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }
        Label header = new Label(I18n.get("settings.ai.memory.archive.header"));
        header.setWrapText(true);
        header.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setCellFactory(view -> rowCell());
        list.setPlaceholder(new Label(I18n.get("settings.ai.memory.archive.none")));
        list.getSelectionModel().getSelectedItems()
            .addListener((ListChangeListener<AiMemoryArchiveRows.Row>) change -> updateButtons());
        VBox.setVgrow(list, Priority.ALWAYS);
        restoreButton.setOnAction(event -> restore());
        deleteButton.setOnAction(event -> deleteChosen());
        status.setWrapText(true);
        status.setStyle(MutedTextStyle.HINT);
        HBox.setHgrow(status, Priority.ALWAYS);
        HBox footer = new HBox(10, status, restoreButton, deleteButton);
        VBox root = new VBox(10, header, list, footer);
        root.setPadding(new Insets(14));
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefSize(720, 480);
        setResultConverter(button -> null);
        setOnHidden(event -> disposed = true);
        updateButtons();
        load();
    }

    private ListCell<AiMemoryArchiveRows.Row> rowCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(AiMemoryArchiveRows.Row row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                Label meta = new Label(I18n.get("settings.ai.memory.badge.korttyEntry", row.created(), row.source()));
                meta.setStyle(MutedTextStyle.HINT);
                Label text = new Label(row.firstLine());
                text.setWrapText(true);
                setText(null);
                setGraphic(new VBox(2, meta, text));
                setTooltip(new Tooltip(row.text()));
            }
        };
    }

    /** Reads the archive and shows the entries of the scope. */
    private void load() {
        background(() -> AiMemoryArchiveRows.forScope(store.readArchive().text(), scope), rows -> {
            list.getSelectionModel().clearSelection();
            list.setItems(FXCollections.observableArrayList(rows));
            updateButtons();
        });
    }

    private void restore() {
        List<AiMemoryArchiveRows.Row> chosen = selectedRows();
        if (chosen.isEmpty() || !writable || !confirmRestore(chosen)) {
            return;
        }
        Set<String> keys = keysOf(chosen);
        int count = chosen.size();
        background(() -> cleanup.restoreFromArchive(store, scope, keys, budgetTokens), written -> {
            status.setStyle(MutedTextStyle.HINT);
            status.setText(I18n.get("settings.ai.memory.archive.restored", count));
            onChanged.run();
            load();
        });
    }

    private void deleteChosen() {
        List<AiMemoryArchiveRows.Row> chosen = selectedRows();
        if (chosen.isEmpty() || !writable || !confirmDelete()) {
            return;
        }
        Set<String> keys = keysOf(chosen);
        background(() -> cleanup.deleteFromArchive(store, keys), removed -> {
            status.setStyle(MutedTextStyle.HINT);
            status.setText(I18n.get("settings.ai.memory.archive.deleted", removed));
            load();
        });
    }

    /** Names the entries and the file they go back into; only the confirmed restore is run. */
    private boolean confirmRestore(List<AiMemoryArchiveRows.Row> chosen) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.ownedBy(confirm, ownerWindow());
        confirm.setTitle(I18n.get("settings.ai.memory.archive.restoreConfirm.title"));
        confirm.setHeaderText(I18n.get("settings.ai.memory.archive.restoreConfirm.header", chosen.size(), targetLabel));
        confirm.setContentText(preview(chosen));
        ButtonType restore = new ButtonType(I18n.get("settings.ai.memory.archive.restore"), ButtonBar.ButtonData.OK_DONE);
        confirm.getButtonTypes().setAll(restore, ButtonType.CANCEL);
        DialogThemeHelper.applyTheme(confirm);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == restore;
    }

    private boolean confirmDelete() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.ownedBy(confirm, ownerWindow());
        confirm.setTitle(I18n.get("settings.ai.memory.archive.delete.title"));
        confirm.setHeaderText(I18n.get("settings.ai.memory.archive.delete.title"));
        confirm.setContentText(I18n.get("settings.ai.memory.archive.delete.content"));
        ButtonType delete = new ButtonType(I18n.get("settings.ai.memory.archive.deleteConfirm"),
            ButtonBar.ButtonData.OK_DONE);
        confirm.getButtonTypes().setAll(delete, ButtonType.CANCEL);
        DialogThemeHelper.applyTheme(confirm);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == delete;
    }

    /** The first lines of the chosen entries, with their dates; a long list is cut and says how many are left out. */
    static String preview(List<AiMemoryArchiveRows.Row> rows) {
        StringBuilder text = new StringBuilder();
        int shown = Math.min(rows.size(), PREVIEW_LINES);
        for (AiMemoryArchiveRows.Row row : rows.subList(0, shown)) {
            text.append("- ").append(row.created()).append("  ").append(truncate(row.firstLine())).append('\n');
        }
        if (rows.size() > shown) {
            text.append(I18n.get("settings.ai.memory.archive.restoreConfirm.more", rows.size() - shown));
        }
        return text.toString().strip();
    }

    private static String truncate(String text) {
        return text.length() <= PREVIEW_CHARS ? text : text.substring(0, PREVIEW_CHARS - 3) + "...";
    }

    private void updateButtons() {
        boolean any = !list.getSelectionModel().getSelectedItems().isEmpty();
        restoreButton.setDisable(!writable || !any);
        deleteButton.setDisable(!writable || !any);
        restoreButton.setTooltip(writable ? null : new Tooltip(readOnlyReason));
        deleteButton.setTooltip(writable ? null : new Tooltip(readOnlyReason));
    }

    private List<AiMemoryArchiveRows.Row> selectedRows() {
        return new ArrayList<>(list.getSelectionModel().getSelectedItems());
    }

    private static Set<String> keysOf(List<AiMemoryArchiveRows.Row> rows) {
        return rows.stream().map(AiMemoryArchiveRows.Row::key).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Window ownerWindow() {
        return getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
    }

    /** Runs {@code work} off the FX thread and hands its result to {@code onDone} on the FX thread. */
    private <T> void background(Io<T> work, Consumer<T> onDone) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return work.run();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }).whenComplete((value, error) -> Platform.runLater(() -> {
            if (disposed) {
                return;
            }
            if (error != null) {
                logger.debug("A memory archive action was refused or failed: {}", error.getClass().getSimpleName());
                status.setStyle(ERROR_STYLE);
                status.setText(AiMemoryMessages.describe(error));
                return;
            }
            onDone.accept(value);
        }));
    }
}
