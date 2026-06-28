package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.WorkflowScriptSupport;
import de.kortty.core.WorkflowScriptSupport.HeaderFacts;
import de.kortty.core.WorkflowScriptSupport.ScriptLanguage;
import de.kortty.core.WorkflowScriptSupport.SwarmHost;
import de.kortty.core.WorkflowScriptSupport.SwarmScriptOption;
import de.kortty.model.AiProfile;
import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
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
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a multi-server workflow script from the swarm's selected hosts and the last query. The
 * script iterates the host list and runs the per-host work on each. Host facts are secret-free; the
 * generator and prompt both forbid embedding passwords/key contents.
 */
public final class SwarmWorkflowScriptDialog {

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

        TextArea preview = new TextArea();
        preview.setEditable(true);
        preview.setWrapText(false);
        preview.setStyle("-fx-font-family: 'monospace';");

        int hostCount = connections != null ? connections.size() : 0;
        Label hostLabel = new Label(I18n.get("ai.workflow.swarm.hosts") + " " + hostCount);
        Label status = new Label();
        Button generateButton = new Button(I18n.get("ai.workflow.generate"));
        Button copyButton = new Button(I18n.get("ai.workflow.copy"));
        copyButton.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(preview.getText());
            Clipboard.getSystemClipboard().setContent(content);
            status.setText(I18n.get("ai.workflow.copied"));
        });

        generateButton.setOnAction(e -> {
            ScriptLanguage language = languageCombo.getValue();
            EnumSet<SwarmScriptOption> selected = EnumSet.noneOf(SwarmScriptOption.class);
            for (Map.Entry<SwarmScriptOption, CheckBox> entry : optionChecks.entrySet()) {
                if (entry.getValue().isSelected()) {
                    selected.add(entry.getKey());
                }
            }
            HeaderFacts facts = new HeaderFacts(
                WorkflowScriptSupport.defaultScriptName(query, language),
                System.getProperty("user.name"), null, null, LocalDateTime.now(),
                query != null ? query : "", profile != null ? profile.getName() : "");
            WorkflowScriptGenerator.SwarmRequest request = new WorkflowScriptGenerator.SwarmRequest(
                language, WorkflowScriptSupport.HardeningOption.defaults(), selected, "", facts, null);
            WorkflowScriptGenerator.SwarmRunExportData data = new WorkflowScriptGenerator.SwarmRunExportData(
                profile != null ? profile.getId() : null,
                profile != null ? profile.getName() : null,
                query, buildHosts(connections), null, null);

            generateButton.setDisable(true);
            status.setText(I18n.get("ai.workflow.generating"));
            Task<WorkflowScriptGenerator.Outcome> task = new Task<>() {
                @Override
                protected WorkflowScriptGenerator.Outcome call() {
                    return new WorkflowScriptGenerator(KorTTYApplication.getInstance()).generateSwarm(data, request);
                }
            };
            task.setOnSucceeded(ev -> {
                WorkflowScriptGenerator.Outcome outcome = task.getValue();
                preview.setText(outcome != null ? outcome.script() : "");
                status.setText(I18n.get("ai.workflow.ready"));
                generateButton.setDisable(false);
            });
            task.setOnFailed(ev -> {
                Throwable error = task.getException();
                status.setText(error != null && error.getMessage() != null
                    ? error.getMessage() : I18n.get("ai.result.error"));
                generateButton.setDisable(false);
            });
            Thread thread = new Thread(task, "ai-swarm-workflow");
            thread.setDaemon(true);
            thread.start();
        });

        HBox controls = new HBox(8,
            new Label(I18n.get("ai.workflow.language")), languageCombo, generateButton, copyButton, status);
        controls.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10,
            hostLabel, controls, new Label(I18n.get("ai.workflow.swarm.options")), optionsPane, preview);
        box.setPadding(new Insets(10));
        VBox.setVgrow(preview, Priority.ALWAYS);
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefSize(840, 660);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
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
            if (jumpServer != null && jumpServer.getHost() != null && !jumpServer.getHost().isBlank()) {
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
