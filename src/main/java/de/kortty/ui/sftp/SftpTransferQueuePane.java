package de.kortty.ui.sftp;

import de.kortty.core.sftp.transfer.PartFiles;
import de.kortty.core.sftp.transfer.SftpTransferQueue;
import de.kortty.core.sftp.transfer.TransferBatch;
import de.kortty.core.sftp.transfer.TransferItem;
import de.kortty.core.sftp.transfer.TransferQueueListener;
import de.kortty.core.sftp.transfer.TransferState;
import de.kortty.policy.FileTransferGate;
import de.kortty.ui.I18n;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The transfer list at the bottom of the SFTP manager tab: one row per uploaded or downloaded
 * file or folder (and one per failed file inside a folder, so it can be retried on its own), with
 * progress, bytes, speed and time left, and Cancel, Retry, Cancel all and Clear finished.
 *
 * <p>The queue reports on its worker threads; the pane only collects those events and shows them
 * on the FX thread every {@value #FLUSH_MILLIS} ms, so a fast transfer of many small files never
 * floods the FX thread. The drawer appears with the first transfer and can be collapsed to its
 * header.
 */
public final class SftpTransferQueuePane extends VBox {

    /** How often collected queue events are shown. */
    static final long FLUSH_MILLIS = 100;
    private static final PseudoClass FAILED = PseudoClass.getPseudoClass("failed");
    private static final PseudoClass NESTED = PseudoClass.getPseudoClass("nested");
    /** Marks a listing row of a part file left by an interrupted transfer. */
    private static final PseudoClass PART_FILE = PseudoClass.getPseudoClass("kortty-part");

    private final Queue<TransferItem> added = new ConcurrentLinkedQueue<>();
    private final Queue<TransferItem> changed = new ConcurrentLinkedQueue<>();
    private final Queue<TransferItem> removed = new ConcurrentLinkedQueue<>();
    private final Queue<TransferBatch> finished = new ConcurrentLinkedQueue<>();
    private final ObservableList<TransferItem> rows = FXCollections.observableArrayList();
    /** The rows shown, for quick membership checks (FX thread). */
    private final Set<TransferItem> shown = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    /** The last state seen per item, so a terminal state is reported once (FX thread). */
    private final Map<TransferItem, TransferState> lastStates = new IdentityHashMap<>();
    private final TableView<TransferItem> table = new TableView<>(rows);
    private final Label titleLabel = new Label(I18n.get("sftp.queue.title"));
    private final ToggleButton toggle = new ToggleButton();
    private final Button cancelButton = new Button(I18n.get("sftp.queue.action.cancel"));
    private final Button retryButton = new Button(I18n.get("sftp.queue.action.retry"));
    private final Button cancelAllButton = new Button(I18n.get("sftp.queue.action.cancelAll"));
    private final Button clearButton = new Button(I18n.get("sftp.queue.action.clearFinished"));
    private final Timeline flushTimer;
    private final TransferQueueListener listener = new Collector();

    private SftpTransferQueue queue;
    private Consumer<TransferItem> onItemFinished = item -> { };
    private Consumer<TransferBatch> onBatchFinished = batch -> { };
    private Consumer<String> onStatus = text -> { };
    private boolean userCollapsed;

    public SftpTransferQueuePane() {
        super(4);
        getStyleClass().add("sftp-transfer-queue");
        setPadding(new Insets(4, 0, 0, 0));

        toggle.setSelected(true);
        toggle.setText("▾");
        toggle.setTooltip(new Tooltip(I18n.get("sftp.queue.toggle.hide")));
        toggle.setOnAction(event -> {
            userCollapsed = !toggle.isSelected();
            applyExpanded(toggle.isSelected());
        });
        titleLabel.getStyleClass().add("sftp-transfer-title");
        for (javafx.scene.control.ButtonBase button : List.of(toggle, cancelButton, retryButton, cancelAllButton,
                clearButton)) {
            button.getStyleClass().add("file-browser-toolbar-button");
        }

        cancelButton.setOnAction(event -> forSelection(item -> {
            queue.cancel(item);
            return null;
        }));
        retryButton.setOnAction(event -> {
            // Defence in depth: the button is greyed out while the policy denies file transfer.
            if (transferAllowed()) {
                forSelection(item -> queue.retry(item));
            }
        });
        // A retry copies files again; under a policy denial it is greyed out with the reason (D6).
        String refusal = FileTransferGate.current(FileTransferGate.Route.SFTP_UPLOAD).reason();
        if (refusal != null) {
            retryButton.setTooltip(new Tooltip(refusal));
        }
        cancelAllButton.setOnAction(event -> {
            if (queue != null) {
                queue.cancelAll();
            }
        });
        clearButton.setOnAction(event -> {
            if (queue != null) {
                queue.clearFinished();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(6, toggle, titleLabel, spacer, cancelButton, retryButton, cancelAllButton, clearButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("sftp-transfer-header");

        buildTable();
        getChildren().addAll(header, table);
        setVisible(false);
        setManaged(false);
        updateButtons();

        flushTimer = new Timeline(new KeyFrame(Duration.millis(FLUSH_MILLIS), event -> flush()));
        flushTimer.setCycleCount(Timeline.INDEFINITE);
        flushTimer.play();
    }

    /** The listener to register on the queue; it may be called on any thread. */
    public TransferQueueListener listener() {
        return listener;
    }

    /** The queue the buttons act on. FX thread. */
    public void setQueue(SftpTransferQueue queue) {
        this.queue = queue;
        updateButtons();
    }

    /** Called on the FX thread once per item that reached a terminal state. */
    public void setOnItemFinished(Consumer<TransferItem> action) {
        onItemFinished = action == null ? item -> { } : action;
    }

    /** Called on the FX thread once per finished batch. */
    public void setOnBatchFinished(Consumer<TransferBatch> action) {
        onBatchFinished = action == null ? batch -> { } : action;
    }

    /** Called on the FX thread with the status bar text while transfers are active. */
    public void setOnStatus(Consumer<String> action) {
        onStatus = action == null ? text -> { } : action;
    }

    /** Shows the drawer open, unless the user collapsed it. FX thread. */
    public void reveal() {
        setVisible(true);
        setManaged(true);
        if (!userCollapsed) {
            toggle.setSelected(true);
            applyExpanded(true);
        }
    }

    /** Stops the refresh timer. FX thread. */
    public void dispose() {
        flushTimer.stop();
    }

    /** The rows shown, for tests. */
    List<TransferItem> rows() {
        return List.copyOf(rows);
    }

    /**
     * Marks a file listing row that holds a part file of an interrupted transfer
     * ({@code name.kortty-part}) with the {@code :kortty-part} pseudo class and a tooltip.
     */
    public static void markPartRows(TableRow<SftpFileItem> row) {
        Tooltip tooltip = new Tooltip(I18n.get("sftp.part.tooltip"));
        row.itemProperty().addListener((observable, old, item) -> {
            boolean part = item != null && item.isFile() && PartFiles.isPartName(item.getName());
            row.pseudoClassStateChanged(PART_FILE, part);
            row.setTooltip(part ? tooltip : null);
        });
    }

    // ------------------------------------------------------------------ table

    private void buildTable() {
        table.getStyleClass().addAll("file-browser-table", "sftp-transfer-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPrefHeight(150);
        table.setMinHeight(80);
        table.setPlaceholder(new Label(""));

        TableColumn<TransferItem, String> name = textColumn(I18n.get("sftp.queue.column.name"),
            SftpTransferRowModel::name, 220);
        TableColumn<TransferItem, String> direction = textColumn(I18n.get("sftp.queue.column.direction"),
            SftpTransferRowModel::direction, 80);
        TableColumn<TransferItem, SftpTransferRowModel> progress = new TableColumn<>(
            I18n.get("sftp.queue.column.progress"));
        progress.setPrefWidth(120);
        progress.setSortable(false);
        progress.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(SftpTransferRowModel.of(cell.getValue())));
        progress.setCellFactory(column -> new ProgressCell());
        TableColumn<TransferItem, String> bytes = textColumn(I18n.get("sftp.queue.column.bytes"),
            SftpTransferRowModel::bytes, 140);
        TableColumn<TransferItem, String> speed = textColumn(I18n.get("sftp.queue.column.speed"),
            SftpTransferRowModel::speed, 90);
        TableColumn<TransferItem, String> eta = textColumn(I18n.get("sftp.queue.column.eta"),
            SftpTransferRowModel::eta, 110);
        TableColumn<TransferItem, SftpTransferRowModel> state = new TableColumn<>(I18n.get("sftp.queue.column.state"));
        state.setPrefWidth(160);
        state.setSortable(false);
        state.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(SftpTransferRowModel.of(cell.getValue())));
        state.setCellFactory(column -> new StateCell());
        table.getColumns().addAll(List.of(name, direction, progress, bytes, speed, eta, state));

        table.setRowFactory(view -> new TableRow<>() {
            @Override
            protected void updateItem(TransferItem item, boolean empty) {
                super.updateItem(item, empty);
                pseudoClassStateChanged(FAILED, !empty && item != null && item.state() == TransferState.FAILED);
                pseudoClassStateChanged(NESTED, !empty && item != null && item.parent() != null);
            }
        });
        table.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<TransferItem>) change -> updateButtons());
    }

    private static TableColumn<TransferItem, String> textColumn(String title,
            Function<SftpTransferRowModel, String> value, double width) {
        TableColumn<TransferItem, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setSortable(false);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(
            value.apply(SftpTransferRowModel.of(cell.getValue()))));
        return column;
    }

    private static final class ProgressCell extends TableCell<TransferItem, SftpTransferRowModel> {
        private final ProgressBar bar = new ProgressBar(0);

        ProgressCell() {
            bar.setMaxWidth(Double.MAX_VALUE);
        }

        @Override
        protected void updateItem(SftpTransferRowModel model, boolean empty) {
            super.updateItem(model, empty);
            if (empty || model == null) {
                setGraphic(null);
                return;
            }
            bar.setProgress(model.progress() < 0 ? ProgressBar.INDETERMINATE_PROGRESS : model.progress());
            setGraphic(bar);
        }
    }

    private static final class StateCell extends TableCell<TransferItem, SftpTransferRowModel> {
        @Override
        protected void updateItem(SftpTransferRowModel model, boolean empty) {
            super.updateItem(model, empty);
            if (empty || model == null) {
                setText(null);
                setTooltip(null);
                return;
            }
            String message = model.message();
            setText(message == null || message.isBlank() ? model.state() : model.state() + " – " + message);
            setTooltip(message == null || message.isBlank() ? null : new Tooltip(message));
        }
    }

    private void applyExpanded(boolean expanded) {
        table.setVisible(expanded);
        table.setManaged(expanded);
        toggle.setText(expanded ? "▾" : "▸");
        toggle.getTooltip().setText(I18n.get(expanded ? "sftp.queue.toggle.hide" : "sftp.queue.toggle.show"));
    }

    private void forSelection(Function<TransferItem, Object> action) {
        if (queue == null) {
            return;
        }
        for (TransferItem item : List.copyOf(table.getSelectionModel().getSelectedItems())) {
            if (item != null) {
                action.apply(item);
            }
        }
        updateButtons();
    }

    /** Whether the organization's policy lets this pane start a transfer again (resolved once at startup). */
    private static boolean transferAllowed() {
        return FileTransferGate.current(FileTransferGate.Route.SFTP_UPLOAD).allowed();
    }

    /** Whether Retry is usable: a queue, a retryable selection and the policy's consent to file transfer. */
    static boolean retryEnabled(boolean hasQueue, boolean anyRetryable, boolean transferAllowed) {
        return hasQueue && anyRetryable && transferAllowed;
    }

    private void updateButtons() {
        List<TransferItem> selected = table.getSelectionModel().getSelectedItems();
        boolean anyActive = false;
        boolean anyRetryable = false;
        for (TransferItem item : selected) {
            if (item == null) {
                continue;
            }
            anyActive |= item.state().isActive();
            anyRetryable |= item.isRetryable();
        }
        boolean hasQueue = queue != null;
        cancelButton.setDisable(!hasQueue || !anyActive);
        retryButton.setDisable(!retryEnabled(hasQueue, anyRetryable, transferAllowed()));
        boolean anyRowActive = false;
        boolean anyRowFinished = false;
        for (TransferItem row : rows) {
            if (row.parent() != null) {
                continue;
            }
            anyRowActive |= row.state().isActive();
            anyRowFinished |= row.state().isTerminal() && row.state() != TransferState.FAILED;
        }
        cancelAllButton.setDisable(!hasQueue || !anyRowActive);
        clearButton.setDisable(!hasQueue || !anyRowFinished);
    }

    // ------------------------------------------------------------------ flush

    /** Shows what the queue reported since the last flush. FX thread. */
    void flush() {
        boolean dirty = false;
        TransferItem item;
        while ((item = added.poll()) != null) {
            dirty = true;
            if (item.parent() == null && shown.add(item)) {
                rows.add(item);
            }
            changed.add(item);
        }
        Set<TransferItem> changedNow = new HashSet<>();
        while ((item = changed.poll()) != null) {
            changedNow.add(item);
        }
        for (TransferItem one : changedNow) {
            dirty = true;
            if (one.parent() != null && one.kind() == TransferItem.Kind.FILE) {
                showNestedFailure(one);
            }
            TransferState now = one.state();
            TransferState before = lastStates.put(one, now);
            if (now.isTerminal() && before != now) {
                onItemFinished.accept(one);
            }
        }
        List<TransferItem> gone = new ArrayList<>();
        while ((item = removed.poll()) != null) {
            gone.add(item);
        }
        if (!gone.isEmpty()) {
            dirty = true;
            for (TransferItem one : gone) {
                shown.remove(one);
                lastStates.remove(one);
            }
            rows.removeIf(row -> !shown.contains(row));
        }
        TransferBatch batch;
        List<TransferBatch> batchesDone = new ArrayList<>();
        while ((batch = finished.poll()) != null) {
            batchesDone.add(batch);
        }
        if (!dirty && batchesDone.isEmpty()) {
            return;
        }
        table.refresh();
        updateTitle();
        updateButtons();
        if (queue != null) {
            String status = SftpTransferRowModel.status(SftpTransferRowModel.Totals.of(queue.batches()),
                Locale.getDefault());
            if (status != null) {
                onStatus.accept(status);
            }
        }
        for (TransferBatch done : batchesDone) {
            onBatchFinished.accept(done);
        }
        if (!rows.isEmpty() && !isVisible()) {
            reveal();
        }
    }

    /** A failed file inside a folder gets a row of its own (below its top-level item) until it is retried. */
    private void showNestedFailure(TransferItem item) {
        boolean failed = item.state() == TransferState.FAILED;
        if (failed && shown.add(item)) {
            TransferItem top = item;
            while (top.parent() != null) {
                top = top.parent();
            }
            int index = rows.indexOf(top);
            if (index < 0) {
                rows.add(item);
                return;
            }
            int insert = index + 1;
            while (insert < rows.size() && isBelow(rows.get(insert), top)) {
                insert++;
            }
            rows.add(insert, item);
        } else if (!failed && shown.contains(item)) {
            shown.remove(item);
            rows.remove(item);
        }
    }

    private static boolean isBelow(TransferItem item, TransferItem top) {
        for (TransferItem parent = item.parent(); parent != null; parent = parent.parent()) {
            if (parent == top) {
                return true;
            }
        }
        return false;
    }

    private void updateTitle() {
        int active = 0;
        for (TransferItem row : rows) {
            if (row.parent() == null && row.state().isActive()) {
                active++;
            }
        }
        titleLabel.setText(active == 0
            ? I18n.get("sftp.queue.title")
            : I18n.get("sftp.queue.titleActive", String.valueOf(active)));
    }

    /** Collects queue events on any thread; {@link #flush()} shows them. */
    private final class Collector implements TransferQueueListener {
        @Override
        public void itemsAdded(List<TransferItem> items) {
            added.addAll(items);
        }

        @Override
        public void itemChanged(TransferItem item) {
            changed.add(item);
        }

        @Override
        public void itemsRemoved(List<TransferItem> items) {
            removed.addAll(items);
        }

        @Override
        public void batchFinished(TransferBatch batch) {
            finished.add(batch);
        }
    }
}
