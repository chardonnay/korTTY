package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.WorkflowScriptSupport.HardeningOption;
import de.kortty.model.GlobalSettings;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * Reusable hardening-option chooser shared by every window that shows the "Hardening options" panel
 * (the workflow-script generator, the snippet editor's improve/custom dialogs, and the Full code analysis
 * window). It renders the option checkboxes in a two-column grid plus an <b>All</b> / <b>Clear</b> /
 * <b>Save</b> button row:
 * <ul>
 *   <li><b>All</b> ticks every option, <b>Clear</b> unticks every option;</li>
 *   <li><b>Save</b> persists the current selection to {@link GlobalSettings#setSnippetHardeningOptions}, so
 *       it becomes the default the next time any hardening panel opens.</li>
 * </ul>
 * The initial selection is the persisted set (all options when nothing was saved yet).
 */
public final class HardeningOptionsSelector extends VBox {

    private final Map<HardeningOption, CheckBox> checks = new EnumMap<>(HardeningOption.class);
    private final Button saveButton;
    private Runnable onSelectionChanged;

    public HardeningOptionsSelector() {
        setSpacing(8);

        EnumSet<HardeningOption> initial = loadPersistedSelection();
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(4);
        HardeningOption[] options = HardeningOption.values();
        for (int i = 0; i < options.length; i++) {
            HardeningOption option = options[i];
            CheckBox check = new CheckBox(I18n.get("ai.workflow.option." + option.name()));
            check.setSelected(initial.contains(option));
            check.selectedProperty().addListener((obs, was, isNow) -> fireSelectionChanged());
            checks.put(option, check);
            grid.add(check, i % 2, i / 2);
        }

        Button selectAllButton = new Button(I18n.get("ai.workflow.options.all"));
        selectAllButton.setOnAction(event -> setAllSelected(true));
        Button clearButton = new Button(I18n.get("ai.workflow.options.clear"));
        clearButton.setOnAction(event -> setAllSelected(false));
        saveButton = new Button(I18n.get("ai.workflow.options.save"));
        saveButton.setTooltip(new Tooltip(I18n.get("ai.workflow.options.save.tooltip")));
        saveButton.setOnAction(event -> persistSelection());

        HBox buttons = new HBox(6, selectAllButton, clearButton, saveButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(grid, buttons);
    }

    /** The currently ticked options. */
    public EnumSet<HardeningOption> selectedOptions() {
        EnumSet<HardeningOption> selected = EnumSet.noneOf(HardeningOption.class);
        checks.forEach((option, check) -> {
            if (check.isSelected()) {
                selected.add(option);
            }
        });
        return selected;
    }

    /** The number of currently ticked options — cheaper than {@link #selectedOptions()} for a live counter. */
    public int selectedCount() {
        int count = 0;
        for (CheckBox check : checks.values()) {
            if (check.isSelected()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Registers a callback fired whenever the ticked set changes (including via <b>All</b>/<b>Clear</b>),
     * so a host can show a live "(N)" counter next to the panel title. Runs on the JavaFX thread.
     */
    public void setOnSelectionChanged(Runnable callback) {
        this.onSelectionChanged = callback;
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
                manager.getSettings().setSnippetHardeningOptions(HardeningOption.serializeOptions(selectedOptions()));
                manager.save();
                saveButton.setText(I18n.get("ai.workflow.options.saved"));
            }
        } catch (Exception ignored) {
            // Persistence is best-effort; the selection still applies to the current run.
        }
    }

    private static EnumSet<HardeningOption> loadPersistedSelection() {
        GlobalSettings settings = currentSettings();
        return HardeningOption.parseOptions(settings != null ? settings.getSnippetHardeningOptions() : null);
    }

    private static GlobalSettings currentSettings() {
        KorTTYApplication application = KorTTYApplication.getInstance();
        GlobalSettingsManager manager = application != null ? application.getGlobalSettingsManager() : null;
        return manager != null ? manager.getSettings() : null;
    }
}
