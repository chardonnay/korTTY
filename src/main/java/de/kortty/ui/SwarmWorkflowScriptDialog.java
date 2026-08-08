package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.core.WorkflowScriptSupport;
import de.kortty.core.WorkflowScriptSupport.HeaderFacts;
import de.kortty.core.WorkflowScriptSupport.ScriptLanguage;
import de.kortty.core.WorkflowScriptSupport.SwarmHost;
import de.kortty.core.WorkflowScriptSupport.SwarmScriptOption;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Generates a multi-server workflow script from the swarm's selected hosts and the last query. The
 * script iterates the host list and runs the per-host work on each. Host facts are secret-free; the
 * generator and prompt both forbid embedding passwords/key contents.
 *
 * <p>The script preview is a Monaco editor (syntax highlighting follows the selected language).
 * While the AI generates, a spinner plus a live elapsed timer are shown; the final status reports
 * the total duration. Extra user instructions are passed into the generation prompt and kept in a
 * de-duplicated 10-entry history. The result can be saved as a snippet with a pre-filled name.
 */
public final class SwarmWorkflowScriptDialog {

    private static final Logger logger = LoggerFactory.getLogger(SwarmWorkflowScriptDialog.class);

    private SwarmWorkflowScriptDialog() {
    }

    public static void open(MainWindow ownerWindow, AiProfile profile, String query,
                            List<ServerConnection> connections, Window owner) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("ai.workflow.swarm.title"));
        dialog.setResizable(true);
        if (owner != null) {
            dialog.initOwner(owner);
        }

        ComboBox<ScriptLanguage> languageCombo = new ComboBox<>();
        languageCombo.getItems().setAll(
            ScriptLanguage.BASH, ScriptLanguage.ANSIBLE, ScriptLanguage.PYTHON, ScriptLanguage.POWERSHELL);
        languageCombo.setValue(ScriptLanguage.BASH);
        languageCombo.setCellFactory(list -> languageCell());
        languageCombo.setButtonCell(languageCell());

        Map<SwarmScriptOption, CheckBox> optionChecks = new LinkedHashMap<>();
        TilePane optionsPane = new TilePane(10, 6);
        optionsPane.setPrefColumns(2);
        EnumSet<SwarmScriptOption> defaults = SwarmScriptOption.defaults();
        for (SwarmScriptOption option : SwarmScriptOption.values()) {
            CheckBox check = new CheckBox(I18n.get("ai.workflow.swarm.option." + option.name()));
            check.setSelected(defaults.contains(option));
            optionChecks.put(option, check);
            optionsPane.getChildren().add(check);
        }

        // Input hardening (AI-generated input guard; strictly opt-in per run). The swarm dialog has
        // no classic hardening panel — it silently applies HardeningOption.defaults() — but the
        // guard changes runtime behaviour, so it gets explicit UI here too. Grayed out for the
        // declarative Ansible target, where the guard rules render empty by design.
        InputHardeningSelector inputHardeningSelector = new InputHardeningSelector();
        TitledPane inputHardeningPane = new TitledPane(I18n.get("ai.inputHardening.title"), inputHardeningSelector);
        inputHardeningPane.setExpanded(false);
        Runnable updateInputHardeningSupport = () -> {
            boolean supported = languageCombo.getValue() == null || !languageCombo.getValue().isDeclarative();
            inputHardeningSelector.setSupported(supported);
            inputHardeningPane.setDisable(!supported);
        };
        languageCombo.valueProperty().addListener((obs, oldValue, newValue) -> updateInputHardeningSupport.run());
        updateInputHardeningSupport.run();

        MonacoEditorPane preview = new MonacoEditorPane();
        preview.setEditable(true);
        preview.setWrapText(false);
        EditorSettingsHelper.applyStyle(preview, EditorSettingsHelper.loadSnippetSettings());
        preview.setLanguage(languageCombo.getValue().snippetLanguage());
        preview.activate();
        languageCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                preview.setLanguage(newValue.snippetLanguage());
            }
        });

        TextArea instructionsArea = new TextArea();
        instructionsArea.setPrefRowCount(3);
        instructionsArea.setWrapText(true);
        instructionsArea.setPromptText(I18n.get("ai.workflow.instructions.prompt"));
        MenuButton historyButton = new MenuButton(I18n.get("ai.workflow.instructions.history"));
        rebuildHistoryMenu(historyButton, instructionsArea);

        int hostCount = connections != null ? connections.size() : 0;
        Label hostLabel = new Label(I18n.get("ai.workflow.swarm.hosts") + " " + hostCount);
        Label status = new Label();
        ProgressIndicator busyIndicator = new ProgressIndicator();
        busyIndicator.setPrefSize(24, 24);
        busyIndicator.setVisible(false);
        busyIndicator.setManaged(false);
        AtomicLong startedAtMillis = new AtomicLong();
        Timeline elapsedTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e ->
            status.setText(I18n.get("ai.workflow.generating.elapsed", formatElapsed(startedAtMillis.get())))));
        elapsedTimeline.setCycleCount(Timeline.INDEFINITE);
        AtomicReference<Task<?>> activeTask = new AtomicReference<>();

        Button generateButton = new Button(I18n.get("ai.workflow.generate"));
        Button copyButton = new Button(I18n.get("ai.workflow.copy"));
        copyButton.setOnAction(e -> {
            de.kortty.core.KorttyClipboard.setText(preview.textProperty().getValue());
            status.setText(I18n.get("ai.workflow.copied"));
        });
        Button saveSnippetButton = new Button(I18n.get("ai.result.saveSnippet"));
        saveSnippetButton.setTooltip(new Tooltip(I18n.get("ai.result.saveSnippet.tooltip")));
        saveSnippetButton.setOnAction(e ->
            saveAsSnippet(preview.textProperty().getValue(), languageCombo.getValue(), query, status));

        generateButton.setOnAction(e -> {
            ScriptLanguage language = languageCombo.getValue();
            EnumSet<SwarmScriptOption> selected = EnumSet.noneOf(SwarmScriptOption.class);
            for (Map.Entry<SwarmScriptOption, CheckBox> entry : optionChecks.entrySet()) {
                if (entry.getValue().isSelected()) {
                    selected.add(entry.getKey());
                }
            }
            String extraInstructions = instructionsArea.getText() != null
                ? instructionsArea.getText().trim()
                : "";
            recordInstructionsHistory(extraInstructions);
            rebuildHistoryMenu(historyButton, instructionsArea);
            HeaderFacts facts = new HeaderFacts(
                WorkflowScriptSupport.defaultScriptName(query, language),
                System.getProperty("user.name"), null, null, LocalDateTime.now(),
                query != null ? query : "", profile != null ? profile.getName() : "");
            WorkflowScriptGenerator.SwarmRequest request = new WorkflowScriptGenerator.SwarmRequest(
                language, WorkflowScriptSupport.HardeningOption.defaults(), selected,
                extraInstructions, facts, null, inputHardeningSelector.currentConfig());
            WorkflowScriptGenerator.SwarmRunExportData data = new WorkflowScriptGenerator.SwarmRunExportData(
                profile != null ? profile.getId() : null,
                profile != null ? profile.getName() : null,
                query, buildHosts(connections), null, null);

            generateButton.setDisable(true);
            busyIndicator.setVisible(true);
            busyIndicator.setManaged(true);
            startedAtMillis.set(System.currentTimeMillis());
            status.setText(I18n.get("ai.workflow.generating.elapsed", formatElapsed(startedAtMillis.get())));
            elapsedTimeline.playFromStart();
            Task<WorkflowScriptGenerator.Outcome> task = new Task<>() {
                @Override
                protected WorkflowScriptGenerator.Outcome call() {
                    return new WorkflowScriptGenerator(KorTTYApplication.getInstance()).generateSwarm(data, request);
                }
            };
            activeTask.set(task);
            task.setOnSucceeded(ev -> {
                // A success/failure event can already be queued on the FX thread when the dialog is
                // closed — cancel() on an already-finished task is a no-op, so this must also guard
                // against running after preview.dispose() rather than relying on cancel() alone.
                if (!dialog.isShowing() || task.isCancelled()) {
                    return;
                }
                elapsedTimeline.stop();
                busyIndicator.setVisible(false);
                busyIndicator.setManaged(false);
                WorkflowScriptGenerator.Outcome outcome = task.getValue();
                preview.replaceText(outcome != null ? outcome.script() : "");
                status.setText(I18n.get("ai.workflow.ready.duration", formatElapsed(startedAtMillis.get())));
                generateButton.setDisable(false);
                activeTask.compareAndSet(task, null);
            });
            task.setOnFailed(ev -> {
                if (!dialog.isShowing() || task.isCancelled()) {
                    return;
                }
                elapsedTimeline.stop();
                busyIndicator.setVisible(false);
                busyIndicator.setManaged(false);
                Throwable error = task.getException();
                status.setText(error != null && error.getMessage() != null
                    ? error.getMessage() : I18n.get("ai.result.error"));
                generateButton.setDisable(false);
                activeTask.compareAndSet(task, null);
            });
            Thread thread = new Thread(task, "ai-swarm-workflow");
            thread.setDaemon(true);
            thread.start();
        });
        // Tear down the Monaco WebView on close (SnippetEditDialog precedent) — otherwise every
        // dialog open leaks a native WebKit page with running Monaco workers until a late GC.
        dialog.setOnHidden(e -> {
            elapsedTimeline.stop();
            Task<?> running = activeTask.get();
            if (running != null) {
                running.cancel();
            }
            preview.dispose();
        });

        HBox controls = new HBox(8,
            new Label(I18n.get("ai.workflow.language")), languageCombo,
            generateButton, copyButton, saveSnippetButton, busyIndicator, status);
        controls.setAlignment(Pos.CENTER_LEFT);
        Region instructionsSpacer = new Region();
        HBox.setHgrow(instructionsSpacer, Priority.ALWAYS);
        HBox instructionsHeader = new HBox(8,
            new Label(I18n.get("ai.workflow.instructions")), instructionsSpacer, historyButton);
        instructionsHeader.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10,
            hostLabel, controls,
            new Label(I18n.get("ai.workflow.swarm.options")), optionsPane, inputHardeningPane,
            instructionsHeader, instructionsArea,
            preview);
        box.setPadding(new Insets(10));
        VBox.setVgrow(preview, Priority.ALWAYS);
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefSize(840, 720);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    // ---- Save as snippet -------------------------------------------------------

    private static void saveAsSnippet(String content, ScriptLanguage language, String query, Label status) {
        if (content == null || content.isBlank() || language == null) {
            return;
        }
        var snippetManager = KorTTYApplication.getInstance().getSnippetManager();
        if (snippetManager == null) {
            status.setText(I18n.get("ai.result.saveSnippet.failed"));
            return;
        }
        Snippet snippet = new Snippet();
        snippet.setName(WorkflowScriptSupport.defaultScriptName(query, language));
        snippet.setLanguage(SnippetLanguageSupport.detectSnippetLanguage(language.snippetLanguage(), content));
        snippet.setContent(content);
        List<String> categoryNames = snippetManager.getAllCategories().stream()
            .map(SnippetCategory::getName)
            .filter(Objects::nonNull)
            .sorted(String::compareToIgnoreCase)
            .toList();
        SnippetEditDialog editDialog = new SnippetEditDialog(snippet, categoryNames);
        editDialog.showNonBlocking(saved -> {
            try {
                String category = saved.getCategory();
                if (category != null && !category.isBlank()
                    && snippetManager.findCategoryByName(category.trim()).isEmpty()) {
                    snippetManager.addCategory(new SnippetCategory(category.trim()));
                }
                snippetManager.addSnippet(saved);
                snippetManager.save();
                status.setText(I18n.get("ai.result.saveSnippet.success", saved.getName()));
            } catch (Exception ex) {
                logger.warn("Could not save workflow script as snippet", ex);
                status.setText(I18n.get("ai.result.saveSnippet.failed"));
            }
        });
    }

    // ---- Instructions history ----------------------------------------------------

    private static List<String> instructionsHistory() {
        GlobalSettings settings = settingsOrNull();
        return settings != null ? settings.getWorkflowInstructionsHistory() : List.of();
    }

    private static void recordInstructionsHistory(String instructions) {
        if (instructions == null || instructions.isBlank()) {
            return;
        }
        try {
            GlobalSettings settings = settingsOrNull();
            if (settings != null) {
                settings.addWorkflowInstructionsHistoryEntry(instructions);
                KorTTYApplication.getInstance().getGlobalSettingsManager().save();
            }
        } catch (Exception e) {
            logger.debug("Could not persist workflow instructions history", e);
        }
    }

    private static GlobalSettings settingsOrNull() {
        try {
            KorTTYApplication app = KorTTYApplication.getInstance();
            return app != null && app.getGlobalSettingsManager() != null
                ? app.getGlobalSettingsManager().getSettings()
                : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void rebuildHistoryMenu(MenuButton button, TextArea target) {
        button.getItems().clear();
        List<String> entries = instructionsHistory();
        if (entries.isEmpty()) {
            MenuItem empty = new MenuItem(I18n.get("ai.workflow.instructions.history.empty"));
            empty.setMnemonicParsing(false);
            empty.setDisable(true);
            button.getItems().add(empty);
            return;
        }
        for (String entry : entries) {
            String label = entry.length() > 60 ? entry.substring(0, 57) + "…" : entry;
            MenuItem item = new MenuItem(label.replace('\n', ' '));
            // instructions frequently contain snake_case/paths; default mnemonic parsing eats "_"
            item.setMnemonicParsing(false);
            item.setOnAction(event -> {
                target.setText(entry);
                target.positionCaret(entry.length());
                target.requestFocus();
            });
            button.getItems().add(item);
        }
    }

    private static String formatElapsed(long startedAtMillis) {
        long seconds = startedAtMillis > 0
            ? Math.max(0L, (System.currentTimeMillis() - startedAtMillis) / 1000L)
            : 0L;
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private static List<SwarmHost> buildHosts(List<ServerConnection> connections) {
        List<SwarmHost> hosts = new ArrayList<>();
        if (connections == null) {
            return hosts;
        }
        for (ServerConnection connection : connections) {
            if (connection == null) {
                continue;
            }
            String jump = null;
            JumpServer jumpServer = connection.getJumpServer();
            // Only route through the bastion when it is actually enabled — a disabled jump server
            // now means a direct connection, and the generated script must match that.
            if (jumpServer != null && jumpServer.isEnabled()
                && jumpServer.getHost() != null && !jumpServer.getHost().isBlank()) {
                jump = (jumpServer.getUsername() != null ? jumpServer.getUsername() + "@" : "")
                    + jumpServer.getHost() + ":" + jumpServer.getPort();
            }
            hosts.add(new SwarmHost(
                SwarmTargetCollector.displayName(connection),
                connection.getHost(),
                connection.getPort(),
                connection.getUsername(),
                connection.getGroup(),
                connection.getAuthMethod() != null ? connection.getAuthMethod().name() : null,
                connection.getPrivateKeyPath(),
                jump));
        }
        return hosts;
    }

    private static ListCell<ScriptLanguage> languageCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ScriptLanguage item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.displayName());
            }
        };
    }
}
