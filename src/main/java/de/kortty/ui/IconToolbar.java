package de.kortty.ui;

import de.kortty.model.IconToolbarSettings;
import de.kortty.model.IconToolbarSettings.Entry;
import de.kortty.model.IconToolbarSettings.Kind;
import de.kortty.model.IconToolbarSettings.Section;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;

/** Two-row toolbar and inline customization surface. All methods run on the FX thread. */
final class IconToolbar extends VBox {
    private static final DataFormat DRAG_ENTRY = new DataFormat("application/x-kortty-toolbar-entry");
    private static final PseudoClass DROP_TARGET = PseudoClass.getPseudoClass("drop-target");
    private static final PseudoClass CHECKED = PseudoClass.getPseudoClass("checked");
    private final ToolbarCatalog catalog;
    private final BiConsumer<IconToolbarSettings, Consumer<Boolean>> save;
    private final VBox rows = new VBox(3);
    private final VBox editorPane = new VBox(8);
    private final List<Runnable> stateUpdates = new ArrayList<>();
    private final TextField search = new TextField();
    private final FlowPane available = new FlowPane(8, 8);
    private final javafx.beans.property.DoubleProperty iconSize = new javafx.beans.property.SimpleDoubleProperty(24);
    private final javafx.beans.property.DoubleProperty catalogRowHeight = new javafx.beans.property.SimpleDoubleProperty(65);
    private final ListView<String> categories = new ListView<>();
    private final javafx.beans.property.ObjectProperty<javafx.scene.paint.Color> contourColor =
        new javafx.beans.property.SimpleObjectProperty<>(javafx.scene.paint.Color.web("#e4e8ed"));
    private IconToolbarSettings settings;
    private ToolbarEditorModel editor;
    private Entry dragged;

    IconToolbar(IconToolbarSettings settings, ToolbarCatalog catalog, BiConsumer<IconToolbarSettings, Consumer<Boolean>> save) {
        this.settings = new IconToolbarSettings(settings);
        this.catalog = catalog;
        this.save = save;
        getStyleClass().add("icon-toolbar");
        getStylesheets().add(Objects.requireNonNull(getClass().getResource("/styles/icon-toolbar.css")).toExternalForm());
        getChildren().addAll(rows, editorPane);
        buildEditor();
        setOnContextMenuRequested(event -> {
            catalog.refresh();
            ContextMenu menu = new ContextMenu();
            MenuItem customize = new MenuItem(I18n.get("toolbar.customize"));
            customize.setOnAction(action -> beginEditing());
            CheckMenuItem actions = new CheckMenuItem(I18n.get("menu.view.iconToolbar"));
            actions.setSelected(this.settings.isVisible());
            actions.setOnAction(action -> setToolbarVisible(actions.isSelected()));
            CheckMenuItem connections = new CheckMenuItem(I18n.get("menu.view.connectionToolbar"));
            connections.setSelected(this.settings.isConnectionsVisible());
            connections.setOnAction(action -> setConnectionsVisible(connections.isSelected()));
            Entry menuBarAction = Entry.action("menu.view.menuBar", Section.SETTINGS);
            ToolbarCatalog.Presentation menuBarState = catalog.describe(menuBarAction);
            CheckMenuItem menuBar = new CheckMenuItem(I18n.get("toolbar.menuBar"));
            menuBar.setSelected(menuBarState.checked());
            menuBar.setDisable(!menuBarState.enabled());
            menuBar.setOnAction(action -> catalog.run(menuBarAction));
            menu.getItems().addAll(customize, new SeparatorMenuItem(), actions, connections, menuBar);
            menu.show(this, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        setOnMouseEntered(event -> refreshStates());
        focusedProperty().addListener((observable, oldValue, focused) -> { if (focused) refreshStates(); });
        reload(settings);
    }

    void reload(IconToolbarSettings value) {
        settings = new IconToolbarSettings(value);
        if (!settings.isVisible() && !settings.isConnectionsVisible()) editor = null;
        rebuildRows();
        updateEditorVisibility();
        if (editor != null) rebuildCatalog();
    }

    void renameGroup(String oldPath, String newPath, String separator) {
        settings = settings.renameGroup(oldPath, newPath, separator);
        if (editor != null) editor.renameGroup(oldPath, newPath, separator);
        rebuildRows();
        rebuildCatalog();
    }

    void setToolbarVisible(boolean visible) {
        IconToolbarSettings changed = new IconToolbarSettings(settings);
        changed.setVisible(visible);
        save.accept(changed, success -> { if (success) reload(changed); });
    }

    void setConnectionsVisible(boolean visible) {
        IconToolbarSettings changed = new IconToolbarSettings(settings);
        changed.setConnectionsVisible(visible);
        save.accept(changed, success -> { if (success) reload(changed); });
    }

    void setIconSize(int size) {
        iconSize.set(Math.max(de.kortty.model.GlobalSettings.MIN_TOOLBAR_ICON_SIZE,
            Math.min(de.kortty.model.GlobalSettings.MAX_TOOLBAR_ICON_SIZE, size)));
    }

    void applyTheme(String background, String foreground) {
        var bg = javafx.scene.paint.Color.web(background);
        contourColor.set(javafx.scene.paint.Color.web(foreground));
        var hover = bg.deriveColor(0, 1, bg.getBrightness() > 0.5 ? 0.92 : 1.3, 1);
        String hoverCss = String.format(Locale.ROOT, "#%02x%02x%02x", Math.round(hover.getRed() * 255),
            Math.round(hover.getGreen() * 255), Math.round(hover.getBlue() * 255));
        setStyle("-toolbar-background: " + background + "; -toolbar-foreground: " + foreground
            + "; -toolbar-hover: " + hoverCss + ";");
    }

    void refreshStates() {
        if (editor != null || !isVisible()) return;
        catalog.refresh();
        stateUpdates.forEach(Runnable::run);
    }

    void beginEditing() {
        if (editor != null) return;
        editor = new ToolbarEditorModel(settings);
        rebuildRows();
        updateEditorVisibility();
        rebuildCatalog();
        search.requestFocus();
    }

    private void beginEditingConnections() {
        beginEditing();
        categories.getSelectionModel().select(2);
    }

    private void updateEditorVisibility() {
        editorPane.setVisible(editor != null);
        editorPane.setManaged(editor != null);
        pseudoClassStateChanged(PseudoClass.getPseudoClass("editing"), editor != null);
        boolean hasContent = editor != null || !rows.getChildren().isEmpty();
        setVisible(hasContent);
        setManaged(hasContent);
    }

    private void rebuildRows() {
        stateUpdates.clear();
        rows.getChildren().clear();
        List<Entry> entries = editor == null ? settings.entries() : editor.entries();
        // Groups keep their full width and wrap onto another line when the window is too narrow, so
        // every button stays one click away instead of disappearing into a group's overflow menu.
        FlowPane actions = new FlowPane(8, 4);
        actions.getStyleClass().add("toolbar-tasks");
        for (Section section : List.of(Section.TERMINAL, Section.TOOLS, Section.SETTINGS)) {
            List<Entry> members = entries.stream().filter(entry -> entry.section() == section).toList();
            if (members.isEmpty() && editor == null) continue;
            Label heading = new Label(sectionLabel(section));
            heading.getStyleClass().add("toolbar-heading");
            ToolBar bar = sectionBar(section, members);
            VBox group = new VBox(2, heading, bar);
            group.setAlignment(Pos.TOP_LEFT);
            group.getStyleClass().add("toolbar-task-group");
            group.setMinWidth(56);
            // FlowPane lays groups out at their preferred width; only a group wider than the whole
            // row is narrowed to it and falls back to the toolbar's overflow menu.
            group.maxWidthProperty().bind(actions.widthProperty());
            actions.getChildren().add(group);
        }
        if (!actions.getChildren().isEmpty() && (settings.isVisible() || editor != null)) rows.getChildren().add(actions);
        List<Entry> connections = entries.stream().filter(entry -> entry.section() == Section.CONNECTIONS).toList();
        if (settings.isConnectionsVisible() || editor != null) {
            Label label = new Label(sectionLabel(Section.CONNECTIONS));
            label.getStyleClass().add("toolbar-heading");
            ToolBar bar = sectionBar(Section.CONNECTIONS, connections);
            // A switched-on but empty row would otherwise vanish, so the menu toggle seemed to do nothing.
            if (connections.isEmpty() && editor == null) {
                Button add = new Button(I18n.get("toolbar.addConnection"), ToolbarIcons.create("add", contourColor, iconSize));
                add.setOnAction(event -> beginEditingConnections());
                bar.getItems().add(add);
            }
            HBox row = new HBox(12, label, bar);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(bar, Priority.ALWAYS);
            row.getStyleClass().add("toolbar-connections");
            rows.getChildren().add(row);
        }
        if (rows.getChildren().isEmpty() && settings.isVisible()) {
            Button customize = new Button(I18n.get("toolbar.customize"));
            customize.setOnAction(event -> beginEditing());
            rows.getChildren().add(customize);
        }
    }

    private ToolBar sectionBar(Section section, List<Entry> entries) {
        ToolBar bar = new ToolBar();
        bar.setMinWidth(40);
        bar.setMaxWidth(Double.MAX_VALUE);
        for (Entry entry : entries) bar.getItems().add(entryNode(entry));
        if (entries.isEmpty() && editor != null) {
            Label target = new Label(I18n.get("toolbar.dropHere"));
            target.getStyleClass().add("toolbar-drop-hint");
            bar.getItems().add(target);
        }
        installDrop(bar, section, null);
        return bar;
    }

    private Node entryNode(Entry entry) {
        if (entry.kind() == Kind.SEPARATOR && editor == null) return new Separator(Orientation.VERTICAL);
        ToolbarCatalog.Presentation description = catalog.describe(entry);
        Button button = new Button(entry.section() == Section.CONNECTIONS ? description.label() : "",
            ToolbarIcons.create(description.icon(), contourColor, iconSize));
        button.setId("toolbar-entry-" + entry.id());
        button.setAccessibleText(description.label());
        button.getStyleClass().add("toolbar-action");
        button.setMinSize(36, 34);
        Tooltip tooltip = new Tooltip();
        button.setTooltip(tooltip);
        if (editor == null) {
            Runnable update = () -> {
                ToolbarCatalog.Presentation current = catalog.describe(entry);
                button.setDisable(!current.enabled());
                button.setAccessibleText(current.label());
                button.pseudoClassStateChanged(CHECKED, current.checked());
                if (entry.section() == Section.CONNECTIONS) button.setText(current.label());
                tooltip.setText(current.label() + (current.shortcut().isBlank() ? "" : "\n" + current.shortcut())
                    + (current.enabled() ? "" : "\n" + I18n.get("toolbar.disabled")));
            };
            stateUpdates.add(update);
            update.run();
            button.setOnAction(event -> { catalog.run(entry); refreshStates(); });
            return button;
        }
        tooltip.setText(description.label() + "\n" + I18n.get("toolbar.editHint"));
        button.setOnAction(event -> editMenu(entry).show(button, javafx.geometry.Side.BOTTOM, 0, 0));
        button.setOnContextMenuRequested(event -> {
            editMenu(entry).show(button, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        button.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                editor.remove(entry.id()); rebuildRows(); event.consume();
            } else if (event.isAltDown() && (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.RIGHT)) {
                editor.step(entry.id(), event.getCode() == KeyCode.LEFT ? -1 : 1);
                rebuildRows();
                Node moved = lookup("#toolbar-entry-" + entry.id());
                if (moved != null) moved.requestFocus();
                event.consume();
            }
        });
        installDrag(button, entry, false);
        installDrop(button, entry.section(), entry.id());
        return button;
    }

    private ContextMenu editMenu(Entry entry) {
        ContextMenu menu = new ContextMenu();
        MenuItem remove = new MenuItem(I18n.get("toolbar.remove"));
        remove.setOnAction(event -> { editor.remove(entry.id()); rebuildRows(); });
        MenuItem left = new MenuItem(I18n.get("toolbar.moveLeft"));
        left.setOnAction(event -> { editor.step(entry.id(), -1); rebuildRows(); });
        MenuItem right = new MenuItem(I18n.get("toolbar.moveRight"));
        right.setOnAction(event -> { editor.step(entry.id(), 1); rebuildRows(); });
        Menu move = new Menu(I18n.get("toolbar.moveTo"));
        for (Section section : Section.values()) {
            if (!ToolbarEditorModel.accepts(entry, section)) continue;
            MenuItem destination = new MenuItem(sectionLabel(section));
            destination.setOnAction(event -> { editor.place(entry, section, null); rebuildRows(); });
            move.getItems().add(destination);
        }
        menu.getItems().addAll(remove, left, right, move);
        return menu;
    }

    private void installDrag(Node node, Entry entry, boolean fromCatalog) {
        node.setOnDragDetected(event -> {
            if (editor == null) return;
            dragged = fromCatalog ? Entry.create(entry.kind(), entry.section(), entry.target()) : entry;
            ClipboardContent content = new ClipboardContent();
            content.put(DRAG_ENTRY, dragged.id());
            node.startDragAndDrop(fromCatalog ? TransferMode.COPY : TransferMode.MOVE).setContent(content);
            event.consume();
        });
        node.setOnDragDone(event -> { dragged = null; event.consume(); });
    }

    private boolean acceptsDrop(javafx.scene.input.DragEvent event, Section section) {
        return editor != null && dragged != null && dragged.id().equals(event.getDragboard().getContent(DRAG_ENTRY))
            && ToolbarEditorModel.accepts(dragged, section);
    }

    private void installDrop(Node node, Section section, String beforeId) {
        node.setOnDragOver(event -> {
            if (acceptsDrop(event, section)) {
                event.acceptTransferModes(TransferMode.COPY, TransferMode.MOVE);
                node.pseudoClassStateChanged(DROP_TARGET, true);
                event.consume();
            }
        });
        node.setOnDragExited(event -> node.pseudoClassStateChanged(DROP_TARGET, false));
        node.setOnDragDropped(event -> {
            boolean accepted = acceptsDrop(event, section);
            if (accepted) editor.place(dragged, section, beforeId);
            event.setDropCompleted(accepted);
            node.pseudoClassStateChanged(DROP_TARGET, false);
            if (accepted) { rebuildRows(); event.consume(); }
        });
    }

    private void buildEditor() {
        editorPane.getStyleClass().add("toolbar-editor");
        search.setPromptText(I18n.get("toolbar.search"));
        search.textProperty().addListener((observable, oldValue, value) -> rebuildCatalog());
        categories.getItems().addAll(I18n.get("toolbar.actions"), I18n.get("toolbar.settings"), I18n.get("toolbar.connections"));
        categories.setPrefWidth(145);
        categories.setMinWidth(110);
        categories.setMaxWidth(180);
        categories.setPrefHeight(0);
        categories.setMinHeight(0);
        categories.getSelectionModel().select(0);
        categories.getSelectionModel().selectedIndexProperty().addListener((observable, oldValue, value) -> rebuildCatalog());
        ScrollPane scroll = new ScrollPane(available);
        available.setPadding(new Insets(4));
        scroll.getStyleClass().add("toolbar-catalog");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // Reserve two complete rows, including labels that wrap or use a larger UI font.
        scroll.prefViewportHeightProperty().bind(Bindings.createDoubleBinding(() ->
            2 * Math.ceil(catalogRowHeight.get()) + available.getVgap()
                + available.getInsets().getTop() + available.getInsets().getBottom(),
            catalogRowHeight, available.vgapProperty(), available.insetsProperty()));
        // Explicit control dimensions also invalidate the parent layout when icons grow.
        // ScrollPane's viewport preference alone can leave its parent using the previous height.
        scroll.minHeightProperty().bind(Bindings.createDoubleBinding(() -> scroll.getPrefViewportHeight()
            + scroll.getInsets().getTop() + scroll.getInsets().getBottom(),
            scroll.prefViewportHeightProperty(), scroll.insetsProperty()));
        scroll.prefHeightProperty().bind(scroll.minHeightProperty());
        VBox source = new VBox(8, search, scroll);
        HBox.setHgrow(source, Priority.ALWAYS);
        HBox content = new HBox(12, categories, source);
        Button addConnection = new Button(I18n.get("toolbar.addConnection"), ToolbarIcons.create("add", contourColor, iconSize));
        addConnection.setOnAction(event -> { categories.getSelectionModel().select(2); search.clear(); });
        Entry separator = Entry.create(Kind.SEPARATOR, Section.TOOLS, "");
        Button addSeparator = new Button(I18n.get("toolbar.separator"), ToolbarIcons.create("separator", contourColor, iconSize));
        addSeparator.setOnAction(event -> { editor.place(Entry.create(Kind.SEPARATOR, Section.TOOLS, ""), Section.TOOLS, null); rebuildRows(); });
        installDrag(addSeparator, separator, true);
        Button defaults = new Button(I18n.get("toolbar.reset"));
        defaults.setOnAction(event -> { editor.reset(); rebuildRows(); });
        Button cancel = new Button(I18n.get("toolbar.cancel"));
        cancel.setOnAction(event -> { editor = null; reload(settings); });
        Button finish = new Button(I18n.get("toolbar.finish"));
        finish.getStyleClass().add("toolbar-finish");
        finish.setOnAction(event -> {
            IconToolbarSettings changed = editor.finish(settings.isVisible());
            changed.setConnectionsVisible(settings.isConnectionsVisible());
            save.accept(changed, success -> {
                if (success) { editor = null; reload(changed); }
            });
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, defaults, spacer, cancel, finish);
        editorPane.getChildren().addAll(content, new HBox(8, addConnection, addSeparator), footer);
    }

    private void rebuildCatalog() {
        if (editor == null) return;
        available.getChildren().clear();
        int category = categories.getSelectionModel().getSelectedIndex();
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        List<Entry> candidates = category == 2 ? catalog.connections() : catalog.actions();
        for (Entry entry : candidates) {
            boolean settingsShortcut = entry.target().startsWith("toolbar.settings.");
            if (category == 0 && settingsShortcut || category == 1 && !settingsShortcut) continue;
            ToolbarCatalog.Presentation presentation = catalog.describe(entry);
            if (!presentation.label().toLowerCase(Locale.ROOT).contains(query)) continue;
            Button button = new Button("", ToolbarIcons.create(presentation.icon(), contourColor, iconSize));
            button.setContentDisplay(ContentDisplay.TOP);
            double preferredWidth = category == 2 ? 175 : 150;
            button.textProperty().bind(Bindings.createStringBinding(() -> catalogLabel(presentation.label(),
                button.getFont(), preferredWidth - button.getInsets().getLeft() - button.getInsets().getRight()),
                button.fontProperty(), button.insetsProperty()));
            button.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> Math.max(preferredWidth,
                textWidth(button.getText(), button.getFont()) + button.getInsets().getLeft()
                    + button.getInsets().getRight() + 2),
                button.textProperty(), button.fontProperty(), button.insetsProperty()));
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setMaxWidth(Region.USE_PREF_SIZE);
            button.setAccessibleText(presentation.label());
            button.layoutBoundsProperty().addListener((observable, oldValue, value) -> updateCatalogRowHeight());
            button.fontProperty().addListener((observable, oldValue, value) -> updateCatalogRowHeight());
            button.textProperty().addListener((observable, oldValue, value) -> updateCatalogRowHeight());
            button.setMinHeight(65);
            button.getStyleClass().add("toolbar-catalog-action");
            button.setOnAction(event -> {
                Entry added = Entry.create(entry.kind(), entry.section(), entry.target());
                editor.place(added, added.section(), null);
                rebuildRows();
            });
            installDrag(button, entry, true);
            available.getChildren().add(button);
        }
        if (available.getChildren().isEmpty()) available.getChildren().add(new Label(I18n.get("toolbar.noMatches")));
        updateCatalogRowHeight();
    }

    private void updateCatalogRowHeight() {
        double height = available.getChildren().stream().filter(Region.class::isInstance).map(Region.class::cast)
            .mapToDouble(item -> Math.max(item.prefHeight(item.prefWidth(-1)), item.getLayoutBounds().getHeight()))
            .max().orElse(65);
        catalogRowHeight.set(Math.max(65, height));
    }

    /** Wrap at spaces or after hyphens; an indivisible word widens the button instead. */
    private static String catalogLabel(String label, javafx.scene.text.Font font, double width) {
        StringBuilder result = new StringBuilder();
        String line = "";
        for (String word : label.split("(?<=\\p{Pd})\\s*|\\s+")) {
            if (word.isEmpty()) continue;
            boolean hyphen = !line.isEmpty() && Character.getType(line.charAt(line.length() - 1)) == Character.DASH_PUNCTUATION;
            String candidate = line.isEmpty() ? word : line + (hyphen ? "" : " ") + word;
            if (!line.isEmpty() && textWidth(candidate, font) > width) {
                result.append(line).append('\n');
                line = word;
            } else {
                line = candidate;
            }
        }
        return result.append(line).toString();
    }

    private static double textWidth(String label, javafx.scene.text.Font font) {
        var text = new javafx.scene.text.Text(label);
        text.setFont(font);
        return Math.ceil(text.getLayoutBounds().getWidth());
    }

    private static String sectionLabel(Section section) {
        return I18n.get("toolbar.section." + section.name().toLowerCase(Locale.ROOT));
    }
}
