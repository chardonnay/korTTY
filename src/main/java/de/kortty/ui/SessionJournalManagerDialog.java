package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SessionJournalExportService;
import de.kortty.core.SessionJournalHeaderSupport;
import de.kortty.core.SessionJournalService;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalLogFormat;
import de.kortty.model.SessionJournalMeta;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Node;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.PasswordField;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Management window for session journals: a sortable, filterable table of all journals (newest
 * first) with open/rename/export/delete actions, an editable per-journal description, optional
 * full-text search over journal contents, and the global journal options (log format, AI window,
 * chunking, AI title).
 */
public class SessionJournalManagerDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalManagerDialog.class);
    private static final DateTimeFormatter STARTED_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final MainWindow ownerWindow;
    private final KorTTYApplication app;
    private final ObservableList<SessionJournalMeta> journals = FXCollections.observableArrayList();
    private final FilteredList<SessionJournalMeta> filteredJournals = new FilteredList<>(journals, meta -> true);
    /** Interactive journals as rows; automation journals grouped by source and run. */
    private final TreeTableView<SessionJournalTreeSupport.Node> table;
    private final ComboBox<SessionJournalTreeSupport.Filter> sourceFilterCombo = new ComboBox<>();
    /** Keys of expanded group/run rows, kept across rebuilds. */
    private final Set<String> expandedKeys = new HashSet<>();
    private final TextField searchField = new TextField();
    private final CheckBox fulltextCheck = new CheckBox(I18n.get("journal.manager.fulltext"));
    private final TextArea descriptionArea = new TextArea();
    private final Label descriptionStatus = new Label();
    /** Directories matching the latest full-text scan; null = no content filter active. */
    private volatile Set<Path> fulltextMatches;
    private final AtomicInteger fulltextScanGeneration = new AtomicInteger();
    private final javafx.scene.control.ToggleButton aiSearchToggle =
        new javafx.scene.control.ToggleButton(I18n.get("journal.search.toggle"));
    /** Holds the table alone, or a vertical split of table and AI search panel. */
    private final VBox centerBox = new VBox();
    private SessionJournalSearchPanel searchPanel;
    /** Clickable AI-keyword chips under the filter field; hidden while no journal has keywords. */
    private final javafx.scene.layout.FlowPane keywordChips = new javafx.scene.layout.FlowPane(6, 4);
    /** AI-search hit counts per journal directory; null = no hit column/highlight shown. */
    private volatile java.util.Map<Path, Long> aiHitCounts;
    private TreeTableColumn<SessionJournalTreeSupport.Node, Long> hitsColumn;

    public SessionJournalManagerDialog(MainWindow ownerWindow) {
        this.ownerWindow = ownerWindow;
        this.app = KorTTYApplication.getInstance();
        initModality(Modality.NONE);
        setTitle(I18n.get("journal.manager.title"));
        setResizable(true);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        table = buildTable();
        // The tree follows the filtered list: a refresh, a new filter or a finished content scan.
        filteredJournals.addListener((javafx.collections.ListChangeListener<SessionJournalMeta>) change -> rebuildTree());
        getDialogPane().setContent(buildRoot());
        getDialogPane().setPrefSize(940, 620);
        getDialogPane().setMinSize(680, 460);
        restoreGeometry();
        setOnCloseRequest(event -> saveGeometry());
        setOnHidden(event -> {
            saveGeometry();
            if (searchPanel != null) {
                searchPanel.dispose();
            }
        });

        refresh();
    }

    private VBox buildRoot() {
        searchField.setPromptText(I18n.get("journal.manager.search.prompt"));
        searchField.textProperty().addListener((obs, old, value) -> onSearchChanged());
        fulltextCheck.selectedProperty().addListener((obs, old, value) -> onSearchChanged());
        HBox.setHgrow(searchField, Priority.ALWAYS);
        sourceFilterCombo.getItems().setAll(SessionJournalTreeSupport.Filter.values());
        sourceFilterCombo.setValue(SessionJournalTreeSupport.Filter.ALL);
        sourceFilterCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(SessionJournalTreeSupport.Filter filter) {
                return filter == null ? "" : I18n.get("journal.manager.filter." + filter.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public SessionJournalTreeSupport.Filter fromString(String value) {
                return null;
            }
        });
        sourceFilterCombo.valueProperty().addListener((obs, old, value) -> onSearchChanged());
        HBox searchBar = new HBox(8, searchField, sourceFilterCombo, fulltextCheck);
        searchBar.setStyle("-fx-alignment: center-left;");
        if (de.kortty.policy.PolicyManager.effective().sessionJournalAiAskAllowed()) {
            aiSearchToggle.setOnAction(event -> toggleAiSearchPanel());
            searchBar.getChildren().add(aiSearchToggle);
        }

        Button openButton = new Button(I18n.get("journal.manager.open"));
        ButtonIcons.apply(openButton, ButtonIcons.OPEN);
        openButton.setOnAction(event -> openSelected());
        Button renameButton = new Button(I18n.get("journal.manager.rename"));
        ButtonIcons.apply(renameButton, ButtonIcons.RENAME);
        renameButton.setOnAction(event -> renameSelected());
        MenuButton exportButton = new MenuButton(I18n.get("journal.manager.export"));
        for (SessionJournalExportService.Format format : SessionJournalExportService.Format.values()) {
            MenuItem item = new MenuItem(formatLabel(format));
            item.setOnAction(event -> exportSelected(format));
            exportButton.getItems().add(item);
        }
        Button deleteButton = new Button(I18n.get("journal.manager.delete"));
        ButtonIcons.apply(deleteButton, ButtonIcons.DELETE);
        deleteButton.setOnAction(event -> deleteSelected());
        Button optionsButton = new Button(I18n.get("journal.manager.options"));
        optionsButton.setOnAction(event -> showOptionsDialog());
        Button refreshButton = new Button(I18n.get("journal.manager.refresh"));
        ButtonIcons.apply(refreshButton, ButtonIcons.REFRESH);
        refreshButton.setOnAction(event -> refresh());

        var selectedItems = table.getSelectionModel().getSelectedItems();
        // Open and rename act on exactly one journal; export and delete accept a whole selection —
        // a selected run or group stands for all of its journals.
        var exactlyOne = javafx.beans.binding.Bindings.createBooleanBinding(
            () -> selectedJournals().size() == 1, selectedItems);
        var noneSelected = javafx.beans.binding.Bindings.createBooleanBinding(
            () -> selectedJournals().isEmpty(), selectedItems);
        var anyLiveSelected = javafx.beans.binding.Bindings.createBooleanBinding(
            () -> selectedJournals().stream().anyMatch(SessionJournalMeta::isLive), selectedItems);
        openButton.disableProperty().bind(exactlyOne.not());
        exportButton.disableProperty().bind(noneSelected);
        de.kortty.policy.EffectivePolicy policy = de.kortty.policy.PolicyManager.effective();
        if (!policy.sessionJournalRenameAllowed()) {
            renameButton.setDisable(true);
            renameButton.setTooltip(new Tooltip(I18n.get("journal.options.managed")));
        } else {
            renameButton.disableProperty().bind(exactlyOne.not().or(anyLiveSelected));
        }
        if (!policy.sessionJournalDeleteAllowed()) {
            deleteButton.setDisable(true);
            deleteButton.setTooltip(new Tooltip(I18n.get("journal.options.managed")));
        } else {
            deleteButton.disableProperty().bind(noneSelected.or(anyLiveSelected));
        }

        HBox buttonBar = new HBox(8, openButton, renameButton, exportButton, deleteButton, optionsButton, refreshButton);

        descriptionArea.setPromptText(I18n.get("journal.manager.description"));
        descriptionArea.setPrefRowCount(3);
        descriptionArea.setWrapText(true);
        descriptionArea.setDisable(true);
        Button saveDescriptionButton = new Button(I18n.get("journal.manager.description.save"));
        saveDescriptionButton.disableProperty().bind(exactlyOne.not());
        saveDescriptionButton.setOnAction(event -> saveDescription());
        descriptionStatus.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");
        selectedItems.addListener((javafx.collections.ListChangeListener<TreeItem<SessionJournalTreeSupport.Node>>) change -> {
            SessionJournalMeta meta = selected();
            descriptionArea.setDisable(meta == null);
            descriptionArea.setText(meta != null && meta.getDescription() != null ? meta.getDescription() : "");
            descriptionStatus.setText("");
        });
        Label descriptionLabel = new Label(I18n.get("journal.manager.description"));
        HBox descriptionBar = new HBox(8, saveDescriptionButton, descriptionStatus);
        descriptionBar.setStyle("-fx-alignment: center-left;");
        VBox descriptionBox = new VBox(4, descriptionLabel, descriptionArea, descriptionBar);

        keywordChips.setVisible(false);
        keywordChips.setManaged(false);
        // Exactly one selected journal shows its own keywords; otherwise the most common ones.
        table.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<TreeItem<SessionJournalTreeSupport.Node>>) change -> rebuildKeywordChips());

        centerBox.getChildren().setAll(table);
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox root = new VBox(10, searchBar, keywordChips, centerBox, buttonBar, descriptionBox);
        root.setPadding(new Insets(6));
        VBox.setVgrow(centerBox, Priority.ALWAYS);
        return root;
    }

    /** Top keywords across all listed journals, or the selected journal's own keywords. */
    private void rebuildKeywordChips() {
        List<SessionJournalMeta> selected = selectedJournals();
        List<String> keywords;
        if (selected.size() == 1 && !selected.get(0).getAiKeywords().isEmpty()) {
            keywords = selected.get(0).getAiKeywords();
        } else {
            java.util.Map<String, Long> frequency = new java.util.LinkedHashMap<>();
            for (SessionJournalMeta meta : journals) {
                for (String keyword : meta.getAiKeywords()) {
                    frequency.merge(keyword.toLowerCase(Locale.ROOT), 1L, Long::sum);
                }
            }
            keywords = frequency.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(15)
                .map(java.util.Map.Entry::getKey)
                .toList();
        }
        keywordChips.getChildren().clear();
        for (String keyword : keywords) {
            Button chip = new Button(keyword);
            chip.getStyleClass().add("journal-keyword-chip");
            chip.setStyle("-fx-background-radius: 999; -fx-padding: 2 10 2 10; -fx-font-size: 0.85em;");
            chip.setOnAction(event -> {
                searchField.setText(keyword);
                searchField.requestFocus();
                searchField.positionCaret(keyword.length());
            });
            keywordChips.getChildren().add(chip);
        }
        boolean hasChips = !keywordChips.getChildren().isEmpty();
        keywordChips.setVisible(hasChips);
        keywordChips.setManaged(hasChips);
    }

    // ==== AI cross-journal search ====

    private void toggleAiSearchPanel() {
        if (searchPanel != null) {
            hideAiSearchPanel();
            return;
        }
        searchPanel = new SessionJournalSearchPanel(
            de.kortty.core.SessionJournalCrossSearchService.application(service()),
            new SessionJournalSearchPanel.Host() {
                @Override
                public List<SessionJournalMeta> allJournals() {
                    return List.copyOf(journals);
                }

                @Override
                public List<SessionJournalMeta> selectedJournals() {
                    return SessionJournalManagerDialog.this.selectedJournals();
                }

                @Override
                public void showHitCounts(java.util.Map<Path, Long> counts) {
                    SessionJournalManagerDialog.this.showHitCounts(counts);
                }

                @Override
                public void clearHitCounts() {
                    SessionJournalManagerDialog.this.clearHitCounts();
                }

                @Override
                public void openHit(SessionJournalMeta meta,
                                    de.kortty.core.SessionJournalCrossSearchService.HitTarget target) {
                    ownerWindow.openSessionJournal(meta, pane -> {
                        if (target instanceof de.kortty.core.SessionJournalCrossSearchService.EntryTarget entry) {
                            pane.jumpToEntry(entry.entryId());
                        } else if (target instanceof de.kortty.core.SessionJournalCrossSearchService.LogTarget log) {
                            pane.jumpToLogSeq(log.seq());
                        }
                    });
                }
            });
        javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane(table, searchPanel);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.55);
        centerBox.getChildren().setAll(split);
        VBox.setVgrow(split, Priority.ALWAYS);
        aiSearchToggle.setSelected(true);
        searchPanel.focusQuestionField();
    }

    private void hideAiSearchPanel() {
        if (searchPanel == null) {
            return;
        }
        searchPanel.dispose();
        searchPanel = null;
        clearHitCounts();
        centerBox.getChildren().setAll(table);
        VBox.setVgrow(table, Priority.ALWAYS);
        aiSearchToggle.setSelected(false);
    }

    /** Adds the sorted "Hits" column and the row highlight; the table itself stays unfiltered. */
    private void showHitCounts(java.util.Map<Path, Long> counts) {
        aiHitCounts = counts;
        if (hitsColumn == null) {
            hitsColumn = new TreeTableColumn<>(I18n.get("journal.search.hitsColumn"));
            hitsColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(hitCount(cell.getValue().getValue())));
            hitsColumn.setCellFactory(column -> new TreeTableCell<>() {
                @Override
                protected void updateItem(Long count, boolean empty) {
                    super.updateItem(count, empty);
                    setText(empty || count == null || count <= 0 ? "" : String.valueOf(count));
                }
            });
            hitsColumn.setMinWidth(70);
        }
        if (!table.getColumns().contains(hitsColumn)) {
            table.getColumns().add(hitsColumn);
        }
        hitsColumn.setSortType(TreeTableColumn.SortType.DESCENDING);
        table.getSortOrder().setAll(List.of(hitsColumn));
        table.refresh();
    }

    private void clearHitCounts() {
        aiHitCounts = null;
        if (hitsColumn != null) {
            table.getColumns().remove(hitsColumn);
            if (table.getSortOrder().contains(hitsColumn)) {
                table.getSortOrder().clear();
                if (startedColumn != null) {
                    startedColumn.setSortType(TreeTableColumn.SortType.DESCENDING);
                    table.getSortOrder().add(startedColumn);
                }
            }
        }
        table.refresh();
    }

    /** Hits of a journal, or the sum over the journals of a run or group row. */
    private Long hitCount(SessionJournalTreeSupport.Node node) {
        java.util.Map<Path, Long> counts = aiHitCounts;
        if (counts == null || node == null) {
            return null;
        }
        long sum = 0;
        boolean any = false;
        for (SessionJournalMeta meta : node.journals()) {
            Long count = meta.getDirectory() != null ? counts.get(meta.getDirectory().toAbsolutePath().normalize()) : null;
            if (count != null) {
                sum += count;
                any = true;
            }
        }
        return any ? sum : null;
    }

    /** "📌 kept", the (next) automatic deletion date of automation journals, or empty. */
    static String expiresText(SessionJournalTreeSupport.Node node) {
        if (node == null || node.journals().isEmpty() || !node.journals().get(0).isAutomation()) {
            return "";
        }
        if (node.allPinned()) {
            return "\uD83D\uDCCC " + I18n.get("journal.manager.pinned");
        }
        OffsetDateTime expiry = node.nextExpiry();
        return expiry != null ? expiry.atZoneSameInstant(ZoneId.systemDefault()).format(STARTED_FORMAT) : "";
    }

    /** Pins every journal of the row (exempt from automatic deletion), or releases them all. */
    private void setPinned(SessionJournalTreeSupport.Node node, boolean pinned) {
        SessionJournalService service = service();
        if (node == null || service == null) {
            return;
        }
        try {
            for (SessionJournalMeta meta : node.journals()) {
                if (meta.getDirectory() != null && meta.isAutomation()) {
                    service.setPinned(meta.getDirectory(), pinned);
                }
            }
            refresh();
        } catch (Exception e) {
            showError(e.getMessage());
        }
    }

    /** The AI tokens tooltip of a row: one journal's breakdown, or the sums of a run or group. */
    static String aiUsageTooltip(SessionJournalTreeSupport.Node node) {
        if (node == null || node.aiCallCount() <= 0) {
            return "";
        }
        if (node.meta() != null) {
            return aiUsageTooltip(node.meta());
        }
        return I18n.get("journal.ai.usage.detail",
            Long.toString(node.aiPromptTokens()),
            Long.toString(node.aiCompletionTokens()),
            Integer.toString(node.aiCallCount()),
            "");
    }

    /** "Prompt 9 812 · completion 2 488 · 4 calls · Profile" for the AI tokens tooltip. */
    static String aiUsageTooltip(SessionJournalMeta meta) {
        if (meta == null || meta.getAiCallCount() <= 0) {
            return "";
        }
        return I18n.get("journal.ai.usage.detail",
            Long.toString(meta.getAiPromptTokens()),
            Long.toString(meta.getAiCompletionTokens()),
            Integer.toString(meta.getAiCallCount()),
            meta.getAiProfileName() != null ? meta.getAiProfileName() : "");
    }

    private TreeTableColumn<SessionJournalTreeSupport.Node, Long> startedColumn;

    private TreeTableView<SessionJournalTreeSupport.Node> buildTable() {
        TreeTableView<SessionJournalTreeSupport.Node> view = new TreeTableView<>(new TreeItem<>());
        view.setShowRoot(false);
        view.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // Several journals can be exported into one archive or deleted in one go.
        view.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        view.setPlaceholder(new Label(I18n.get("journal.manager.empty")));
        // One fixed row height for every row: measured per row, the title cell (rebuilt with its
        // badges on every update) made rows differ and grow when selected. Derived from the font
        // so larger UI fonts still get comfortable rows.
        view.setFixedCellSize(rowHeight(javafx.scene.text.Font.getDefault().getSize()));

        TreeTableColumn<SessionJournalTreeSupport.Node, String> titleColumn =
            new TreeTableColumn<>(I18n.get("journal.manager.column.title"));
        titleColumn.setCellValueFactory(cell -> new SimpleStringProperty(titleText(cell.getValue().getValue())));
        titleColumn.setCellFactory(column -> new TreeTableCell<>() {
            @Override
            protected void updateItem(String title, boolean empty) {
                super.updateItem(title, empty);
                SessionJournalTreeSupport.Node node = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (node == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label text = new Label(title);
                // Follow the cell's own colour (theme, selection) instead of the dialog's label colour.
                text.textFillProperty().bind(textFillProperty());
                if (node.kind() != SessionJournalTreeSupport.Kind.JOURNAL) {
                    text.setStyle("-fx-font-weight: bold;");
                }
                text.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                HBox badges = SessionJournalBadges.render(SessionJournalBadges.badges(
                    node, OffsetDateTime.now(), de.kortty.core.AutomationJournalPolicy.current().hasCaps()));
                badges.getChildren().forEach(pill -> ((Label) pill).textFillProperty().bind(textFillProperty()));
                HBox box = new HBox(8, text, badges);
                box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                setText(null);
                setGraphic(box);
            }
        });
        titleColumn.setMinWidth(320);
        titleColumn.setPrefWidth(420);

        // Sorted on the epoch value, never on the dd.MM.yyyy display string.
        startedColumn = new TreeTableColumn<>(I18n.get("journal.manager.column.started"));
        startedColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(startedEpoch(cell.getValue().getValue())));
        startedColumn.setCellFactory(column -> new TreeTableCell<>() {
            @Override
            protected void updateItem(Long epochMillis, boolean empty) {
                super.updateItem(epochMillis, empty);
                setText(empty || epochMillis == null || epochMillis <= 0
                    ? ""
                    : java.time.Instant.ofEpochMilli(epochMillis)
                        .atZone(ZoneId.systemDefault()).format(STARTED_FORMAT));
            }
        });
        startedColumn.setMinWidth(130);

        TreeTableColumn<SessionJournalTreeSupport.Node, String> durationColumn =
            new TreeTableColumn<>(I18n.get("journal.manager.column.duration"));
        durationColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            cell.getValue().getValue() != null && cell.getValue().getValue().meta() != null
                ? durationText(cell.getValue().getValue().meta()) : ""));
        durationColumn.setMinWidth(80);

        TreeTableColumn<SessionJournalTreeSupport.Node, String> connectionColumn =
            new TreeTableColumn<>(I18n.get("journal.manager.column.connection"));
        connectionColumn.setCellValueFactory(cell -> new SimpleStringProperty(connectionText(cell.getValue().getValue())));
        connectionColumn.setMinWidth(140);

        TreeTableColumn<SessionJournalTreeSupport.Node, String> serverColumn =
            new TreeTableColumn<>(I18n.get("journal.manager.column.server"));
        serverColumn.setCellValueFactory(cell -> new SimpleStringProperty(serverText(cell.getValue().getValue())));
        serverColumn.setMinWidth(140);

        TreeTableColumn<SessionJournalTreeSupport.Node, Long> entriesColumn =
            new TreeTableColumn<>(I18n.get("journal.manager.column.entries"));
        entriesColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(
            cell.getValue().getValue() != null ? cell.getValue().getValue().logEntryCount() : 0L));
        entriesColumn.setMinWidth(70);

        // Sorted on the token count; the cell shows tokens and cost, the tooltip the breakdown.
        TreeTableColumn<SessionJournalTreeSupport.Node, Long> aiTokensColumn =
            new TreeTableColumn<>(I18n.get("journal.manager.column.aiTokens"));
        aiTokensColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(
            cell.getValue().getValue() != null ? cell.getValue().getValue().aiTotalTokens() : 0L));
        aiTokensColumn.setCellFactory(column -> new TreeTableCell<>() {
            @Override
            protected void updateItem(Long tokens, boolean empty) {
                super.updateItem(tokens, empty);
                SessionJournalTreeSupport.Node node = empty || getTableRow() == null ? null : getTableRow().getItem();
                String summary = aiUsageSummary(node);
                setText(summary);
                setTooltip(summary.isEmpty() ? null : new Tooltip(aiUsageTooltip(node)));
            }
        });
        aiTokensColumn.setMinWidth(110);

        // Automation run journals are deleted automatically; the column says when, or that a pin keeps them.
        TreeTableColumn<SessionJournalTreeSupport.Node, String> expiresColumn =
            new TreeTableColumn<>(I18n.get("journal.manager.column.expires"));
        expiresColumn.setCellValueFactory(cell -> new SimpleStringProperty(expiresText(cell.getValue().getValue())));
        expiresColumn.setMinWidth(110);

        view.getColumns().addAll(List.of(
            titleColumn, startedColumn, durationColumn, connectionColumn, serverColumn, entriesColumn,
            aiTokensColumn, expiresColumn));
        view.setTreeColumn(titleColumn);
        startedColumn.setSortType(TreeTableColumn.SortType.DESCENDING);
        view.getSortOrder().add(startedColumn);

        view.setRowFactory(tableView -> {
            TreeTableRow<SessionJournalTreeSupport.Node> row = new TreeTableRow<>() {
                @Override
                protected void updateItem(SessionJournalTreeSupport.Node node, boolean empty) {
                    super.updateItem(node, empty);
                    Long count = empty ? null : hitCount(node);
                    // Subtle accent wash on AI-search hits; low alpha works on both themes.
                    setStyle(count != null && count > 0
                        ? "-fx-background-color: rgba(120, 170, 255, 0.14);"
                        : "");
                }
            };
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() != 2 || row.isEmpty() || row.getItem() == null) {
                    return;
                }
                if (row.getItem().meta() != null) {
                    ownerWindow.openSessionJournal(row.getItem().meta());
                } else if (row.getTreeItem() != null) {
                    row.getTreeItem().setExpanded(!row.getTreeItem().isExpanded());
                }
            });
            row.setContextMenu(null);
            row.itemProperty().addListener((obs, old, node) -> row.setContextMenu(buildRowMenu(node)));
            return row;
        });
        return view;
    }

    /** Right-click menu of an automation row: pin or release, and open the job. */
    private javafx.scene.control.ContextMenu buildRowMenu(SessionJournalTreeSupport.Node node) {
        if (node == null || node.journals().isEmpty() || !node.journals().get(0).isAutomation()) {
            return null;
        }
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        boolean single = node.meta() != null;
        if (!node.allPinned()) {
            MenuItem pin = new MenuItem(I18n.get(single ? "journal.manager.pin" : "journal.manager.pinAll"));
            pin.setOnAction(event -> setPinned(node, true));
            menu.getItems().add(pin);
        }
        if (node.anyPinned()) {
            MenuItem unpin = new MenuItem(I18n.get(single ? "journal.manager.unpin" : "journal.manager.unpinAll"));
            unpin.setOnAction(event -> setPinned(node, false));
            menu.getItems().add(unpin);
        }
        SessionJournalMeta first = node.journals().get(0);
        if (first.getEffectiveSourceKind() == de.kortty.model.SessionJournalSourceKind.JOB && first.getSourceId() != null) {
            MenuItem openJob = new MenuItem(I18n.get("journal.manager.openJob"));
            openJob.setOnAction(event -> ownerWindow.showJobSchedulerForJob(first.getSourceId()));
            menu.getItems().add(openJob);
        }
        return menu;
    }

    /** Row height of the journal table for a UI font size: roomy, never below 30 px. */
    static double rowHeight(double fontSize) {
        return Math.max(30, Math.ceil(fontSize * 2.4));
    }

    /** "Job: Nightly check", "Run 30.09.2026 22:00", or the journal's title. */
    private static String titleText(SessionJournalTreeSupport.Node node) {
        if (node == null) {
            return "";
        }
        return switch (node.kind()) {
            case GROUP -> {
                String name = node.sourceName() != null ? node.sourceName() : "";
                String key = SessionJournalTreeSupport.isScheduledSwarm(node) ? "journal.manager.group.scheduledSwarm"
                    : node.sourceKind() == de.kortty.model.SessionJournalSourceKind.SWARM ? "journal.manager.group.swarm"
                    : "journal.manager.group.job";
                yield I18n.get(key, name);
            }
            case RUN -> I18n.get("journal.manager.run.title", node.startedAt() != null
                ? node.startedAt().atZoneSameInstant(ZoneId.systemDefault()).format(STARTED_FORMAT) : "?");
            case JOURNAL -> node.meta().getTitle() != null ? node.meta().getTitle() : "";
        };
    }

    /** Journal: connection name; run: its servers; group: runs, journals and disk space. */
    private static String connectionText(SessionJournalTreeSupport.Node node) {
        if (node == null) {
            return "";
        }
        return switch (node.kind()) {
            case JOURNAL -> node.meta().getConnectionName() != null ? node.meta().getConnectionName() : "";
            case RUN -> I18n.get("journal.manager.run.summary", node.journalCount());
            case GROUP -> I18n.get("journal.manager.group.summary", node.runCount(), node.journalCount(),
                String.format(Locale.getDefault(), "%.1f MB", node.storageBytes() / (1024.0 * 1024.0)));
        };
    }

    private static String aiUsageSummary(SessionJournalTreeSupport.Node node) {
        if (node == null) {
            return "";
        }
        if (node.meta() != null) {
            return SessionJournalHeaderSupport.aiUsageSummary(node.meta(), I18n.get("journal.ai.usage.local"), Locale.getDefault());
        }
        if (node.aiCallCount() <= 0) {
            return "";
        }
        StringBuilder text = new StringBuilder(de.kortty.core.AiTokenUsageManager.formatCompact(node.aiTotalTokens()));
        if (node.aiCost() > 0.0) {
            text.append(SessionJournalHeaderSupport.SEPARATOR).append("≈ ")
                .append(de.kortty.core.AiCostCalculator.format(node.aiCost(), node.costCurrency(), Locale.getDefault()));
        } else if (node.allLocal()) {
            text.append(SessionJournalHeaderSupport.SEPARATOR).append(I18n.get("journal.ai.usage.local"));
        }
        return text.toString();
    }

    /** Rebuilds the tree from the filtered journals, keeping expanded rows and the selection. */
    private void rebuildTree() {
        Set<String> selectedKeys = new HashSet<>();
        for (TreeItem<SessionJournalTreeSupport.Node> item : table.getSelectionModel().getSelectedItems()) {
            if (item != null && item.getValue() != null) {
                selectedKeys.add(item.getValue().key());
            }
        }
        rememberExpansion(table.getRoot());
        TreeItem<SessionJournalTreeSupport.Node> root = new TreeItem<>();
        for (SessionJournalTreeSupport.Node node : SessionJournalTreeSupport.build(List.copyOf(filteredJournals))) {
            root.getChildren().add(treeItem(node));
        }
        table.setRoot(root);
        table.sort();
        table.getSelectionModel().clearSelection();
        selectKeys(root, selectedKeys);
    }

    private TreeItem<SessionJournalTreeSupport.Node> treeItem(SessionJournalTreeSupport.Node node) {
        TreeItem<SessionJournalTreeSupport.Node> item = new TreeItem<>(node);
        for (SessionJournalTreeSupport.Node child : node.children()) {
            item.getChildren().add(treeItem(child));
        }
        item.setExpanded(expandedKeys.contains(node.key()));
        item.expandedProperty().addListener((obs, was, expanded) -> {
            if (expanded) {
                expandedKeys.add(node.key());
            } else {
                expandedKeys.remove(node.key());
            }
        });
        return item;
    }

    private void rememberExpansion(TreeItem<SessionJournalTreeSupport.Node> item) {
        if (item == null) {
            return;
        }
        for (TreeItem<SessionJournalTreeSupport.Node> child : item.getChildren()) {
            if (child.getValue() != null && child.isExpanded()) {
                expandedKeys.add(child.getValue().key());
            }
            rememberExpansion(child);
        }
    }

    private void selectKeys(TreeItem<SessionJournalTreeSupport.Node> item, Set<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        for (TreeItem<SessionJournalTreeSupport.Node> child : item.getChildren()) {
            if (child.getValue() != null && keys.contains(child.getValue().key())) {
                table.getSelectionModel().select(child);
            }
            selectKeys(child, keys);
        }
    }

    // ==== search ====

    private void onSearchChanged() {
        String query = searchField.getText() != null
            ? searchField.getText().strip().toLowerCase(Locale.ROOT) : "";
        if (fulltextCheck.isSelected() && !query.isEmpty()) {
            startFulltextScan(query);
        } else {
            fulltextMatches = null;
            applyPredicate(query);
        }
    }

    private void applyPredicate(String query) {
        Set<Path> contentMatches = fulltextMatches;
        SessionJournalTreeSupport.Filter filter = sourceFilterCombo.getValue() != null
            ? sourceFilterCombo.getValue() : SessionJournalTreeSupport.Filter.ALL;
        filteredJournals.setPredicate(meta -> {
            if (!filter.matches(meta)) {
                return false;
            }
            if (query.isEmpty()) {
                return true;
            }
            if (matchesMetadata(meta, query)) {
                return true;
            }
            return contentMatches != null && meta.getDirectory() != null
                && contentMatches.contains(meta.getDirectory().toAbsolutePath().normalize());
        });
    }

    private static boolean matchesMetadata(SessionJournalMeta meta, String query) {
        return containsIgnoreCase(meta.getTitle(), query)
            || containsIgnoreCase(meta.getSourceName(), query)
            || containsIgnoreCase(meta.getConnectionName(), query)
            || containsIgnoreCase(meta.getHost(), query)
            || containsIgnoreCase(meta.getUsername(), query)
            || containsIgnoreCase(meta.getDescription(), query)
            || meta.getAiKeywords().stream().anyMatch(keyword -> containsIgnoreCase(keyword, query));
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    /** Scans entry texts and capture logs on a background thread; results narrow the filter. */
    private void startFulltextScan(String query) {
        int generation = fulltextScanGeneration.incrementAndGet();
        List<SessionJournalMeta> snapshot = List.copyOf(journals);
        Thread scanner = new Thread(() -> {
            Set<Path> matches = new HashSet<>();
            SessionJournalService service = service();
            for (SessionJournalMeta meta : snapshot) {
                if (fulltextScanGeneration.get() != generation) {
                    return; // superseded by a newer query
                }
                Path dir = meta.getDirectory();
                if (dir == null || service == null) {
                    continue;
                }
                try {
                    boolean match = service.loadDocument(dir).getEntries().stream().anyMatch(entry ->
                        containsIgnoreCase(entry.getTitle(), query)
                            || containsIgnoreCase(entry.getText(), query)
                            || containsIgnoreCase(entry.getUserNote(), query)
                            || containsIgnoreCase(entry.getAiDescription(), query)
                            || entry.getAiTags().stream().anyMatch(tag -> containsIgnoreCase(tag, query)));
                    if (!match) {
                        // Streaming scan: parts are read line by line, never materialized whole.
                        match = de.kortty.core.SessionJournalLogSearcher.search(
                            dir,
                            de.kortty.core.SessionJournalLogSearcher.Spec.ofLiteral(List.of(query)),
                            1,
                            () -> fulltextScanGeneration.get() != generation
                        ).totalMatches() > 0;
                    }
                    if (match) {
                        matches.add(dir.toAbsolutePath().normalize());
                    }
                } catch (Exception e) {
                    logger.debug("Full-text scan skipped {}: {}", dir.getFileName(), e.getMessage());
                }
            }
            if (fulltextScanGeneration.get() == generation) {
                Platform.runLater(() -> {
                    fulltextMatches = matches;
                    applyPredicate(query);
                });
            }
        }, "SessionJournal-FulltextScan");
        scanner.setDaemon(true);
        scanner.start();
    }

    // ==== actions ====

    private SessionJournalService service() {
        return app != null ? app.getSessionJournalService() : null;
    }

    private GlobalSettings settings() {
        return app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings() : null;
    }

    void refresh() {
        SessionJournalService service = service();
        if (service == null) {
            return;
        }
        try {
            journals.setAll(service.listJournals(settings()));
        } catch (Exception e) {
            logger.error("Could not list session journals: {}", e.getMessage());
        }
        rebuildKeywordChips();
        onSearchChanged();
    }

    /** The single selected journal (a run or group of exactly one journal counts), else null. */
    private SessionJournalMeta selected() {
        List<SessionJournalMeta> selected = selectedJournals();
        return selected.size() == 1 ? selected.get(0) : null;
    }

    /**
     * A stable copy of the selected journals — a selected run or group stands for all journals
     * below it; each journal appears once.
     */
    private List<SessionJournalMeta> selectedJournals() {
        java.util.LinkedHashMap<Path, SessionJournalMeta> unique = new java.util.LinkedHashMap<>();
        List<SessionJournalMeta> withoutDirectory = new ArrayList<>();
        for (TreeItem<SessionJournalTreeSupport.Node> item : List.copyOf(table.getSelectionModel().getSelectedItems())) {
            if (item == null || item.getValue() == null) {
                continue;
            }
            for (SessionJournalMeta meta : item.getValue().journals()) {
                if (meta.getDirectory() != null) {
                    unique.putIfAbsent(meta.getDirectory().toAbsolutePath().normalize(), meta);
                } else {
                    withoutDirectory.add(meta);
                }
            }
        }
        List<SessionJournalMeta> result = new ArrayList<>(unique.values());
        result.addAll(withoutDirectory);
        return result;
    }

    private void openSelected() {
        SessionJournalMeta meta = selected();
        if (meta != null) {
            ownerWindow.openSessionJournal(meta);
        }
    }

    private void renameSelected() {
        SessionJournalMeta meta = selected();
        if (meta == null || meta.getDirectory() == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog(meta.getTitle() != null ? meta.getTitle() : "");
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("journal.manager.rename.title"));
        dialog.setHeaderText(I18n.get("journal.manager.rename.header"));
        dialog.showAndWait().ifPresent(newTitle -> {
            if (newTitle == null || newTitle.isBlank()) {
                return;
            }
            try {
                service().renameJournal(meta.getDirectory(), newTitle.strip());
                refresh();
            } catch (Exception e) {
                showError(I18n.get("journal.export.error", e.getMessage()));
            }
        });
    }

    private void deleteSelected() {
        List<SessionJournalMeta> targets = selectedJournals();
        if (targets.isEmpty()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(confirm);
        confirm.initOwner(ownerWindow.getStage());
        confirm.setTitle(I18n.get("journal.manager.delete.title"));
        if (targets.size() == 1) {
            SessionJournalMeta meta = targets.get(0);
            confirm.setHeaderText(I18n.get("journal.manager.delete.header"));
            confirm.setContentText(I18n.get("journal.manager.delete.content",
                meta.getTitle() != null ? meta.getTitle() : meta.getConnectionName()));
        } else {
            confirm.setHeaderText(I18n.get("journal.manager.delete.multiple.header", targets.size()));
            confirm.setContentText(I18n.get("journal.manager.delete.multiple.content"));
        }
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        List<String> failures = new ArrayList<>();
        for (SessionJournalMeta meta : targets) {
            if (meta.getDirectory() == null) {
                continue;
            }
            try {
                service().deleteJournal(settings(), meta.getDirectory());
            } catch (Exception e) {
                failures.add(meta.getTitle() + ": " + e.getMessage());
            }
        }
        refresh();
        if (!failures.isEmpty()) {
            showError(I18n.get("journal.export.error", String.join("\n", failures)));
        }
    }

    private void saveDescription() {
        SessionJournalMeta meta = selected();
        if (meta == null || meta.getDirectory() == null) {
            return;
        }
        try {
            service().updateDescription(meta.getDirectory(), descriptionArea.getText());
            descriptionStatus.setText(I18n.get("journal.manager.description.saved"));
            refresh();
        } catch (Exception e) {
            showError(I18n.get("journal.export.error", e.getMessage()));
        }
    }

    private void exportSelected(SessionJournalExportService.Format format) {
        List<SessionJournalMeta> targets = selectedJournals();
        if (targets.isEmpty()) {
            return;
        }
        // Several journals always go into one archive, and so does the HTML bundle of a single one.
        boolean archive = targets.size() > 1
            || format == SessionJournalExportService.Format.HTML_BUNDLE;
        List<java.nio.file.Path> previewDirs = targets.stream()
            .map(SessionJournalMeta::getDirectory)
            .filter(java.util.Objects::nonNull)
            .toList();
        SessionJournalExportOptionsDialog.ExportChoice choice = SessionJournalExportOptionsDialog.ask(
            service(),
            new SessionJournalExportOptionsDialog.Request(format, previewDirs, archive,
                ownerWindow.getStage()))
            .orElse(null);
        if (choice == null) {
            return;
        }
        String extension = archive ? ".zip" : format.getExtension();
        String filterKey = archive ? "journal.export.file.bundle" : format.getFilterKey();
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("journal.export.title"));
        String baseName = targets.size() == 1
            ? de.kortty.core.TerminalRecordingService.sanitizeFileName(
                targets.get(0).getTitle() != null ? targets.get(0).getTitle() : "session-journal")
            : "session-journals";
        chooser.setInitialFileName(baseName + extension);
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(I18n.get(filterKey), "*" + extension));
        File target = chooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (target == null) {
            java.util.Arrays.fill(choice.password() != null ? choice.password() : new char[0], '\0');
            return;
        }
        List<java.nio.file.Path> directories = targets.stream()
            .map(SessionJournalMeta::getDirectory)
            .filter(java.util.Objects::nonNull)
            .toList();
        Thread exporter = new Thread(() -> {
            try {
                SessionJournalExportService exportService = new SessionJournalExportService(
                    service(), app != null ? app.getSessionJournalHtmlRenderer() : null);
                SessionJournalExportService.Options options =
                    new SessionJournalExportService.Options(choice.includeScreenshots(), choice.filter());
                SessionJournalExportService.ExportResult result = directories.size() > 1
                    ? exportService.exportArchive(format, directories, target.toPath(), options,
                        choice.password())
                    : exportService.export(format, directories.get(0), target.toPath(), options,
                        choice.password());
                Platform.runLater(() -> {
                    try {
                        new de.kortty.core.AiChatShareService().share(target.toPath());
                    } catch (Exception ignored) {
                        // opening the result is best-effort
                    }
                    // A degraded AI selection or a skipped journal must not pass unnoticed.
                    if (result.aiSelectionWarning() != null && !result.aiSelectionWarning().isBlank()) {
                        showInfo(result.aiSelectionWarning());
                    }
                    if (!result.skippedJournals().isEmpty()) {
                        showInfo(I18n.get("journal.export.skipped", result.skippedJournals().size()));
                    }
                    showInfo(directories.size() > 1
                        ? I18n.get("journal.export.done.multiple", directories.size(), target.getAbsolutePath())
                        : I18n.get("journal.export.done", target.getAbsolutePath()));
                });
            } catch (Exception e) {
                logger.error("Session journal export failed: {}", e.getMessage(), e);
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            } finally {
                if (choice.password() != null) {
                    java.util.Arrays.fill(choice.password(), '\0');
                }
            }
        }, "SessionJournal-Export");
        exporter.setDaemon(true);
        exporter.start();
    }

    private String formatLabel(SessionJournalExportService.Format format) {
        return switch (format) {
            case PDF -> I18n.get("journal.export.pdf");
            case MARKDOWN -> I18n.get("journal.export.markdown");
            case HTML_BUNDLE -> I18n.get("journal.export.htmlBundle");
        };
    }

    // ==== options dialog ====

    private void showOptionsDialog() {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        de.kortty.policy.EffectivePolicy policy = de.kortty.policy.PolicyManager.effective();
        var journalPolicy = policy.sessionJournal();

        Dialog<ButtonType> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(getDialogPane().getScene().getWindow());
        dialog.setTitle(I18n.get("journal.options.title"));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ComboBox<SessionJournalLogFormat> formatCombo = new ComboBox<>();
        formatCombo.getItems().addAll(SessionJournalLogFormat.values());
        formatCombo.setValue(settings.getSessionJournalLogFormat());

        Spinner<Integer> maxLinesSpinner = new Spinner<>(0, 1_000_000, settings.getSessionJournalAiMaxLines());
        maxLinesSpinner.setEditable(true);
        maxLinesSpinner.setPrefWidth(120);

        Spinner<Integer> tokenBudgetSpinner = new Spinner<>(1_000, 10_000_000,
            settings.getSessionJournalAiTokenBudget(), 1_000);
        tokenBudgetSpinner.setEditable(true);
        tokenBudgetSpinner.setPrefWidth(140);
        Label tokenBudgetLabel = new Label(I18n.get("journal.options.tokenBudget"));
        Runnable updateTokenBudgetVisibility = () -> {
            boolean visible = maxLinesSpinner.getValue() != null && maxLinesSpinner.getValue() == 0;
            tokenBudgetLabel.setVisible(visible);
            tokenBudgetLabel.setManaged(visible);
            tokenBudgetSpinner.setVisible(visible);
            tokenBudgetSpinner.setManaged(visible);
        };
        maxLinesSpinner.valueProperty().addListener((obs, old, value) -> updateTokenBudgetVisibility.run());
        updateTokenBudgetVisibility.run();

        CheckBox chunkingCheck = new CheckBox(I18n.get("journal.options.chunking"));
        chunkingCheck.setSelected(settings.isSessionJournalAiChunkingEnabled());
        Label chunkingWarning = new Label(I18n.get("journal.options.chunking.warning"));
        chunkingWarning.setWrapText(true);
        chunkingWarning.setMaxWidth(420);
        chunkingWarning.setStyle("-fx-text-fill: #d29922; -fx-font-size: 0.8462em;");

        CheckBox aiTitleCheck = new CheckBox(I18n.get("journal.options.aiTitle"));
        aiTitleCheck.setSelected(settings.isSessionJournalAiTitleEnabled());

        CheckBox aiScreenshotCheck = new CheckBox(I18n.get("journal.options.aiScreenshotAnalysis"));
        aiScreenshotCheck.setSelected(settings.isSessionJournalAiScreenshotAnalysisEnabled());

        CheckBox semanticSearchCheck = new CheckBox(I18n.get("journal.options.semanticSearch"));
        semanticSearchCheck.setSelected(settings.isSessionJournalSemanticSearchEnabled());
        if (!de.kortty.core.SessionJournalSemanticIndex.embeddingModelConfigured()) {
            semanticSearchCheck.setDisable(true);
            semanticSearchCheck.setTooltip(new Tooltip(
                I18n.get("journal.options.semanticSearch.needsModel")));
        } else {
            semanticSearchCheck.setTooltip(new Tooltip(
                I18n.get("journal.options.semanticSearch.tooltip")));
        }

        // Dedicated journal AI profile; empty = the user's default AI profile.
        ComboBox<de.kortty.model.AiProfile> aiProfileCombo = new ComboBox<>();
        aiProfileCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(de.kortty.model.AiProfile profile) {
                return profile != null && profile.getName() != null
                    ? profile.getName()
                    : I18n.get("settings.journal.defaultProfile");
            }

            @Override
            public de.kortty.model.AiProfile fromString(String value) {
                return null;
            }
        });
        aiProfileCombo.getItems().add(null);
        aiProfileCombo.setValue(null);
        String journalProfileId = settings.getSessionJournalAiProfileId();
        if (settings.getAiProfiles() != null) {
            for (de.kortty.model.AiProfile profile : settings.getAiProfiles()) {
                if (profile != null) {
                    aiProfileCombo.getItems().add(profile);
                    if (journalProfileId != null && journalProfileId.equals(profile.getId())) {
                        aiProfileCombo.setValue(profile);
                    }
                }
            }
        }

        Label managedHint = new Label(I18n.get("journal.options.managed"));
        managedHint.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");
        managedHint.setWrapText(true);
        boolean anyManaged = false;
        if (journalPolicy.logFormat() != null) {
            formatCombo.setDisable(true);
            anyManaged = true;
        }
        if (journalPolicy.aiMaxLines() != null) {
            maxLinesSpinner.setDisable(true);
            tokenBudgetSpinner.setDisable(true);
            anyManaged = true;
        }
        if (Boolean.TRUE.equals(journalPolicy.aiTitle())) {
            aiTitleCheck.setSelected(true);
            aiTitleCheck.setDisable(true);
            anyManaged = true;
        }
        if (journalPolicy.aiScreenshotAnalysis() != null) {
            // Bidirectional, unlike ai-title: true forces the analysis on, false forbids it.
            aiScreenshotCheck.setSelected(journalPolicy.aiScreenshotAnalysis());
            aiScreenshotCheck.setDisable(true);
            anyManaged = true;
        }

        // Opt-in catch-up summarization for journals recorded without AI summaries.
        de.kortty.core.SessionJournalSummaryBackfill backfill =
            new de.kortty.core.SessionJournalSummaryBackfill(
                service(), app.getSessionJournalSummarizer());
        List<SessionJournalMeta> backfillCandidates = backfill.findCandidates(settings);
        Button backfillButton = new Button(
            I18n.get("journal.backfill.button", String.valueOf(backfillCandidates.size())));
        backfillButton.setTooltip(new Tooltip(I18n.get("journal.backfill.tooltip")));
        boolean backfillPossible = !backfillCandidates.isEmpty()
            && policy.sessionJournalAiSummariesAllowed()
            && de.kortty.core.SessionJournalAiSupport.applicationInvoker().isAvailable();
        backfillButton.setDisable(!backfillPossible);
        backfillButton.setOnAction(event -> {
            dialog.close();
            runSummaryBackfill(backfill, backfillCandidates);
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));
        int row = 0;
        grid.add(new Label(I18n.get("journal.options.logFormat")), 0, row);
        grid.add(formatCombo, 1, row++);
        Label maxLinesLabel = new Label(I18n.get("journal.options.maxLines"));
        maxLinesLabel.setWrapText(true);
        maxLinesLabel.setMaxWidth(300);
        grid.add(maxLinesLabel, 0, row);
        grid.add(maxLinesSpinner, 1, row++);
        grid.add(tokenBudgetLabel, 0, row);
        grid.add(tokenBudgetSpinner, 1, row++);
        grid.add(new Label(I18n.get("settings.journal.aiProfile")), 0, row);
        grid.add(aiProfileCombo, 1, row++);
        grid.add(chunkingCheck, 0, row++, 2, 1);
        grid.add(chunkingWarning, 0, row++, 2, 1);
        grid.add(aiTitleCheck, 0, row++, 2, 1);
        grid.add(aiScreenshotCheck, 0, row++, 2, 1);
        grid.add(semanticSearchCheck, 0, row++, 2, 1);
        grid.add(backfillButton, 0, row++, 2, 1);
        if (anyManaged) {
            grid.add(managedHint, 0, row, 2, 1);
        }
        dialog.getDialogPane().setContent(grid);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        if (!formatCombo.isDisabled()) {
            settings.setSessionJournalLogFormat(formatCombo.getValue());
        }
        if (!maxLinesSpinner.isDisabled()) {
            settings.setSessionJournalAiMaxLines(maxLinesSpinner.getValue());
            settings.setSessionJournalAiTokenBudget(tokenBudgetSpinner.getValue());
        }
        settings.setSessionJournalAiChunkingEnabled(chunkingCheck.isSelected());
        if (!aiTitleCheck.isDisabled()) {
            settings.setSessionJournalAiTitleEnabled(aiTitleCheck.isSelected());
        }
        if (!aiScreenshotCheck.isDisabled()) {
            settings.setSessionJournalAiScreenshotAnalysisEnabled(aiScreenshotCheck.isSelected());
        }
        if (!semanticSearchCheck.isDisabled()) {
            settings.setSessionJournalSemanticSearchEnabled(semanticSearchCheck.isSelected());
        }
        de.kortty.model.AiProfile selectedProfile = aiProfileCombo.getValue();
        settings.setSessionJournalAiProfileId(selectedProfile != null ? selectedProfile.getId() : null);
        try {
            app.getGlobalSettingsManager().save();
        } catch (Exception e) {
            logger.error("Could not save session journal options: {}", e.getMessage());
        }
    }

    /**
     * Runs the summary backfill on a background thread behind a small non-modal progress
     * dialog. Closing the dialog cancels after the journal currently being summarized —
     * the summarizer's persisted progress makes a later re-run resume cleanly.
     */
    private void runSummaryBackfill(de.kortty.core.SessionJournalSummaryBackfill backfill,
                                    List<SessionJournalMeta> candidates) {
        Dialog<ButtonType> progressDialog = new Dialog<>();
        DialogThemeHelper.applyTheme(progressDialog);
        progressDialog.initOwner(getDialogPane().getScene().getWindow());
        progressDialog.initModality(Modality.NONE);
        progressDialog.setTitle(I18n.get("journal.backfill.title"));
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(0);
        bar.setPrefWidth(360);
        Label statusLabel = new Label(I18n.get("journal.backfill.title"));
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(360);
        VBox content = new VBox(8, statusLabel, bar);
        content.setPadding(new Insets(10));
        progressDialog.getDialogPane().setContent(content);

        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        progressDialog.setOnHidden(event -> cancelled.set(true));
        progressDialog.show();

        Thread worker = new Thread(() -> {
            de.kortty.core.SessionJournalSummaryBackfill.Outcome outcome = backfill.run(
                candidates,
                progress -> Platform.runLater(() -> {
                    if (progress.total() > 0) {
                        bar.setProgress((double) progress.done() / progress.total());
                    }
                    if (progress.currentTitle() != null) {
                        statusLabel.setText(I18n.get("journal.backfill.progress",
                            String.valueOf(progress.done() + 1),
                            String.valueOf(progress.total()),
                            progress.currentTitle()));
                    }
                }),
                cancelled::get);
            Platform.runLater(() -> {
                refresh();
                if (!progressDialog.isShowing()) {
                    return;
                }
                bar.setProgress(1);
                String summary = I18n.get("journal.backfill.done", String.valueOf(outcome.processed()));
                if (!outcome.failedTitles().isEmpty()) {
                    summary += "\n" + I18n.get("journal.backfill.failed",
                        String.valueOf(outcome.failedTitles().size()),
                        String.join(", ", outcome.failedTitles()));
                }
                statusLabel.setText(summary);
                progressDialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
            });
        }, "SessionJournal-SummaryBackfill");
        worker.setDaemon(true);
        worker.start();
    }

    // ==== helpers ====

    private static long startedEpoch(SessionJournalMeta meta) {
        OffsetDateTime startedAt = meta.getStartedAt();
        return startedAt != null ? startedAt.toInstant().toEpochMilli() : 0;
    }

    private static long startedEpoch(SessionJournalTreeSupport.Node node) {
        OffsetDateTime startedAt = node != null ? node.startedAt() : null;
        return startedAt != null ? startedAt.toInstant().toEpochMilli() : 0;
    }

    private String durationText(SessionJournalMeta meta) {
        if (meta.isLive()) {
            return "● " + I18n.get("journal.manager.running");
        }
        Duration duration = meta.getDuration();
        if (duration == null || meta.getEndedAt() == null) {
            return "";
        }
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m " + duration.toSecondsPart() + "s";
    }

    /** Journal: user@host; run and group: their servers. */
    private static String serverText(SessionJournalTreeSupport.Node node) {
        if (node == null) {
            return "";
        }
        if (node.meta() != null) {
            return serverText(node.meta());
        }
        List<String> servers = node.servers();
        return servers.size() <= 3 ? String.join(", ", servers)
            : String.join(", ", servers.subList(0, 3)) + " +" + (servers.size() - 3);
    }

    private static String serverText(SessionJournalMeta meta) {
        String username = meta.getUsername() != null ? meta.getUsername() : "";
        String host = meta.getHost() != null ? meta.getHost() : "";
        if (host.isEmpty()) {
            return "";
        }
        return username.isEmpty() ? host : username + "@" + host;
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        DialogThemeHelper.applyTheme(alert);
        alert.initOwner(ownerWindow.getStage());
        alert.setTitle(I18n.get("journal.manager.title"));
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.initOwner(ownerWindow.getStage());
        alert.setTitle(I18n.get("journal.manager.title"));
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void restoreGeometry() {
        DialogGeometrySupport.restore(this, settings -> settings.getSessionJournalManagerGeometry());
    }

    private void saveGeometry() {
        if (isHostedInTab()) {
            return; // the pane's window is the main window's stage, not this dialog's geometry
        }
        DialogGeometrySupport.persist(this, (settings, geometry) -> settings.setSessionJournalManagerGeometry(geometry));
    }
}
