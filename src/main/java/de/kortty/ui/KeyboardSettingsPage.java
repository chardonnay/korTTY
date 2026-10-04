package de.kortty.ui;

import de.kortty.core.KeyChord;
import de.kortty.core.KeymapOverrides;
import de.kortty.core.KeymapOverrides.Problem;
import de.kortty.ui.KeyboardSettingsModel.Edit;
import de.kortty.ui.KeyboardSettingsModel.Row;
import de.kortty.ui.KeyboardSettingsModel.Status;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * The Settings → Keyboard page: a filterable table of korTTY's actions with their shortcuts, a
 * recorder field that takes the next key press as the selected action's new chord, Remove, Reset
 * and Reset All, and a red badge on the tab while two actions share a chord, which blocks saving
 * (see {@link #canSave()}). The rules and texts live in the toolkit-free
 * {@link KeyboardSettingsModel}; this class only lays them out. FX thread only.
 */
final class KeyboardSettingsPage {

    static final String ROOT_ID = "settings-keyboard";
    static final String TABLE_ID = "settings-keyboard-table";
    static final String RECORDER_ID = "settings-keyboard-recorder";
    static final String CONFLICTS_ID = "settings-keyboard-conflicts";
    static final String BADGE_ID = "settings-keyboard-badge";

    static final String FILTER_PROMPT_KEY = "settings.keyboard.filter.prompt";
    static final String ONLY_CHANGED_KEY = "settings.keyboard.onlyChanged";
    static final String COLUMN_ACTION_KEY = "settings.keyboard.column.action";
    static final String COLUMN_MENU_KEY = "settings.keyboard.column.menu";
    static final String COLUMN_SHORTCUT_KEY = "settings.keyboard.column.shortcut";
    static final String COLUMN_STATUS_KEY = "settings.keyboard.column.status";
    static final String NO_MATCH_KEY = "settings.keyboard.noMatch";
    static final String SELECTED_KEY = "settings.keyboard.selected";
    static final String NO_SELECTION_KEY = "settings.keyboard.noSelection";
    static final String RECORDER_PROMPT_KEY = "settings.keyboard.recorder.prompt";
    static final String RECORDER_RECORDING_KEY = "settings.keyboard.recorder.recording";
    static final String RECORDER_TOOLTIP_KEY = "settings.keyboard.recorder.tooltip";
    static final String DEFAULT_KEY = "settings.keyboard.default";
    static final String REMOVE_KEY = "settings.keyboard.remove";
    static final String RESET_KEY = "settings.keyboard.reset";
    static final String RESET_ALL_KEY = "settings.keyboard.resetAll";
    static final String BADGE_TOOLTIP_KEY = "settings.keyboard.conflicts.tooltip";
    static final String INFO_KEY = "settings.keyboard.info";
    static final String UNAVAILABLE_KEY = "settings.keyboard.unavailable";

    /** Every key this page reads besides the model's, for the i18n coverage test. */
    static final List<String> KEYS = List.of(FILTER_PROMPT_KEY, ONLY_CHANGED_KEY, COLUMN_ACTION_KEY, COLUMN_MENU_KEY,
        COLUMN_SHORTCUT_KEY, COLUMN_STATUS_KEY, NO_MATCH_KEY, SELECTED_KEY, NO_SELECTION_KEY, RECORDER_PROMPT_KEY,
        RECORDER_RECORDING_KEY, RECORDER_TOOLTIP_KEY, DEFAULT_KEY, REMOVE_KEY, RESET_KEY, RESET_ALL_KEY,
        BADGE_TOOLTIP_KEY, INFO_KEY, UNAVAILABLE_KEY);

    private static final String ERROR_STYLE = "-fx-text-fill: #d9534f;";
    private static final String WARNING_STYLE = "-fx-text-fill: #d97706;";
    private static final String HINT_STYLE = "-fx-font-size: 0.8462em; -fx-text-fill: gray;";

    private final KeyboardSettingsModel model;
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final VBox root = new VBox(10);
    private final Label badge = new Label();
    private final Label conflictsBanner = new Label();
    private final Label unknownNotice = new Label();
    private final TextField filterField = new TextField();
    private final CheckBox onlyChangedCheck = new CheckBox(I18n.get(ONLY_CHANGED_KEY));
    private final Button resetAllButton = new Button(I18n.get(RESET_ALL_KEY));
    private final TableView<Row> table = new TableView<>(rows);
    private final Label selectedLabel = new Label();
    private final TextField recorder = new TextField();
    private final Button removeButton = new Button(I18n.get(REMOVE_KEY));
    private final Button resetButton = new Button(I18n.get(RESET_KEY));
    private final Label defaultLabel = new Label();
    private final Label messageLabel = new Label();
    /** Set while the rows are replaced, so the selection change does not clear the last message. */
    private boolean refreshing;

    KeyboardSettingsPage(@NotNull KeyboardSettingsModel model) {
        this.model = Objects.requireNonNull(model, "model");
        build();
        refresh();
    }

    /** The page for the settings tab when no window's menu bar is at hand, such as a dialog without one. */
    static @NotNull Node unavailable() {
        Label label = new Label(I18n.get(UNAVAILABLE_KEY));
        label.setWrapText(true);
        VBox box = new VBox(label);
        box.setPadding(new Insets(20));
        return box;
    }

    @NotNull Node node() {
        return root;
    }

    /** The red count of conflicting chords for the tab header; hidden while there is none. */
    @NotNull Node badge() {
        return badge;
    }

    @NotNull KeyboardSettingsModel model() {
        return model;
    }

    /** Whether saving may go ahead: no chord is used by two actions. */
    boolean canSave() {
        return model.canSave();
    }

    /** Whether the overrides were changed on this page. */
    boolean hasChanges() {
        return model.hasChanges();
    }

    /** The overrides to store. */
    @NotNull KeymapOverrides overrides() {
        return model.overrides();
    }

    /** Shows every row, selects the first conflicting one and points out why saving is blocked. */
    void revealConflicts() {
        filterField.clear();
        onlyChangedCheck.setSelected(false);
        refresh();
        for (Row row : rows) {
            if (row.status() == Status.CONFLICT) {
                table.getSelectionModel().select(row);
                table.scrollTo(row);
                break;
            }
        }
        table.requestFocus();
    }

    private void build() {
        root.setId(ROOT_ID);
        root.setPadding(new Insets(20));

        Label header = new Label(I18n.get(KeyboardSettingsModel.HEADER_KEY));
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 1.0769em;");
        Label description = new Label(I18n.get(KeyboardSettingsModel.DESCRIPTION_KEY));
        description.setWrapText(true);
        description.setStyle(HINT_STYLE);

        conflictsBanner.setId(CONFLICTS_ID);
        conflictsBanner.setWrapText(true);
        conflictsBanner.setStyle(ERROR_STYLE + " -fx-font-weight: bold;");
        unknownNotice.setWrapText(true);
        unknownNotice.setStyle("-fx-font-size: 0.8462em; " + WARNING_STYLE);

        badge.setId(BADGE_ID);
        badge.setStyle("-fx-background-color: #d9534f; -fx-text-fill: white; -fx-font-weight: bold; "
            + "-fx-font-size: 0.7692em; -fx-padding: 0 5 0 5; -fx-background-radius: 8;");
        badge.setTooltip(new Tooltip(I18n.get(BADGE_TOOLTIP_KEY)));

        filterField.setPromptText(I18n.get(FILTER_PROMPT_KEY));
        filterField.setAccessibleText(I18n.get(FILTER_PROMPT_KEY));
        filterField.setPrefWidth(320);
        filterField.textProperty().addListener((obs, was, now) -> refresh());
        onlyChangedCheck.selectedProperty().addListener((obs, was, now) -> refresh());
        resetAllButton.setOnAction(event -> {
            model.resetAll();
            refresh();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, filterField, onlyChangedCheck, spacer, resetAllButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        recorder.setId(RECORDER_ID);
        recorder.setEditable(false);
        recorder.setFocusTraversable(true);
        recorder.setPrefWidth(240);
        recorder.setTooltip(new Tooltip(I18n.get(RECORDER_TOOLTIP_KEY)));
        recorder.focusedProperty().addListener((obs, was, focused) -> updateEditor());
        // A filter, not a handler: Enter, Escape and the arrow keys would otherwise press the
        // dialog's buttons or move the focus before the recorder sees them.
        recorder.addEventFilter(KeyEvent.KEY_PRESSED, this::record);
        removeButton.setOnAction(event -> edit(model::remove));
        resetButton.setOnAction(event -> edit(model::reset));
        HBox editorRow = new HBox(10, recorder, removeButton, resetButton);
        editorRow.setAlignment(Pos.CENTER_LEFT);
        selectedLabel.setStyle("-fx-font-weight: bold;");
        defaultLabel.setStyle(HINT_STYLE);
        messageLabel.setWrapText(true);
        show(messageLabel, false);
        VBox editor = new VBox(6, selectedLabel, editorRow, defaultLabel, messageLabel);

        Label info = new Label(I18n.get(INFO_KEY));
        info.setWrapText(true);
        info.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");

        root.getChildren().addAll(header, description, conflictsBanner, unknownNotice, toolbar, table, editor, info);
    }

    private void buildTable() {
        table.setId(TABLE_ID);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(I18n.get(NO_MATCH_KEY)));
        table.setPrefHeight(360);

        TableColumn<Row, String> actionColumn = new TableColumn<>(I18n.get(COLUMN_ACTION_KEY));
        actionColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().action().label()));
        actionColumn.setPrefWidth(260);
        TableColumn<Row, String> menuColumn = new TableColumn<>(I18n.get(COLUMN_MENU_KEY));
        menuColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().action().category()));
        menuColumn.setPrefWidth(200);
        TableColumn<Row, Row> shortcutColumn = new TableColumn<>(I18n.get(COLUMN_SHORTCUT_KEY));
        shortcutColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        shortcutColumn.setCellFactory(column -> new RowCell(true));
        shortcutColumn.setPrefWidth(170);
        TableColumn<Row, Row> statusColumn = new TableColumn<>(I18n.get(COLUMN_STATUS_KEY));
        statusColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        statusColumn.setCellFactory(column -> new RowCell(false));
        statusColumn.setPrefWidth(200);
        for (TableColumn<Row, ?> column : List.of(actionColumn, menuColumn, shortcutColumn, statusColumn)) {
            column.setSortable(false);
            table.getColumns().add(column);
        }
        table.setRowFactory(view -> new TableRow<>() {
            @Override
            protected void updateItem(Row row, boolean empty) {
                super.updateItem(row, empty);
                String detail = empty || row == null ? "" : model.detailText(row);
                setTooltip(detail.isEmpty() ? null : new Tooltip(detail));
                setStyle(!empty && row != null && row.action().fixed() ? "-fx-opacity: 0.7;" : "");
            }
        });
        table.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> {
            if (!refreshing) {
                messageLabel.setText("");
                show(messageLabel, false);
            }
            updateEditor();
        });
    }

    /** The shortcut or status cell of a row, coloured by the row's status. */
    private final class RowCell extends TableCell<Row, Row> {
        private final boolean shortcut;

        RowCell(boolean shortcut) {
            this.shortcut = shortcut;
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setText(null);
                setStyle("");
                return;
            }
            setText(shortcut ? model.shortcutText(row) : model.statusText(row));
            setStyle(switch (row.status()) {
                case CONFLICT -> ERROR_STYLE + " -fx-font-weight: bold;";
                case NOT_IN_EFFECT -> shortcut ? "" : WARNING_STYLE;
                case CHANGED -> shortcut ? "-fx-font-weight: bold;" : "";
                default -> "";
            });
        }
    }

    private void record(KeyEvent event) {
        Row selected = table.getSelectionModel().getSelectedItem();
        KeyCode code = event.getCode();
        boolean modified = event.isControlDown() || event.isAltDown() || event.isMetaDown();
        if (code == KeyCode.TAB && !modified) {
            return; // Tab and Shift+Tab leave the field
        }
        event.consume();
        if (code == KeyCode.ESCAPE && !modified && !event.isShiftDown()) {
            table.requestFocus();
            return;
        }
        if (selected == null || !selected.editable()) {
            return;
        }
        KeyChord chord = KeyChord.fromKeyPress(code.name(), event.isShiftDown(), event.isControlDown(),
            event.isAltDown(), event.isMetaDown(), model.catalog().os());
        if (chord == null) {
            return; // a modifier on its own, or a key no chord names
        }
        Edit edit = model.assign(selected.id(), chord);
        showEdit(edit);
        refresh();
        if (edit.applied()) {
            table.requestFocus();
        }
    }

    private void edit(java.util.function.Function<String, Edit> action) {
        Row selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || !selected.editable()) {
            return;
        }
        showEdit(action.apply(selected.id()));
        refresh();
    }

    private void showEdit(Edit edit) {
        messageLabel.setText(edit.message());
        show(messageLabel, !edit.message().isEmpty());
        if (edit.problem() == null) {
            messageLabel.setStyle("");
        } else {
            messageLabel.setStyle(edit.problem() == Problem.CONFLICT ? WARNING_STYLE : ERROR_STYLE);
        }
    }

    /** Replaces the rows for the filter, keeps the selection, and updates the banner, badge and editor. */
    private void refresh() {
        Row selected = table.getSelectionModel().getSelectedItem();
        String selectedId = selected != null ? selected.id() : null;
        refreshing = true;
        try {
            rows.setAll(model.rows(filterField.getText(), onlyChangedCheck.isSelected()));
            if (selectedId != null) {
                for (Row row : rows) {
                    if (row.id().equals(selectedId)) {
                        table.getSelectionModel().select(row);
                        break;
                    }
                }
            }
        } finally {
            refreshing = false;
        }
        String conflicts = model.conflictsText();
        conflictsBanner.setText(conflicts);
        show(conflictsBanner, !conflicts.isEmpty());
        int count = model.conflictCount();
        badge.setText(Integer.toString(count));
        badge.setAccessibleText(conflicts);
        show(badge, count > 0);
        String unknown = model.unknownActionsText();
        unknownNotice.setText(unknown);
        show(unknownNotice, !unknown.isEmpty());
        resetAllButton.setDisable(model.overrides().actionIds().stream()
            .noneMatch(id -> model.catalog().defaults().containsKey(id)));
        table.refresh();
        updateEditor();
    }

    private void updateEditor() {
        Row selected = table.getSelectionModel().getSelectedItem();
        Row row = selected != null ? model.row(selected.id()) : null;
        boolean editable = row != null && row.editable();
        selectedLabel.setText(row == null ? I18n.get(NO_SELECTION_KEY) : I18n.get(SELECTED_KEY, row.action().label()));
        recorder.setDisable(!editable);
        removeButton.setDisable(!editable || row.chord() == null && !row.overridden());
        resetButton.setDisable(!editable || !row.overridden());
        if (recorder.isFocused() && editable) {
            recorder.setText("");
            recorder.setPromptText(I18n.get(RECORDER_RECORDING_KEY));
        } else {
            recorder.setText(row == null ? "" : model.shortcutText(row));
            recorder.setPromptText(I18n.get(RECORDER_PROMPT_KEY));
        }
        recorder.setAccessibleText(row == null ? I18n.get(NO_SELECTION_KEY)
            : I18n.get(SELECTED_KEY, row.action().label()) + " " + model.shortcutText(row));
        if (row == null) {
            defaultLabel.setText("");
        } else if (!editable) {
            defaultLabel.setText(model.detailText(row));
        } else {
            String detail = model.detailText(row);
            String defaults = I18n.get(DEFAULT_KEY, model.chordText(row.action().defaultChord()));
            defaultLabel.setText(detail.isEmpty() ? defaults : defaults + " — " + detail);
        }
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /** The row of {@code actionId} in the table, for tests and the screenshot generator. */
    @Nullable Row tableRow(String actionId) {
        for (Row row : rows) {
            if (row.id().equals(actionId)) {
                return row;
            }
        }
        return null;
    }

    /** Selects the row of {@code actionId}, for the screenshot generator. */
    void select(String actionId) {
        Row row = tableRow(actionId);
        if (row != null) {
            table.getSelectionModel().select(row);
            table.scrollTo(row);
        }
    }
}
