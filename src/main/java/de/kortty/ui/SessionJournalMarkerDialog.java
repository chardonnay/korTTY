package de.kortty.ui;

import de.kortty.core.SessionJournalMarkers;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalMarker;
import de.kortty.model.SessionJournalMarkerDefinition;
import de.kortty.model.SessionJournalMarkerRule;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Manages the named, coloured markers and the rules that apply them automatically.
 *
 * <p>Both live in the global settings so they can be defined once and used in every journal; a
 * marker that is actually applied is additionally snapshotted into the journal it was used in, so
 * deleting it here never changes how an existing journal renders.</p>
 */
public final class SessionJournalMarkerDialog {

    /** Fallback palette for new markers, cycled so two new markers never look the same. */
    private static final String[] NEW_COLORS = {
        "#7c3aed", "#0e7490", "#b45309", "#be123c", "#15803d", "#4338ca", "#a21caf"};

    private final GlobalSettings settings;
    private final ObservableList<SessionJournalMarkerDefinition> definitions;
    private final ObservableList<SessionJournalMarkerRule> rules;
    private final CheckBox rulesEnabled =
        new CheckBox(I18n.get("journal.marker.rules.enabled"));
    private final CheckBox overwriteManual =
        new CheckBox(I18n.get("journal.marker.rules.overwriteManual"));
    private final Label applyStatus = new Label();

    private SessionJournalMarkerDialog(GlobalSettings settings) {
        this.settings = settings;
        List<SessionJournalMarkerDefinition> custom = new ArrayList<>();
        for (SessionJournalMarkerDefinition definition : settings.getSessionJournalMarkers()) {
            custom.add(new SessionJournalMarkerDefinition(definition));
        }
        this.definitions = FXCollections.observableArrayList(custom);
        List<SessionJournalMarkerRule> ruleCopies = new ArrayList<>();
        for (SessionJournalMarkerRule rule : settings.getSessionJournalMarkerRules()) {
            ruleCopies.add(new SessionJournalMarkerRule(rule));
        }
        this.rules = FXCollections.observableArrayList(ruleCopies);
    }

    /**
     * Shows the dialog and writes the result back into {@code settings} on OK.
     *
     * @param applyNow runs the rules over the current journal and reports how many entries
     *                 changed; may be null when no journal is open.
     * @return true when the settings were changed and should be saved.
     */
    public static boolean show(Window owner, GlobalSettings settings,
                               java.util.function.Consumer<ApplyRequest> applyNow) {
        return new SessionJournalMarkerDialog(settings).open(owner, applyNow);
    }

    /** What "apply now" should do, and where to report the outcome. */
    public record ApplyRequest(boolean overwriteManual, IntConsumer onChanged) {
    }

    /** Builds the fully wired dialog without showing it; also the seam the screenshot uses. */
    private Dialog<ButtonType> buildDialog(Window owner,
                                           java.util.function.Consumer<ApplyRequest> applyNow) {
        Dialog<ButtonType> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(I18n.get("journal.marker.manage.title"));
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        SplitPane split = new SplitPane(buildDefinitionsPane(), buildRulesPane(applyNow));
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.5);
        split.setPrefSize(720, 540);
        dialog.getDialogPane().setContent(split);
        return dialog;
    }

    /** The built, unshown dialog — used by the manual's screenshot generator. */
    static Dialog<ButtonType> buildForCapture(GlobalSettings settings) {
        return new SessionJournalMarkerDialog(settings).buildDialog(null, request -> { });
    }

    private boolean open(Window owner, java.util.function.Consumer<ApplyRequest> applyNow) {
        Dialog<ButtonType> dialog = buildDialog(owner, applyNow);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return false;
        }
        settings.setSessionJournalMarkers(new ArrayList<>(definitions));
        settings.setSessionJournalMarkerRules(new ArrayList<>(rules));
        settings.setSessionJournalMarkerRulesEnabled(rulesEnabled.isSelected());
        return true;
    }

    // ==== definitions ====

    private VBox buildDefinitionsPane() {
        TableView<SessionJournalMarkerDefinition> table = new TableView<>(definitions);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setPlaceholder(new Label(I18n.get("journal.marker.empty")));

        TableColumn<SessionJournalMarkerDefinition, String> colourColumn =
            new TableColumn<>(I18n.get("journal.marker.column.colour"));
        colourColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getColor()));
        colourColumn.setCellFactory(column -> new ColorPickerCell());
        colourColumn.setMinWidth(120);
        colourColumn.setEditable(true);

        TableColumn<SessionJournalMarkerDefinition, String> nameColumn =
            new TableColumn<>(I18n.get("journal.marker.column.name"));
        nameColumn.setCellValueFactory(cell ->
            new SimpleStringProperty(SessionJournalMarkers.displayName(cell.getValue())));
        nameColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        nameColumn.setOnEditCommit(event -> {
            event.getRowValue().setName(event.getNewValue());
            table.refresh();
        });
        nameColumn.setMinWidth(200);
        nameColumn.setEditable(true);

        TableColumn<SessionJournalMarkerDefinition, String> severityColumn =
            new TableColumn<>(I18n.get("journal.marker.column.severity"));
        severityColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            markerLabel(cell.getValue().getLegacyMarker())));
        severityColumn.setMinWidth(110);

        table.getColumns().addAll(List.of(colourColumn, nameColumn, severityColumn));

        Button add = new Button(I18n.get("journal.marker.add"));
        add.setOnAction(event -> {
            SessionJournalMarkerDefinition created = newDefinition();
            definitions.add(created);
            table.getSelectionModel().select(created);
        });
        Button duplicate = new Button(I18n.get("journal.marker.duplicate"));
        duplicate.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        duplicate.setOnAction(event -> {
            SessionJournalMarkerDefinition source = table.getSelectionModel().getSelectedItem();
            SessionJournalMarkerDefinition copy = new SessionJournalMarkerDefinition(source);
            copy.setId(uniqueId(SessionJournalMarkers.displayName(source) + "-copy"));
            copy.setName(SessionJournalMarkers.displayName(source) + " (2)");
            definitions.add(copy);
        });
        Button remove = new Button(I18n.get("journal.marker.delete"));
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(event -> {
            SessionJournalMarkerDefinition selected = table.getSelectionModel().getSelectedItem();
            definitions.remove(selected);
            // Rules pointing at a marker that no longer exists would silently do nothing.
            rules.removeIf(rule -> selected.getId() != null && selected.getId().equals(rule.getMarkerId()));
        });

        Label hint = new Label(I18n.get("journal.marker.snapshotHint"));
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");

        VBox box = new VBox(8, new Label(I18n.get("journal.marker.definitions")), table,
            new HBox(8, add, duplicate, remove), hint);
        box.setPadding(new Insets(8));
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    private SessionJournalMarkerDefinition newDefinition() {
        String colour = NEW_COLORS[definitions.size() % NEW_COLORS.length];
        SessionJournalMarkerDefinition created = new SessionJournalMarkerDefinition(
            uniqueId(I18n.get("journal.marker.new")), I18n.get("journal.marker.new"), colour,
            false, SessionJournalMarker.INFO);
        return created;
    }

    /** An id nobody uses yet — ids end up in CSS selectors and rule references. */
    private String uniqueId(String base) {
        String normalized = SessionJournalMarkerDefinition.normalizeId(base);
        if (normalized == null) {
            normalized = "marker";
        }
        String candidate = normalized;
        int counter = 2;
        while (taken(candidate)) {
            candidate = normalized + "-" + counter++;
            if (counter > 99) {
                candidate = "marker-" + UUID.randomUUID().toString().substring(0, 6);
                break;
            }
        }
        return candidate;
    }

    private boolean taken(String id) {
        if (SessionJournalMarkers.isBuiltInId(id)) {
            return true;
        }
        for (SessionJournalMarkerDefinition definition : definitions) {
            if (id.equals(definition.getId())) {
                return true;
            }
        }
        return false;
    }

    /** Colour cell that writes the picked value back as {@code #rrggbb}. */
    private final class ColorPickerCell extends TableCell<SessionJournalMarkerDefinition, String> {

        private final ColorPicker picker = new ColorPicker();

        ColorPickerCell() {
            picker.setOnAction(event -> {
                SessionJournalMarkerDefinition definition = definitionOfRow();
                if (definition != null) {
                    definition.setColor(toHex(picker.getValue()));
                }
            });
        }

        private SessionJournalMarkerDefinition definitionOfRow() {
            int index = getIndex();
            return index >= 0 && index < definitions.size() ? definitions.get(index) : null;
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            SessionJournalMarkerDefinition definition = empty ? null : definitionOfRow();
            if (definition == null) {
                setGraphic(null);
                return;
            }
            picker.setValue(colorOf(definition));
            setGraphic(picker);
        }
    }

    // ==== rules ====

    private VBox buildRulesPane(java.util.function.Consumer<ApplyRequest> applyNow) {
        TableView<SessionJournalMarkerRule> table = new TableView<>(rules);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(I18n.get("journal.marker.rules.empty")));

        TableColumn<SessionJournalMarkerRule, Boolean> enabledColumn =
            new TableColumn<>(I18n.get("journal.marker.rules.column.enabled"));
        enabledColumn.setCellValueFactory(cell -> {
            SimpleBooleanProperty property = new SimpleBooleanProperty(cell.getValue().isEnabled());
            property.addListener((obs, old, value) -> cell.getValue().setEnabled(value));
            return property;
        });
        enabledColumn.setCellFactory(CheckBoxTableCell.forTableColumn(enabledColumn));
        enabledColumn.setMinWidth(70);
        enabledColumn.setEditable(true);

        TableColumn<SessionJournalMarkerRule, String> markerColumn =
            new TableColumn<>(I18n.get("journal.marker.rules.column.marker"));
        markerColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getMarkerId()));
        markerColumn.setCellFactory(column -> new MarkerChoiceCell());
        markerColumn.setMinWidth(150);
        markerColumn.setEditable(true);

        TableColumn<SessionJournalMarkerRule, String> patternColumn =
            new TableColumn<>(I18n.get("journal.marker.rules.column.pattern"));
        patternColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getPattern()));
        patternColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        patternColumn.setOnEditCommit(event -> {
            event.getRowValue().setPattern(event.getNewValue());
            table.refresh();
        });
        patternColumn.setMinWidth(220);
        patternColumn.setEditable(true);

        TableColumn<SessionJournalMarkerRule, Boolean> regexColumn =
            new TableColumn<>(I18n.get("journal.marker.rules.column.regex"));
        regexColumn.setCellValueFactory(cell -> {
            SimpleBooleanProperty property = new SimpleBooleanProperty(cell.getValue().isRegex());
            property.addListener((obs, old, value) -> cell.getValue().setRegex(value));
            return property;
        });
        regexColumn.setCellFactory(CheckBoxTableCell.forTableColumn(regexColumn));
        regexColumn.setMinWidth(70);
        regexColumn.setEditable(true);

        TableColumn<SessionJournalMarkerRule, Boolean> caseColumn =
            new TableColumn<>(I18n.get("journal.marker.rules.column.ignoreCase"));
        caseColumn.setCellValueFactory(cell -> {
            SimpleBooleanProperty property = new SimpleBooleanProperty(cell.getValue().isIgnoreCase());
            property.addListener((obs, old, value) -> cell.getValue().setIgnoreCase(value));
            return property;
        });
        caseColumn.setCellFactory(CheckBoxTableCell.forTableColumn(caseColumn));
        caseColumn.setMinWidth(90);
        caseColumn.setEditable(true);

        table.getColumns().addAll(List.of(enabledColumn, markerColumn, patternColumn, regexColumn, caseColumn));

        Button add = new Button(I18n.get("journal.marker.rules.add"));
        add.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(definitions));
        add.setOnAction(event -> {
            SessionJournalMarkerRule rule = new SessionJournalMarkerRule(
                definitions.get(0).getId(), "", false);
            rules.add(rule);
            table.getSelectionModel().select(rule);
        });
        Button remove = new Button(I18n.get("journal.marker.rules.remove"));
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(event -> rules.remove(table.getSelectionModel().getSelectedItem()));
        Button up = new Button("▲");
        up.setOnAction(event -> move(table, -1));
        Button down = new Button("▼");
        down.setOnAction(event -> move(table, 1));

        HBox buttons = new HBox(8, add, remove, up, down);
        rulesEnabled.setSelected(settings.isSessionJournalMarkerRulesEnabled());
        applyStatus.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");

        VBox box = new VBox(8, new Label(I18n.get("journal.marker.rules")), rulesEnabled, table, buttons);
        if (applyNow != null) {
            Button apply = new Button(I18n.get("journal.marker.rules.applyNow"));
            apply.setOnAction(event -> {
                // Write the edits back first so "apply now" uses what is on screen.
                settings.setSessionJournalMarkers(new ArrayList<>(definitions));
                settings.setSessionJournalMarkerRules(new ArrayList<>(rules));
                applyStatus.setText(I18n.get("journal.marker.rules.running"));
                applyNow.accept(new ApplyRequest(overwriteManual.isSelected(),
                    changed -> applyStatus.setText(I18n.get("journal.marker.rules.applied", changed))));
            });
            box.getChildren().add(new HBox(8, apply, overwriteManual, applyStatus));
        }
        Label priorityHint = new Label(I18n.get("journal.marker.rules.priorityHint"));
        priorityHint.setWrapText(true);
        priorityHint.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");
        box.getChildren().add(priorityHint);
        box.setPadding(new Insets(8));
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    private void move(TableView<SessionJournalMarkerRule> table, int delta) {
        int index = table.getSelectionModel().getSelectedIndex();
        int target = index + delta;
        if (index < 0 || target < 0 || target >= rules.size()) {
            return;
        }
        SessionJournalMarkerRule rule = rules.remove(index);
        rules.add(target, rule);
        table.getSelectionModel().select(target);
    }

    /** Combo cell listing every definition with its colour swatch. */
    private final class MarkerChoiceCell extends TableCell<SessionJournalMarkerRule, String> {

        private final ComboBox<SessionJournalMarkerDefinition> combo = new ComboBox<>(definitions);

        MarkerChoiceCell() {
            combo.setCellFactory(view -> new DefinitionListCell());
            combo.setButtonCell(new DefinitionListCell());
            combo.setOnAction(event -> {
                int index = getIndex();
                SessionJournalMarkerDefinition value = combo.getValue();
                if (index >= 0 && index < rules.size() && value != null) {
                    rules.get(index).setMarkerId(value.getId());
                }
            });
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setGraphic(null);
                return;
            }
            combo.setValue(SessionJournalMarkers.byId(item, definitions));
            setGraphic(combo);
        }
    }

    // ==== shared cell rendering ====

    /** Colour swatch plus name; used by the rule combo and by the viewer's marker picker. */
    static final class DefinitionListCell extends ListCell<SessionJournalMarkerDefinition> {

        @Override
        protected void updateItem(SessionJournalMarkerDefinition item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(SessionJournalMarkers.displayName(item));
            setGraphic(item.isNone() ? null : swatch(item));
        }
    }

    static Rectangle swatch(SessionJournalMarkerDefinition definition) {
        Rectangle rectangle = new Rectangle(10, 10);
        rectangle.setArcWidth(4);
        rectangle.setArcHeight(4);
        rectangle.setFill(colorOf(definition));
        return rectangle;
    }

    static Color colorOf(SessionJournalMarkerDefinition definition) {
        java.awt.Color awt = SessionJournalMarkers.awtColor(
            definition, defaultAwtColor(definition));
        return Color.rgb(awt.getRed(), awt.getGreen(), awt.getBlue());
    }

    /** The palette a built-in without an own colour is drawn in, matching the generated page. */
    private static java.awt.Color defaultAwtColor(SessionJournalMarkerDefinition definition) {
        return switch (definition.getLegacyMarker()) {
            case ERROR -> new java.awt.Color(0xcf, 0x22, 0x2e);
            case IMPORTANT -> new java.awt.Color(0x9a, 0x67, 0x00);
            case INFO -> new java.awt.Color(0x09, 0x69, 0xda);
            case NONE -> new java.awt.Color(0x6e, 0x77, 0x81);
        };
    }

    static String toHex(Color color) {
        if (color == null) {
            return null;
        }
        return String.format("#%02x%02x%02x",
            Math.round(color.getRed() * 255),
            Math.round(color.getGreen() * 255),
            Math.round(color.getBlue() * 255));
    }

    static String markerLabel(SessionJournalMarker marker) {
        SessionJournalMarker effective = marker != null ? marker : SessionJournalMarker.NONE;
        return I18n.get("journal.marker." + effective.name().toLowerCase(Locale.ROOT));
    }
}
