package de.kortty.ui;

import de.kortty.core.ConfigurationManager;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.LogTimeRange;
import de.kortty.core.SessionJournalExportFilter;
import de.kortty.core.TerminalLogCatalog;
import de.kortty.core.TerminalLogExporter;
import de.kortty.core.TerminalLogFile;
import de.kortty.core.TerminalLogFilter;
import de.kortty.core.TerminalLogFiles;
import de.kortty.core.TerminalLogNaming;
import de.kortty.model.TerminalLogConfig;
import de.kortty.model.TerminalLogKnownFolder;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Logfile Manager: every terminal log korTTY wrote, across the connections' log folders, the
 * default folder and the folders the user added or the loggers remembered.
 *
 * <p>Reading the folders, deleting and exporting all run on daemon threads, because a log folder can
 * hold gigabytes and a network share can stall. Filtering is pure in-memory work over the listing
 * that is already loaded, so it runs on every keystroke-debounced change without touching the disk.
 * Every scan is numbered, so a slow scan that finishes after a newer one cannot overwrite its result.</p>
 *
 * <p>Live files (a logger still has them open) are listed, shown with a pill, and never deleted or
 * exported: a copy of a file that is still being written would stop mid-entry.</p>
 */
public final class LogfileManagerDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(LogfileManagerDialog.class);

    private static final double MIB = 1024.0 * 1024.0;
    private static final DateTimeFormatter STARTED_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Duration SEARCH_DEBOUNCE = Duration.millis(150);
    /** How many failing files a message lists before it says nothing more about them. */
    private static final int PREVIEW_LIMIT = 5;
    /** How many existing targets the overwrite question lists by full path. */
    private static final int OVERWRITE_PREVIEW = 10;
    private static final String DASH = "—";
    private static final String DOT = "·";
    private static final String ELLIPSIS = "…";

    private final GlobalSettingsManager settings;
    private final ConfigurationManager configs;
    private final Consumer<TerminalLogFile> openViewer;
    private final Consumer<Path> openFolder;

    private final ObservableList<TerminalLogFile> source = FXCollections.observableArrayList();
    private final FilteredList<TerminalLogFile> filtered = new FilteredList<>(source, file -> true);
    private final SortedList<TerminalLogFile> sorted = new SortedList<>(filtered);

    private final Button refreshButton = new Button(I18n.get("logfile.manager.refresh"));
    private final Button viewButton = new Button(I18n.get("logfile.manager.view"));
    private final Button exportButton = new Button(I18n.get("logfile.manager.export"));
    private final Button deleteButton = new Button(I18n.get("logfile.manager.delete"));
    private final Button openFolderButton = new Button(I18n.get("logfile.manager.openFolder"));
    private final Button addFolderButton = new Button(I18n.get("logfile.manager.addFolder"));
    private final ProgressIndicator scanIndicator = new ProgressIndicator();

    private final TextField searchField = new TextField();
    private final PauseTransition searchDebounce = new PauseTransition(SEARCH_DEBOUNCE);
    private final DatePicker fromDate = new DatePicker();
    private final TextField fromTime = new TextField();
    private final DatePicker toDate = new DatePicker();
    private final TextField toTime = new TextField();
    private final ComboBox<TerminalLogConfig.LogFormat> typeCombo = new ComboBox<>();
    private final ComboBox<TerminalLogNaming.Compression> compressionCombo = new ComboBox<>();
    private final Button clearFilters = new Button(I18n.get("logfile.manager.filter.clear"));

    private final TableView<TerminalLogFile> table = new TableView<>();
    private final Label placeholder = new Label();
    private final Label statusLabel = new Label();
    private TableColumn<TerminalLogFile, Path> folderColumn;

    /** Incremented per scan; a finished scan whose number is no longer the newest is dropped. */
    private int scanSeq;
    private boolean scanning;
    private boolean operationRunning;
    private List<TerminalLogCatalog.Folder> folders = List.of();
    private List<Path> logFolders = List.of();
    /** The time window of the current filter, offered as the preset of an export. */
    private LogTimeRange currentRange;
    /** A message to show once with the next listing, such as what a delete just did. */
    private String notice = "";

    /**
     * The result of the filter fields: the filter itself, and which of the two time fields could not
     * be used. An unusable time is ignored for filtering, and its field is marked for the user.
     */
    record FilterState(TerminalLogFilter filter, boolean fromTimeInvalid, boolean toTimeInvalid) {
    }

    /** Live files split off a selection: the ones that may be acted on, and the ones skipped. */
    record Partition(List<TerminalLogFile> actionable, List<TerminalLogFile> live) {
    }

    private record ScanResult(List<TerminalLogCatalog.Folder> folders, List<TerminalLogFile> files) {
    }

    private record ExportTarget(Path directory, List<Path> paths) {
    }

    private record Preflight(boolean swept, List<Path> existing) {
    }

    /**
     * @param openViewer receives the file to show in the log viewer
     * @param openFolder receives a folder to reveal in the file manager
     */
    public LogfileManagerDialog(GlobalSettingsManager settings, ConfigurationManager configs,
                                Consumer<TerminalLogFile> openViewer, Consumer<Path> openFolder) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.openViewer = Objects.requireNonNull(openViewer, "openViewer");
        this.openFolder = Objects.requireNonNull(openFolder, "openFolder");

        setTitle(I18n.get("logfile.manager.title"));
        setHeaderText(I18n.get("logfile.manager.header"));
        setResizable(true);
        initModality(Modality.NONE);
        getDialogPane().setPrefSize(1040, 620);
        getDialogPane().setMinSize(760, 420);
        getDialogPane().setContent(buildContent());
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        applyFilter();
        updateActionState();
        // Started here rather than on DIALOG_SHOWN: a hosted dialog (a main-window tab) never fires that.
        refresh();
    }

    // ---- Layout ----

    private Node buildContent() {
        VBox root = new VBox(8, buildActionBar(), buildFilterBar(), buildTable(), statusLabel);
        root.setPadding(new Insets(10));
        VBox.setVgrow(table, Priority.ALWAYS);
        return root;
    }

    private Node buildActionBar() {
        ButtonIcons.apply(refreshButton, ButtonIcons.REFRESH);
        ButtonIcons.apply(viewButton, ButtonIcons.OPEN);
        ButtonIcons.apply(exportButton, ButtonIcons.DOWNLOAD);
        ButtonIcons.apply(deleteButton, ButtonIcons.DELETE);
        ButtonIcons.apply(openFolderButton, ButtonIcons.FOLDER);
        ButtonIcons.apply(addFolderButton, ButtonIcons.ADD);
        refreshButton.setOnAction(event -> refresh());
        viewButton.setOnAction(event -> viewSelected());
        exportButton.setOnAction(event -> exportSelected());
        deleteButton.setOnAction(event -> deleteSelected());
        openFolderButton.setOnAction(event -> openFolderOfSelection());
        addFolderButton.setOnAction(event -> addFolder());

        scanIndicator.setMaxSize(20, 20);
        scanIndicator.setVisible(false);
        scanIndicator.setManaged(false);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, refreshButton, viewButton, exportButton, deleteButton, openFolderButton,
            addFolderButton, spacer, scanIndicator);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private Node buildFilterBar() {
        searchField.setPrefColumnCount(18);
        searchField.setPromptText(I18n.get("logfile.manager.filter.text.prompt"));
        fromDate.setPrefWidth(140);
        toDate.setPrefWidth(140);
        fromTime.setPrefWidth(62);
        toTime.setPrefWidth(62);
        fromTime.setPromptText(I18n.get("logfile.manager.filter.time.prompt"));
        toTime.setPromptText(I18n.get("logfile.manager.filter.time.prompt"));

        // A null entry is "all": the combo shows its prompt for it, and the list shows "All" for it.
        typeCombo.getItems().add(null);
        typeCombo.getItems().addAll(TerminalLogConfig.LogFormat.values());
        typeCombo.setConverter(new StringConverter<TerminalLogConfig.LogFormat>() {
            @Override
            public String toString(TerminalLogConfig.LogFormat format) {
                return format == null ? I18n.get("logfile.manager.filter.all") : format.getDisplayName();
            }

            @Override
            public TerminalLogConfig.LogFormat fromString(String text) {
                return null;
            }
        });
        typeCombo.setPromptText(I18n.get("logfile.manager.filter.all"));
        // The default cell would draw the null entry as an empty row; it has to read "All" like the prompt.
        typeCombo.setCellFactory(list -> new ListCell<TerminalLogConfig.LogFormat>() {
            @Override
            protected void updateItem(TerminalLogConfig.LogFormat format, boolean empty) {
                super.updateItem(format, empty);
                setText(empty ? null : format == null ? I18n.get("logfile.manager.filter.all") : format.getDisplayName());
            }
        });

        compressionCombo.getItems().add(null);
        compressionCombo.getItems().addAll(TerminalLogNaming.Compression.values());
        compressionCombo.setConverter(new StringConverter<TerminalLogNaming.Compression>() {
            @Override
            public String toString(TerminalLogNaming.Compression compression) {
                return compression == null ? I18n.get("logfile.manager.filter.all") : compressionLabel(compression);
            }

            @Override
            public TerminalLogNaming.Compression fromString(String text) {
                return null;
            }
        });
        compressionCombo.setPromptText(I18n.get("logfile.manager.filter.all"));
        compressionCombo.setCellFactory(list -> new ListCell<TerminalLogNaming.Compression>() {
            @Override
            protected void updateItem(TerminalLogNaming.Compression compression, boolean empty) {
                super.updateItem(compression, empty);
                setText(empty ? null : compression == null ? I18n.get("logfile.manager.filter.all")
                    : compressionLabel(compression));
            }
        });

        searchDebounce.setOnFinished(event -> applyFilter());
        searchField.textProperty().addListener((obs, old, value) -> searchDebounce.playFromStart());
        fromDate.valueProperty().addListener((obs, old, value) -> applyFilter());
        toDate.valueProperty().addListener((obs, old, value) -> applyFilter());
        fromTime.textProperty().addListener((obs, old, value) -> applyFilter());
        toTime.textProperty().addListener((obs, old, value) -> applyFilter());
        typeCombo.valueProperty().addListener((obs, old, value) -> applyFilter());
        compressionCombo.valueProperty().addListener((obs, old, value) -> applyFilter());
        clearFilters.setOnAction(event -> clearFilters());

        FlowPane bar = new FlowPane(8, 6,
            labeled(I18n.get("logfile.manager.filter.text"), searchField),
            labeled(I18n.get("logfile.manager.filter.from"), fromDate, fromTime),
            labeled(I18n.get("logfile.manager.filter.to"), toDate, toTime),
            labeled(I18n.get("logfile.manager.filter.type"), typeCombo),
            labeled(I18n.get("logfile.manager.filter.compression"), compressionCombo),
            clearFilters);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private static HBox labeled(String text, Node... nodes) {
        HBox box = new HBox(6);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(new Label(text));
        box.getChildren().addAll(nodes);
        return box;
    }

    private Node buildTable() {
        table.setItems(sorted);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        // One fixed row height for every row, derived from the UI font so larger fonts get room.
        table.setFixedCellSize(rowHeight());
        placeholder.setWrapText(true);
        table.setPlaceholder(placeholder);

        // Every column sorts on a typed value, never on its display string.
        TableColumn<TerminalLogFile, String> nameColumn = new TableColumn<>(I18n.get("logfile.manager.column.name"));
        nameColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().name()));
        nameColumn.setCellFactory(column -> new NameCell());
        nameColumn.setComparator(String.CASE_INSENSITIVE_ORDER);
        nameColumn.setMinWidth(300);

        TableColumn<TerminalLogFile, LocalDateTime> startedColumn = LogfileManagerDialog.<LocalDateTime>typedColumn(
            I18n.get("logfile.manager.column.started"),
            TerminalLogFile::startedAt,
            time -> time != null ? STARTED_FORMAT.format(time) : "",
            Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));
        startedColumn.setMinWidth(150);

        TableColumn<TerminalLogFile, Long> sizeColumn = LogfileManagerDialog.<Long>typedColumn(
            I18n.get("logfile.manager.column.size"),
            TerminalLogFile::sizeBytes,
            bytes -> formatSize(bytes),
            Comparator.<Long>naturalOrder());
        sizeColumn.setMinWidth(110);
        withHeaderTooltip(sizeColumn, "logfile.manager.column.size.tooltip");

        TableColumn<TerminalLogFile, TerminalLogConfig.LogFormat> typeColumn =
            LogfileManagerDialog.<TerminalLogConfig.LogFormat>typedColumn(
                I18n.get("logfile.manager.column.type"),
                TerminalLogFile::format,
                format -> format != null ? format.getDisplayName() : "",
                Comparator.nullsLast(Comparator.<TerminalLogConfig.LogFormat>naturalOrder()));
        typeColumn.setMinWidth(90);

        TableColumn<TerminalLogFile, TerminalLogNaming.Compression> compressionColumn =
            LogfileManagerDialog.<TerminalLogNaming.Compression>typedColumn(
                I18n.get("logfile.manager.column.compression"),
                TerminalLogFile::compression,
                LogfileManagerDialog::compressionLabel,
                Comparator.nullsLast(Comparator.<TerminalLogNaming.Compression>naturalOrder()));
        compressionColumn.setMinWidth(110);

        TableColumn<TerminalLogFile, LocalDate> deletableColumn = LogfileManagerDialog.<LocalDate>typedColumn(
            I18n.get("logfile.manager.column.deletableFrom"),
            TerminalLogFile::deletableFrom,
            day -> day != null ? DATE_FORMAT.format(day) : DASH,
            Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()));
        deletableColumn.setMinWidth(120);
        withHeaderTooltip(deletableColumn, "logfile.manager.column.deletableFrom.tooltip");

        folderColumn = LogfileManagerDialog.<Path>typedColumn(
            I18n.get("logfile.manager.column.folder"),
            TerminalLogFile::directory,
            dir -> dir != null ? dir.toString() : "",
            Comparator.nullsLast(Comparator.<Path>naturalOrder()));
        folderColumn.setMinWidth(220);
        // Only useful when the files really come from several folders; hidden for the common case.
        folderColumn.setVisible(false);

        table.getColumns().setAll(List.of(nameColumn, startedColumn, sizeColumn, typeColumn, compressionColumn,
            deletableColumn, folderColumn));
        startedColumn.setSortType(TableColumn.SortType.DESCENDING);
        table.getSortOrder().add(startedColumn);

        table.getSelectionModel().getSelectedItems().addListener(
            (ListChangeListener<TerminalLogFile>) change -> updateActionState());
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE) {
                deleteSelected();
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER) {
                viewSelected();
                event.consume();
            }
        });
        table.setRowFactory(tableView -> {
            TableRow<TerminalLogFile> row = new TableRow<>();
            ContextMenu menu = buildRowMenu(row);
            row.itemProperty().addListener((obs, old, file) -> row.setContextMenu(file != null ? menu : null));
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && row.getItem() != null) {
                    viewFile(row.getItem());
                }
            });
            return row;
        });
        return table;
    }

    /** Builds a column that shows {@code text(value)} and sorts on {@code value} with {@code order}. */
    private static <T> TableColumn<TerminalLogFile, T> typedColumn(String header,
                                                                   Function<TerminalLogFile, T> value,
                                                                   Function<T, String> text,
                                                                   Comparator<T> order) {
        TableColumn<TerminalLogFile, T> column = new TableColumn<>(header);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        column.setCellFactory(cellColumn -> new TableCell<TerminalLogFile, T>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : text.apply(item));
            }
        });
        column.setComparator(order);
        return column;
    }

    /**
     * Moves a column's text into a label that carries a tooltip. A header tooltip is the only place to
     * explain what "Size" or "Deletable from" means without adding a second line of text to the table.
     */
    private static void withHeaderTooltip(TableColumn<?, ?> column, String tooltipKey) {
        Label label = new Label(column.getText());
        label.setTooltip(new Tooltip(I18n.get(tooltipKey)));
        column.setText(null);
        column.setGraphic(label);
    }

    private static double rowHeight() {
        return Math.max(24, Math.ceil(javafx.scene.text.Font.getDefault().getSize() * 2.1));
    }

    private ContextMenu buildRowMenu(TableRow<TerminalLogFile> row) {
        MenuItem view = new MenuItem(I18n.get("logfile.manager.view"));
        view.setOnAction(event -> viewSelected());
        MenuItem export = new MenuItem(I18n.get("logfile.manager.export"));
        export.setOnAction(event -> exportSelected());
        MenuItem delete = new MenuItem(I18n.get("logfile.manager.delete"));
        delete.setOnAction(event -> deleteSelected());
        MenuItem openFolderItem = new MenuItem(I18n.get("logfile.manager.openFolder"));
        openFolderItem.setOnAction(event -> openFolderOfSelection());
        MenuItem forget = new MenuItem(I18n.get("logfile.manager.forgetFolder"));
        forget.setOnAction(event -> forgetFolderOfSelection());

        ContextMenu menu = new ContextMenu(view, export, delete, new SeparatorMenuItem(), openFolderItem, forget);
        menu.setOnShowing(event -> {
            // Right-clicking a row acts on that row, unless it is already part of the selection.
            if (row.getIndex() >= 0 && !table.getSelectionModel().isSelected(row.getIndex())) {
                table.getSelectionModel().clearAndSelect(row.getIndex());
            }
            boolean idle = isIdle();
            List<TerminalLogFile> selected = selectedFiles();
            boolean actionable = selected.stream().anyMatch(file -> !file.live());
            view.setDisable(selected.isEmpty());
            export.setDisable(!idle || !actionable);
            delete.setDisable(!idle || !actionable);
            openFolderItem.setDisable(!idle || selected.isEmpty());
            forget.setDisable(!idle);
            forget.setVisible(manualFolderOf(firstSelected()) != null);
        });
        return menu;
    }

    // ---- Filtering ----

    /**
     * Turns the filter fields into a filter. A time field that does not parse is ignored; so is a
     * range whose start is after its end, because nothing could match it and the list would just go
     * empty. Both are reported through the flags so the fields can be marked.
     */
    static FilterState buildFilter(String text, LocalDate fromDate, String fromTime, LocalDate toDate,
                                   String toTime, TerminalLogConfig.LogFormat format,
                                   TerminalLogNaming.Compression compression) {
        LocalTime from = SessionJournalExportFilter.parseTimeOfDay(fromTime);
        LocalTime to = SessionJournalExportFilter.parseTimeOfDay(toTime);
        boolean fromBroken = !isBlank(fromTime) && from == null;
        boolean toBroken = !isBlank(toTime) && to == null;
        LogTimeRange range = LogTimeRange.of(fromDate, from, toDate, to);
        boolean rangeBroken = range.isInvalid();
        if (rangeBroken) {
            range = null;
        }
        TerminalLogFilter filter = new TerminalLogFilter(text, range, format, compression);
        return new FilterState(filter, fromBroken || rangeBroken, toBroken || rangeBroken);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private void applyFilter() {
        FilterState state = buildFilter(searchField.getText(), fromDate.getValue(), fromTime.getText(),
            toDate.getValue(), toTime.getText(), typeCombo.getValue(), compressionCombo.getValue());
        currentRange = state.filter().range();
        filtered.setPredicate(state.filter()::test);
        markTimeField(fromTime, state.fromTimeInvalid());
        markTimeField(toTime, state.toTimeInvalid());
        updateStatus();
        updatePlaceholder();
    }

    private void clearFilters() {
        searchField.clear();
        fromDate.setValue(null);
        fromTime.clear();
        toDate.setValue(null);
        toTime.clear();
        typeCombo.setValue(null);
        compressionCombo.setValue(null);
        applyFilter();
    }

    private static void markTimeField(TextField field, boolean invalid) {
        // A literal red: the Normal design has no AtlantaFX colour tokens (same tone as the journal badges).
        field.setStyle(invalid ? "-fx-border-color: rgba(239, 68, 68, 0.9); -fx-border-radius: 3;" : "");
        field.setTooltip(invalid ? new Tooltip(I18n.get("logfile.manager.filter.timeInvalid")) : null);
    }

    private void updateStatus() {
        long bytes = 0;
        long live = 0;
        for (TerminalLogFile file : sorted) {
            bytes += file.sizeBytes();
            if (file.live()) {
                live++;
            }
        }
        String summary = I18n.get("logfile.manager.status", sorted.size(), source.size(),
            String.format(Locale.ROOT, "%.1f", bytes / MIB), live);
        statusLabel.setText(notice.isEmpty() ? summary : notice + " " + DOT + " " + summary);
    }

    private void updatePlaceholder() {
        String text;
        if (scanning) {
            text = I18n.get("logfile.manager.scanning");
        } else if (source.isEmpty()) {
            text = I18n.get("logfile.manager.empty");
        } else {
            text = I18n.get("logfile.manager.noMatch");
        }
        placeholder.setText(text);
    }

    // ---- Listing ----

    /**
     * Reads the folders and their files on a daemon thread. Only the newest scan may update the list;
     * an older one that finishes late is discarded, and the busy state stays until the newest is done.
     */
    private void refresh() {
        int seq = ++scanSeq;
        scanning = true;
        updateActionState();
        updatePlaceholder();
        Task<ScanResult> task = new Task<>() {
            @Override
            protected ScanResult call() throws Exception {
                List<TerminalLogCatalog.Folder> found = TerminalLogCatalog.folders(
                    configs.getConnections(), settings.getSettings().getTerminalLogKnownFolders());
                List<TerminalLogFile> files = TerminalLogCatalog.scan(found, LocalDate.now(), Instant.now());
                return new ScanResult(found, files);
            }
        };
        task.setOnSucceeded(event -> {
            if (seq != scanSeq) {
                return;
            }
            scanning = false;
            ScanResult result = task.getValue();
            folders = result.folders();
            logFolders = folders.stream().map(TerminalLogCatalog.Folder::path).toList();
            source.setAll(result.files());
            long directories = source.stream().map(TerminalLogFile::directory).distinct().count();
            folderColumn.setVisible(directories > 1);
            applyFilter();
            notice = "";
            updateActionState();
        });
        task.setOnFailed(event -> {
            if (seq != scanSeq) {
                return;
            }
            scanning = false;
            logger.warn("The terminal log folders could not be read", task.getException());
            statusLabel.setText(I18n.get("logfile.manager.scanFailed", messageOf(task.getException())));
            updatePlaceholder();
            updateActionState();
        });
        runInBackground(task, "LogfileManagerScan");
    }

    // ---- Selection and state ----

    private boolean isIdle() {
        return !scanning && !operationRunning;
    }

    private void setBusy(boolean busy) {
        operationRunning = busy;
        updateActionState();
    }

    private void updateActionState() {
        boolean idle = isIdle();
        List<TerminalLogFile> selected = selectedFiles();
        boolean any = !selected.isEmpty();
        boolean actionable = selected.stream().anyMatch(file -> !file.live());
        refreshButton.setDisable(!idle);
        addFolderButton.setDisable(!idle);
        viewButton.setDisable(!idle || !any);
        exportButton.setDisable(!idle || !actionable);
        deleteButton.setDisable(!idle || !actionable);
        openFolderButton.setDisable(!idle || !any);
        scanIndicator.setVisible(scanning);
        scanIndicator.setManaged(scanning);
    }

    private List<TerminalLogFile> selectedFiles() {
        return new ArrayList<>(table.getSelectionModel().getSelectedItems());
    }

    private TerminalLogFile firstSelected() {
        List<TerminalLogFile> selected = selectedFiles();
        return selected.isEmpty() ? null : selected.get(0);
    }

    /**
     * The listing entry of this file's folder, when that folder is listed only because the user added
     * it by hand. Only then does "Forget folder" make sense: a folder a connection still writes into
     * would simply reappear.
     */
    private TerminalLogCatalog.Folder manualFolderOf(TerminalLogFile file) {
        if (file == null || file.directory() == null) {
            return null;
        }
        return folders.stream()
            .filter(folder -> folder.path().equals(file.directory()))
            .filter(TerminalLogCatalog.Folder::manual)
            .findFirst()
            .orElse(null);
    }

    static Partition partitionLive(List<TerminalLogFile> files) {
        List<TerminalLogFile> actionable = new ArrayList<>();
        List<TerminalLogFile> live = new ArrayList<>();
        for (TerminalLogFile file : files) {
            if (file == null) {
                continue;
            }
            (file.live() ? live : actionable).add(file);
        }
        return new Partition(List.copyOf(actionable), List.copyOf(live));
    }

    // ---- Actions ----

    private void viewSelected() {
        TerminalLogFile file = firstSelected();
        if (file != null) {
            viewFile(file);
        }
    }

    private void viewFile(TerminalLogFile file) {
        openViewer.accept(file);
    }

    private void openFolderOfSelection() {
        TerminalLogFile file = firstSelected();
        if (file != null) {
            openFolder.accept(file.directory());
        }
    }

    private void addFolder() {
        if (!isIdle()) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("logfile.manager.addFolder.title"));
        File chosen = chooser.showDialog(ownerWindow());
        if (chosen == null) {
            return;
        }
        rememberManually(chosen.toPath().toAbsolutePath().normalize());
        refresh();
    }

    /**
     * Remembers a folder the user picked and marks it as added by hand, which is what lets the manager
     * offer "Forget folder" for it. A folder that is already remembered keeps its retention: only the
     * manual flag changes, because re-adding it must not quietly reset a sweep the user configured.
     */
    private void rememberManually(Path dir) {
        if (!hasEntry(dir)) {
            // A new entry gets no retention: the user, not a logger, put this folder in the list.
            TerminalLogCatalog.rememberFolder(settings, dir, 0);
        }
        for (TerminalLogKnownFolder entry : settings.getSettings().getTerminalLogKnownFolders()) {
            if (entry != null && sameFolder(entry.getPath(), dir)) {
                entry.setManual(true);
            }
        }
        saveInBackground();
    }

    private void forgetFolderOfSelection() {
        if (!isIdle()) {
            return;
        }
        TerminalLogCatalog.Folder folder = manualFolderOf(firstSelected());
        if (folder == null) {
            return;
        }
        // The display path is the one the entry was stored under; the real path may be a symlink target.
        TerminalLogCatalog.forgetFolder(settings, folder.display());
        refresh();
    }

    private boolean hasEntry(Path dir) {
        return settings.getSettings().getTerminalLogKnownFolders().stream()
            .anyMatch(entry -> entry != null && sameFolder(entry.getPath(), dir));
    }

    private static boolean sameFolder(String stored, Path dir) {
        if (stored == null || stored.isBlank()) {
            return false;
        }
        try {
            return Path.of(stored).toAbsolutePath().normalize().equals(dir);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private void saveInBackground() {
        settings.saveAsync().exceptionally(failure -> {
            logger.warn("Could not save the remembered terminal log folders: {}", failure.getMessage());
            return null;
        });
    }

    private void deleteSelected() {
        if (!isIdle()) {
            return;
        }
        Partition parts = partitionLive(selectedFiles());
        if (!parts.live().isEmpty()) {
            info(I18n.get("logfile.manager.liveSkipped", parts.live().size()));
        }
        List<TerminalLogFile> files = parts.actionable();
        if (files.isEmpty()) {
            return;
        }
        long plain = files.stream().filter(file -> file.compression() == TerminalLogNaming.Compression.NONE).count();
        String content = I18n.get("logfile.manager.delete.content")
            + (plain > 0 ? "\n\n" + I18n.get("logfile.manager.delete.plainWarning", plain) : "");
        Optional<ButtonType> answer = newAlert(Alert.AlertType.CONFIRMATION,
            I18n.get("logfile.manager.delete.title"),
            I18n.get("logfile.manager.delete.header", files.size()),
            content).showAndWait();
        if (answer.orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        Task<List<TerminalLogFiles.DeleteOutcome>> task = new Task<>() {
            @Override
            protected List<TerminalLogFiles.DeleteOutcome> call() {
                return TerminalLogFiles.delete(files, Instant.now());
            }
        };
        setBusy(true);
        task.setOnSucceeded(event -> {
            setBusy(false);
            List<TerminalLogFiles.DeleteOutcome> outcomes = task.getValue();
            long deleted = outcomes.stream()
                .filter(outcome -> outcome.status() == TerminalLogFiles.DeleteStatus.DELETED)
                .count();
            List<TerminalLogFiles.DeleteOutcome> failed = outcomes.stream()
                .filter(outcome -> outcome.status() != TerminalLogFiles.DeleteStatus.DELETED
                    && outcome.status() != TerminalLogFiles.DeleteStatus.MISSING)
                .toList();
            notice = I18n.get("logfile.manager.delete.done", deleted);
            refresh();
            if (!failed.isEmpty()) {
                showError(I18n.get("logfile.manager.delete.failed", failed.size(), deleteFailureLines(failed)));
            }
        });
        task.setOnFailed(event -> {
            setBusy(false);
            refresh();
            showError(I18n.get("logfile.manager.delete.failed", files.size(), messageOf(task.getException())));
        });
        runInBackground(task, "LogfileDelete");
    }

    private static String deleteFailureLines(List<TerminalLogFiles.DeleteOutcome> failed) {
        return failed.stream()
            .limit(PREVIEW_LIMIT)
            .map(outcome -> outcome.file().getFileName() + ": "
                + (outcome.error() != null ? outcome.error() : outcome.status().name()))
            .collect(Collectors.joining("\n"));
    }

    private void exportSelected() {
        if (!isIdle()) {
            return;
        }
        Partition parts = partitionLive(selectedFiles());
        if (!parts.live().isEmpty()) {
            info(I18n.get("logfile.manager.liveSkipped", parts.live().size()));
        }
        if (parts.actionable().isEmpty()) {
            return;
        }
        exportFiles(parts.actionable(), currentRange);
    }

    /**
     * The export steps: the options, the destination, then a background check for a copy the sweep
     * would take and for targets that already exist. A swept copy sends the user back to the options,
     * because only a different format or folder fixes it.
     */
    private void exportFiles(List<TerminalLogFile> files, LogTimeRange preset) {
        Optional<LogfileExportDialog.ExportChoice> asked =
            new LogfileExportDialog(ownerWindow(), files, preset).showAndWait();
        if (asked.isEmpty()) {
            return;
        }
        LogfileExportDialog.ExportChoice choice = asked.get();
        ExportTarget target = chooseTarget(files, choice);
        if (target == null) {
            return;
        }
        List<Path> folders = logFolders;
        Task<Preflight> check = new Task<>() {
            @Override
            protected Preflight call() {
                boolean swept = choice.range() == null && target.paths().stream()
                    .anyMatch(path -> TerminalLogExporter.wouldBeSwept(path, folders));
                List<Path> existing = target.paths().stream().filter(Files::exists).toList();
                return new Preflight(swept, existing);
            }
        };
        setBusy(true);
        check.setOnSucceeded(event -> {
            setBusy(false);
            Preflight preflight = check.getValue();
            if (preflight.swept()) {
                showError(I18n.get("logfile.export.wouldBeSwept"));
                exportFiles(files, preset);
                return;
            }
            if (!preflight.existing().isEmpty() && !confirmReplace(preflight.existing())) {
                return;
            }
            startExport(files, choice, target, !preflight.existing().isEmpty());
        });
        check.setOnFailed(event -> {
            setBusy(false);
            showError(messageOf(check.getException()));
        });
        runInBackground(check, "LogfileExportCheck");
    }

    /** Asks for the destination: a save name for one file, a folder for several. Null when cancelled. */
    private ExportTarget chooseTarget(List<TerminalLogFile> files, LogfileExportDialog.ExportChoice choice) {
        Window owner = ownerWindow();
        TerminalLogNaming.Compression format = choice.format();
        LogTimeRange range = choice.range();
        if (files.size() == 1) {
            TerminalLogFile file = files.get(0);
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.get("logfile.export.saveAs"));
            chooser.setInitialFileName(TerminalLogExporter.targetName(file.name(), format, range));
            chooser.setInitialDirectory(initialDirectory(file.directory()));
            chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(compressionLabel(format), "*" + format.suffix));
            File chosen = chooser.showSaveDialog(owner);
            if (chosen == null) {
                return null;
            }
            Path target = withSuffix(chosen.toPath().toAbsolutePath().normalize(), format.suffix);
            Path directory = target.getParent() != null ? target.getParent() : initialDirectory(null).toPath();
            return new ExportTarget(directory, List.of(target));
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("logfile.export.chooseFolder"));
        chooser.setInitialDirectory(initialDirectory(files.get(0).directory()));
        File chosen = chooser.showDialog(owner);
        if (chosen == null) {
            return null;
        }
        Path directory = chosen.toPath();
        List<Path> paths = files.stream()
            .map(file -> directory.resolve(TerminalLogExporter.targetName(file.name(), format, range)))
            .toList();
        return new ExportTarget(directory, paths);
    }

    /**
     * The folder a file chooser opens in: the parent of the log folder, so the user does not land
     * inside the folder the logs live in. Falls back to the home folder.
     */
    private static File initialDirectory(Path logFolder) {
        Path parent = logFolder != null ? logFolder.getParent() : null;
        return parent != null ? parent.toFile() : Path.of(System.getProperty("user.home")).toFile();
    }

    private static Path withSuffix(Path path, String suffix) {
        if (suffix.isEmpty() || path.getFileName() == null) {
            return path;
        }
        String name = path.getFileName().toString();
        return name.endsWith(suffix) ? path : path.resolveSibling(name + suffix);
    }

    private boolean confirmReplace(List<Path> existing) {
        String shown = existing.stream()
            .limit(OVERWRITE_PREVIEW)
            .map(Path::toString)
            .collect(Collectors.joining("\n"));
        if (existing.size() > OVERWRITE_PREVIEW) {
            shown += "\n" + ELLIPSIS;
        }
        Optional<ButtonType> answer = newAlert(Alert.AlertType.CONFIRMATION,
            I18n.get("logfile.export.title"),
            I18n.get("logfile.export.overwrite.header", existing.size()),
            I18n.get("logfile.export.overwrite.content", shown)).showAndWait();
        return answer.orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /**
     * Runs the export on a daemon thread behind a progress dialog with a working Cancel. Cancelling
     * sets a flag the exporter polls, so the exporter finishes its cleanup before the result arrives;
     * the Task itself is never cancelled, which keeps the result and the removed partial files in step.
     */
    private void startExport(List<TerminalLogFile> files, LogfileExportDialog.ExportChoice choice,
                             ExportTarget target, boolean overwrite) {
        List<Path> folders = logFolders;
        LogTimeRange range = choice.range();
        TerminalLogNaming.Compression format = choice.format();
        AtomicBoolean cancelRequested = new AtomicBoolean();
        BooleanSupplier cancelled = cancelRequested::get;
        boolean single = files.size() == 1;

        Task<List<TerminalLogExporter.Result>> task = new Task<>() {
            @Override
            protected List<TerminalLogExporter.Result> call() {
                if (single) {
                    TerminalLogFile file = files.get(0);
                    long total = Math.max(1, file.sizeBytes());
                    TerminalLogExporter.Result result = TerminalLogExporter.exportOne(file, target.paths().get(0),
                        format, overwrite, range, folders,
                        bytes -> {
                            updateProgress(bytes, total);
                            updateMessage(I18n.get("logfile.export.progress.message", 0, 1, formatMb(bytes)));
                        },
                        cancelled);
                    return List.of(result);
                }
                return TerminalLogExporter.exportAll(files, target.directory(), format, overwrite, range, folders,
                    progress -> {
                        updateProgress(progress.bytesDone(), Math.max(1, progress.bytesTotal()));
                        updateMessage(I18n.get("logfile.export.progress.message",
                            progress.filesDone(), progress.filesTotal(), formatMb(progress.bytesDone())));
                    },
                    cancelled);
            }
        };

        ProgressBar bar = new ProgressBar();
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.progressProperty().bind(task.progressProperty());
        Label message = new Label();
        message.textProperty().bind(task.messageProperty());
        VBox content = new VBox(10, bar, message);
        content.setPadding(new Insets(8));

        ThemeAwareDialog<Void> progress = new ThemeAwareDialog<>();
        Window owner = ownerWindow();
        if (owner != null) {
            progress.initOwner(owner);
        }
        progress.setTitle(I18n.get("logfile.export.progress.title"));
        progress.getDialogPane().setPrefWidth(460);
        progress.getDialogPane().setContent(content);
        progress.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        Button cancelButton = (Button) progress.getDialogPane().lookupButton(ButtonType.CANCEL);
        Runnable requestCancel = () -> {
            cancelRequested.set(true);
            cancelButton.setDisable(true);
        };
        cancelButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            requestCancel.run();
        });
        progress.setOnCloseRequest(event -> {
            if (!task.isDone()) {
                event.consume();
                requestCancel.run();
            }
        });
        // Whatever hides the dialog before the export ends (Escape, the window manager) is a cancel too.
        progress.setOnHidden(event -> {
            if (!task.isDone()) {
                cancelRequested.set(true);
            }
        });

        task.setOnSucceeded(event -> {
            closeProgress(progress);
            setBusy(false);
            List<TerminalLogExporter.Result> results = task.getValue();
            refresh();
            showExportOutcome(results, target.directory());
        });
        task.setOnFailed(event -> {
            closeProgress(progress);
            setBusy(false);
            refresh();
            showError(I18n.get("logfile.export.failed", files.size(), messageOf(task.getException())));
        });
        setBusy(true);
        progress.show();
        runInBackground(task, "LogfileExport");
    }

    private static void closeProgress(ThemeAwareDialog<Void> progress) {
        if (progress.isShowing()) {
            progress.close();
        }
    }

    private void showExportOutcome(List<TerminalLogExporter.Result> results, Path directory) {
        long written = countStatus(results, TerminalLogExporter.Status.OK);
        long empty = countStatus(results, TerminalLogExporter.Status.EMPTY_RANGE);
        long cancelled = countStatus(results, TerminalLogExporter.Status.CANCELLED);
        List<TerminalLogExporter.Result> failed = results.stream()
            .filter(result -> result.status() != TerminalLogExporter.Status.OK
                && result.status() != TerminalLogExporter.Status.EMPTY_RANGE
                && result.status() != TerminalLogExporter.Status.CANCELLED)
            .toList();

        List<String> lines = new ArrayList<>();
        if (cancelled > 0) {
            lines.add(I18n.get("logfile.export.cancelled"));
        }
        if (written > 0) {
            lines.add(I18n.get("logfile.export.done", written, directory.toString()));
        }
        if (empty > 0) {
            lines.add(I18n.get("logfile.export.skippedEmpty", empty));
        }
        if (!failed.isEmpty()) {
            String details = failed.stream()
                .limit(PREVIEW_LIMIT)
                .map(result -> result.source().getFileName() + ": "
                    + (result.error() != null ? result.error() : result.status().name()))
                .collect(Collectors.joining("\n"));
            lines.add(I18n.get("logfile.export.failed", failed.size(), details));
        }
        if (lines.isEmpty()) {
            return;
        }

        boolean problems = !failed.isEmpty();
        Alert alert = newAlert(problems ? Alert.AlertType.WARNING : Alert.AlertType.INFORMATION,
            I18n.get("logfile.export.title"),
            problems ? I18n.get("logfile.manager.error") : null,
            String.join("\n", lines));
        ButtonType openTarget = new ButtonType(I18n.get("logfile.export.openTarget"), ButtonBar.ButtonData.OTHER);
        if (written > 0) {
            alert.getButtonTypes().setAll(openTarget, ButtonType.OK);
        }
        Optional<ButtonType> answer = alert.showAndWait();
        if (answer.filter(openTarget::equals).isPresent()) {
            openFolder.accept(directory);
        }
    }

    private static long countStatus(List<TerminalLogExporter.Result> results, TerminalLogExporter.Status status) {
        return results.stream().filter(result -> result.status() == status).count();
    }

    // ---- Dialog and background helpers ----

    /** Compression labels, shared with the export dialog so both name a format the same way. */
    static String compressionLabel(TerminalLogNaming.Compression compression) {
        if (compression == null) {
            return "";
        }
        return switch (compression) {
            case NONE -> I18n.get("logfile.manager.compression.none");
            case GZIP -> I18n.get("logfile.manager.compression.gzip");
            case ZSTD -> I18n.get("logfile.manager.compression.zstd");
            case BZIP2 -> I18n.get("logfile.manager.compression.bzip2");
        };
    }

    private static String formatSize(long bytes) {
        return String.format(Locale.ROOT, "%.2f MB", bytes / MIB);
    }

    private static String formatMb(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / MIB);
    }

    private Window ownerWindow() {
        Scene scene = getDialogPane().getScene();
        return scene != null ? scene.getWindow() : null;
    }

    private Alert newAlert(Alert.AlertType type, String title, String header, String content) {
        Alert alert = new Alert(type);
        DialogThemeHelper.applyTheme(alert);
        Window owner = ownerWindow();
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        return alert;
    }

    private void showError(String content) {
        newAlert(Alert.AlertType.ERROR, I18n.get("logfile.manager.title"),
            I18n.get("logfile.manager.error"), content).showAndWait();
    }

    private void info(String content) {
        newAlert(Alert.AlertType.INFORMATION, I18n.get("logfile.manager.title"), null, content).showAndWait();
    }

    private static void runInBackground(Task<?> task, String threadName) {
        Thread thread = new Thread(task, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    private static String messageOf(Throwable failure) {
        if (failure == null) {
            return "";
        }
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }

    /**
     * A name cell: the file name, and a "live" pill when the row's file is still being written. The
     * pill reads the row item, because the cell itself only carries the name.
     */
    private static final class NameCell extends TableCell<TerminalLogFile, String> {

        private final Label name = new Label();
        private final Label pill = new Label(I18n.get("logfile.manager.live"));
        private final HBox box = new HBox(6, name, pill);

        NameCell() {
            // Follow the cell's own colour (theme, selection) instead of the default label colour.
            name.textFillProperty().bind(textFillProperty());
            // Same translucent pill as the journal badges (SessionJournalBadges.style), so it reads on
            // every theme: the text keeps the row's colour, only the tint says "still being written".
            pill.textFillProperty().bind(textFillProperty());
            pill.setStyle("-fx-background-color: rgba(34, 197, 94, 0.18); -fx-border-color: rgba(34, 197, 94, 0.55);"
                + " -fx-background-radius: 999; -fx-border-radius: 999; -fx-padding: 0 7 0 7; -fx-font-size: 0.82em;");
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(String fileName, boolean empty) {
            super.updateItem(fileName, empty);
            if (empty || fileName == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            TableRow<TerminalLogFile> row = getTableRow();
            TerminalLogFile file = row != null ? row.getItem() : null;
            boolean live = file != null && file.live();
            name.setText(fileName);
            pill.setVisible(live);
            pill.setManaged(live);
            setText(null);
            setGraphic(box);
        }
    }
}
