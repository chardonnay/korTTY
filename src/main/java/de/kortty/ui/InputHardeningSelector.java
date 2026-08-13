package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.WorkflowScriptSupport.InputHardeningConfig;
import de.kortty.core.WorkflowScriptSupport.InputHardeningOption;
import de.kortty.model.GlobalSettings;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * Reusable "Input hardening" chooser shared by every window that offers the AI-generated input
 * guard (the workflow-script generators, the snippet editor's improve/custom dialogs, and the Full
 * code analysis window). Deliberately separate from {@link HardeningOptionsSelector}: this panel
 * does not tune prompt style, it asks the AI to build a guard block into the script itself that
 * validates parameters and input files at run time — korTTY performs no validation when the script
 * runs. The master toggle is strictly opt-in (default off) because the guard changes the script's
 * runtime behaviour; <b>Save</b> persists the toggle, the sub-option set and the max file size as
 * the default for the next panel.
 */
public final class InputHardeningSelector extends VBox {

    private static final long BYTES_PER_MB = 1_048_576L;

    private final CheckBox enableCheck;
    private final Map<InputHardeningOption, CheckBox> checks = new EnumMap<>(InputHardeningOption.class);
    private final Spinner<Integer> maxFileSizeSpinner;
    private final Button saveButton;
    private Runnable onSelectionChanged;
    private boolean supported = true;

    public InputHardeningSelector() {
        setSpacing(8);
        GlobalSettings settings = currentSettings();

        enableCheck = new CheckBox(I18n.get("ai.inputHardening.enable"));
        enableCheck.setId("inputHardeningEnable");
        enableCheck.setTooltip(new Tooltip(I18n.get("ai.inputHardening.enable.tooltip")));
        enableCheck.setSelected(settings != null && settings.isSnippetInputHardeningEnabled());
        enableCheck.selectedProperty().addListener((obs, was, isNow) -> fireSelectionChanged());

        EnumSet<InputHardeningOption> initial = InputHardeningOption.parseOptions(
            settings != null ? settings.getSnippetInputHardeningOptions() : null);
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(4);
        InputHardeningOption[] options = InputHardeningOption.values();
        for (int i = 0; i < options.length; i++) {
            InputHardeningOption option = options[i];
            CheckBox check = new CheckBox(I18n.get("ai.inputHardening.option." + option.name()));
            check.setSelected(initial.contains(option));
            check.selectedProperty().addListener((obs, was, isNow) -> fireSelectionChanged());
            check.setTooltip(HardeningOptionsSelector.optionTooltip(
                "ai.inputHardening.option." + option.name() + ".tooltip"));
            checks.put(option, check);
            grid.add(check, i % 2, i / 2);
        }
        grid.disableProperty().bind(enableCheck.selectedProperty().not());

        int initialMb = settings != null ? settings.getSnippetInputHardeningMaxFileSizeMb() : 10;
        maxFileSizeSpinner = new Spinner<>(0, 1024, initialMb);
        maxFileSizeSpinner.setEditable(true);
        maxFileSizeSpinner.setPrefWidth(90);
        maxFileSizeSpinner.setId("inputHardeningMaxSize");
        // An editable Spinner only commits typed text on Enter/arrow — without this, a typed value
        // is silently dropped by Generate/Save (pattern from TeamworkSourceEditDialog).
        TextFormatter<Integer> sizeFormatter = new TextFormatter<>(
            new javafx.util.converter.IntegerStringConverter(), initialMb, change -> {
                String newText = change.getControlNewText();
                return newText.isEmpty() || newText.matches("[0-9]+") ? change : null;
            });
        maxFileSizeSpinner.getEditor().setTextFormatter(sizeFormatter);
        maxFileSizeSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                sizeFormatter.setValue(newVal);
            }
        });
        maxFileSizeSpinner.getEditor().focusedProperty().addListener((obs, wasFocused, nowFocused) -> {
            if (!nowFocused) {
                commitSpinnerEditorText();
            }
        });
        Label sizeLabel = new Label(I18n.get("ai.inputHardening.maxFileSize"));
        Tooltip sizeTooltip = new Tooltip(I18n.get("ai.inputHardening.maxFileSize.tooltip"));
        sizeLabel.setTooltip(sizeTooltip);
        maxFileSizeSpinner.setTooltip(sizeTooltip);
        Label sizeHint = new Label(I18n.get("ai.inputHardening.maxFileSize.hint"));
        sizeHint.setStyle("-fx-font-size: 0.8462em;");
        HBox sizeRow = new HBox(6, sizeLabel, maxFileSizeSpinner, sizeHint);
        sizeRow.setAlignment(Pos.CENTER_LEFT);
        // The limit only reaches the script through the FILE_SIZE_LIMIT bullet, so the spinner is
        // meaningful exactly when the master toggle and that sub-option are both ticked.
        sizeRow.disableProperty().bind(enableCheck.selectedProperty().not()
            .or(checks.get(InputHardeningOption.FILE_SIZE_LIMIT).selectedProperty().not()));

        Button selectAllButton = new Button(I18n.get("ai.workflow.options.all"));
        selectAllButton.setOnAction(event -> setAllSelected(true));
        selectAllButton.disableProperty().bind(enableCheck.selectedProperty().not());
        Button clearButton = new Button(I18n.get("ai.workflow.options.clear"));
        clearButton.setOnAction(event -> setAllSelected(false));
        clearButton.disableProperty().bind(enableCheck.selectedProperty().not());
        // Save stays enabled while the master toggle is off: the toggle state is part of what Save
        // persists, so switching the saved default back to "off" must remain possible.
        saveButton = new Button(I18n.get("ai.workflow.options.save"));
        saveButton.setTooltip(new Tooltip(I18n.get("ai.inputHardening.saveDefault.tooltip")));
        saveButton.setOnAction(event -> persistSelection());
        HBox buttons = new HBox(6, selectAllButton, clearButton, saveButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(enableCheck, grid, sizeRow, buttons);
    }

    /**
     * The per-run guard configuration: {@link InputHardeningConfig#disabled()} unless the master
     * toggle is ticked, otherwise the ticked sub-options plus the spinner value converted to bytes
     * (so the generated MAX_FILE_SIZE default always matches the UI, including zero = unlimited).
     */
    public InputHardeningConfig currentConfig() {
        if (!supported || !enableCheck.isSelected()) {
            return InputHardeningConfig.disabled();
        }
        return new InputHardeningConfig(selectedOptions(), maxFileSizeMb() * BYTES_PER_MB);
    }

    /** The number of effectively active sub-options: 0 while the master toggle is off. */
    public int selectedCount() {
        if (!supported || !enableCheck.isSelected()) {
            return 0;
        }
        int count = 0;
        for (CheckBox check : checks.values()) {
            if (check.isSelected()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Registers a callback fired whenever the effective selection changes (master toggle or any
     * sub-option), so a host can show a live "(N)" counter next to the panel title. Runs on the
     * JavaFX thread.
     */
    public void setOnSelectionChanged(Runnable callback) {
        this.onSelectionChanged = callback;
    }

    /**
     * Marks whether the current target language can receive an imperative input guard. Unsupported
     * targets are visibly disabled and always yield a disabled effective config, even if a saved
     * default had the master toggle enabled.
     */
    public void setSupported(boolean supported) {
        if (this.supported == supported) {
            return;
        }
        this.supported = supported;
        setDisable(!supported);
        fireSelectionChanged();
    }

    public boolean isSupported() {
        return supported;
    }

    private EnumSet<InputHardeningOption> selectedOptions() {
        EnumSet<InputHardeningOption> selected = EnumSet.noneOf(InputHardeningOption.class);
        checks.forEach((option, check) -> {
            if (check.isSelected()) {
                selected.add(option);
            }
        });
        return selected;
    }

    private int maxFileSizeMb() {
        commitSpinnerEditorText();
        Integer value = maxFileSizeSpinner.getValue();
        return value != null ? value : 10;
    }

    /** Commits typed-but-uncommitted editor text into the spinner value, clamped to 0..1024. */
    private void commitSpinnerEditorText() {
        try {
            String text = maxFileSizeSpinner.getEditor().getText();
            Integer current = maxFileSizeSpinner.getValue();
            int fallback = current != null ? current : 10;
            int value = text == null || text.isBlank() ? fallback : Integer.parseInt(text.trim());
            int clamped = Math.max(0, Math.min(1024, value));
            maxFileSizeSpinner.getValueFactory().setValue(clamped);
            maxFileSizeSpinner.getEditor().setText(String.valueOf(clamped));
        } catch (NumberFormatException e) {
            Integer current = maxFileSizeSpinner.getValue();
            maxFileSizeSpinner.getEditor().setText(String.valueOf(current != null ? current : 10));
        }
    }

    private void fireSelectionChanged() {
        if (onSelectionChanged != null) {
            onSelectionChanged.run();
        }
    }

    private void setAllSelected(boolean selected) {
        checks.values().forEach(check -> check.setSelected(selected));
    }

    private void persistSelection() {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance() != null
                ? KorTTYApplication.getInstance().getGlobalSettingsManager()
                : null;
            if (manager != null && manager.getSettings() != null) {
                GlobalSettings settings = manager.getSettings();
                settings.setSnippetInputHardeningEnabled(enableCheck.isSelected());
                settings.setSnippetInputHardeningOptions(InputHardeningOption.serializeOptions(selectedOptions()));
                settings.setSnippetInputHardeningMaxFileSizeMb(maxFileSizeMb());
                manager.save();
                saveButton.setText(I18n.get("ai.workflow.options.saved"));
            }
        } catch (Exception ignored) {
            // Persistence is best-effort; the selection still applies to the current run.
        }
    }

    private static GlobalSettings currentSettings() {
        KorTTYApplication application = KorTTYApplication.getInstance();
        GlobalSettingsManager manager = application != null ? application.getGlobalSettingsManager() : null;
        return manager != null ? manager.getSettings() : null;
    }
}
