package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SessionJournalExportService;
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
import javafx.collections.transformation.SortedList;
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
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
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
    private final TableView<SessionJournalMeta> table;
    private final TextField searchField = new TextField();
    private final CheckBox fulltextCheck = new CheckBox(I18n.get("journal.manager.fulltext"));
    private final TextArea descriptionArea = new TextArea();
    private final Label descriptionStatus = new Label();
    /** Directories matching the latest full-text scan; null = no content filter active. */
    private volatile Set<Path> fulltextMatches;
    private final AtomicInteger fulltextScanGeneration = new AtomicInteger();

    public SessionJournalManagerDialog(MainWindow ownerWindow) {
        this.ownerWindow = ownerWindow;
        this.app = KorTTYApplication.getInstance();
        initModality(Modality.NONE);
        setTitle(I18n.get("journal.manager.title"));
        setResizable(true);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        table = buildTable();
        getDialogPane().setContent(buildRoot());
        getDialogPane().setPrefSize(940, 620);
        getDialogPane().setMinSize(680, 460);
        restoreGeometry();
        setOnCloseRequest(event -> saveGeometry());
        setOnHidden(event -> saveGeometry());

        refresh();
    }

    private VBox buildRoot() {
        searchField.setPromptText(I18n.get("journal.manager.search.prompt"));
        searchField.textProperty().addListener((obs, old, value) -> onSearchChanged());
        fulltextCheck.selectedProperty().addListener((obs, old, value) -> onSearchChanged());
        HBox.setHgrow(searchField, Priority.ALWAYS);
        HBox searchBar = new HBox(8, searchField, fulltextCheck);

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

        var selection = table.getSelectionModel().selectedItemProperty();
        var selectedItems = table.getSelectionModel().getSelectedItems();
        // Open and rename act on exactly one journal; export and delete accept a whole selection.
        var exactlyOne = javafx.beans.binding.Bindings.createBooleanBinding(
            () -> selectedItems.size() == 1, selectedItems);
        var noneSelected = javafx.beans.binding.Bindings.createBooleanBinding(
            selectedItems::isEmpty, selectedItems);
        var anyLiveSelected = javafx.beans.binding.Bindings.createBooleanBinding(
            () -> selectedItems.stream().anyMatch(SessionJournalMeta::isLive), selectedItems);
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
        saveDescriptionButton.disableProperty().bind(selection.isNull());
        saveDescriptionButton.setOnAction(event -> saveDescription());
        descriptionStatus.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");
        selection.addListener((obs, old, meta) -> {
            descriptionArea.setDisable(meta == null);
            descriptionArea.setText(meta != null && meta.getDescription() != null ? meta.getDescription() : "");
            descriptionStatus.setText("");
        });
        Label descriptionLabel = new Label(I18n.get("journal.manager.description"));
        HBox descriptionBar = new HBox(8, saveDescriptionButton, descriptionStatus);
        descriptionBar.setStyle("-fx-alignment: center-left;");
        VBox descriptionBox = new VBox(4, descriptionLabel, descriptionArea, descriptionBar);

        VBox root = new VBox(10, searchBar, table, buttonBar, descriptionBox);
        root.setPadding(new Insets(6));
        VBox.setVgrow(table, Priority.ALWAYS);
        return root;
    }

    private TableView<SessionJournalMeta> buildTable() {
        TableView<SessionJournalMeta> view = new TableView<>();
        view.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // Several journals can be exported into one archive or deleted in one go.
        view.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        view.setPlaceholder(new Label(I18n.get("journal.manager.empty")));

        // Sorted on the epoch value, never on the dd.MM.yyyy display string.
        TableColumn<SessionJournalMeta, Long> startedColumn =
            new TableColumn<>(I18n.get("journal.manager.column.started"));
        startedColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(startedEpoch(cell.getValue())));
        startedColumn.setCellFactory(column -> new TableCell<>() {
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

        TableColumn<SessionJournalMeta, String> durationColumn =
            new TableColumn<>(I18n.get("journal.manager.column.duration"));
        durationColumn.setCellValueFactory(cell -> new SimpleStringProperty(durationText(cell.getValue())));
        durationColumn.setMinWidth(90);

        TableColumn<SessionJournalMeta, String> connectionColumn =
            new TableColumn<>(I18n.get("journal.manager.column.connection"));
        connectionColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            cell.getValue().getConnectionName() != null ? cell.getValue().getConnectionName() : ""));
        connectionColumn.setMinWidth(140);

        TableColumn<SessionJournalMeta, String> serverColumn =
            new TableColumn<>(I18n.get("journal.manager.column.server"));
        serverColumn.setCellValueFactory(cell -> new SimpleStringProperty(serverText(cell.getValue())));
        serverColumn.setMinWidth(160);

        TableColumn<SessionJournalMeta, String> titleColumn =
            new TableColumn<>(I18n.get("journal.manager.column.title"));
        titleColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            cell.getValue().getTitle() != null ? cell.getValue().getTitle() : ""));
        titleColumn.setMinWidth(200);

        TableColumn<SessionJournalMeta, Long> entriesColumn =
            new TableColumn<>(I18n.get("journal.manager.column.entries"));
        entriesColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().getLogEntryCount()));
        entriesColumn.setMinWidth(70);

        view.getColumns().addAll(List.of(
            startedColumn, durationColumn, connectionColumn, serverColumn, titleColumn, entriesColumn));

        SortedList<SessionJournalMeta> sorted = new SortedList<>(filteredJournals);
        sorted.comparatorProperty().bind(view.comparatorProperty());
        view.setItems(sorted);
        startedColumn.setSortType(TableColumn.SortType.DESCENDING);
        view.getSortOrder().add(startedColumn);

        view.setRowFactory(tableView -> {
            TableRow<SessionJournalMeta> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    ownerWindow.openSessionJournal(row.getItem());
                }
            });
            return row;
        });
        return view;
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
        filteredJournals.setPredicate(meta -> {
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
            || containsIgnoreCase(meta.getConnectionName(), query)
            || containsIgnoreCase(meta.getHost(), query)
            || containsIgnoreCase(meta.getUsername(), query)
            || containsIgnoreCase(meta.getDescription(), query);
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
        onSearchChanged();
    }

    private SessionJournalMeta selected() {
        return table.getSelectionModel().getSelectedItem();
    }

    /** A stable copy of the selection — the live list changes while we work through it. */
    private List<SessionJournalMeta> selectedJournals() {
        return List.copyOf(table.getSelectionModel().getSelectedItems());
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
        de.kortty.model.AiProfile selectedProfile = aiProfileCombo.getValue();
        settings.setSessionJournalAiProfileId(selectedProfile != null ? selectedProfile.getId() : null);
        try {
            app.getGlobalSettingsManager().save();
        } catch (Exception e) {
            logger.error("Could not save session journal options: {}", e.getMessage());
        }
    }

    // ==== helpers ====

    private static long startedEpoch(SessionJournalMeta meta) {
        OffsetDateTime startedAt = meta.getStartedAt();
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
