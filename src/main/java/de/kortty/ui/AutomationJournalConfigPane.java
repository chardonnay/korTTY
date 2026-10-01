package de.kortty.ui;

import de.kortty.core.AiCostCalculator;
import de.kortty.core.AutomationJournalCostEstimator;
import de.kortty.core.AutomationJournalPolicy;
import de.kortty.model.AiProfile;
import de.kortty.model.AutomationJournalAiMode;
import de.kortty.model.AutomationJournalConfig;
import de.kortty.model.AutomationJournalKeepMode;
import de.kortty.model.AutomationJournalRetentionMode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Editor for one automation source's "session journal per run" settings — shared by the
 * JobScheduler's job editor and the AI Swarm tab. Switching the journal on, or switching its AI
 * summaries on, first shows the {@link AutomationJournalCostWarning}; cancelling it reverts the
 * control.
 */
public final class AutomationJournalConfigPane extends VBox {

    private final CheckBox enableCheck = new CheckBox(I18n.get("journal.automation.enable"));
    private final ComboBox<AutomationJournalAiMode> aiModeCombo = new ComboBox<>();
    private final ComboBox<AiProfile> aiProfileCombo = new ComboBox<>();
    private final ComboBox<AutomationJournalKeepMode> keepModeCombo = new ComboBox<>();
    private final ToggleGroup retentionGroup = new ToggleGroup();
    private final RadioButton retentionDaysRadio = new RadioButton(I18n.get("journal.automation.retention.days"));
    private final RadioButton retentionDateRadio = new RadioButton(I18n.get("journal.automation.retention.date"));
    private final RadioButton retentionNoneRadio = new RadioButton(I18n.get("journal.automation.retention.none"));
    private final Spinner<Integer> retentionDaysSpinner =
        new Spinner<>(1, AutomationJournalConfig.MAX_RETENTION_DAYS, AutomationJournalConfig.DEFAULT_RETENTION_DAYS);
    private final DatePicker expiryDatePicker = new DatePicker();
    private final Spinner<Integer> maxJournalsSpinner = new Spinner<>(0, 100_000, 0);
    private final Spinner<Integer> maxStorageSpinner = new Spinner<>(0, 1_000_000, 0);
    private final CheckBox dedupCheck = new CheckBox(I18n.get("journal.automation.dedup"));
    private final CheckBox captureInputCheck = new CheckBox(I18n.get("journal.automation.captureInput"));
    private final Label hintLabel = new Label();
    private final Label statsLabel = new Label();
    private final GridPane grid = new GridPane();

    private final Supplier<Window> ownerSupplier;
    private final Function<AutomationJournalConfig, AutomationJournalCostEstimator.Estimate> estimator;
    private AutomationJournalPolicy policy = AutomationJournalPolicy.UNRESTRICTED;
    private boolean loading;
    private Runnable onChange = () -> { };

    /**
     * @param ownerSupplier window for the cost warning
     * @param estimator     cost estimate for the settings shown (may return null)
     */
    public AutomationJournalConfigPane(
            Supplier<Window> ownerSupplier,
            Function<AutomationJournalConfig, AutomationJournalCostEstimator.Estimate> estimator) {
        super(8);
        this.ownerSupplier = ownerSupplier != null ? ownerSupplier : () -> null;
        this.estimator = estimator != null ? estimator : config -> null;
        setPadding(new Insets(6, 0, 0, 0));
        buildControls();
    }

    private void buildControls() {
        aiModeCombo.getItems().setAll(AutomationJournalAiMode.values());
        aiModeCombo.setConverter(enumConverter("journal.automation.aiMode."));
        keepModeCombo.getItems().setAll(AutomationJournalKeepMode.values());
        keepModeCombo.setConverter(enumConverter("journal.automation.keepMode."));
        aiProfileCombo.setCellFactory(list -> profileCell());
        aiProfileCombo.setButtonCell(profileCell());
        retentionDaysRadio.setToggleGroup(retentionGroup);
        retentionDateRadio.setToggleGroup(retentionGroup);
        retentionNoneRadio.setToggleGroup(retentionGroup);
        retentionDaysSpinner.setEditable(true);
        retentionDaysSpinner.setPrefWidth(90);
        maxJournalsSpinner.setEditable(true);
        maxJournalsSpinner.setPrefWidth(100);
        maxStorageSpinner.setEditable(true);
        maxStorageSpinner.setPrefWidth(110);
        expiryDatePicker.setPrefWidth(140);
        hintLabel.setWrapText(true);
        hintLabel.setStyle(MutedTextStyle.HINT);
        statsLabel.setWrapText(true);
        statsLabel.setStyle(MutedTextStyle.HINT);

        grid.setHgap(10);
        grid.setVgap(8);
        int row = 0;
        grid.add(new Label(I18n.get("journal.automation.aiMode")), 0, row);
        grid.add(aiModeCombo, 1, row++);
        grid.add(new Label(I18n.get("journal.automation.aiProfile")), 0, row);
        grid.add(aiProfileCombo, 1, row++);
        grid.add(new Label(I18n.get("journal.automation.keepMode")), 0, row);
        grid.add(keepModeCombo, 1, row++);
        grid.add(new Label(I18n.get("journal.automation.retention")), 0, row);
        HBox daysBox = new HBox(6, retentionDaysRadio, retentionDaysSpinner,
            new Label(I18n.get("journal.automation.retention.daysUnit")));
        daysBox.setAlignment(Pos.CENTER_LEFT);
        HBox dateBox = new HBox(6, retentionDateRadio, expiryDatePicker);
        dateBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(new VBox(6, daysBox, dateBox, retentionNoneRadio), 1, row++);
        grid.add(new Label(I18n.get("journal.automation.maxJournals")), 0, row);
        HBox maxJournalsBox = new HBox(6, maxJournalsSpinner, new Label(I18n.get("journal.automation.unlimitedHint")));
        maxJournalsBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(maxJournalsBox, 1, row++);
        grid.add(new Label(I18n.get("journal.automation.maxStorage")), 0, row);
        HBox maxStorageBox = new HBox(6, maxStorageSpinner, new Label(I18n.get("journal.automation.maxStorageUnit")));
        maxStorageBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(maxStorageBox, 1, row++);
        grid.add(dedupCheck, 1, row++);
        grid.add(captureInputCheck, 1, row);
        dedupCheck.setTooltip(new Tooltip(I18n.get("journal.automation.dedup.tooltip")));

        getChildren().setAll(enableCheck, grid, hintLabel, statsLabel);

        enableCheck.selectedProperty().addListener((obs, was, now) -> {
            if (!loading && now && !confirmCost(aiModeCombo.getValue())) {
                runWithoutWarning(() -> enableCheck.setSelected(false));
            }
            updateEnablement();
            onChange.run();
        });
        aiModeCombo.valueProperty().addListener((obs, was, now) -> {
            boolean aiSwitchedOn = was == AutomationJournalAiMode.OFF && now != null && now != AutomationJournalAiMode.OFF;
            if (!loading && enableCheck.isSelected() && aiSwitchedOn && !confirmCost(now)) {
                runWithoutWarning(() -> aiModeCombo.setValue(was));
            }
            updateEnablement();
            onChange.run();
        });
        retentionGroup.selectedToggleProperty().addListener((obs, was, now) -> {
            updateEnablement();
            onChange.run();
        });
        keepModeCombo.valueProperty().addListener((obs, was, now) -> onChange.run());
    }

    /** Called after any change the user makes; for dirty flags or live previews. */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange != null ? onChange : () -> { };
    }

    /** The profiles offered for the summaries; the first entry (null) means "from the settings". */
    public void setProfiles(List<AiProfile> profiles) {
        AiProfile selected = aiProfileCombo.getValue();
        List<AiProfile> items = new ArrayList<>();
        items.add(null);
        if (profiles != null) {
            profiles.stream().filter(p -> p != null && p.getId() != null).forEach(items::add);
        }
        aiProfileCombo.getItems().setAll(items);
        aiProfileCombo.setValue(selected);
    }

    /** Applies administrator restrictions (locks, caps) and explains them. */
    public void setPolicy(AutomationJournalPolicy policy) {
        this.policy = policy != null ? policy : AutomationJournalPolicy.UNRESTRICTED;
        Integer cap = this.policy.maxRetentionDays();
        int max = cap != null && cap > 0 ? Math.min(cap, AutomationJournalConfig.MAX_RETENTION_DAYS)
            : AutomationJournalConfig.MAX_RETENTION_DAYS;
        var factory = (javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory)
            retentionDaysSpinner.getValueFactory();
        factory.setMax(max);
        if (factory.getValue() > max) {
            factory.setValue(max);
        }
        updateEnablement();
    }

    /** Extra line under the controls, e.g. "global AI summaries are off". */
    public void setStatsText(String text) {
        statsLabel.setText(text != null ? text : "");
        statsLabel.setVisible(text != null && !text.isBlank());
        statsLabel.setManaged(statsLabel.isVisible());
    }

    public void load(AutomationJournalConfig config) {
        AutomationJournalConfig source = config != null ? config : new AutomationJournalConfig();
        runWithoutWarning(() -> {
            enableCheck.setSelected(source.isEnabled());
            aiModeCombo.setValue(source.getAiMode());
            aiProfileCombo.setValue(findProfile(source.getAiProfileId()));
            keepModeCombo.setValue(source.getKeepMode());
            retentionDaysSpinner.getValueFactory().setValue(Math.min(source.getEffectiveRetentionDays(),
                ((javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory)
                    retentionDaysSpinner.getValueFactory()).getMax()));
            expiryDatePicker.setValue(source.parseExpiryDate() != null
                ? source.parseExpiryDate() : LocalDate.now().plusDays(AutomationJournalConfig.DEFAULT_RETENTION_DAYS));
            retentionGroup.selectToggle(switch (source.getRetentionMode()) {
                case DAYS -> retentionDaysRadio;
                case FIXED_DATE -> retentionDateRadio;
                case NONE -> retentionNoneRadio;
            });
            maxJournalsSpinner.getValueFactory().setValue(source.getMaxJournals());
            maxStorageSpinner.getValueFactory().setValue(source.getMaxStorageMb());
            dedupCheck.setSelected(source.isDedupEnabled());
            captureInputCheck.setSelected(source.isCaptureInput());
        });
        updateEnablement();
    }

    /** The settings shown, as a new config. */
    public AutomationJournalConfig read() {
        commitSpinner(retentionDaysSpinner);
        commitSpinner(maxJournalsSpinner);
        commitSpinner(maxStorageSpinner);
        AutomationJournalConfig config = new AutomationJournalConfig();
        config.setEnabled(enableCheck.isSelected());
        config.setAiMode(aiModeCombo.getValue());
        config.setAiProfileId(aiProfileCombo.getValue() != null ? aiProfileCombo.getValue().getId() : null);
        config.setKeepMode(keepModeCombo.getValue());
        config.setRetentionMode(selectedRetentionMode());
        config.setRetentionDays(retentionDaysSpinner.getValue());
        config.setExpiryDate(expiryDatePicker.getValue() != null ? expiryDatePicker.getValue().toString() : null);
        config.setMaxJournals(maxJournalsSpinner.getValue());
        config.setMaxStorageMb(maxStorageSpinner.getValue());
        config.setDedupEnabled(dedupCheck.isSelected());
        config.setCaptureInput(captureInputCheck.isSelected());
        return config;
    }

    public boolean isJournalEnabled() {
        return enableCheck.isSelected();
    }

    /** Shows the cost warning for switching on {@code aiMode}; true when the user goes ahead. */
    private boolean confirmCost(AutomationJournalAiMode aiMode) {
        AutomationJournalConfig candidate = read();
        candidate.setEnabled(true);
        if (aiMode != null) {
            candidate.setAiMode(aiMode);
        }
        AutomationJournalCostEstimator.Estimate estimate;
        try {
            estimate = estimator.apply(candidate);
        } catch (RuntimeException e) {
            estimate = null;
        }
        return AutomationJournalCostWarning.confirm(ownerSupplier.get(), candidate.getAiMode(), estimate);
    }

    private void updateEnablement() {
        boolean allowed = policy.allowed();
        enableCheck.setDisable(!allowed);
        boolean on = allowed && enableCheck.isSelected();
        grid.setDisable(!on);
        aiProfileCombo.setDisable(!on || aiModeCombo.getValue() == AutomationJournalAiMode.OFF);
        retentionDaysSpinner.setDisable(!on || !retentionDaysRadio.isSelected());
        expiryDatePicker.setDisable(!on || !retentionDateRadio.isSelected());
        Integer cap = policy.maxRetentionDays();
        boolean capped = cap != null && cap > 0;
        retentionNoneRadio.setDisable(capped);
        if (capped && retentionNoneRadio.isSelected()) {
            retentionDaysRadio.setSelected(true);
        }
        // Without delete permission korTTY may not discard journals because of a user setting.
        keepModeCombo.setDisable(!on || !policy.userDeleteAllowed());
        dedupCheck.setDisable(!on || !policy.userDeleteAllowed());

        List<String> hints = new ArrayList<>();
        if (!allowed) {
            hints.add(I18n.get("journal.automation.hint.policyForbidden"));
        } else {
            if (capped) {
                hints.add(I18n.get("journal.automation.hint.retentionCap", cap));
            }
            if (policy.maxStorageMb() != null && policy.maxStorageMb() > 0) {
                hints.add(I18n.get("journal.automation.hint.storageCap", policy.maxStorageMb()));
            }
            if (policy.maxJournals() != null && policy.maxJournals() > 0) {
                hints.add(I18n.get("journal.automation.hint.journalsCap", policy.maxJournals()));
            }
            if (!policy.userDeleteAllowed()) {
                hints.add(I18n.get("journal.automation.hint.noDelete"));
            }
            if (!policy.aiAllowed()) {
                hints.add(I18n.get("journal.automation.hint.noAi"));
            } else if (!globalAiSummariesEnabled()) {
                hints.add(I18n.get("journal.automation.hint.globalAiOff"));
            }
        }
        hintLabel.setText(String.join("\n", hints));
        hintLabel.setVisible(!hints.isEmpty());
        hintLabel.setManaged(!hints.isEmpty());
    }

    private static boolean globalAiSummariesEnabled() {
        de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
        if (app == null || app.getGlobalSettingsManager() == null || app.getGlobalSettingsManager().getSettings() == null) {
            return true;
        }
        return app.getGlobalSettingsManager().getSettings().isSessionJournalAiSummariesEnabled();
    }

    private AutomationJournalRetentionMode selectedRetentionMode() {
        if (retentionDateRadio.isSelected()) {
            return AutomationJournalRetentionMode.FIXED_DATE;
        }
        if (retentionNoneRadio.isSelected()) {
            return AutomationJournalRetentionMode.NONE;
        }
        return AutomationJournalRetentionMode.DAYS;
    }

    private AiProfile findProfile(String id) {
        if (id == null) {
            return null;
        }
        return aiProfileCombo.getItems().stream()
            .filter(p -> p != null && id.equals(p.getId()))
            .findFirst()
            .orElse(null);
    }

    private void runWithoutWarning(Runnable action) {
        boolean previous = loading;
        loading = true;
        try {
            action.run();
        } finally {
            loading = previous;
        }
    }

    private static void commitSpinner(Spinner<Integer> spinner) {
        if (!spinner.isEditable()) {
            return;
        }
        String text = spinner.getEditor().getText();
        try {
            spinner.getValueFactory().setValue(spinner.getValueFactory().getConverter().fromString(text));
        } catch (RuntimeException ignored) {
            spinner.getEditor().setText(spinner.getValueFactory().getConverter().toString(spinner.getValue()));
        }
    }

    private static ListCell<AiProfile> profileCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setText("");
                } else if (item == null) {
                    setText(I18n.get("journal.automation.aiProfile.fromSettings"));
                } else {
                    setText(profileLabel(item));
                }
            }
        };
    }

    /** "Name · local · no token costs" or "Name · 3.00 € / 15.00 € per 1M tokens". */
    static String profileLabel(AiProfile profile) {
        String name = profile.getName() != null ? profile.getName() : profile.getId();
        if (AiCostCalculator.isLocal(profile)) {
            return name + " · " + I18n.get("settings.ai.price.local");
        }
        if (AiCostCalculator.hasPrice(profile)) {
            Locale locale = Locale.getDefault();
            String currency = AiCostCalculator.currency(profile);
            return name + " · " + I18n.get("journal.automation.aiProfile.price",
                AiCostCalculator.format(nonNull(profile.getPricePerMillionPromptTokens()), currency, locale),
                AiCostCalculator.format(nonNull(profile.getPricePerMillionCompletionTokens()), currency, locale));
        }
        return name;
    }

    private static double nonNull(Double value) {
        return value != null ? value : 0.0;
    }

    private static <E extends Enum<E>> StringConverter<E> enumConverter(String prefix) {
        return new StringConverter<>() {
            @Override
            public String toString(E value) {
                return value == null ? "" : I18n.get(prefix + value.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public E fromString(String string) {
                return null;
            }
        };
    }
}
