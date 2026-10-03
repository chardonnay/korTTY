package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetOneLiner;
import de.kortty.core.SnippetPlaceholderResolver;
import de.kortty.core.SnippetVariableManager;
import de.kortty.model.Snippet;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Resolving a snippet for use and sending it to a terminal: the variable dialogs, the one-liner
 * payload (stderr banner, then the embedded base64 pipe or the compact form), the send into the
 * main window's snippet target terminal and the usage counter. Extracted from
 * {@link SnippetLibraryPane} so other entry points can run a snippet the same way; the caller
 * supplies the owner of the dialogs and what follows a usage bump (the library refreshes its table).
 */
final class SnippetTerminalSend {

    private static final Logger logger = LoggerFactory.getLogger(SnippetTerminalSend.class);

    private record TerminalParameterInput(String resolvedText, List<String> arguments) {
    }

    /** What a variable dialog returned: the values entered for this use and the names to remember. */
    private record VariablePromptResult(Map<String, String> values, Set<String> remember) {
    }

    private record TerminalParameterDialogResult(VariablePromptResult variables, List<String> arguments) {
    }

    /** One row of a variable dialog: the name, the text its field starts with, and whether Remember starts ticked. */
    private record VariableRow(String name, String initialValue, boolean rememberInitially) {
    }

    private final SnippetManager snippetManager;
    private final Supplier<Window> ownerWindow;
    private final Runnable afterUsage;

    /**
     * @param ownerWindow owner of the variable dialogs and info alerts, resolved at use time
     * @param afterUsage  runs after a use was counted and saved (e.g. refresh a list showing the count)
     */
    SnippetTerminalSend(SnippetManager snippetManager, Supplier<Window> ownerWindow, Runnable afterUsage) {
        this.snippetManager = snippetManager;
        this.ownerWindow = ownerWindow;
        this.afterUsage = afterUsage != null ? afterUsage : () -> { };
    }

    private static SnippetVariableManager variableManager() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        return app != null ? app.getSnippetVariableManager() : null;
    }

    /**
     * Resolves a snippet for Copy, Insert into editor and Send to Terminal: built-ins and declared
     * variables are replaced, a declared variable without a stored value is asked for, and every
     * other {@code ${...}} stays as written. Counts the use. Returns {@code null} when the user
     * cancels the dialog.
     */
    SnippetPlaceholderResolver.ResolvedSnippet resolveAndPrompt(Snippet snippet) {
        SnippetVariableManager varManager = variableManager();
        String content = snippet.getContent();
        List<VariableRow> missing = new ArrayList<>();
        for (String name : snippetManager.declaredVariables(content, varManager)) {
            if (varManager.getValue(name) == null) {
                missing.add(new VariableRow(name, "", false));
            }
        }
        Map<String, String> entered = Map.of();
        if (!missing.isEmpty()) {
            VariablePromptResult prompted = promptForVariables(missing);
            if (prompted == null) {
                return null;
            }
            entered = prompted.values();
            rememberValues(varManager, prompted);
        }
        SnippetPlaceholderResolver.ResolvedSnippet resolved = snippetManager.resolve(content, varManager, entered);

        countUsage(snippet);
        return resolved;
    }

    /**
     * Send to Terminal: resolves {@code snippet} (see {@link #resolveAndPrompt}) and runs it in the
     * snippet target terminal of the main window {@code mainWindow} supplies, as a one-liner where
     * the language allows. Says so when the one-liner cannot be built or no terminal is open.
     */
    void sendToTerminal(Snippet snippet, Supplier<MainWindow> mainWindow) {
        SnippetPlaceholderResolver.ResolvedSnippet resolvedSnippet = resolveAndPrompt(snippet);
        if (resolvedSnippet == null || resolvedSnippet.text().isBlank()) {
            return;
        }
        String resolved = resolvedSnippet.text();

        String toSend = buildOneLinerPayloadForTerminal(resolved, snippet.getLanguage(), bannerText(snippet));
        if (toSend == null) {
            showInfo(I18n.get("snippets.insertTerminal.onelinerFailed"));
            return;
        }

        // The terminal tab of the workspace's main window (the last selected one in tab mode).
        try {
            MainWindow window = mainWindow.get();
            if (window == null) return;

            TerminalTab terminalTab = window.snippetInsertTarget(TerminalTab.class);
            if (terminalTab != null) {
                sendPayload(terminalTab, toSend, SnippetOneLiner.isEmbeddedSupported(snippet.getLanguage()));
                window.revealSnippetInsertTarget(terminalTab);
                logger.info("Snippet '{}' sent to terminal (one-liner where supported)", snippet.getName());
            } else {
                showInfo(I18n.get("snippets.noTerminalOpen"));
            }
        } catch (Exception e) {
            logger.error("Failed to insert snippet into terminal", e);
        }
    }

    /**
     * Send to Terminal with Parameters: every declared variable is editable for this send and the
     * script gets positional arguments. The use is counted only once the snippet was sent.
     */
    void sendToTerminalWithParameters(Snippet snippet, Supplier<MainWindow> mainWindow) {
        TerminalParameterInput input = resolveAndPromptForTerminalParameters(snippet);
        if (input == null || input.resolvedText().isBlank()) {
            return;
        }

        String toSend = buildOneLinerPayloadForTerminal(
                input.resolvedText(),
                snippet.getLanguage(),
                bannerText(snippet),
                input.arguments());
        if (toSend == null) {
            if (!input.arguments().isEmpty() && !SnippetOneLiner.isEmbeddedSupported(snippet.getLanguage())) {
                showInfo(I18n.get("snippets.insertTerminal.parameters.unsupported"));
            } else {
                showInfo(I18n.get("snippets.insertTerminal.onelinerFailed"));
            }
            return;
        }

        try {
            MainWindow window = mainWindow.get();
            if (window == null) return;

            TerminalTab terminalTab = window.snippetInsertTarget(TerminalTab.class);
            if (terminalTab != null) {
                sendPayload(terminalTab, toSend, SnippetOneLiner.isEmbeddedSupported(snippet.getLanguage()));
                countUsage(snippet);
                window.revealSnippetInsertTarget(terminalTab);
                logger.info("Snippet '{}' sent to terminal with {} argument(s)", snippet.getName(), input.arguments().size());
            } else {
                showInfo(I18n.get("snippets.noTerminalOpen"));
            }
        } catch (Exception e) {
            logger.error("Failed to insert snippet into terminal with parameters", e);
        }
    }

    /**
     * Resolves a snippet for Send to Terminal with Parameters: every declared variable the snippet
     * uses is listed, pre-filled with its stored value and editable for this send.
     */
    private TerminalParameterInput resolveAndPromptForTerminalParameters(Snippet snippet) {
        SnippetVariableManager varManager = variableManager();
        String content = snippet.getContent();
        List<VariableRow> rows = new ArrayList<>();
        for (String name : snippetManager.declaredVariables(content, varManager)) {
            String stored = varManager.getValue(name);
            rows.add(new VariableRow(name, stored != null ? stored : "", stored != null));
        }

        TerminalParameterDialogResult dialogResult = promptForTerminalParameters(rows);
        if (dialogResult == null) {
            return null;
        }
        rememberValues(varManager, dialogResult.variables());
        String text = snippetManager.resolve(content, varManager, dialogResult.variables().values()).text();
        return new TerminalParameterInput(text, dialogResult.arguments());
    }

    /** Bumps the usage counter, saves it and runs the caller's follow-up. */
    private void countUsage(Snippet snippet) {
        snippetManager.incrementUsage(snippet);
        saveQuietly();
        afterUsage.run();
    }

    /** Usage bookkeeping (copy/insert counters): a failure is logged, not worth an alert. */
    private void saveQuietly() {
        try {
            snippetManager.save();
        } catch (Exception e) {
            logger.error("Failed to save snippets", e);
        }
    }

    /** Stores only the values whose Remember box was ticked; the others were used once. */
    private void rememberValues(SnippetVariableManager varManager, VariablePromptResult result) {
        if (varManager == null || result == null) {
            return;
        }
        if (varManager.remember(result.values(), result.remember())) {
            try {
                varManager.save();
            } catch (Exception e) {
                logger.warn("Failed to save variables", e);
            }
        }
    }

    /**
     * Adds one row per variable to {@code grid}: its label, a value field and a Remember check box,
     * and collects the fields and boxes by variable name.
     */
    private void addVariableRows(
            GridPane grid,
            List<VariableRow> rows,
            Map<String, TextField> fields,
            Map<String, CheckBox> rememberBoxes) {
        int row = 0;
        for (VariableRow variable : rows) {
            Label label = new Label("${" + variable.name() + "}:");
            TextField field = new TextField(variable.initialValue());
            field.setPromptText(variable.name());
            field.setPrefWidth(300);
            CheckBox remember = new CheckBox(I18n.get("snippets.variables.remember"));
            remember.setSelected(variable.rememberInitially());
            remember.setTooltip(new Tooltip(I18n.get("snippets.variables.remember.tooltip")));
            grid.add(label, 0, row);
            grid.add(field, 1, row);
            grid.add(remember, 2, row);
            fields.put(variable.name(), field);
            rememberBoxes.put(variable.name(), remember);
            row++;
        }
    }

    private static VariablePromptResult collectVariables(
            Map<String, TextField> fields, Map<String, CheckBox> rememberBoxes) {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> remember = new LinkedHashSet<>();
        fields.forEach((name, field) -> {
            values.put(name, field.getText());
            CheckBox box = rememberBoxes.get(name);
            if (box != null && box.isSelected()) {
                remember.add(name);
            }
        });
        return new VariablePromptResult(values, remember);
    }

    private VariablePromptResult promptForVariables(List<VariableRow> rows) {
        Dialog<VariablePromptResult> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("snippets.promptVariable"));
        dialog.initOwner(ownerWindow.get());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));

        Map<String, TextField> fields = new LinkedHashMap<>();
        Map<String, CheckBox> rememberBoxes = new LinkedHashMap<>();
        addVariableRows(grid, rows, fields, rememberBoxes);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(bt -> bt == ButtonType.OK ? collectVariables(fields, rememberBoxes) : null);

        return dialog.showAndWait().orElse(null);
    }

    private TerminalParameterDialogResult promptForTerminalParameters(List<VariableRow> rows) {
        Dialog<TerminalParameterDialogResult> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("snippets.insertTerminal.parameters.title"));
        dialog.initOwner(ownerWindow.get());

        VBox layout = new VBox(10);
        layout.setPadding(new Insets(10));

        Map<String, TextField> fields = new LinkedHashMap<>();
        Map<String, CheckBox> rememberBoxes = new LinkedHashMap<>();
        if (rows != null && !rows.isEmpty()) {
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            addVariableRows(grid, rows, fields, rememberBoxes);
            layout.getChildren().add(grid);
        }

        Label argumentsLabel = new Label(I18n.get("snippets.insertTerminal.parameters.arguments"));
        TextArea argumentsArea = new TextArea();
        argumentsArea.setPromptText(I18n.get("snippets.insertTerminal.parameters.argumentsPrompt"));
        argumentsArea.setPrefRowCount(5);
        argumentsArea.setPrefColumnCount(42);
        layout.getChildren().addAll(argumentsLabel, argumentsArea);

        dialog.getDialogPane().setContent(layout);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(bt -> bt == ButtonType.OK
            ? new TerminalParameterDialogResult(
                collectVariables(fields, rememberBoxes), parseArgumentLines(argumentsArea.getText()))
            : null);

        return dialog.showAndWait().orElse(null);
    }

    /** One argument per non-blank line, kept as typed (no trimming), in order. */
    static List<String> parseArgumentLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> arguments = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            if (!line.isBlank()) {
                arguments.add(line);
            }
        }
        return List.copyOf(arguments);
    }

    /** The stderr banner announcing {@code snippet} in the terminal ("unnamed" without a name). */
    private static String bannerText(Snippet snippet) {
        String rawName = snippet.getName();
        String displayName = (rawName != null && !rawName.isBlank()) ? rawName.trim() : I18n.get("snippets.insertTerminal.unnamed");
        return I18n.get("snippets.insertTerminal.banner", displayName);
    }

    /**
     * Runs {@code payload} in the tab's terminal (text plus newline); a generated one-liner is sent
     * without the remote echo of its base64 text over SSH.
     */
    private static void sendPayload(TerminalTab terminalTab, String payload, boolean generatedOneLiner) {
        if (generatedOneLiner) {
            terminalTab.getTerminalView().sendGeneratedInputLineHidden(payload);
        } else {
            terminalTab.getTerminalView().sendInputLine(payload);
        }
    }

    /**
     * For bash/shell/python/perl/ruby, sends a one-liner (stderr banner, then embedded base64 pipe or compact fallback).
     * Other languages: full resolved text (no shell banner — content may not be shell).
     */
    static String buildOneLinerPayloadForTerminal(String resolved, String language, String bannerText) {
        return buildOneLinerPayloadForTerminal(resolved, language, bannerText, List.of());
    }

    /**
     * As {@link #buildOneLinerPayloadForTerminal(String, String, String)} with positional script
     * arguments; {@code null} when the one-liner cannot be built, and always for arguments to a
     * language without an embedded one-liner or when only the compact form would work.
     */
    static String buildOneLinerPayloadForTerminal(
            String resolved,
            String language,
            String bannerText,
            List<String> arguments) {
        List<String> safeArguments = arguments != null ? arguments : List.of();
        if (!SnippetOneLiner.isEmbeddedSupported(language)) {
            return safeArguments.isEmpty() ? resolved : null;
        }
        String prefix = SnippetOneLiner.terminalStderrBannerShellPrefix(bannerText);
        SnippetOneLiner.OneLinerResult embedded = SnippetOneLiner.toEmbedded(resolved, language, safeArguments);
        if (embedded.isOk()) {
            return prefix + " && " + embedded.line();
        }
        if (!safeArguments.isEmpty()) {
            return null;
        }
        SnippetOneLiner.OneLinerResult compact = SnippetOneLiner.toCompact(resolved, language);
        if (compact.isOk()) {
            return prefix + " && " + compact.line();
        }
        return null;
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(I18n.get("snippets.title"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(ownerWindow.get());
        alert.showAndWait();
    }
}
