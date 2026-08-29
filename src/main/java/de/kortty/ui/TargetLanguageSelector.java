package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.ScriptLanguageMixSupport;
import de.kortty.core.ScriptLanguageMixSupport.HostFormat;
import de.kortty.core.ScriptLanguageMixSupport.LanguageMix;
import de.kortty.core.ScriptLanguageMixSupport.MigrationMode;
import de.kortty.core.SnippetAiWorkflowSupport.MigrationPlan;
import de.kortty.core.WorkflowScriptSupport.ScriptLanguage;
import de.kortty.model.GlobalSettings;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable "Language unification" chooser shared by every window that may migrate a snippet: the
 * Full code analysis, the snippet editor's migration dialog and the security-fix apply.
 *
 * <p>This class is the single place where the host-format rule reaches the UI. An Azure DevOps
 * pipeline, a GitHub Actions workflow or a Jenkinsfile invokes Bash or PowerShell by construction,
 * so it is never offered a whole-script migration and never reported as "mixed". What it may be
 * offered is narrower and is decided in {@link #setDetectedMix}, not by the four host windows —
 * each of them repeating that reasoning would be four chances to get it wrong.
 *
 * <p><b>Save</b> persists the toggle and the target language, mirroring {@link HardeningOptionsSelector}.
 * The target <em>platform</em> is deliberately never persisted: a remembered platform choice would
 * silently convert the next pipeline that is opened.
 */
public final class TargetLanguageSelector extends VBox {

    /** Step bodies of a host document are shells, so only the shell-ish targets make sense there. */
    private static final List<ScriptLanguage> STEP_TARGETS =
        List.of(ScriptLanguage.BASH, ScriptLanguage.POWERSHELL, ScriptLanguage.PYTHON);

    private final CheckBox enableCheck;
    private final ComboBox<ScriptLanguage> languageCombo = new ComboBox<>();
    private final ComboBox<HostFormat> platformCombo = new ComboBox<>();
    private final Label detectionLabel = new Label();
    private final Label platformWarning = new Label();
    private final HBox languageRow;
    private final VBox platformBox;
    private final Button saveButton;

    private final boolean armWhenDetected;
    private LanguageMix mix = new LanguageMix(HostFormat.NONE, "plain", List.of());
    private Runnable onSelectionChanged;

    /**
     * @param armWhenDetected whether a detected mix arrives with the master toggle already ticked.
     *     Only the dedicated migration dialog does that, where this control <em>is</em> the dialog and
     *     the choice is in plain sight. Inside the Full code analysis and the security report the
     *     panel sits collapsed among other panels, so arming it there would silently rewrite the whole
     *     script into another language on "Apply selected" — a change the user never asked for and
     *     could not even see.
     */
    public TargetLanguageSelector(boolean armWhenDetected) {
        this.armWhenDetected = armWhenDetected;
        setSpacing(8);
        GlobalSettings settings = currentSettings();

        enableCheck = new CheckBox(I18n.get("ai.migration.enable"));
        enableCheck.setId("languageMigrationEnable");
        enableCheck.setTooltip(new Tooltip(I18n.get("ai.migration.enable.tooltip")));
        enableCheck.setSelected(armWhenDetected
            && settings != null && settings.isSnippetLanguageMigrationEnabled());
        enableCheck.selectedProperty().addListener((obs, was, isNow) -> fireSelectionChanged());

        languageCombo.setId("languageMigrationTarget");
        languageCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ScriptLanguage language) {
                return language != null ? language.displayName() : "";
            }

            @Override
            public ScriptLanguage fromString(String value) {
                return ScriptLanguage.fromId(value);
            }
        });
        languageCombo.disableProperty().bind(enableCheck.selectedProperty().not());
        languageCombo.valueProperty().addListener((obs, was, isNow) -> fireSelectionChanged());

        platformCombo.setId("languageMigrationPlatform");
        platformCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(HostFormat format) {
                return format == null || format == HostFormat.NONE
                    ? I18n.get("ai.migration.platform.unchanged", currentHostDisplayName())
                    : format.displayName();
            }

            @Override
            public HostFormat fromString(String value) {
                return HostFormat.NONE;
            }
        });
        platformCombo.valueProperty().addListener((obs, was, isNow) -> fireSelectionChanged());

        platformWarning.setText(I18n.get("ai.migration.platform.warning"));
        platformWarning.setWrapText(true);
        platformWarning.setStyle("-fx-font-size: 0.8462em;");

        detectionLabel.setWrapText(true);

        saveButton = new Button(I18n.get("ai.workflow.options.save"));
        saveButton.setTooltip(new Tooltip(I18n.get("ai.workflow.options.save.tooltip")));
        saveButton.setOnAction(event -> persistSelection());

        languageRow = new HBox(6, enableCheck, new Label(I18n.get("ai.migration.target")), languageCombo);
        languageRow.setAlignment(Pos.CENTER_LEFT);

        HBox platformRow = new HBox(6, new Label(I18n.get("ai.migration.platform")), platformCombo);
        platformRow.setAlignment(Pos.CENTER_LEFT);
        platformBox = new VBox(4, platformRow, platformWarning);

        HBox buttons = new HBox(6, saveButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(detectionLabel, languageRow, platformBox, buttons);
        setDetectedMix(mix);
    }

    /**
     * Feeds in what {@link ScriptLanguageMixSupport#detect} found and shapes the whole control from
     * it. Call this whenever the snippet's content or declared language changes.
     */
    public void setDetectedMix(LanguageMix detected) {
        this.mix = detected != null ? detected : new LanguageMix(HostFormat.NONE, "plain", List.of());
        MigrationMode mode = mix.defaultMode();
        boolean isHostDocument = mix.hostFormat() != HostFormat.NONE;

        List<ScriptLanguage> targets = isHostDocument ? STEP_TARGETS : ScriptLanguage.migrationTargets();
        languageCombo.getItems().setAll(targets);
        languageCombo.setValue(preferredTarget(targets));

        // A single-language pipeline still is a perfectly legitimate starting point for a platform
        // conversion, so only the language half of the control disappears when there is no mix.
        boolean offerLanguage = mode == MigrationMode.WHOLE_SCRIPT || mode == MigrationMode.EMBEDDED_STEPS_ONLY;
        show(languageRow, offerLanguage);
        // Both offered modes mean a real, detected inconsistency — but only a host that shows this
        // control prominently may arm it. The platform conversion below is never armed anywhere: that
        // is a preference, not a defect.
        enableCheck.setSelected(offerLanguage && armWhenDetected);

        List<HostFormat> platforms = new ArrayList<>();
        platforms.add(HostFormat.NONE);
        for (HostFormat format : HostFormat.conversionTargets()) {
            if (format != mix.hostFormat()) {
                platforms.add(format);
            }
        }
        platformCombo.getItems().setAll(platforms);
        // Never preselected: a platform change is a deliberate choice, never a suggestion.
        platformCombo.setValue(HostFormat.NONE);
        boolean offerPlatform = isHostDocument && mix.hostFormat().isConversionTarget();
        show(platformBox, offerPlatform);

        detectionLabel.setText(describeDetection(mode));
        show(detectionLabel, !detectionLabel.getText().isBlank());
        show(saveButton.getParent(), offerLanguage);
        fireSelectionChanged();
    }

    /** How many migration steps the current order performs; hosts show this in a collapsed pane title. */
    public int selectedCount() {
        return buildPlan().modes().size();
    }

    /** Whether this control currently offers anything at all; hosts hide their pane when it does not. */
    public boolean hasAnythingToOffer() {
        return languageRow.isVisible() || platformBox.isVisible();
    }

    public boolean isEnabled() {
        return languageRow.isVisible() && enableCheck.isSelected();
    }

    /** The chosen target language, or {@code null} when the language half is off. */
    public ScriptLanguage selectedTarget() {
        return isEnabled() ? languageCombo.getValue() : null;
    }

    /** The chosen target platform, or {@code null} for "unchanged". */
    public HostFormat selectedHostFormat() {
        if (!platformBox.isVisible()) {
            return null;
        }
        HostFormat selected = platformCombo.getValue();
        return selected == null || selected == HostFormat.NONE ? null : selected;
    }

    /** The complete migration order; {@link MigrationPlan#isNoOp()} when nothing was chosen. */
    public MigrationPlan buildPlan() {
        return new MigrationPlan(mix, selectedTarget(), selectedHostFormat());
    }

    public LanguageMix detectedMix() {
        return mix;
    }

    /** Registers a callback fired whenever the order changes, so a host can update a pane title. */
    public void setOnSelectionChanged(Runnable callback) {
        this.onSelectionChanged = callback;
    }

    // ---------------------------------------------------------------- internals

    private String describeDetection(MigrationMode mode) {
        return switch (mode) {
            case WHOLE_SCRIPT -> I18n.get("ai.migration.detected",
                String.join(", ", mix.embeddedLanguages()), mix.dominantLanguage());
            case EMBEDDED_STEPS_ONLY -> I18n.get("ai.migration.detected.steps",
                mix.hostFormat().displayName(), String.join(", ", mix.stepLanguages()));
            default -> mix.hostFormat() == HostFormat.NONE
                ? I18n.get("ai.migration.detected.none")
                : mix.hostFormat().displayName();
        };
    }

    private String currentHostDisplayName() {
        return mix.hostFormat() == HostFormat.NONE ? "" : mix.hostFormat().displayName();
    }

    private ScriptLanguage preferredTarget(List<ScriptLanguage> targets) {
        ScriptLanguage saved = savedTarget();
        if (saved != null && targets.contains(saved)) {
            return saved;
        }
        ScriptLanguage detected = ScriptLanguage.fromId(dominantOrMajorityLanguage());
        return targets.contains(detected) ? detected : targets.get(0);
    }

    /** For a host document the majority step language; for a plain script its own language. */
    private String dominantOrMajorityLanguage() {
        if (mix.hostFormat() == HostFormat.NONE) {
            return mix.dominantLanguage();
        }
        return mix.stepLanguages().stream().findFirst().orElse("bash");
    }

    private ScriptLanguage savedTarget() {
        GlobalSettings settings = currentSettings();
        String saved = settings != null ? settings.getSnippetLanguageMigrationTarget() : null;
        if (saved == null || saved.isBlank()) {
            return null;
        }
        try {
            return ScriptLanguage.valueOf(saved.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void fireSelectionChanged() {
        if (onSelectionChanged != null) {
            onSelectionChanged.run();
        }
    }

    private void persistSelection() {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance() != null
                ? KorTTYApplication.getInstance().getGlobalSettingsManager()
                : null;
            if (manager != null && manager.getSettings() != null) {
                GlobalSettings settings = manager.getSettings();
                settings.setSnippetLanguageMigrationEnabled(enableCheck.isSelected());
                ScriptLanguage target = languageCombo.getValue();
                settings.setSnippetLanguageMigrationTarget(target != null ? target.name() : null);
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
