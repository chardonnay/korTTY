package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.jobscheduler.ActiveJobSummary;
import de.kortty.jobscheduler.JobAction;
import de.kortty.jobscheduler.JobActionType;
import de.kortty.jobscheduler.JobArchiveFormat;
import de.kortty.jobscheduler.JobJournalEntry;
import de.kortty.jobscheduler.JobSchedule;
import de.kortty.jobscheduler.JobSchedulerConnectionResolver;
import de.kortty.jobscheduler.JobSchedulerRemoteSession;
import de.kortty.jobscheduler.JobSchedulerService;
import de.kortty.jobscheduler.JobSchedulerSudoService;
import de.kortty.jobscheduler.JournalDetailMode;
import de.kortty.jobscheduler.PinnedHostKey;
import de.kortty.jobscheduler.RsyncDirection;
import de.kortty.jobscheduler.ScheduledJob;
import de.kortty.jobscheduler.SftpSyncDirection;
import de.kortty.model.ServerConnection;
import de.kortty.model.WindowGeometry;
import de.kortty.security.EncryptionService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class JobSchedulerDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(JobSchedulerDialog.class);
    private static final double DEFAULT_DIALOG_WIDTH = 1360;
    private static final double DEFAULT_DIALOG_HEIGHT = 800;
    private static final double FORM_LABEL_MIN_WIDTH = 170;
    private static final double TIME_COMBO_WIDTH = 86;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter JOURNAL_TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final String PERMISSIONS_HELP = """
        Valid permission values:

        Octal: 644, 755, 0640
        Symbolic chmod clauses: u+rw,o-w, g=rx, a+rX, u+s

        Separate multiple symbolic clauses with commas.
        """;

    private final KorTTYApplication app;
    private final JobSchedulerService schedulerService;
    private final EncryptionService encryptionService = new EncryptionService();
    private final ObservableList<ScheduledJob> jobs = FXCollections.observableArrayList();
    private final ObservableList<JobJournalEntry> journal = FXCollections.observableArrayList();
    private final ObservableList<ServerConnection> connections = FXCollections.observableArrayList();
    private final TableView<ScheduledJob> jobsTable = new TableView<>();
    private final TableView<JobJournalEntry> journalTable = new TableView<>();
    private final Label statusLabel = new Label();
    private final CheckBox menuBarStatusCheck = new CheckBox(I18n.get("jobscheduler.menu.statusVisible"));
    private final Label hostKeyLabel = new Label();
    private final Label hostKeyVerificationWarningLabel = new Label(
        "Warning: unattended SSH/SFTP for this job will trust any host key.");

    private final TextField nameField = new TextField();
    private final CheckBox enabledCheck = new CheckBox("Enabled");
    private final CheckBox hostKeyVerificationDisabledCheck = new CheckBox("Disable host-key verification for this job (unsafe)");
    private final TextField connectionSummaryField = new TextField();
    private final TextField workingDirectoryField = new TextField();
    private final ComboBox<JobActionType> actionTypeCombo = new ComboBox<>();
    private final ComboBox<JournalDetailMode> journalModeCombo = new ComboBox<>();
    private final DatePicker activeFromPicker = new DatePicker();
    private final DatePicker activeUntilPicker = new DatePicker();
    private final ComboBox<String> windowStartCombo = new ComboBox<>();
    private final ComboBox<String> windowEndCombo = new ComboBox<>();
    private final ComboBox<String> fixedTimeCombo = new ComboBox<>();
    private final ObservableList<String> fixedTimes = FXCollections.observableArrayList();
    private final ListView<String> fixedTimesList = new ListView<>(fixedTimes);
    private final Spinner<Integer> intervalSpinner = new Spinner<>(0, 10080, 0, 5);
    private final CheckBox allWeekdaysCheck = new CheckBox("All");
    private final CheckBox[] weekdayChecks = Arrays.stream(DayOfWeek.values())
        .map(day -> new CheckBox(day.name().substring(0, 3)))
        .toArray(CheckBox[]::new);

    private final TextArea commandArea = new TextArea();
    private final TextArea aiPromptArea = new TextArea();
    private final ComboBox<String> aiProfileCombo = new ComboBox<>();
    private final CheckBox aiAutoApproveCheck = new CheckBox("Auto-approve AI commands");
    private final TextField localPathField = new TextField();
    private final TextField remotePathField = new TextField();
    private final TextField remoteSourceField = new TextField();
    private final TextField remoteDestinationField = new TextField();
    private final TextField permissionsField = new TextField();
    private final TextField ownerField = new TextField();
    private final TextField groupField = new TextField();
    private final ComboBox<SftpSyncDirection> syncDirectionCombo = new ComboBox<>();
    private final CheckBox sudoCheck = new CheckBox("Use sudo");
    private final CheckBox sudoStagingCheck = new CheckBox("Use sudo staging for SFTP paths");
    private final TextArea archiveSourcesArea = new TextArea();
    private final TextArea archiveExcludesArea = new TextArea();
    private final TextField archivePathField = new TextField();
    private final ComboBox<JobArchiveFormat> archiveFormatCombo = new ComboBox<>();
    private final Spinner<Integer> archiveCompressionSpinner = new Spinner<>(0, 9, 6, 1);
    private final CheckBox archiveDownloadCheck = new CheckBox("Download archive after creation");
    private final TextField archiveDownloadPathField = new TextField();
    private final PasswordField archivePasswordField = new PasswordField();
    private final Label archivePasswordStatusLabel = new Label();
    private final ComboBox<RsyncDirection> rsyncDirectionCombo = new ComboBox<>();
    private final TextArea rsyncSourcesArea = new TextArea();
    private final TextField rsyncTargetRootField = new TextField();
    private final CheckBox rsyncDeleteCheck = new CheckBox("Delete missing files");
    private final List<String> selectedConnectionIds = new ArrayList<>();
    private final List<String> selectedGroupNames = new ArrayList<>();
    private final PauseTransition geometrySaveDelay = new PauseTransition(Duration.millis(500));

    private ScheduledJob selectedJob;
    private String loadedEncryptedArchivePassword;
    private boolean geometryListenersInstalled;
    private boolean updatingWeekdaySelection;
    private boolean suppressJobSelectionLoad;

    public JobSchedulerDialog(KorTTYApplication app, Window owner) {
        this.app = app;
        this.schedulerService = app.getJobSchedulerService();
        initOwner(owner);
        initModality(Modality.NONE);
        setTitle("JobScheduler");
        setHeaderText("Scheduled background automation");
        setResizable(true);
        getDialogPane().setPrefSize(DEFAULT_DIALOG_WIDTH, DEFAULT_DIALOG_HEIGHT);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        buildUi();
        restoreGeometry();
        schedulerService.addListener(this::refreshLater);
        setOnCloseRequest(event -> {
            saveGeometry();
            schedulerService.removeListener(this::refreshLater);
        });
        setOnHidden(event -> {
            saveGeometry();
            schedulerService.removeListener(this::refreshLater);
        });
        setOnShown(event -> installGeometryPersistence());
        refresh();
    }

    private void buildUi() {
        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(buildJobList(), buildDetails());
        splitPane.setDividerPositions(0.27);
        getDialogPane().setContent(splitPane);
    }

    private VBox buildJobList() {
        jobsTable.setItems(jobs);
        jobsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<ScheduledJob, String> nameColumn = new TableColumn<>("Job");
        nameColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getName()));
        TableColumn<ScheduledJob, Boolean> enabledColumn = new TableColumn<>("Enabled");
        enabledColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().isEnabled()));
        TableColumn<ScheduledJob, String> nextRunColumn = new TableColumn<>("Next run");
        nextRunColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(nonBlank(cell.getValue().getNextRunAt(), "")));
        jobsTable.getColumns().add(nameColumn);
        jobsTable.getColumns().add(enabledColumn);
        jobsTable.getColumns().add(nextRunColumn);
        jobsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldJob, newJob) -> {
            if (!suppressJobSelectionLoad) {
                loadJob(newJob);
            }
        });

        Button newButton = new Button("New");
        newButton.setGraphic(iconLabel("\u2795", 18));
        newButton.setOnAction(event -> createNewJob());
        Button saveButton = new Button("Save");
        saveButton.setGraphic(iconLabel("\u2713", 18));
        saveButton.setOnAction(event -> saveSelectedJob());
        Button deleteButton = new Button("Delete");
        deleteButton.setGraphic(iconLabel("\u2715", 18));
        deleteButton.setOnAction(event -> deleteSelectedJob());
        Button runButton = new Button("Run now");
        runButton.setGraphic(iconLabel("\u25B6", 18));
        runButton.setOnAction(event -> runSelectedJob());
        HBox buttons = new HBox(8, newButton, saveButton, deleteButton, runButton);
        menuBarStatusCheck.setSelected(isJobSchedulerMenuStatusEnabled());
        menuBarStatusCheck.setOnAction(event -> saveJobSchedulerMenuStatusPreference());

        VBox box = new VBox(10, jobsTable, buttons, menuBarStatusCheck, statusLabel);
        box.setPadding(new Insets(12));
        VBox.setVgrow(jobsTable, Priority.ALWAYS);
        return box;
    }

    private TabPane buildDetails() {
        TabPane tabs = new TabPane();
        tabs.getTabs().add(new Tab("Job", buildJobEditor()));
        tabs.getTabs().add(new Tab("Action", buildActionEditor()));
        tabs.getTabs().add(new Tab("Journal", buildJournalView()));
        for (Tab tab : tabs.getTabs()) {
            tab.setClosable(false);
        }
        return tabs;
    }

    private VBox buildJobEditor() {
        intervalSpinner.setEditable(true);
        List<String> timeValues = timeValues();
        windowStartCombo.getItems().setAll(timeValues);
        windowEndCombo.getItems().setAll(timeValues);
        fixedTimeCombo.getItems().setAll(timeValues);
        configureCompactTimeCombo(windowStartCombo);
        configureCompactTimeCombo(windowEndCombo);
        windowStartCombo.getSelectionModel().select("00:00");
        windowEndCombo.getSelectionModel().select("23:59");
        fixedTimeCombo.getSelectionModel().select("00:00");
        fixedTimesList.setPrefHeight(96);
        fixedTimesList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        configureWeekdaySelectionControls();
        connectionSummaryField.setEditable(false);
        connectionSummaryField.setPromptText("No server or server group selected");
        journalModeCombo.getItems().setAll(JournalDetailMode.values());
        journalModeCombo.getSelectionModel().select(JournalDetailMode.LIMITED_REDACTED);
        hostKeyVerificationWarningLabel.setWrapText(true);
        hostKeyVerificationWarningLabel.visibleProperty().bind(hostKeyVerificationDisabledCheck.selectedProperty());
        hostKeyVerificationWarningLabel.managedProperty().bind(hostKeyVerificationWarningLabel.visibleProperty());
        hostKeyVerificationDisabledCheck.selectedProperty().addListener((obs, oldValue, newValue) -> updateHostKeyLabel());
        Button selectTargetsButton = new Button("Select...");
        selectTargetsButton.setGraphic(iconLabel("\uD83D\uDD0C", 16));
        selectTargetsButton.setOnAction(event -> selectTargets());
        HBox connectionBox = new HBox(8, connectionSummaryField, selectTargetsButton);
        HBox.setHgrow(connectionSummaryField, Priority.ALWAYS);
        Button workingDirectoryButton = new Button("Browse...");
        workingDirectoryButton.setGraphic(iconLabel("\uD83D\uDCC1", 16));
        workingDirectoryButton.setOnAction(event -> selectRemoteDirectory(workingDirectoryField, true));
        HBox workingDirectoryBox = fieldButtonBox(workingDirectoryField, workingDirectoryButton);
        Button addFixedTimeButton = new Button("Add");
        addFixedTimeButton.setGraphic(iconLabel("\u2795", 16));
        addFixedTimeButton.setOnAction(event -> addFixedTime());
        Button removeFixedTimeButton = new Button("Remove");
        removeFixedTimeButton.setGraphic(iconLabel("\u2715", 16));
        removeFixedTimeButton.setOnAction(event -> removeFixedTime());
        HBox fixedTimeControls = new HBox(8, fixedTimeCombo, addFixedTimeButton, removeFixedTimeButton);
        fixedTimeControls.setAlignment(Pos.CENTER_LEFT);
        VBox fixedTimesBox = new VBox(8, fixedTimeControls, fixedTimesList);

        GridPane grid = formGrid();
        int row = 0;
        addRow(grid, row++, "Name", nameField);
        addRow(grid, row++, "Connection", connectionBox);
        addRow(grid, row++, "Working directory", workingDirectoryBox);
        addRow(grid, row++, "Journal", journalModeCombo);
        addRow(grid, row++, "Active from", activeFromPicker);
        addRow(grid, row++, "Active until", activeUntilPicker);
        addRow(grid, row++, "Window start", windowStartCombo);
        addRow(grid, row++, "Window end", windowEndCombo);
        addRow(grid, row++, "Interval minutes", intervalSpinner);
        addRow(grid, row++, "Fixed times", fixedTimesBox);

        HBox weekdays = new HBox(6, allWeekdaysCheck);
        weekdays.getChildren().addAll(weekdayChecks);
        weekdays.setAlignment(Pos.CENTER_LEFT);
        addRow(grid, row++, "Weekdays", weekdays);

        Button pinButton = new Button("Confirm host key");
        pinButton.setGraphic(iconLabel("\uD83D\uDD12", 16));
        pinButton.setOnAction(event -> pinHostKey());
        Button serverSudoButton = new Button("Set server sudo");
        serverSudoButton.setGraphic(iconLabel("\uD83D\uDD11", 16));
        serverSudoButton.setOnAction(event -> setServerSudoPassword());
        Button groupSudoButton = new Button("Set group sudo");
        groupSudoButton.setGraphic(iconLabel("\uD83D\uDD11", 16));
        groupSudoButton.setOnAction(event -> setGroupSudoPassword());
        HBox securityButtons = new HBox(8, pinButton, serverSudoButton, groupSudoButton);

        return padded(new VBox(
            12,
            enabledCheck,
            grid,
            hostKeyLabel,
            hostKeyVerificationDisabledCheck,
            hostKeyVerificationWarningLabel,
            securityButtons));
    }

    private VBox buildActionEditor() {
        actionTypeCombo.getItems().setAll(JobActionType.values());
        actionTypeCombo.setVisibleRowCount(JobActionType.values().length);
        syncDirectionCombo.getItems().setAll(SftpSyncDirection.values());
        archiveFormatCombo.getItems().setAll(JobArchiveFormat.values());
        rsyncDirectionCombo.getItems().setAll(RsyncDirection.values());
        actionTypeCombo.getSelectionModel().select(JobActionType.COMMAND);
        syncDirectionCombo.getSelectionModel().select(SftpSyncDirection.UPLOAD);
        archiveFormatCombo.getSelectionModel().select(JobArchiveFormat.ZIP);
        rsyncDirectionCombo.getSelectionModel().select(RsyncDirection.UPLOAD);
        archiveFormatCombo.valueProperty().addListener((obs, oldValue, newValue) -> updateArchivePasswordState());
        archiveCompressionSpinner.setEditable(true);
        commandArea.setPrefRowCount(4);
        aiPromptArea.setPrefRowCount(4);
        archiveSourcesArea.setPrefRowCount(4);
        archiveExcludesArea.setPrefRowCount(3);
        rsyncSourcesArea.setPrefRowCount(4);
        permissionsField.setTooltip(new Tooltip(PERMISSIONS_HELP));
        permissionsField.setPromptText("e.g. 755 or u+rw,o-w");
        archivePasswordField.setPromptText("ZIP password");
        aiProfileCombo.getItems().setAll(app.getGlobalSettingsManager().getSettings().getAiProfiles().stream()
            .filter(profile -> profile != null)
            .map(profile -> profile.getId() + " - " + nonBlank(profile.getName(), profile.getModel()))
            .toList());

        Button localPathButton = new Button("Browse...");
        localPathButton.setGraphic(iconLabel("\uD83D\uDCC1", 16));
        localPathButton.setOnAction(event -> selectLocalDirectory(localPathField));
        Button remotePathButton = new Button("Browse...");
        remotePathButton.setGraphic(iconLabel("\uD83D\uDCC1", 16));
        remotePathButton.setOnAction(event -> selectRemoteDirectory(remotePathField, false));
        Button remoteSourceButton = new Button("Browse...");
        remoteSourceButton.setGraphic(iconLabel("\uD83D\uDCC1", 16));
        remoteSourceButton.setOnAction(event -> selectRemoteDirectory(remoteSourceField, false));
        Button remoteDestinationButton = new Button("Browse...");
        remoteDestinationButton.setGraphic(iconLabel("\uD83D\uDCC1", 16));
        remoteDestinationButton.setOnAction(event -> selectRemoteDirectory(remoteDestinationField, false));
        Button permissionsHelpButton = new Button("?");
        permissionsHelpButton.setOnAction(event -> showInfo("Permissions", PERMISSIONS_HELP));
        Button ownerButton = new Button("Select...");
        ownerButton.setGraphic(iconLabel("\uD83D\uDC64", 16));
        ownerButton.setOnAction(event -> selectRemoteOwner());
        Button groupButton = new Button("Select...");
        groupButton.setGraphic(iconLabel("\uD83D\uDC65", 16));
        groupButton.setOnAction(event -> selectRemoteGroup());
        Button archivePathButton = new Button("Browse...");
        archivePathButton.setGraphic(iconLabel("\uD83D\uDCC1", 16));
        archivePathButton.setOnAction(event -> selectRemoteDirectory(archivePathField, false));
        Button rsyncSourceButton = new Button("Add...");
        rsyncSourceButton.setGraphic(iconLabel("\uD83D\uDCC1", 16));
        rsyncSourceButton.setOnAction(event -> selectRsyncSourcePath());
        Button rsyncTargetRootButton = new Button("Browse...");
        rsyncTargetRootButton.setGraphic(iconLabel("\uD83D\uDCC1", 16));
        rsyncTargetRootButton.setOnAction(event -> selectRsyncTargetRoot());

        GridPane grid = formGrid();
        int row = 0;
        addRow(grid, row++, "Action", actionTypeCombo);
        addRow(grid, row++, "Command", commandArea);
        addRow(grid, row++, "AI profile", aiProfileCombo);
        addRow(grid, row++, "AI prompt", aiPromptArea);
        addRow(grid, row++, "Local path", fieldButtonBox(localPathField, localPathButton));
        addRow(grid, row++, "Remote path", fieldButtonBox(remotePathField, remotePathButton));
        addRow(grid, row++, "Remote source", fieldButtonBox(remoteSourceField, remoteSourceButton));
        addRow(grid, row++, "Remote destination", fieldButtonBox(remoteDestinationField, remoteDestinationButton));
        addRow(grid, row++, "Permissions", fieldButtonBox(permissionsField, permissionsHelpButton));
        addRow(grid, row++, "Owner", fieldButtonBox(ownerField, ownerButton));
        addRow(grid, row++, "Group", fieldButtonBox(groupField, groupButton));
        addRow(grid, row++, "Sync direction", syncDirectionCombo);
        addRow(grid, row++, "Archive sources", archiveSourcesArea);
        addRow(grid, row++, "Archive excludes", archiveExcludesArea);
        addRow(grid, row++, "Archive path", fieldButtonBox(archivePathField, archivePathButton));
        addRow(grid, row++, "Archive format", archiveFormatCombo);
        addRow(grid, row++, "Archive password", new VBox(4, archivePasswordField, archivePasswordStatusLabel));
        addRow(grid, row++, "Compression", archiveCompressionSpinner);
        addRow(grid, row++, "Archive download path", archiveDownloadPathField);
        addRow(grid, row++, "Rsync direction", rsyncDirectionCombo);
        addRow(grid, row++, "Rsync sources", fieldButtonBox(rsyncSourcesArea, rsyncSourceButton));
        addRow(grid, row++, "Rsync target root", fieldButtonBox(rsyncTargetRootField, rsyncTargetRootButton));
        addRow(grid, row++, "Rsync", rsyncDeleteCheck);

        updateArchivePasswordState();
        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        return padded(new VBox(12, scrollPane, sudoCheck, sudoStagingCheck, aiAutoApproveCheck, archiveDownloadCheck));
    }

    private VBox buildJournalView() {
        journalTable.setItems(journal);
        journalTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        journalTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        TableColumn<JobJournalEntry, String> startedColumn = new TableColumn<>("Started");
        startedColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(formatJournalTimestamp(cell.getValue().getStartedAt())));
        TableColumn<JobJournalEntry, String> statusColumn = new TableColumn<>("Status");
        statusColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getStatus().name()));
        TableColumn<JobJournalEntry, String> jobColumn = new TableColumn<>("Job");
        jobColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(nonBlank(cell.getValue().getJobName(), "")));
        TableColumn<JobJournalEntry, String> summaryColumn = new TableColumn<>("Summary");
        summaryColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(nonBlank(cell.getValue().getSummary(), "")));
        journalTable.getColumns().add(startedColumn);
        journalTable.getColumns().add(statusColumn);
        journalTable.getColumns().add(jobColumn);
        journalTable.getColumns().add(summaryColumn);
        TextArea detailArea = new TextArea();
        detailArea.setEditable(false);
        detailArea.setPrefRowCount(8);
        journalTable.getSelectionModel().selectedItemProperty().addListener((obs, oldEntry, entry) -> {
            if (entry == null) {
                detailArea.clear();
            } else {
                detailArea.setText("stdout:\n" + nonBlank(entry.getStdoutText(), "")
                    + "\n\nstderr:\n" + nonBlank(entry.getStderrText(), "")
                    + "\n\ndetail:\n" + nonBlank(entry.getDetailText(), ""));
            }
        });
        Button refreshButton = new Button("Refresh");
        refreshButton.setGraphic(iconLabel("\u21BB", 16));
        refreshButton.setOnAction(event -> refresh());
        Button deleteButton = new Button("Delete selected");
        deleteButton.setGraphic(iconLabel("\u2715", 18));
        deleteButton.disableProperty().bind(journalTable.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.setOnAction(event -> deleteSelectedJournalEntries(detailArea));
        HBox buttons = new HBox(8, refreshButton, deleteButton);
        VBox box = padded(new VBox(10, journalTable, detailArea, buttons));
        VBox.setVgrow(journalTable, Priority.ALWAYS);
        return box;
    }

    private void refreshLater() {
        Platform.runLater(this::refresh);
    }

    private void refresh() {
        String selectedJobId = selectedJob != null
            ? selectedJob.getId()
            : jobsTable.getSelectionModel().getSelectedItem() != null
                ? jobsTable.getSelectionModel().getSelectedItem().getId()
                : null;
        List<ScheduledJob> refreshedJobs = new ArrayList<>(schedulerService.getJobs());
        boolean keepLocalDraft = selectedJob != null
            && refreshedJobs.stream().noneMatch(job -> job.getId().equals(selectedJob.getId()))
            && jobs.contains(selectedJob);
        if (keepLocalDraft) {
            refreshedJobs.add(0, selectedJob);
        }
        suppressJobSelectionLoad = true;
        try {
            jobs.setAll(refreshedJobs);
            restoreJobSelection(selectedJobId);
            jobsTable.refresh();
        } finally {
            suppressJobSelectionLoad = false;
        }
        connections.setAll(app.getConfigManager().getConnections());
        menuBarStatusCheck.setSelected(isJobSchedulerMenuStatusEnabled());
        updateConnectionSummary();
        journal.setAll(schedulerService.getJournal());
        List<ActiveJobSummary> active = schedulerService.getActiveJobSummaries();
        statusLabel.setText(active.isEmpty()
            ? "No background jobs running."
            : active.size() + " background job(s) running: " + active.stream().map(ActiveJobSummary::jobName).toList());
        updateHostKeyLabel();
    }

    private void restoreJobSelection(String selectedJobId) {
        if (selectedJobId == null || selectedJobId.isBlank()) {
            jobsTable.getSelectionModel().clearSelection();
            return;
        }
        Optional<ScheduledJob> refreshedSelection = jobs.stream()
            .filter(job -> selectedJobId.equals(job.getId()))
            .findFirst();
        if (refreshedSelection.isPresent()) {
            selectedJob = selectedJob != null && selectedJobId.equals(selectedJob.getId())
                ? selectedJob
                : refreshedSelection.get();
            jobsTable.getSelectionModel().select(refreshedSelection.get());
        } else {
            selectedJob = null;
            jobsTable.getSelectionModel().clearSelection();
        }
    }

    private boolean isJobSchedulerMenuStatusEnabled() {
        var settings = app.getGlobalSettingsManager().getSettings();
        return settings == null || settings.isJobSchedulerMenuStatusEnabled();
    }

    private void saveJobSchedulerMenuStatusPreference() {
        boolean selected = menuBarStatusCheck.isSelected();
        try {
            app.getGlobalSettingsManager().getSettings().setJobSchedulerMenuStatusEnabled(selected);
            app.getGlobalSettingsManager().save();
        } catch (Exception e) {
            menuBarStatusCheck.setSelected(!selected);
            showError("Could not save JobScheduler setting", e.getMessage());
        }
    }

    private void createNewJob() {
        ScheduledJob job = new ScheduledJob();
        jobs.add(job);
        jobsTable.getSelectionModel().select(job);
    }

    private void loadJob(ScheduledJob job) {
        selectedJob = job;
        if (job == null) {
            return;
        }
        nameField.setText(job.getName());
        enabledCheck.setSelected(job.isEnabled());
        hostKeyVerificationDisabledCheck.setSelected(job.isHostKeyVerificationDisabled());
        selectedConnectionIds.clear();
        selectedConnectionIds.addAll(job.getTargetConnectionIds());
        selectedGroupNames.clear();
        selectedGroupNames.addAll(job.getTargetGroupNames());
        updateConnectionSummary();
        workingDirectoryField.setText(nonBlank(job.getWorkingDirectory(), ""));
        journalModeCombo.getSelectionModel().select(job.getJournalDetailMode());
        JobSchedule schedule = job.getSchedule();
        activeFromPicker.setValue(parseDate(schedule.getActiveFromDate()));
        activeUntilPicker.setValue(parseDate(schedule.getActiveUntilDate()));
        selectTime(windowStartCombo, schedule.getWindowStartTime(), "00:00");
        selectTime(windowEndCombo, schedule.getWindowEndTime(), "23:59");
        intervalSpinner.getValueFactory().setValue(schedule.getIntervalMinutes() != null ? schedule.getIntervalMinutes() : 0);
        fixedTimes.setAll(schedule.getFixedTimes().stream()
            .filter(this::isValidTime)
            .sorted(Comparator.comparing(LocalTime::parse))
            .toList());
        for (int i = 0; i < weekdayChecks.length; i++) {
            weekdayChecks[i].setSelected(schedule.getWeekdays().contains(DayOfWeek.values()[i].name()));
        }
        syncAllWeekdaysCheck();
        loadAction(job.getAction());
        updateHostKeyLabel();
    }

    private void loadAction(JobAction action) {
        actionTypeCombo.getSelectionModel().select(action.getType());
        commandArea.setText(nonBlank(action.getCommand(), ""));
        aiPromptArea.setText(nonBlank(action.getAiPrompt(), ""));
        selectAiProfile(action.getAiProfileId());
        aiAutoApproveCheck.setSelected(action.isAiAutoApproveCommands());
        localPathField.setText(nonBlank(action.getLocalPath(), ""));
        remotePathField.setText(nonBlank(action.getRemotePath(), ""));
        remoteSourceField.setText(nonBlank(action.getRemoteSourcePath(), ""));
        remoteDestinationField.setText(nonBlank(action.getRemoteDestinationPath(), ""));
        permissionsField.setText(nonBlank(action.getPermissions(), ""));
        ownerField.setText(nonBlank(action.getOwner(), ""));
        groupField.setText(nonBlank(action.getGroup(), ""));
        syncDirectionCombo.getSelectionModel().select(action.getSyncDirection());
        sudoCheck.setSelected(action.isUseSudo());
        sudoStagingCheck.setSelected(action.isSudoStagingEnabled());
        archiveSourcesArea.setText(String.join("\n", action.getArchiveSourcePaths()));
        archiveExcludesArea.setText(String.join("\n", action.getArchiveExcludePatterns()));
        archivePathField.setText(nonBlank(action.getArchivePath(), ""));
        archiveFormatCombo.getSelectionModel().select(action.getArchiveFormat());
        archiveCompressionSpinner.getValueFactory().setValue(action.getArchiveCompressionLevel());
        archiveDownloadCheck.setSelected(action.isArchiveDownloadAfterCreate());
        archiveDownloadPathField.setText(nonBlank(action.getArchiveDownloadLocalPath(), ""));
        loadedEncryptedArchivePassword = action.getEncryptedArchivePassword();
        archivePasswordField.clear();
        rsyncDirectionCombo.getSelectionModel().select(action.getRsyncDirection());
        rsyncSourcesArea.setText(String.join("\n", action.getRsyncSourcePaths()));
        rsyncTargetRootField.setText(nonBlank(action.getRsyncTargetRoot(), ""));
        rsyncDeleteCheck.setSelected(action.isRsyncDeleteEnabled());
        updateArchivePasswordState();
    }

    private void saveSelectedJob() {
        if (selectedJob == null) {
            selectedJob = jobsTable.getSelectionModel().getSelectedItem();
        }
        if (selectedJob == null) {
            selectedJob = new ScheduledJob();
            jobs.add(selectedJob);
            suppressJobSelectionLoad = true;
            try {
                jobsTable.getSelectionModel().select(selectedJob);
            } finally {
                suppressJobSelectionLoad = false;
            }
        }
        try {
            selectedJob.setName(nameField.getText());
            selectedJob.setEnabled(enabledCheck.isSelected());
            selectedJob.setHostKeyVerificationDisabled(hostKeyVerificationDisabledCheck.isSelected());
            selectedJob.setTargetConnectionIds(selectedConnectionIds);
            selectedJob.setTargetGroupNames(selectedGroupNames);
            selectedJob.setConnectionId(selectedGroupNames.isEmpty() && selectedConnectionIds.size() == 1
                ? selectedConnectionIds.get(0)
                : null);
            selectedJob.setConnectionDisplayName(targetSummary());
            selectedJob.setWorkingDirectory(workingDirectoryField.getText());
            selectedJob.setJournalDetailMode(journalModeCombo.getSelectionModel().getSelectedItem());
            selectedJob.setSchedule(readSchedule());
            selectedJob.setAction(readAction());
            schedulerService.saveJob(selectedJob);
            refresh();
        } catch (Exception e) {
            showError("Could not save job", e.getMessage());
        }
    }

    private JobSchedule readSchedule() {
        JobSchedule schedule = new JobSchedule();
        schedule.setEnabled(true);
        schedule.setActiveFromDate(activeFromPicker.getValue() != null ? activeFromPicker.getValue().toString() : null);
        schedule.setActiveUntilDate(activeUntilPicker.getValue() != null ? activeUntilPicker.getValue().toString() : null);
        schedule.setWindowStartTime(windowStartCombo.getValue());
        schedule.setWindowEndTime(windowEndCombo.getValue());
        schedule.setIntervalMinutes(intervalSpinner.getValue() != null && intervalSpinner.getValue() > 0 ? intervalSpinner.getValue() : null);
        schedule.setFixedTimes(fixedTimes.stream().filter(this::isValidTime).toList());
        schedule.setWeekdays(Arrays.stream(weekdayChecks)
            .filter(CheckBox::isSelected)
            .map(check -> DayOfWeek.values()[Arrays.asList(weekdayChecks).indexOf(check)].name())
            .toList());
        return schedule;
    }

    private JobAction readAction() throws Exception {
        JobAction action = new JobAction();
        action.setType(actionTypeCombo.getSelectionModel().getSelectedItem());
        action.setCommand(commandArea.getText());
        action.setAiPrompt(aiPromptArea.getText());
        action.setAiProfileId(selectedAiProfileId());
        action.setAiAutoApproveCommands(aiAutoApproveCheck.isSelected());
        action.setLocalPath(localPathField.getText());
        action.setRemotePath(remotePathField.getText());
        action.setRemoteSourcePath(remoteSourceField.getText());
        action.setRemoteDestinationPath(remoteDestinationField.getText());
        String permissions = permissionsField.getText();
        if (permissions != null && !permissions.isBlank() && !isValidPermissions(permissions.trim())) {
            throw new IllegalArgumentException("Permissions must be octal like 755 or symbolic like u+rw,o-w.");
        }
        action.setPermissions(permissions);
        action.setOwner(ownerField.getText());
        action.setGroup(groupField.getText());
        action.setSyncDirection(syncDirectionCombo.getSelectionModel().getSelectedItem());
        action.setUseSudo(sudoCheck.isSelected());
        action.setSudoStagingEnabled(sudoStagingCheck.isSelected());
        action.setArchiveSourcePaths(lines(archiveSourcesArea.getText()));
        action.setArchiveExcludePatterns(lines(archiveExcludesArea.getText()));
        action.setArchivePath(archivePathField.getText());
        action.setArchiveFormat(archiveFormatCombo.getSelectionModel().getSelectedItem());
        action.setArchiveCompressionLevel(archiveCompressionSpinner.getValue());
        action.setArchiveDownloadAfterCreate(archiveDownloadCheck.isSelected());
        action.setArchiveDownloadLocalPath(archiveDownloadPathField.getText());
        action.setEncryptedArchivePassword(action.getType() == JobActionType.SFTP_ARCHIVE
            ? readEncryptedArchivePassword(action.getArchiveFormat())
            : null);
        action.setRsyncDirection(rsyncDirectionCombo.getSelectionModel().getSelectedItem());
        action.setRsyncSourcePaths(lines(rsyncSourcesArea.getText()));
        action.setRsyncTargetRoot(rsyncTargetRootField.getText());
        action.setRsyncDeleteEnabled(rsyncDeleteCheck.isSelected());
        return action;
    }

    private void deleteSelectedJob() {
        if (selectedJob == null) {
            return;
        }
        try {
            schedulerService.deleteJob(selectedJob.getId());
            selectedJob = null;
            refresh();
        } catch (Exception e) {
            showError("Could not delete job", e.getMessage());
        }
    }

    private void deleteSelectedJournalEntries(TextArea detailArea) {
        List<JobJournalEntry> selectedEntries = new ArrayList<>(journalTable.getSelectionModel().getSelectedItems());
        List<String> entryIds = selectedEntries.stream()
            .map(JobJournalEntry::getId)
            .filter(id -> id != null && !id.isBlank())
            .toList();
        if (entryIds.isEmpty()) {
            showInfo("Delete journal entries", "Select at least one journal entry first.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(confirm);
        confirm.initOwner(getDialogPane().getScene().getWindow());
        confirm.setTitle("Delete journal entries");
        confirm.setHeaderText(null);
        confirm.setContentText(entryIds.size() == 1
            ? "Delete the selected journal entry?"
            : "Delete " + entryIds.size() + " selected journal entries?");
        boolean confirmed = confirm.showAndWait()
            .filter(ButtonType.OK::equals)
            .isPresent();
        if (!confirmed) {
            return;
        }
        try {
            schedulerService.deleteJournalEntries(entryIds);
            journalTable.getSelectionModel().clearSelection();
            detailArea.clear();
            refresh();
        } catch (Exception e) {
            showError("Could not delete journal entries", e.getMessage());
        }
    }

    private void runSelectedJob() {
        saveSelectedJob();
        if (selectedJob != null) {
            schedulerService.runJobNow(selectedJob.getId());
            refresh();
        }
    }

    private void pinHostKey() {
        List<ServerConnection> targets = resolveSelectedTargetConnections();
        if (targets.isEmpty()) {
            showError("No connection selected", "Select at least one server or server group first.");
            return;
        }
        ProgressIndicator progress = new ProgressIndicator();
        Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
        DialogThemeHelper.applyTheme(progressAlert);
        progressAlert.setTitle("Confirm host key");
        progressAlert.setHeaderText("Reading host key fingerprint...");
        progressAlert.getDialogPane().setContent(progress);
        progressAlert.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL);
        Thread worker = new Thread(() -> {
            try {
                List<String> failures = new ArrayList<>();
                int pinned = 0;
                for (ServerConnection target : targets) {
                    try {
                        schedulerService.probeAndPinHostKey(target.getId());
                        pinned++;
                    } catch (Exception e) {
                        logger.warn("JobScheduler host key confirmation failed for {}", target.getDisplayName(), e);
                        failures.add(target.getDisplayName() + ": " + safeThrowableMessage(e));
                    }
                }
                int pinnedCount = pinned;
                Platform.runLater(() -> {
                    progressAlert.close();
                    refresh();
                    if (failures.isEmpty()) {
                        hostKeyLabel.setText("Pinned host keys for " + pinnedCount + " target(s).");
                    } else {
                        showError("Host key confirmation failed", String.join("\n", failures));
                    }
                });
            } catch (Exception e) {
                logger.warn("JobScheduler host key confirmation failed", e);
                Platform.runLater(() -> {
                    progressAlert.close();
                    showError("Host key confirmation failed", safeThrowableMessage(e));
                });
            }
        }, "JobScheduler-HostKey-Probe");
        worker.setDaemon(true);
        worker.start();
        progressAlert.showAndWait();
    }

    private void setServerSudoPassword() {
        List<ServerConnection> targets = resolveSelectedTargetConnections();
        if (targets.size() != 1) {
            showError("Select one server", "Select exactly one server target before saving a server-specific sudo password.");
            return;
        }
        ServerConnection connection = targets.get(0);
        promptPassword("Set server sudo password", connection.getDisplayName()).ifPresent(password -> {
            try {
                char[] master = requireMasterPassword();
                new JobSchedulerSudoService(schedulerService.getRepository())
                    .setServerSudoPassword(connection.getId(), password, master);
                schedulerService.getRepository().save();
            } catch (Exception e) {
                showError("Could not save sudo password", e.getMessage());
            }
        });
    }

    private void setGroupSudoPassword() {
        List<ServerConnection> targets = resolveSelectedTargetConnections();
        String initialGroup = selectedGroupNames.size() == 1
            ? selectedGroupNames.get(0)
            : targets.size() == 1 ? targets.get(0).getGroup() : "";
        TextInputDialog groupDialog = new TextInputDialog(nonBlank(initialGroup, ""));
        DialogThemeHelper.applyTheme(groupDialog);
        groupDialog.setTitle("Set group sudo password");
        groupDialog.setHeaderText("Server group");
        Optional<String> group = groupDialog.showAndWait().map(String::trim).filter(value -> !value.isEmpty());
        if (group.isEmpty()) {
            return;
        }
        promptPassword("Set group sudo password", group.get()).ifPresent(password -> {
            try {
                char[] master = requireMasterPassword();
                new JobSchedulerSudoService(schedulerService.getRepository())
                    .setGroupSudoPassword(group.get(), password, master);
                schedulerService.getRepository().save();
            } catch (Exception e) {
                showError("Could not save sudo password", e.getMessage());
            }
        });
    }

    private Optional<String> promptPassword(String title, String header) {
        javafx.scene.control.Dialog<String> dialog = new javafx.scene.control.Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("SUDO password");
        dialog.getDialogPane().setContent(new VBox(8, new Label("Password"), passwordField));
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.setResultConverter(buttonType -> buttonType == save ? passwordField.getText() : null);
        return dialog.showAndWait().filter(value -> !value.isBlank());
    }

    private char[] requireMasterPassword() {
        char[] master = app.getMasterPasswordManager().getMasterPassword();
        if (master == null) {
            throw new IllegalStateException("Master password is locked.");
        }
        return master;
    }

    private void updateHostKeyLabel() {
        List<ServerConnection> targets = resolveSelectedTargetConnections();
        if (hostKeyVerificationDisabledCheck.isSelected()) {
            hostKeyLabel.setText("Host-key verification is disabled for this job.");
            return;
        }
        if (targets.isEmpty()) {
            hostKeyLabel.setText("No server target selected.");
            return;
        }
        long pinned = targets.stream()
            .filter(connection -> schedulerService.findPinnedHostKey(connection.getId()).isPresent())
            .count();
        if (pinned == targets.size()) {
            hostKeyLabel.setText("Pinned host keys for all selected target(s).");
        } else {
            hostKeyLabel.setText("Host key missing for " + (targets.size() - pinned) + " of " + targets.size() + " selected target(s).");
        }
    }

    private void selectLocalDirectory(TextField targetField) {
        selectLocalDirectoryPath("Select local directory", targetField.getText())
            .ifPresent(targetField::setText);
    }

    private Optional<String> selectLocalDirectoryPath(String title, String currentPath) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        String current = currentPath;
        if (current != null && !current.isBlank()) {
            File initial = new File(current);
            if (initial.isFile()) {
                initial = initial.getParentFile();
            }
            if (initial != null && initial.isDirectory()) {
                chooser.setInitialDirectory(initial);
            }
        }
        File selected = chooser.showDialog(getDialogPane().getScene().getWindow());
        return selected != null ? Optional.of(selected.getAbsolutePath()) : Optional.empty();
    }

    private void selectRemoteDirectory(TextField targetField, boolean useExactInitialPath) {
        selectRemoteDirectoryValue(targetField.getText(), useExactInitialPath)
            .ifPresent(targetField::setText);
    }

    private Optional<String> selectRemoteDirectoryValue(String currentPath, boolean useExactInitialPath) {
        ServerConnection target = requireSingleRemoteTarget();
        if (target == null) {
            return Optional.empty();
        }
        String initialPath = useExactInitialPath
            ? nonBlank(currentPath, ".")
            : initialRemoteDirectory(currentPath);
        JobSchedulerRemoteDirectoryDialog dialog = new JobSchedulerRemoteDirectoryDialog(
            app,
            schedulerService,
            getDialogPane().getScene().getWindow(),
            target.getId(),
            initialPath,
            hostKeyVerificationDisabledCheck.isSelected());
        return dialog.showAndWait();
    }

    private void selectRsyncSourcePath() {
        if (rsyncDirectionCombo.getSelectionModel().getSelectedItem() == RsyncDirection.DOWNLOAD) {
            selectRemoteDirectoryValue("", false).ifPresent(path -> appendLine(rsyncSourcesArea, path));
            return;
        }
        selectLocalDirectoryPath("Select Rsync source directory", "")
            .ifPresent(path -> appendLine(rsyncSourcesArea, path));
    }

    private void selectRsyncTargetRoot() {
        if (rsyncDirectionCombo.getSelectionModel().getSelectedItem() == RsyncDirection.DOWNLOAD) {
            selectLocalDirectoryPath("Select Rsync target directory", rsyncTargetRootField.getText())
                .ifPresent(rsyncTargetRootField::setText);
            return;
        }
        selectRemoteDirectoryValue(rsyncTargetRootField.getText(), false)
            .ifPresent(rsyncTargetRootField::setText);
    }

    private void appendLine(TextArea area, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String current = area.getText();
        if (current == null || current.isBlank()) {
            area.setText(value);
            return;
        }
        area.setText(current.stripTrailing() + "\n" + value);
    }

    private void selectRemoteOwner() {
        selectRemoteAccountValue(
            "Remote owners",
            "Select a remote owner",
            "Owners",
            "owners",
            "No users were returned by the remote server.",
            "Remote users could not be loaded.",
            "if command -v getent >/dev/null 2>&1; then getent passwd | cut -d: -f1; else cut -d: -f1 /etc/passwd; fi",
            ownerField,
            "JobScheduler-RemoteOwners");
    }

    private void selectRemoteGroup() {
        selectRemoteAccountValue(
            "Remote groups",
            "Select a remote group",
            "Groups",
            "groups",
            "No groups were returned by the remote server.",
            "Remote groups could not be loaded.",
            "if command -v getent >/dev/null 2>&1; then getent group | cut -d: -f1; else cut -d: -f1 /etc/group; fi",
            groupField,
            "JobScheduler-RemoteGroups");
    }

    private void selectRemoteAccountValue(
        String title,
        String selectionHeader,
        String listLabel,
        String loadingNoun,
        String emptyMessage,
        String failureMessage,
        String remoteCommand,
        TextField targetField,
        String threadName) {

        ServerConnection target = requireSingleRemoteTarget();
        if (target == null) {
            return;
        }
        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(42, 42);
        Label loadingLabel = new Label("Loading " + loadingNoun + " from " + target.getDisplayName() + "...");
        loadingLabel.setWrapText(true);
        VBox progressContent = new VBox(10, loadingLabel, progress);
        progressContent.setPadding(new Insets(10));
        Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
        DialogThemeHelper.applyTheme(progressAlert);
        progressAlert.initOwner(getDialogPane().getScene().getWindow());
        progressAlert.setTitle(title);
        progressAlert.setHeaderText("Loading remote " + loadingNoun + "...");
        progressAlert.getDialogPane().setContent(progressContent);
        progressAlert.getDialogPane().setPrefSize(420, 160);
        progressAlert.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL);

        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                try (JobSchedulerRemoteSession remote = openRemoteSession(target)) {
                    JobSchedulerRemoteSession.CommandResult result = remote.execute(
                        "sh -lc " + shellQuote(remoteCommand));
                    if (!result.isSuccess()) {
                        throw new IllegalStateException(result.stderr() != null && !result.stderr().isBlank()
                            ? result.stderr().trim()
                            : title + " list command failed.");
                    }
                    return Arrays.stream(result.stdout().split("\\R"))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .distinct()
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
                }
            }
        };
        task.setOnSucceeded(event -> {
            progressAlert.close();
            List<String> values = task.getValue();
            if (values.isEmpty()) {
                showError(title, emptyMessage);
                return;
            }
            Platform.runLater(() -> showRemoteValueSelection(title, selectionHeader, listLabel, values, targetField));
        });
        task.setOnFailed(event -> {
            progressAlert.close();
            Throwable error = task.getException();
            showError(title, error != null && error.getMessage() != null ? error.getMessage() : failureMessage);
        });
        Thread worker = new Thread(task, threadName);
        worker.setDaemon(true);
        worker.start();
        progressAlert.show();
    }

    private void showRemoteValueSelection(
        String title,
        String header,
        String listLabel,
        List<String> values,
        TextField targetField) {

        Dialog<String> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(getDialogPane().getScene().getWindow());
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setResizable(true);

        String initialValue = initialSelectedRemoteValue(values, targetField.getText());
        ComboBox<String> valueCombo = new ComboBox<>(FXCollections.observableArrayList(values));
        valueCombo.setMaxWidth(Double.MAX_VALUE);
        valueCombo.setPrefWidth(460);
        valueCombo.setVisibleRowCount(Math.min(18, Math.max(1, values.size())));
        valueCombo.getSelectionModel().select(initialValue);
        Label selectedLabel = new Label();
        selectedLabel.setWrapText(true);
        selectedLabel.setText("Selected: " + nonBlank(initialValue, ""));
        valueCombo.valueProperty().addListener((obs, oldValue, newValue) ->
            selectedLabel.setText("Selected: " + nonBlank(newValue, "")));

        Label listTitleLabel = new Label(listLabel);
        VBox content = new VBox(8, listTitleLabel, valueCombo, selectedLabel);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(540, 220);

        ButtonType selectType = new ButtonType("Select", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(selectType, ButtonType.CANCEL);
        Button selectButton = (Button) dialog.getDialogPane().lookupButton(selectType);
        selectButton.disableProperty().bind(valueCombo.getSelectionModel().selectedItemProperty().isNull());
        dialog.setOnShown(event -> Platform.runLater(valueCombo::requestFocus));
        dialog.setResultConverter(buttonType -> buttonType == selectType
            ? valueCombo.getValue()
            : null);
        dialog.showAndWait().ifPresent(targetField::setText);
    }

    private String initialSelectedRemoteValue(List<String> values, String currentValue) {
        String trimmed = currentValue != null ? currentValue.trim() : "";
        if (!trimmed.isEmpty() && values.contains(trimmed)) {
            return trimmed;
        }
        return values.isEmpty() ? "" : values.get(0);
    }

    private ServerConnection requireSingleRemoteTarget() {
        List<ServerConnection> targets = resolveSelectedTargetConnections();
        if (targets.size() != 1) {
            showError("Select one server", "Select exactly one server target before browsing remote data.");
            return null;
        }
        return targets.get(0);
    }

    private JobSchedulerRemoteSession openRemoteSession(ServerConnection selectedTarget) throws Exception {
        ServerConnection connection = new JobSchedulerConnectionResolver(app).resolve(selectedTarget.getId());
        Optional<PinnedHostKey> pinnedHostKey = schedulerService.findPinnedHostKey(connection.getId());
        boolean hostKeyVerificationDisabled = hostKeyVerificationDisabledCheck.isSelected();
        if (!hostKeyVerificationDisabled && pinnedHostKey.isEmpty()) {
            throw new IllegalStateException("Host key is not pinned for this server. Confirm the host key first.");
        }
        char[] masterPassword = app.getMasterPasswordManager() != null
            ? app.getMasterPasswordManager().getMasterPassword()
            : null;
        JobSchedulerRemoteSession remote = new JobSchedulerRemoteSession(
            app,
            connection,
            pinnedHostKey.orElse(null),
            masterPassword,
            hostKeyVerificationDisabled);
        remote.connect();
        return remote;
    }

    private String initialRemoteDirectory(String value) {
        String path = value != null ? value.trim() : "";
        if (path.isEmpty()) {
            return ".";
        }
        if (path.endsWith("/") || !path.contains("/")) {
            return path;
        }
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    private void selectTargets() {
        JobSchedulerTargetSelectionDialog dialog = new JobSchedulerTargetSelectionDialog(
            getDialogPane().getScene().getWindow(),
            app.getConfigManager().getConnections(),
            selectedConnectionIds,
            selectedGroupNames);
        dialog.showAndWait().ifPresent(selection -> {
            selectedConnectionIds.clear();
            selectedConnectionIds.addAll(selection.connectionIds());
            selectedGroupNames.clear();
            selectedGroupNames.addAll(selection.groupNames());
            updateConnectionSummary();
            updateHostKeyLabel();
        });
    }

    private void updateConnectionSummary() {
        connectionSummaryField.setText(nonBlank(targetSummary(), ""));
    }

    private String targetSummary() {
        List<String> parts = new ArrayList<>();
        if (!selectedConnectionIds.isEmpty()) {
            List<String> names = selectedConnectionIds.stream()
                .map(id -> findConnectionById(id)
                    .map(ServerConnection::getDisplayName)
                    .orElse(id))
                .toList();
            parts.add(names.size() == 1 ? names.get(0) : names.size() + " servers");
        }
        if (!selectedGroupNames.isEmpty()) {
            parts.add(selectedGroupNames.size() == 1
                ? "Group: " + selectedGroupNames.get(0)
                : selectedGroupNames.size() + " groups");
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private List<ServerConnection> resolveSelectedTargetConnections() {
        Map<String, ServerConnection> targets = new LinkedHashMap<>();
        for (String connectionId : selectedConnectionIds) {
            findConnectionById(connectionId).ifPresent(connection -> targets.put(connection.getId(), connection));
        }
        for (String groupName : selectedGroupNames) {
            for (ServerConnection connection : connections) {
                if (connectionMatchesGroup(connection, groupName)) {
                    if (connection.getId() == null || connection.getId().isBlank()) {
                        continue;
                    }
                    targets.putIfAbsent(connection.getId(), connection);
                }
            }
        }
        return new ArrayList<>(targets.values());
    }

    private Optional<ServerConnection> findConnectionById(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return Optional.empty();
        }
        return connections.stream()
            .filter(connection -> connectionId.equals(connection.getId()))
            .findFirst();
    }

    private boolean connectionMatchesGroup(ServerConnection connection, String selectedGroup) {
        String connectionGroup = connection != null ? connection.getGroup() : null;
        String normalizedGroup = selectedGroup != null ? selectedGroup.trim() : "";
        return connectionGroup != null
            && !normalizedGroup.isBlank()
            && (connectionGroup.equals(normalizedGroup) || connectionGroup.startsWith(normalizedGroup + "/"));
    }

    private HBox fieldButtonBox(TextField field, Button button) {
        HBox box = new HBox(8, field, button);
        HBox.setHgrow(field, Priority.ALWAYS);
        field.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private HBox fieldButtonBox(TextArea area, Button button) {
        HBox box = new HBox(8, area, button);
        HBox.setHgrow(area, Priority.ALWAYS);
        area.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private void addFixedTime() {
        String selected = fixedTimeCombo.getValue();
        if (!isValidTime(selected) || fixedTimes.contains(selected)) {
            return;
        }
        fixedTimes.add(selected);
        FXCollections.sort(fixedTimes, Comparator.comparing(LocalTime::parse));
    }

    private void removeFixedTime() {
        String selected = fixedTimesList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            fixedTimes.remove(selected);
        }
    }

    private void selectTime(ComboBox<String> combo, String value, String fallback) {
        String selected = isValidTime(value) ? value : fallback;
        if (selected != null && !combo.getItems().contains(selected)) {
            combo.getItems().add(selected);
            FXCollections.sort(combo.getItems(), Comparator.comparing(LocalTime::parse));
        }
        combo.getSelectionModel().select(selected);
    }

    private List<String> timeValues() {
        List<String> values = new ArrayList<>();
        for (int minutes = 0; minutes < 24 * 60; minutes += 5) {
            values.add(LocalTime.of(minutes / 60, minutes % 60).format(TIME_FORMATTER));
        }
        if (!values.contains("23:59")) {
            values.add("23:59");
        }
        return values;
    }

    private boolean isValidTime(String value) {
        if (value == null || !value.matches("\\d{2}:\\d{2}")) {
            return false;
        }
        try {
            LocalTime.parse(value, TIME_FORMATTER);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private boolean isValidPermissions(String permissions) {
        return permissions.matches("[0-7]{3,4}")
            || permissions.matches("([ugoa]*[+=-][rwxXstugo]+)(,([ugoa]*[+=-][rwxXstugo]+))*");
    }

    private String readEncryptedArchivePassword(JobArchiveFormat archiveFormat) throws Exception {
        if (archiveFormat != JobArchiveFormat.ZIP_PASSWORD) {
            loadedEncryptedArchivePassword = null;
            return null;
        }
        String enteredPassword = archivePasswordField.getText();
        if (enteredPassword != null && !enteredPassword.isBlank()) {
            char[] master = requireMasterPassword();
            loadedEncryptedArchivePassword = encryptionService.encryptPassword(enteredPassword, master);
            archivePasswordField.clear();
            return loadedEncryptedArchivePassword;
        }
        if (loadedEncryptedArchivePassword != null && !loadedEncryptedArchivePassword.isBlank()) {
            return loadedEncryptedArchivePassword;
        }
        throw new IllegalArgumentException("Archive password is required for password-protected ZIP jobs.");
    }

    private void updateArchivePasswordState() {
        boolean passwordZip = archiveFormatCombo.getSelectionModel().getSelectedItem() == JobArchiveFormat.ZIP_PASSWORD;
        archivePasswordField.setDisable(!passwordZip);
        archivePasswordStatusLabel.setDisable(!passwordZip);
        if (!passwordZip) {
            archivePasswordField.clear();
            archivePasswordStatusLabel.setText("");
            return;
        }
        archivePasswordStatusLabel.setText(loadedEncryptedArchivePassword != null && !loadedEncryptedArchivePassword.isBlank()
            ? "Stored password exists. Leave empty to keep it."
            : "Password is required for this archive format.");
    }

    private void configureWeekdaySelectionControls() {
        allWeekdaysCheck.setOnAction(event -> setAllWeekdaysSelected(allWeekdaysCheck.isSelected()));
        for (CheckBox weekdayCheck : weekdayChecks) {
            weekdayCheck.selectedProperty().addListener((observable, oldValue, newValue) -> syncAllWeekdaysCheck());
        }
        syncAllWeekdaysCheck();
    }

    private void setAllWeekdaysSelected(boolean selected) {
        updatingWeekdaySelection = true;
        try {
            for (CheckBox weekdayCheck : weekdayChecks) {
                weekdayCheck.setSelected(selected);
            }
        } finally {
            updatingWeekdaySelection = false;
        }
        syncAllWeekdaysCheck();
    }

    private void syncAllWeekdaysCheck() {
        if (updatingWeekdaySelection) {
            return;
        }
        updatingWeekdaySelection = true;
        try {
            allWeekdaysCheck.setSelected(Arrays.stream(weekdayChecks).allMatch(CheckBox::isSelected));
        } finally {
            updatingWeekdaySelection = false;
        }
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private void selectAiProfile(String profileId) {
        if (profileId == null) {
            aiProfileCombo.getSelectionModel().clearSelection();
            return;
        }
        for (String item : aiProfileCombo.getItems()) {
            if (item.startsWith(profileId + " -")) {
                aiProfileCombo.getSelectionModel().select(item);
                return;
            }
        }
        aiProfileCombo.getSelectionModel().clearSelection();
    }

    private String selectedAiProfileId() {
        String value = aiProfileCombo.getSelectionModel().getSelectedItem();
        if (value == null || value.isBlank()) {
            return null;
        }
        int index = value.indexOf(" -");
        return index > 0 ? value.substring(0, index) : value;
    }

    private GridPane formGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(8));
        return grid;
    }

    private void addRow(GridPane grid, int row, String label, javafx.scene.Node control) {
        Label labelNode = new Label(label);
        labelNode.setMinWidth(FORM_LABEL_MIN_WIDTH);
        grid.add(labelNode, 0, row);
        grid.add(control, 1, row);
        GridPane.setValignment(labelNode, VPos.TOP);
        GridPane.setValignment(control, VPos.TOP);
        GridPane.setHgrow(control, Priority.ALWAYS);
        if (control instanceof TextField textField) {
            textField.setMaxWidth(Double.MAX_VALUE);
        }
        if (control instanceof TextArea textArea) {
            textArea.setMaxWidth(Double.MAX_VALUE);
        }
        if (control instanceof ComboBox<?> comboBox) {
            if (comboBox.getMaxWidth() == Region.USE_COMPUTED_SIZE) {
                comboBox.setMaxWidth(Double.MAX_VALUE);
            }
        }
    }

    private void configureCompactTimeCombo(ComboBox<String> comboBox) {
        comboBox.setMinWidth(TIME_COMBO_WIDTH);
        comboBox.setPrefWidth(TIME_COMBO_WIDTH);
        comboBox.setMaxWidth(Region.USE_PREF_SIZE);
    }

    private VBox padded(VBox box) {
        box.setPadding(new Insets(12));
        return box;
    }

    private void restoreGeometry() {
        try {
            WindowGeometry geometry = app.getGlobalSettingsManager().getSettings().getJobSchedulerDialogGeometry();
            if (geometry == null || geometry.getWidth() <= 100 || geometry.getHeight() <= 100) {
                return;
            }
            getDialogPane().setPrefWidth(geometry.getWidth());
            getDialogPane().setPrefHeight(geometry.getHeight());
            setOnShowing(event -> {
                Window window = getDialogPane().getScene().getWindow();
                if (window instanceof Stage stage) {
                    stage.setX(geometry.getX());
                    stage.setY(geometry.getY());
                    stage.setWidth(geometry.getWidth());
                    stage.setHeight(geometry.getHeight());
                    stage.setMaximized(geometry.isMaximized());
                }
            });
        } catch (Exception e) {
            logger.debug("Could not restore JobScheduler geometry", e);
        }
    }

    private void installGeometryPersistence() {
        if (geometryListenersInstalled) {
            return;
        }
        Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
        if (!(window instanceof Stage stage)) {
            return;
        }
        geometryListenersInstalled = true;
        geometrySaveDelay.setOnFinished(event -> saveGeometry());
        ChangeListener<Number> numberListener = (observable, oldValue, newValue) -> geometrySaveDelay.playFromStart();
        ChangeListener<Boolean> booleanListener = (observable, oldValue, newValue) -> geometrySaveDelay.playFromStart();
        stage.xProperty().addListener(numberListener);
        stage.yProperty().addListener(numberListener);
        stage.widthProperty().addListener(numberListener);
        stage.heightProperty().addListener(numberListener);
        stage.maximizedProperty().addListener(booleanListener);
    }

    private void saveGeometry() {
        try {
            Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
            if (window instanceof Stage stage) {
                WindowGeometry geometry = new WindowGeometry(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
                geometry.setMaximized(stage.isMaximized());
                app.getGlobalSettingsManager().getSettings().setJobSchedulerDialogGeometry(geometry);
                app.getGlobalSettingsManager().save();
            }
        } catch (Exception e) {
            logger.debug("Could not save JobScheduler geometry", e);
        }
    }

    private static Label iconLabel(String symbol, int fontSizePx) {
        Label label = new Label(symbol);
        label.setStyle("-fx-font-size: " + fontSizePx + "px;");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    private List<String> lines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\R"))
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .toList();
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String formatJournalTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return ZonedDateTime.parse(value)
                .withZoneSameInstant(ZoneId.systemDefault())
                .format(JOURNAL_TIMESTAMP_FORMAT);
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(value)
                    .atZone(ZoneId.systemDefault())
                    .format(JOURNAL_TIMESTAMP_FORMAT);
            } catch (DateTimeParseException ignored) {
                return value;
            }
        }
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message != null && !message.isBlank() ? message : "Unknown error.");
        alert.showAndWait();
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message != null ? message : "");
        alert.showAndWait();
    }

    private String safeThrowableMessage(Throwable error) {
        if (error == null) {
            return "Unknown error.";
        }
        if (error.getMessage() != null && !error.getMessage().isBlank()) {
            return error.getMessage().trim();
        }
        Throwable cause = error.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage().trim();
        }
        return error.getClass().getSimpleName();
    }
}
