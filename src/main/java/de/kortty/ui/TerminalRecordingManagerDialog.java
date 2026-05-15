package de.kortty.ui;

import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.TerminalRecordingExportFormat;
import de.kortty.core.TerminalRecordingExportOptions;
import de.kortty.core.TerminalRecordingReplayFrame;
import de.kortty.core.TerminalRecordingReplayTimeline;
import de.kortty.core.TerminalRecordingRuntimeState;
import de.kortty.core.TerminalRecordingService;
import de.kortty.core.TerminalRecordingService.ExportProgress;
import de.kortty.core.TerminalRecordingTimeJumpParser;
import de.kortty.core.TerminalRecordingTimeRange;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalRecordingFormat;
import de.kortty.model.TerminalRecordingScope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;

public class TerminalRecordingManagerDialog extends ThemeAwareDialog<Void> {

    private static final String ICON_BROWSE =
        "M10 4l2 2h8c.55 0 1 .45 1 1v11c0 .55-.45 1-1 1H4c-.55 0-1-.45-1-1V5c0-.55.45-1 1-1h6z";
    private static final String ICON_CHECK =
        "M9 16.2l-3.5-3.5L4 14.2 9 19 20 8l-1.5-1.5z";
    private static final String ICON_REFRESH =
        "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-8 3.58-8 8s3.58 8 8 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h8V3z";
    private static final String ICON_PLAY =
        "M8 5v14l11-7z";
    private static final String ICON_RENAME =
        "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75z";
    private static final String ICON_DELETE =
        "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM8 9h8v10H8V9zm7.5-5l-1-1h-5l-1 1H5v2h14V4z";
    private static final String ICON_EXPORT =
        "M14 3h7v7h-2V6.41l-9.29 9.3-1.42-1.42 9.3-9.29H14V3zM5 5h7v2H7v10h10v-5h2v7H5z";
    private static final String ICON_SAVE =
        "M17 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V7l-4-4zM12 19c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zM15 9H5V5h10z";

    private final GlobalSettingsManager settingsManager;
    private final TerminalRecordingService recordingService;
    private final GlobalSettings settings;
    private final CheckBox recordingEnabledCheck = new CheckBox(I18n.get("recording.manager.enabled"));
    private final TextField storagePathField = new TextField();
    private final ComboBox<TerminalRecordingFormat> formatCombo = new ComboBox<>();
    private final ComboBox<TerminalRecordingScope> scopeCombo = new ComboBox<>();
    private final CheckBox captureColorsCheck = new CheckBox(I18n.get("recording.manager.captureColors"));
    private final CheckBox autoPauseCheck = new CheckBox(I18n.get("recording.manager.autoPause"));
    private final Spinner<Integer> idleSecondsSpinner = new Spinner<>(1, 3600, 20);
    private final TextField ffmpegPathField = new TextField();
    private final Label ffmpegStatusLabel = new Label();
    private final ListView<Path> replayList = new ListView<>();
    private final Button viewButton = new Button(I18n.get("recording.manager.view"));
    private final Button renameButton = new Button(I18n.get("recording.manager.rename"));
    private final Button deleteButton = new Button(I18n.get("recording.manager.delete"));
    private final Button exportButton = new Button(I18n.get("recording.manager.export"));
    private boolean ffmpegAvailable;

    public TerminalRecordingManagerDialog(
        GlobalSettingsManager settingsManager,
        TerminalRecordingService recordingService) {
        this.settingsManager = settingsManager;
        this.recordingService = recordingService;
        this.settings = settingsManager.getSettings();

        setTitle(I18n.get("recording.manager.title"));
        setHeaderText(I18n.get("recording.manager.header"));
        setResizable(true);
        initModality(Modality.NONE);
        buildUi();
        loadSettingsIntoControls();
        refreshRecordings();
        refreshFfmpegStatus();
    }

    private void buildUi() {
        storagePathField.setPrefColumnCount(36);
        ffmpegPathField.setPrefColumnCount(36);
        idleSecondsSpinner.setEditable(true);
        formatCombo.getItems().addAll(TerminalRecordingFormat.KORTTY_REPLAY, TerminalRecordingFormat.WEBM);
        scopeCombo.getItems().addAll(TerminalRecordingScope.ACTIVE_SPLIT, TerminalRecordingScope.WHOLE_TAB);

        Button browseStorage = new Button(I18n.get("recording.manager.browse"));
        browseStorage.setOnAction(event -> chooseRecordingDirectory());
        Button checkFfmpeg = new Button(I18n.get("recording.manager.checkFfmpeg"));
        checkFfmpeg.setOnAction(event -> refreshFfmpegStatus());
        Button refreshButton = new Button(I18n.get("recording.manager.refresh"));
        refreshButton.setOnAction(event -> refreshRecordings());
        setButtonIcon(browseStorage, ICON_BROWSE);
        setButtonIcon(checkFfmpeg, ICON_CHECK);
        setButtonIcon(refreshButton, ICON_REFRESH);
        setButtonIcon(viewButton, ICON_PLAY);
        setButtonIcon(renameButton, ICON_RENAME);
        setButtonIcon(deleteButton, ICON_DELETE);
        setButtonIcon(exportButton, ICON_EXPORT);
        viewButton.setOnAction(event -> viewSelectedReplay());
        renameButton.setOnAction(event -> renameSelectedReplay());
        deleteButton.setOnAction(event -> deleteSelectedReplay());
        exportButton.setOnAction(event -> exportSelectedReplay());
        replayList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> updateActionButtons());

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.setPadding(new Insets(12));
        int row = 0;
        form.add(recordingEnabledCheck, 1, row++);
        form.add(new Label(I18n.get("recording.manager.storagePath")), 0, row);
        HBox pathBox = new HBox(8, storagePathField, browseStorage);
        HBox.setHgrow(storagePathField, Priority.ALWAYS);
        form.add(pathBox, 1, row++);
        form.add(new Label(I18n.get("recording.manager.format")), 0, row);
        form.add(formatCombo, 1, row++);
        form.add(new Label(I18n.get("recording.manager.defaultScope")), 0, row);
        form.add(scopeCombo, 1, row++);
        form.add(captureColorsCheck, 1, row++);
        form.add(autoPauseCheck, 1, row++);
        form.add(new Label(I18n.get("recording.manager.idleSeconds")), 0, row);
        form.add(idleSecondsSpinner, 1, row++);
        form.add(new Label(I18n.get("recording.manager.ffmpegPath")), 0, row);
        HBox ffmpegBox = new HBox(8, ffmpegPathField, checkFfmpeg);
        HBox.setHgrow(ffmpegPathField, Priority.ALWAYS);
        form.add(ffmpegBox, 1, row++);
        form.add(ffmpegStatusLabel, 1, row);

        replayList.setPrefHeight(220);
        HBox replayActions = new HBox(8, refreshButton, viewButton, renameButton, deleteButton, exportButton);
        VBox content = new VBox(
            10,
            form,
            new Label(I18n.get("recording.manager.recordings")),
            replayList,
            replayActions);
        content.setPadding(new Insets(8));
        VBox.setVgrow(replayList, Priority.ALWAYS);
        getDialogPane().setContent(content);
        getDialogPane().setPrefWidth(760);
        getDialogPane().setPrefHeight(620);

        ButtonType saveButtonType = new ButtonType(I18n.get("recording.manager.save"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CLOSE);
        Button saveButton = (Button) getDialogPane().lookupButton(saveButtonType);
        setButtonIcon(saveButton, ICON_SAVE);
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (!saveSettings()) {
                event.consume();
            }
        });
        setResultConverter(button -> null);
    }

    private void loadSettingsIntoControls() {
        recordingEnabledCheck.setSelected(TerminalRecordingRuntimeState.isSessionRecordingEnabled());
        storagePathField.setText(settings.getTerminalRecordingStoragePath() != null
            ? settings.getTerminalRecordingStoragePath()
            : TerminalRecordingService.resolveRecordingDirectory(settings).toString());
        formatCombo.setValue(settings.getTerminalRecordingFormat());
        scopeCombo.setValue(settings.getTerminalRecordingDefaultScope());
        captureColorsCheck.setSelected(settings.isTerminalRecordingCaptureColorsEnabled());
        autoPauseCheck.setSelected(settings.isTerminalRecordingAutoPauseEnabled());
        idleSecondsSpinner.getValueFactory().setValue(settings.getTerminalRecordingIdlePauseSeconds());
        ffmpegPathField.setText(settings.getTerminalRecordingFfmpegPath() != null
            ? settings.getTerminalRecordingFfmpegPath()
            : "");
    }

    private boolean saveSettings() {
        try {
            TerminalRecordingRuntimeState.setSessionRecordingEnabled(recordingEnabledCheck.isSelected());
            settings.setTerminalRecordingStoragePath(storagePathField.getText());
            settings.setTerminalRecordingFormat(formatCombo.getValue());
            settings.setTerminalRecordingDefaultScope(scopeCombo.getValue());
            settings.setTerminalRecordingCaptureColorsEnabled(captureColorsCheck.isSelected());
            settings.setTerminalRecordingAutoPauseEnabled(autoPauseCheck.isSelected());
            settings.setTerminalRecordingIdlePauseSeconds(idleSecondsSpinner.getValue());
            settings.setTerminalRecordingFfmpegPath(ffmpegPathField.getText());
            settingsManager.save();
            refreshRecordings();
            refreshFfmpegStatus();
            return true;
        } catch (Exception e) {
            showError(I18n.get("recording.manager.saveFailed", e.getMessage()));
            return false;
        }
    }

    private void chooseRecordingDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("recording.manager.chooseDirectory"));
        java.io.File selected = chooser.showDialog(getDialogPane().getScene().getWindow());
        if (selected != null) {
            storagePathField.setText(selected.toPath().toString());
        }
    }

    private void refreshRecordings() {
        try {
            List<Path> files = recordingService.listReplayFiles(settings);
            replayList.getItems().setAll(files);
            updateActionButtons();
        } catch (IOException e) {
            showError(I18n.get("recording.manager.refreshFailed", e.getMessage()));
        }
    }

    private void refreshFfmpegStatus() {
        boolean available = recordingService.isFfmpegAvailable(ffmpegPathField.getText());
        ffmpegAvailable = available;
        ffmpegStatusLabel.setText(available
            ? I18n.get("recording.manager.ffmpeg.available")
            : I18n.get("recording.manager.ffmpeg.missing"));
        updateActionButtons();
    }

    private void updateActionButtons() {
        boolean hasSelection = replayList.getSelectionModel().getSelectedItem() != null;
        viewButton.setDisable(!hasSelection);
        renameButton.setDisable(!hasSelection);
        deleteButton.setDisable(!hasSelection);
        exportButton.setDisable(!ffmpegAvailable || !hasSelection);
    }

    private void viewSelectedReplay() {
        Path replayFile = selectedReplayFile();
        if (replayFile == null) {
            return;
        }
        viewButton.setDisable(true);
        Task<List<TerminalRecordingReplayFrame>> task = new Task<>() {
            @Override
            protected List<TerminalRecordingReplayFrame> call() throws Exception {
                return recordingService.loadReplayFrames(replayFile);
            }
        };
        task.setOnSucceeded(event -> {
            updateActionButtons();
            TerminalRecordingReplayDialog dialog = new TerminalRecordingReplayDialog(replayFile, task.getValue());
            dialog.initOwner(getDialogPane().getScene().getWindow());
            dialog.showAndWait();
        });
        task.setOnFailed(event -> {
            updateActionButtons();
            Throwable failure = task.getException();
            showError(I18n.get("recording.manager.viewFailed", failure != null ? failure.getMessage() : ""));
        });
        Thread thread = new Thread(task, "TerminalRecordingReplayLoad");
        thread.setDaemon(true);
        thread.start();
    }

    private void renameSelectedReplay() {
        Path replayFile = selectedReplayFile();
        if (replayFile == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog(stripReplayExtension(replayFile.getFileName().toString()));
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(getDialogPane().getScene().getWindow());
        dialog.setTitle(I18n.get("recording.manager.rename.title"));
        dialog.setHeaderText(I18n.get("recording.manager.rename.header"));
        dialog.setContentText(I18n.get("recording.manager.rename.content"));
        dialog.showAndWait().ifPresent(name -> {
            try {
                Path renamed = recordingService.renameReplayFile(replayFile, name);
                refreshRecordings();
                replayList.getSelectionModel().select(renamed);
                showInfo(I18n.get("recording.manager.renameSuccess", renamed.getFileName()));
            } catch (IOException e) {
                showError(I18n.get("recording.manager.renameFailed", e.getMessage()));
            }
        });
    }

    private void deleteSelectedReplay() {
        Path replayFile = selectedReplayFile();
        if (replayFile == null) {
            return;
        }
        javafx.scene.control.Alert alert =
            new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.setTitle(I18n.get("recording.manager.delete.title"));
        alert.setHeaderText(I18n.get("recording.manager.delete.header"));
        alert.setContentText(I18n.get("recording.manager.delete.content", replayFile.getFileName()));
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        try {
            recordingService.deleteReplayFile(replayFile);
            refreshRecordings();
            showInfo(I18n.get("recording.manager.deleteSuccess", replayFile.getFileName()));
        } catch (IOException e) {
            showError(I18n.get("recording.manager.deleteFailed", e.getMessage()));
        }
    }

    private void exportSelectedReplay() {
        Path replayFile = selectedReplayFile();
        if (replayFile == null) {
            return;
        }
        exportButton.setDisable(true);
        Task<List<TerminalRecordingReplayFrame>> task = new Task<>() {
            @Override
            protected List<TerminalRecordingReplayFrame> call() throws Exception {
                updateProgress(-1.0, 1.0);
                updateMessage(I18n.get("recording.manager.exportProgress.preparing"));
                return recordingService.loadReplayFrames(replayFile);
            }
        };
        Dialog<Void> progressDialog = createExportProgressDialog(task);
        task.setOnSucceeded(event -> {
            closeExportProgressDialog(progressDialog);
            updateActionButtons();
            continueExportSelectedReplay(replayFile, task.getValue());
        });
        task.setOnFailed(event -> {
            closeExportProgressDialog(progressDialog);
            updateActionButtons();
            Throwable failure = task.getException();
            showError(I18n.get("recording.manager.exportFailed", failure != null ? failure.getMessage() : ""));
        });
        task.setOnCancelled(event -> {
            closeExportProgressDialog(progressDialog);
            updateActionButtons();
        });
        Thread thread = new Thread(task, "TerminalRecordingExportLoad");
        thread.setDaemon(true);
        progressDialog.show();
        thread.start();
    }

    private void continueExportSelectedReplay(Path replayFile, List<TerminalRecordingReplayFrame> frames) {
        TerminalRecordingReplayTimeline timeline = new TerminalRecordingReplayTimeline(frames);
        TerminalRecordingExportOptions options = showExportOptionsDialog(
            timeline.totalDurationSeconds(),
            frames.stream().anyMatch(TerminalRecordingReplayFrame::hasColorData));
        if (options == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("recording.manager.export"));
        chooser.setInitialFileName(exportFileName(replayFile, options.format()));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            options.format().getDisplayName(),
            "*." + options.format().getExtension()));
        java.io.File selected = chooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (selected == null) {
            return;
        }
        String ffmpegPath = ffmpegPathField.getText();
        exportButton.setDisable(true);
        Task<Path> task = new Task<>() {
            private final long startedAtNanos = System.nanoTime();

            @Override
            protected Path call() throws Exception {
                return recordingService.exportReplay(
                    replayFile,
                    ensureExportExtension(selected.toPath(), options.format()),
                    ffmpegPath,
                    options,
                    progress -> {
                        updateProgress(progress.fraction(), 1.0);
                        updateMessage(exportProgressMessage(progress, startedAtNanos));
                    });
            }
        };
        Dialog<Void> progressDialog = createExportProgressDialog(task);
        task.setOnSucceeded(event -> {
            closeExportProgressDialog(progressDialog);
            updateActionButtons();
            showInfo(I18n.get("recording.manager.exportSuccess", task.getValue()));
        });
        task.setOnFailed(event -> {
            closeExportProgressDialog(progressDialog);
            updateActionButtons();
            Throwable failure = task.getException();
            showError(I18n.get("recording.manager.exportFailed", failure != null ? failure.getMessage() : ""));
        });
        task.setOnCancelled(event -> {
            closeExportProgressDialog(progressDialog);
            updateActionButtons();
        });
        Thread thread = new Thread(task, "TerminalRecordingExport");
        thread.setDaemon(true);
        progressDialog.show();
        thread.start();
    }

    private TerminalRecordingExportOptions showExportOptionsDialog(double totalDurationSeconds, boolean hasColorData) {
        Dialog<TerminalRecordingExportOptions> dialog = new ThemeAwareDialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(getDialogPane().getScene().getWindow());
        dialog.setTitle(I18n.get("recording.manager.exportOptions.title"));
        dialog.setHeaderText(I18n.get("recording.manager.exportOptions.header"));

        ComboBox<TerminalRecordingExportFormat> exportFormatCombo = new ComboBox<>();
        exportFormatCombo.getItems().addAll(TerminalRecordingExportFormat.WEBM, TerminalRecordingExportFormat.MKV);
        exportFormatCombo.setValue(TerminalRecordingExportFormat.WEBM);

        CheckBox allRangeCheck = new CheckBox(I18n.get("recording.manager.exportOptions.all"));
        allRangeCheck.setSelected(true);
        TextField startField = new TextField("0:00");
        TextField endField = new TextField(formatDuration((int) Math.round(totalDurationSeconds)));
        startField.setPrefColumnCount(8);
        endField.setPrefColumnCount(8);
        startField.disableProperty().bind(allRangeCheck.selectedProperty());
        endField.disableProperty().bind(allRangeCheck.selectedProperty());

        CheckBox includeColorCheck = new CheckBox(I18n.get("recording.manager.exportOptions.includeColor"));
        includeColorCheck.setSelected(hasColorData);
        includeColorCheck.setDisable(!hasColorData);

        Label durationLabel = new Label(I18n.get(
            "recording.manager.exportOptions.duration",
            formatDuration((int) Math.round(totalDurationSeconds))));
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #ff8a8a;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        int row = 0;
        grid.add(new Label(I18n.get("recording.manager.exportOptions.format")), 0, row);
        grid.add(exportFormatCombo, 1, row++);
        grid.add(durationLabel, 1, row++);
        grid.add(allRangeCheck, 1, row++);
        grid.add(new Label(I18n.get("recording.manager.exportOptions.start")), 0, row);
        grid.add(startField, 1, row++);
        grid.add(new Label(I18n.get("recording.manager.exportOptions.end")), 0, row);
        grid.add(endField, 1, row++);
        grid.add(includeColorCheck, 1, row++);
        grid.add(errorLabel, 1, row);

        ButtonType exportButtonType = new ButtonType(I18n.get("recording.manager.export"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(exportButtonType, ButtonType.CANCEL);
        TerminalRecordingExportOptions[] selectedOptions = new TerminalRecordingExportOptions[1];
        Button exportDialogButton = (Button) dialog.getDialogPane().lookupButton(exportButtonType);
        setButtonIcon(exportDialogButton, ICON_EXPORT);
        exportDialogButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            TerminalRecordingExportOptions parsed = parseExportOptions(
                exportFormatCombo.getValue(),
                allRangeCheck.isSelected(),
                startField.getText(),
                endField.getText(),
                includeColorCheck.isSelected(),
                totalDurationSeconds,
                errorLabel);
            if (parsed == null) {
                event.consume();
                return;
            }
            selectedOptions[0] = parsed;
        });
        dialog.setResultConverter(button -> button == exportButtonType ? selectedOptions[0] : null);
        return dialog.showAndWait().orElse(null);
    }

    private static TerminalRecordingExportOptions parseExportOptions(
        TerminalRecordingExportFormat format,
        boolean allRange,
        String startText,
        String endText,
        boolean includeColor,
        double totalDurationSeconds,
        Label errorLabel) {
        TerminalRecordingTimeRange range = TerminalRecordingTimeRange.all();
        if (!allRange) {
            OptionalDouble start = TerminalRecordingTimeJumpParser.parseSeconds(startText, totalDurationSeconds);
            OptionalDouble end = TerminalRecordingTimeJumpParser.parseSeconds(endText, totalDurationSeconds);
            if (start.isEmpty() || end.isEmpty() || end.getAsDouble() <= start.getAsDouble()) {
                errorLabel.setText(I18n.get(
                    "recording.manager.exportOptions.invalidRange",
                    formatDuration((int) Math.round(totalDurationSeconds))));
                return null;
            }
            range = TerminalRecordingTimeRange.custom(start.getAsDouble(), end.getAsDouble());
        }
        return new TerminalRecordingExportOptions(format, range, includeColor);
    }

    private static String exportFileName(Path replayFile, TerminalRecordingExportFormat format) {
        String baseName = stripReplayExtension(replayFile.getFileName().toString());
        return baseName + "." + format.getExtension();
    }

    private static Path ensureExportExtension(Path selected, TerminalRecordingExportFormat format) {
        String extension = "." + format.getExtension();
        String fileName = selected.getFileName().toString();
        if (fileName.toLowerCase(Locale.ROOT).endsWith(extension)) {
            return selected;
        }
        Path parent = selected.getParent();
        Path withExtension = Path.of(fileName + extension);
        return parent != null ? parent.resolve(withExtension) : withExtension;
    }

    private Dialog<Void> createExportProgressDialog(Task<?> task) {
        Dialog<Void> dialog = new ThemeAwareDialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(getDialogPane().getScene().getWindow());
        dialog.setTitle(I18n.get("recording.manager.exportProgress.title"));
        dialog.setHeaderText(I18n.get("recording.manager.exportProgress.header"));

        ProgressBar progressBar = new ProgressBar(0.0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.progressProperty().bind(task.progressProperty());
        Label messageLabel = new Label(I18n.get("recording.manager.exportProgress.preparing"));
        task.messageProperty().addListener((obs, oldMessage, newMessage) -> {
            if (newMessage != null && !newMessage.isBlank()) {
                messageLabel.setText(newMessage);
            }
        });

        VBox content = new VBox(10, progressBar, messageLabel);
        content.setPadding(new Insets(8));
        dialog.getDialogPane().setPrefWidth(460);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        Button cancelButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        dialog.setOnCloseRequest(event -> {
            if (task.isRunning()) {
                event.consume();
            }
        });
        return dialog;
    }

    private void closeExportProgressDialog(Dialog<Void> dialog) {
        if (dialog != null && dialog.isShowing()) {
            dialog.setResult(null);
            dialog.close();
        }
    }

    private static String exportProgressMessage(ExportProgress progress, long startedAtNanos) {
        String phaseMessage = switch (progress.phase()) {
            case PREPARING -> I18n.get("recording.manager.exportProgress.preparing");
            case RENDERING -> I18n.get(
                "recording.manager.exportProgress.rendering",
                progress.current(),
                progress.total());
            case ENCODING -> I18n.get(
                "recording.manager.exportProgress.encoding",
                formatDuration(progress.current()),
                formatDuration(progress.total()));
            case FINALIZING -> I18n.get("recording.manager.exportProgress.finalizing");
        };
        return I18n.get(
            "recording.manager.exportProgress.message",
            phaseMessage,
            estimatedRemaining(startedAtNanos, progress.fraction()));
    }

    private static String estimatedRemaining(long startedAtNanos, double fraction) {
        if (!Double.isFinite(fraction) || fraction <= 0.01) {
            return I18n.get("recording.manager.exportProgress.remaining.calculating");
        }
        if (fraction >= 1.0) {
            return formatDuration(0);
        }
        double elapsedSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0;
        double remainingSeconds = elapsedSeconds * ((1.0 - fraction) / fraction);
        return formatDuration((int) Math.max(0, Math.round(remainingSeconds)));
    }

    private static String formatDuration(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        int hours = safeSeconds / 3600;
        int minutes = (safeSeconds % 3600) / 60;
        int remainingSeconds = safeSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainingSeconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, remainingSeconds);
    }

    private Path selectedReplayFile() {
        return replayList.getSelectionModel().getSelectedItem();
    }

    private static String stripReplayExtension(String fileName) {
        String extension = TerminalRecordingService.replayExtension(fileName);
        return extension != null ? fileName.substring(0, fileName.length() - extension.length()) : fileName;
    }

    private static void setButtonIcon(Button button, String iconPath) {
        button.setGraphic(icon(iconPath));
        button.setGraphicTextGap(6);
    }

    private static Node icon(String path) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.setStyle("-fx-fill: -fx-text-base-color;");
        icon.setScaleX(0.72);
        icon.setScaleY(0.72);
        return icon;
    }

    private void showError(String message) {
        Platform.runLater(() -> {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
            DialogThemeHelper.applyTheme(alert);
            alert.initOwner(getDialogPane().getScene().getWindow());
            alert.setTitle(I18n.get("error.title"));
            alert.setHeaderText(I18n.get("recording.manager.error"));
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    private void showInfo(String message) {
        Platform.runLater(() -> {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
            DialogThemeHelper.applyTheme(alert);
            alert.initOwner(getDialogPane().getScene().getWindow());
            alert.setTitle(I18n.get("recording.manager.title"));
            alert.setHeaderText(I18n.get("recording.manager.done"));
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
}
