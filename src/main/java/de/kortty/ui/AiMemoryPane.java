package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiSecretGuard;
import de.kortty.core.AiSecretScanRegistry;
import de.kortty.core.AiTokenWarningLevel;
import de.kortty.core.TerminalAgentService;
import de.kortty.memory.MemoryBlock;
import de.kortty.memory.MemoryCleanupService;
import de.kortty.memory.MemoryCleanupService.Proposal;
import de.kortty.memory.MemoryDocument.KorttyEntry;
import de.kortty.memory.MemoryEntriesModel;
import de.kortty.memory.MemoryEntriesModel.Row;
import de.kortty.memory.MemoryRowEdit;
import de.kortty.memory.MemoryScope;
import de.kortty.memory.MemoryService;
import de.kortty.memory.MemoryStore;
import de.kortty.memory.MemoryUserActions;
import de.kortty.model.AiMemoryLearningMode;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.ManagedSetting;
import de.kortty.policy.PolicyFile;
import de.kortty.policy.PolicyManager;
import de.kortty.policy.PolicyUiSupport;
import de.kortty.policy.UserMemoryMode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * The Memory tab of the AI manager, and the connection's memory editor: the switches of the memory, one scope (global or a
 * connection), the budget with what is sent, the entries or the raw Markdown of the scope's file, and the cleanup steps.
 *
 * <p>All file and AI work runs on background threads; every change of the view happens on the FX thread. Writes name the
 * digest of the text the view was built from, so an outside change is refused rather than overwritten.</p>
 */
final class AiMemoryPane extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(AiMemoryPane.class);
    private static final String ERROR_STYLE = "-fx-text-fill: #d9534f;";
    private static final String SENT_STYLE = "-fx-text-fill: #2f855a; -fx-font-size: 0.85em;";
    private static final String USER_BADGE_STYLE =
        "-fx-background-color: rgba(128,128,128,0.22); -fx-background-radius: 4; -fx-padding: 1 6 1 6;";
    private static final String KORTTY_BADGE_STYLE =
        "-fx-background-color: rgba(56,189,248,0.22); -fx-background-radius: 4; -fx-padding: 1 6 1 6;";
    private static final String BANNER_STYLE =
        "-fx-background-color: rgba(234,179,8,0.14); -fx-background-radius: 6; -fx-padding: 8 10 8 10;";
    private static final String WARNING_STYLE =
        "-fx-text-fill: #d9534f; -fx-background-color: rgba(217,83,79,0.14); -fx-background-radius: 6; -fx-padding: 8 10 8 10;";
    private static final double CLEANUP_HINT_SHARE = 0.8;
    private static final int BUDGET_STEP = 100;

    /** One scope the tab shows: the global file (connection id null), or the file of one connection. */
    record ScopeChoice(String connectionId, String label) {

        MemoryScope scope() {
            return connectionId == null ? MemoryScope.global() : MemoryScope.connection(connectionId);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The file and the block its scope sends, read off the FX thread. */
    private record Loaded(MemoryStore.Snapshot snapshot, MemoryBlock block) {
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    private interface Writer {
        MemoryStore.Snapshot write(String digest) throws IOException;
    }

    private final KorTTYApplication app;
    private final Window owner;
    private final MemoryStore store = MemoryService.get().store();
    private final MemoryUserActions actions = new MemoryUserActions(store);
    private final MemoryCleanupService cleanup = new MemoryCleanupService();
    private final AiMemoryAssistant assistant;
    private final Consumer<MemoryScope> storeListener = this::onStoreChanged;

    private final ComboBox<ScopeChoice> scopeCombo = new ComboBox<>();
    private final CheckBox enabledCheck = new CheckBox(I18n.get("settings.ai.memory.enabled"));
    private final ComboBox<AiMemoryLearningMode> learningCombo = new ComboBox<>();
    private final Spinner<Integer> budgetSpinner = new Spinner<>();
    private final Label budgetPolicyNote = hint("");
    private final AiQuotaBar budgetBar = new AiQuotaBar();
    private final Label budgetLabel = hint("");
    private final Label cleanupHint = hint(I18n.get("settings.ai.memory.budget.cleanupHint"));
    private final ToggleButton entriesToggle = new ToggleButton(I18n.get("settings.ai.memory.view.entries"));
    private final ToggleButton markdownToggle = new ToggleButton(I18n.get("settings.ai.memory.view.markdown"));
    private final ListView<Row> entriesList = new ListView<>();
    private final VBox entriesBox = new VBox(8);
    private final VBox markdownBox = new VBox(8);
    private final MonacoEditorPane markdownEditor = new MonacoEditorPane(false);
    private final Label staleLabel = hint(I18n.get("settings.ai.memory.markdown.stale"));
    private final Button markdownSaveButton = new Button(I18n.get("settings.ai.memory.markdown.save"));
    private final Button cleanupButton = new Button(I18n.get("settings.ai.memory.cleanup"));
    private final Button archiveButton = new Button(I18n.get("settings.ai.memory.archive.show"));
    private final Button historyButton = new Button(I18n.get("settings.ai.memory.history"));
    private final Button clearButton = new Button(I18n.get("settings.ai.memory.clear"));
    private final Button reloadButton = new Button(I18n.get("settings.ai.memory.reload"));
    private final Button saveSettingsButton = new Button(I18n.get("settings.save"));
    private final Label statusLabel = new Label();
    private final VBox bannerBox = new VBox(8);
    /** The warning for the selected scope when its file holds a possible secret; see {@link #updateSecretNotice}. */
    private final VBox secretBox = new VBox(8);
    private final VBox settingsBox = new VBox(10);
    private final VBox filesBox = new VBox(10);
    /**
     * The empty container at the bottom for the "Weitere Memory-Dateien" section; {@link #installExternalFilesSection}
     * fills it.
     */
    private final VBox externalFilesBox = new VBox(8);
    /** The "Weitere Memory-Dateien" section, installed into {@link #externalFilesBox}. */
    private final ExternalMemorySection externalSection;

    /** The file as last read; the entries and the Markdown are built from it. */
    private MemoryStore.Snapshot snapshot;
    private MemoryBlock block = MemoryBlock.empty();
    /** The text the Markdown editor was last filled with, and the digest of the file it came from. */
    private String markdownBase;
    private String markdownDigest;
    private long generation;
    private boolean scopeChanging;
    private boolean disposed;

    AiMemoryPane(KorTTYApplication app, Window owner, ScopeChoice initial) {
        this.app = app;
        this.owner = owner;
        this.assistant = new AiMemoryAssistant(app);
        setSpacing(10);
        setPadding(new Insets(6));
        configureScopes(initial);
        buildSettings();
        buildFiles();
        externalSection = new ExternalMemorySection(owner, List.copyOf(scopeCombo.getItems()), this::currentScopeChoice);
        installExternalFilesSection(externalSection);
        rebuildBanners();
        getChildren().addAll(bannerBox, secretBox, settingsBox, filesBox);
        VBox.setVgrow(filesBox, Priority.ALWAYS);
        scopeCombo.valueProperty().addListener((obs, was, now) -> onScopeChanged(was, now));
        store.addChangeListener(storeListener);
        refresh();
    }

    // ----- layout ---------------------------------------------------------------------------

    private void configureScopes(ScopeChoice initial) {
        List<ScopeChoice> choices = new ArrayList<>();
        choices.add(new ScopeChoice(null, I18n.get("settings.ai.memory.scope.global")));
        List<ServerConnection> connections = app != null && app.getConfigManager() != null
            ? app.getConfigManager().getConnections()
            : List.of();
        connections.stream()
            .filter(connection -> connection != null && connection.getId() != null)
            .sorted(Comparator.comparing(
                (ServerConnection connection) -> Objects.toString(connection.getDisplayName(), ""),
                String.CASE_INSENSITIVE_ORDER))
            .forEach(connection -> choices.add(new ScopeChoice(connection.getId(),
                Objects.toString(connection.getDisplayName(), connection.getId()))));
        if (initial != null && initial.connectionId() != null && choices.stream()
            .noneMatch(choice -> initial.connectionId().equals(choice.connectionId()))) {
            choices.add(initial);
        }
        scopeCombo.getItems().setAll(choices);
        ScopeChoice selected = choices.get(0);
        if (initial != null) {
            selected = choices.stream()
                .filter(choice -> Objects.equals(choice.connectionId(), initial.connectionId()))
                .findFirst()
                .orElse(selected);
        }
        scopeCombo.getSelectionModel().select(selected);
        scopeCombo.setPrefWidth(320);
    }

    private void buildSettings() {
        GlobalSettings settings = settings();
        enabledCheck.setSelected(settings == null || settings.isAiMemoryEnabled());
        PolicyUiSupport.lockIfManaged(enabledCheck, ManagedSetting.AI_MEMORY);

        learningCombo.getItems().setAll(AiMemoryLearningMode.PROPOSE, AiMemoryLearningMode.OFF);
        learningCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(AiMemoryLearningMode mode) {
                return mode == null ? "" : I18n.get(mode == AiMemoryLearningMode.OFF
                    ? "settings.ai.memory.learning.off" : "settings.ai.memory.learning.propose");
            }

            @Override
            public AiMemoryLearningMode fromString(String text) {
                return null;
            }
        });
        learningCombo.getSelectionModel().select(settings != null
            ? settings.getAiMemoryLearningMode() : AiMemoryLearningMode.PROPOSE);
        PolicyUiSupport.lockIfManaged(learningCombo, ManagedSetting.AI_MEMORY_LEARNING);

        OptionalInt cap = PolicyManager.effective().aiMemoryMaxTokens();
        int max = cap.isPresent()
            ? Math.min(GlobalSettings.AI_MEMORY_TOKENS_MAX, cap.getAsInt())
            : GlobalSettings.AI_MEMORY_TOKENS_MAX;
        int min = Math.min(GlobalSettings.AI_MEMORY_TOKENS_MIN, max);
        int stored = settings != null ? settings.getAiMemoryMaxTokens() : GlobalSettings.AI_MEMORY_TOKENS_DEFAULT;
        budgetSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max,
            Math.max(min, Math.min(max, stored)), BUDGET_STEP));
        budgetSpinner.setEditable(true);
        budgetSpinner.setPrefWidth(140);
        if (cap.isPresent()) {
            budgetPolicyNote.setText(I18n.get("settings.ai.memory.budget.policyCap", cap.getAsInt()));
        } else {
            budgetPolicyNote.setVisible(false);
            budgetPolicyNote.setManaged(false);
        }

        saveSettingsButton.setOnAction(event -> save(false));
        ButtonIcons.apply(saveSettingsButton, ButtonIcons.SAVE);
        GridPane grid = AiManagerSections.formGrid();
        AiManagerSections.addFieldRow(grid, enabledCheck);
        AiManagerSections.addFieldRow(grid, hint(I18n.get("settings.ai.memory.enabled.hint")));
        AiManagerSections.addRow(grid, "settings.ai.memory.learning", learningCombo);
        AiManagerSections.addFieldRow(grid, hint(I18n.get("settings.ai.memory.learning.hint")));
        AiManagerSections.addRow(grid, "settings.ai.memory.budget", budgetSpinner);
        AiManagerSections.addFieldRow(grid, budgetPolicyNote);
        HBox saveRow = new HBox(saveSettingsButton);
        saveRow.setAlignment(Pos.CENTER_RIGHT);
        settingsBox.getChildren().addAll(grid, saveRow);
        AiManagerSections.keepButtonWidths(settingsBox);
    }

    private void buildFiles() {
        reloadButton.setOnAction(event -> reload());
        HBox scopeRow = new HBox(10, label(I18n.get("settings.ai.memory.scope")), scopeCombo, reloadButton);
        scopeRow.setAlignment(Pos.CENTER_LEFT);

        cleanupHint.setVisible(false);
        cleanupHint.setManaged(false);
        budgetBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(budgetBar, Priority.ALWAYS);
        HBox budgetRow = new HBox(10, budgetBar, budgetLabel);
        budgetRow.setAlignment(Pos.CENTER_LEFT);
        VBox budgetBox = new VBox(4, budgetRow, cleanupHint);

        ToggleGroup views = new ToggleGroup();
        entriesToggle.setToggleGroup(views);
        markdownToggle.setToggleGroup(views);
        entriesToggle.setSelected(true);
        views.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now == null) {
                entriesToggle.setSelected(true);
                return;
            }
            showView(now == markdownToggle);
        });
        HBox toggles = new HBox(0, entriesToggle, markdownToggle);

        entriesList.setCellFactory(list -> rowCell());
        entriesList.setPrefHeight(300);
        entriesList.setPlaceholder(hint(I18n.get("settings.ai.memory.empty")));
        entriesBox.getChildren().add(entriesList);
        VBox.setVgrow(entriesList, Priority.ALWAYS);

        markdownEditor.setLanguage("markdown");
        markdownEditor.setMinHeight(240);
        markdownEditor.setPrefHeight(300);
        markdownSaveButton.setOnAction(event -> saveMarkdown());
        staleLabel.setVisible(false);
        staleLabel.setManaged(false);
        markdownBox.getChildren().addAll(staleLabel, markdownEditor, new HBox(markdownSaveButton));
        VBox.setVgrow(markdownEditor, Priority.ALWAYS);
        showView(false);

        cleanupButton.setOnAction(event -> openCleanup());
        archiveButton.setOnAction(event -> openArchive());
        historyButton.setOnAction(event -> openHistory());
        clearButton.setOnAction(event -> clearMemory());
        HBox actions = new HBox(10, cleanupButton, archiveButton, historyButton, clearButton);

        statusLabel.setWrapText(true);
        statusLabel.setStyle(MutedTextStyle.HINT);

        filesBox.getChildren().addAll(scopeRow, budgetBox, toggles, entriesBox, markdownBox, actions, statusLabel,
            externalFilesBox);
        // The view on show takes the height the tab has to spare.
        VBox.setVgrow(entriesBox, Priority.ALWAYS);
        VBox.setVgrow(markdownBox, Priority.ALWAYS);
        AiManagerSections.keepButtonWidths(filesBox);
    }

    /** Installs the "Weitere Memory-Dateien" section into the empty container at the bottom of the tab. */
    void installExternalFilesSection(Node section) {
        externalFilesBox.getChildren().setAll(new Separator(), section);
    }

    private void showView(boolean markdown) {
        entriesBox.setVisible(!markdown);
        entriesBox.setManaged(!markdown);
        markdownBox.setVisible(markdown);
        markdownBox.setManaged(markdown);
        if (markdown) {
            markdownEditor.activate();
        }
    }

    private void rebuildBanners() {
        bannerBox.getChildren().clear();
        EffectivePolicy policy = PolicyManager.effective();
        if (!policy.aiMemoryAllowed()) {
            bannerBox.getChildren().add(banner(I18n.get("settings.ai.memory.policy.denied")));
        } else if (policy.userMemoryMode() == UserMemoryMode.DISABLED) {
            bannerBox.getChildren().add(banner(I18n.get("settings.ai.memory.policy.disabled")));
        } else if (policy.userMemoryMode() == UserMemoryMode.READ_ONLY) {
            bannerBox.getChildren().add(banner(I18n.get("settings.ai.memory.policy.readOnly")));
        }
        if (killSwitchOff()) {
            bannerBox.getChildren().add(banner(I18n.get("settings.ai.memory.policy.off", MemoryService.KILL_SWITCH_VARIABLE)));
        }
        List<PolicyFile.OrganizationMemoryBlock> organization = policy.organizationMemory();
        if (!organization.isEmpty()) {
            bannerBox.getChildren().add(organizationBanner(organization));
        }
    }

    private VBox organizationBanner(List<PolicyFile.OrganizationMemoryBlock> rules) {
        Label title = new Label(I18n.get("settings.ai.memory.org.title"));
        title.setStyle("-fx-font-weight: bold;");
        VBox box = new VBox(6, title, hint(I18n.get("settings.ai.memory.org.hint")));
        for (PolicyFile.OrganizationMemoryBlock rule : rules) {
            Label content = new Label(rule.content());
            content.setWrapText(true);
            String heading = rule.title() == null || rule.title().isBlank()
                ? I18n.get("settings.ai.memory.org.untitled")
                : rule.title().strip();
            TitledPane pane = new TitledPane(heading, content);
            pane.setExpanded(false);
            pane.setAnimated(false);
            box.getChildren().add(pane);
        }
        box.setStyle(BANNER_STYLE);
        return box;
    }

    private void applyPolicyVisibility() {
        boolean visible = filesVisible();
        settingsBox.setVisible(visible);
        settingsBox.setManaged(visible);
        filesBox.setVisible(visible);
        filesBox.setManaged(visible);
    }

    // ----- reading --------------------------------------------------------------------------

    /**
     * Reads the file of the current scope and the block it sends, off the FX thread, and shows them. A reload that is
     * overtaken by a newer one is dropped.
     */
    void refresh() {
        applyPolicyVisibility();
        if (filesVisible()) {
            externalSection.refresh();
        }
        if (!filesVisible()) {
            entriesList.getItems().clear();
            snapshot = null;
            updateButtons();
            updateSecretNotice();
            return;
        }
        ScopeChoice choice = scopeCombo.getValue();
        if (choice == null) {
            return;
        }
        long current = ++generation;
        AiProfile profile = assistant.defaultProfile();
        CompletableFuture.supplyAsync(() -> {
            try {
                return new Loaded(store.read(choice.scope()),
                    TerminalAgentService.memoryFor(choice.connectionId(), profile));
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }).whenComplete((loaded, error) -> Platform.runLater(() -> {
            if (disposed || current != generation) {
                return;
            }
            if (error != null) {
                showFailure(error);
                return;
            }
            apply(loaded);
        }));
    }

    private void apply(Loaded loaded) {
        snapshot = loaded.snapshot();
        block = loaded.block();
        entriesList.getItems().setAll(MemoryEntriesModel.rows(snapshot.text(), block, !currentScope().isGlobal()));
        updateMarkdown();
        updateBudget();
        updateButtons();
        updateSecretNotice();
    }

    /** Fills the Markdown editor from the file unless the user has unsaved edits, which are kept and marked stale. */
    private void updateMarkdown() {
        if (markdownBase == null || !markdownDirty()) {
            markdownEditor.setText(snapshot.text());
            markdownBase = snapshot.text();
            markdownDigest = snapshot.sha256();
            setStale(false);
        } else if (!snapshot.sha256().equals(markdownDigest)) {
            setStale(true);
        }
    }

    private boolean markdownDirty() {
        return markdownBase != null && !markdownBase.equals(markdownEditor.getText());
    }

    private void setStale(boolean stale) {
        staleLabel.setVisible(stale);
        staleLabel.setManaged(stale);
    }

    private void updateBudget() {
        int used = block.tokens();
        int budget = effectiveBudget();
        double share = budget > 0 ? (double) used / budget : 0.0;
        AiTokenWarningLevel level = share >= 1.0
            ? AiTokenWarningLevel.RED
            : share >= CLEANUP_HINT_SHARE ? AiTokenWarningLevel.YELLOW : AiTokenWarningLevel.NONE;
        budgetBar.update(Math.min(share, 1.0), 80, 100, level, false);
        budgetLabel.setText(I18n.get("settings.ai.memory.budget.usage", used, budget, block.omittedCount()));
        boolean hintVisible = filesVisible() && share >= CLEANUP_HINT_SHARE;
        cleanupHint.setVisible(hintVisible);
        cleanupHint.setManaged(hintVisible);
    }

    /** The budget the scope sends with: the setting, capped by the enterprise policy. */
    private int effectiveBudget() {
        GlobalSettings settings = settings();
        int configured = settings != null ? settings.getAiMemoryMaxTokens() : GlobalSettings.AI_MEMORY_TOKENS_DEFAULT;
        OptionalInt cap = PolicyManager.effective().aiMemoryMaxTokens();
        return cap.isPresent() ? Math.min(configured, cap.getAsInt()) : configured;
    }

    private void updateButtons() {
        boolean canEdit = editable();
        boolean visible = filesVisible();
        markdownEditor.setEditable(canEdit);
        markdownSaveButton.setDisable(!canEdit);
        clearButton.setDisable(!canEdit);
        cleanupButton.setDisable(!canEdit);
        archiveButton.setDisable(!visible);
        historyButton.setDisable(!visible);
        reloadButton.setDisable(!visible);
        // A disabled action says why: the policy that turned it off, or the read-only policy for the editing actions.
        String readOnlyReason = visible ? I18n.get("settings.ai.memory.policy.readOnly") : I18n.get("settings.ai.memory.policy.disabled");
        setDisabledReason(markdownSaveButton, !canEdit, readOnlyReason);
        setDisabledReason(clearButton, !canEdit, readOnlyReason);
        setDisabledReason(cleanupButton, !canEdit, readOnlyReason);
        setDisabledReason(archiveButton, !visible, I18n.get("settings.ai.memory.policy.disabled"));
        setDisabledReason(historyButton, !visible, I18n.get("settings.ai.memory.policy.disabled"));
        setDisabledReason(reloadButton, !visible, I18n.get("settings.ai.memory.policy.disabled"));
    }

    /** Shows {@code reason} as the control's tooltip while it is disabled; an enabled control has none from here. */
    private static void setDisabledReason(javafx.scene.control.Control control, boolean disabled, String reason) {
        control.setTooltip(disabled ? new Tooltip(reason) : null);
    }

    private ListCell<Row> rowCell() {
        return new ListCell<>() {
            @Override
            protected double computePrefWidth(double height) {
                // The row takes the list's width. Its bound graphic would otherwise report a preferred width a fraction of
                // a pixel wider than the list, and the list would show a horizontal bar for it.
                return getInsets().getLeft() + getInsets().getRight();
            }

            @Override
            protected void updateItem(Row row, boolean empty) {
                super.updateItem(row, empty);
                setText(null);
                if (empty || row == null) {
                    setGraphic(null);
                    return;
                }
                HBox view = rowView(row);
                // The row takes the list's width, so its text wraps there; the unwrapped text length must not widen the list.
                view.prefWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                    () -> Math.max(0, getWidth() - getInsets().getLeft() - getInsets().getRight()),
                    widthProperty(), paddingProperty()));
                setGraphic(view);
            }
        };
    }

    private HBox rowView(Row row) {
        Label badge = new Label(row.isUserText()
            ? I18n.get("settings.ai.memory.badge.user")
            : I18n.get("settings.ai.memory.badge.korttyEntry", row.created(), row.source()));
        badge.setStyle(row.isUserText() ? USER_BADGE_STYLE : KORTTY_BADGE_STYLE);
        badge.setWrapText(true);
        Label status = new Label(row.sent()
            ? I18n.get("settings.ai.memory.status.sent")
            : I18n.get("settings.ai.memory.status.notSent"));
        status.setStyle(row.sent() ? SENT_STYLE : MutedTextStyle.HINT);
        status.setWrapText(true);
        VBox meta = new VBox(4, badge, status);
        meta.setMinWidth(240);
        meta.setPrefWidth(240);
        Label text = new Label(row.text());
        text.setWrapText(true);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox line = new HBox(12, meta, text);
        line.setAlignment(Pos.TOP_LEFT);
        if (editable()) {
            Button edit = new Button(I18n.get("settings.ai.memory.row.edit"));
            edit.setOnAction(event -> editRow(row));
            Button delete = new Button(I18n.get("settings.ai.memory.row.delete"));
            delete.setOnAction(event -> deleteRow(row));
            line.getChildren().add(new VBox(6, edit, delete));
        }
        AiManagerSections.keepButtonWidths(line);
        return line;
    }

    // ----- writing --------------------------------------------------------------------------

    private void editRow(Row row) {
        MemoryStore.Snapshot base = snapshot;
        if (base == null) {
            return;
        }
        boolean user = row.isUserText();
        AiMemoryEntryEditDialog.edit(owner,
                I18n.get(user ? "settings.ai.memory.row.editUserTitle" : "settings.ai.memory.row.editKorttyTitle"),
                user ? null : I18n.get("settings.ai.memory.row.editKorttyNote"), row.text())
            .ifPresent(newText -> {
                String updated;
                try {
                    updated = user
                        ? MemoryRowEdit.replaceUserParagraph(base.text(), row, newText)
                        : MemoryRowEdit.replaceKorttyEntry(base.text(), row.entryId(), newText);
                } catch (RuntimeException failure) {
                    showFailure(failure);
                    return;
                }
                MemoryScope scope = currentScope();
                writeAsync(base.sha256(), digest -> actions.save(scope, digest, updated),
                    "settings.ai.memory.saved", written -> { });
            });
    }

    private void deleteRow(Row row) {
        MemoryStore.Snapshot base = snapshot;
        if (base == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.ownedBy(confirm, owner);
        confirm.setTitle(I18n.get("settings.ai.memory.row.deleteTitle"));
        confirm.setHeaderText(I18n.get("settings.ai.memory.row.deleteTitle"));
        confirm.setContentText(I18n.get("settings.ai.memory.row.deleteContent"));
        DialogThemeHelper.applyTheme(confirm);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        String updated;
        try {
            updated = row.isUserText()
                ? MemoryRowEdit.deleteUserParagraph(base.text(), row)
                : MemoryRowEdit.deleteKorttyEntry(base.text(), row.entryId());
        } catch (RuntimeException failure) {
            showFailure(failure);
            return;
        }
        MemoryScope scope = currentScope();
        writeAsync(base.sha256(), digest -> actions.save(scope, digest, updated),
            "settings.ai.memory.saved", written -> { });
    }

    private void saveMarkdown() {
        String text = markdownEditor.getText();
        String digest = markdownDigest != null ? markdownDigest : snapshot != null ? snapshot.sha256() : null;
        if (digest == null) {
            return;
        }
        MemoryScope scope = currentScope();
        writeAsync(digest, d -> actions.save(scope, d, text), "settings.ai.memory.markdown.saved", written -> {
            markdownBase = text;
            markdownDigest = written.sha256();
            setStale(false);
        });
    }

    private void clearMemory() {
        MemoryStore.Snapshot base = snapshot;
        if (base == null) {
            return;
        }
        boolean confirmed = AiMemoryDiffDialog.confirm(owner, I18n.get("settings.ai.memory.clear.title"),
            I18n.get("settings.ai.memory.clear.content"), base.text(), "", I18n.get("settings.ai.memory.clear.confirm"));
        if (!confirmed) {
            return;
        }
        MemoryScope scope = currentScope();
        writeAsync(base.sha256(), digest -> actions.clear(scope, digest), "settings.ai.memory.cleared", written -> { });
    }

    private void openHistory() {
        MemoryStore.Snapshot base = snapshot;
        if (base == null) {
            return;
        }
        MemoryScope scope = currentScope();
        new AiMemoryHistoryDialog(owner, store, scope, base.text(), editable(), restored ->
            writeAsync(base.sha256(), digest -> actions.restore(scope, digest, restored),
                "settings.ai.memory.restored", written -> { })).show();
    }

    /**
     * Opens the archive of the current scope in its own window. Viewing works in every mode the tab is shown in; restoring and
     * deleting need a memory that may be changed, and the view says why they are off.
     */
    private void openArchive() {
        ScopeChoice choice = currentScopeChoice();
        boolean writable = editable();
        String reason = filesVisible()
            ? I18n.get("settings.ai.memory.policy.readOnly")
            : I18n.get("settings.ai.memory.policy.disabled");
        String target = choice.label() + " (" + choice.scope().relativePath() + ")";
        new AiMemoryArchiveViewDialog(owner, store, choice.scope(), target, writable, reason, effectiveBudget(),
            this::refresh).show();
    }

    private void writeAsync(String digest, Writer writer, String successKey, Consumer<MemoryStore.Snapshot> onWritten) {
        runAsync(() -> writer.write(digest), written -> {
            onWritten.accept(written);
            setStatus(I18n.get(successKey), false);
            refresh();
        });
    }

    /** Settings are saved with the other AI options. {@code quiet}: no status line, used when the dialog closes. */
    void save(boolean quiet) {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        settings.setAiMemoryEnabled(enabledCheck.isSelected());
        settings.setAiMemoryLearningMode(learningCombo.getValue());
        settings.setAiMemoryMaxTokens(budgetSpinner.getValue());
        try {
            app.getGlobalSettingsManager().save();
            if (!quiet) {
                setStatus(I18n.get("settings.ai.memory.settingsSaved"), false);
            }
        } catch (Exception failure) {
            logger.warn("The memory settings could not be saved");
            if (!quiet) {
                setStatus(I18n.get("settings.ai.memory.error", failure.getMessage()), true);
            }
        }
        refresh();
    }

    // ----- cleanup --------------------------------------------------------------------------

    private void openCleanup() {
        if (snapshot == null) {
            return;
        }
        AiMemoryCleanupDialog.choose(owner, assistant.available()).ifPresent(action -> {
            switch (action) {
                case MERGE -> proposeMerge();
                case ARCHIVE -> archive();
                case SHORTEN -> proposeShortening();
            }
        });
    }

    private void proposeMerge() {
        MemoryStore.Snapshot base = snapshot;
        MemoryScope scope = currentScope();
        setStatus(I18n.get("settings.ai.memory.cleanup.busy"), false);
        runAsync(() -> cleanup.proposeMerge(scope, base, assistant), this::confirmProposal);
    }

    private void proposeShortening() {
        MemoryStore.Snapshot base = snapshot;
        MemoryScope scope = currentScope();
        setStatus(I18n.get("settings.ai.memory.cleanup.busy"), false);
        runAsync(() -> cleanup.proposeUserShortening(scope, base, assistant), this::confirmProposal);
    }

    /** Shows the proposal as a diff; only the confirmed change is written. */
    private void confirmProposal(Proposal proposal) {
        boolean merge = proposal.kind() == MemoryCleanupService.Kind.MERGE_KORTTY_ENTRIES;
        boolean confirmed = AiMemoryDiffDialog.confirm(owner, I18n.get("settings.ai.memory.cleanup.diffTitle"),
            I18n.get(merge ? "settings.ai.memory.cleanup.diffHeader" : "settings.ai.memory.cleanup.userDiffHeader"),
            proposal.currentText(), proposal.proposedText(), I18n.get("settings.ai.memory.cleanup.apply"));
        if (!confirmed) {
            setStatus(I18n.get("settings.ai.memory.cleanup.cancelled"), false);
            return;
        }
        setStatus(I18n.get("settings.ai.memory.cleanup.busy"), false);
        runAsync(() -> merge ? cleanup.applyMerge(store, proposal) : cleanup.applyUserShortening(actions, proposal),
            written -> {
                setStatus(I18n.get("settings.ai.memory.cleanup.done"), false);
                refresh();
            });
    }

    private void archive() {
        MemoryStore.Snapshot base = snapshot;
        if (base == null) {
            return;
        }
        MemoryScope scope = currentScope();
        List<KorttyEntry> candidates = MemoryCleanupService.archiveCandidates(base.text(), LocalDate.now());
        if (candidates.isEmpty()) {
            setStatus(I18n.get("settings.ai.memory.cleanup.archiveNone"), false);
            return;
        }
        AiMemoryArchiveDialog.choose(owner, candidates).ifPresent(ids ->
            runAsync(() -> cleanup.archive(store, scope, ids), written -> {
                setStatus(I18n.get("settings.ai.memory.cleanup.archived", ids.size()), false);
                refresh();
            }));
    }

    // ----- background, status, lifecycle ----------------------------------------------------

    /** Runs {@code work} off the FX thread and hands its result to {@code onSuccess} on the FX thread. */
    private <T> void runAsync(Work<T> work, Consumer<T> onSuccess) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return work.run();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            if (disposed) {
                return;
            }
            if (error != null) {
                showFailure(error);
                return;
            }
            onSuccess.accept(result);
        }));
    }

    private void reload() {
        if (markdownDirty() && !confirmDiscardMarkdown()) {
            return;
        }
        markdownBase = null;
        refresh();
    }

    private boolean confirmDiscardMarkdown() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.ownedBy(confirm, owner);
        confirm.setTitle(I18n.get("settings.ai.memory.markdown.discardTitle"));
        confirm.setHeaderText(I18n.get("settings.ai.memory.markdown.discardTitle"));
        confirm.setContentText(I18n.get("settings.ai.memory.markdown.discardContent"));
        DialogThemeHelper.applyTheme(confirm);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void onScopeChanged(ScopeChoice was, ScopeChoice now) {
        if (scopeChanging || now == null) {
            return;
        }
        if (markdownDirty() && !confirmDiscardMarkdown()) {
            scopeChanging = true;
            try {
                scopeCombo.getSelectionModel().select(was);
            } finally {
                scopeChanging = false;
            }
            return;
        }
        markdownBase = null;
        refresh();
    }

    private void onStoreChanged(MemoryScope changed) {
        Platform.runLater(() -> {
            if (!disposed && changed.equals(currentScope())) {
                refresh();
            }
        });
    }

    private void showFailure(Throwable error) {
        logger.debug("A memory action was refused or failed: {}", error.getClass().getSimpleName());
        setStatus(AiMemoryMessages.describe(error), true);
    }

    private void setStatus(String text, boolean error) {
        statusLabel.setText(text);
        statusLabel.setStyle(error ? ERROR_STYLE : MutedTextStyle.HINT);
    }

    /**
     * Whether the dialog may close. Unsaved Markdown is discarded only after the user confirms it, as a reload does.
     */
    boolean confirmClose() {
        return !markdownDirty() || confirmDiscardMarkdown();
    }

    /** Releases the Markdown editor's WebView and stops listening to the store. */
    void close() {
        if (disposed) {
            return;
        }
        disposed = true;
        store.removeChangeListener(storeListener);
        markdownEditor.dispose();
    }

    // ----- helpers --------------------------------------------------------------------------

    private boolean filesVisible() {
        EffectivePolicy policy = PolicyManager.effective();
        return policy.aiMemoryAllowed() && policy.userMemoryMode() != UserMemoryMode.DISABLED;
    }

    private boolean editable() {
        return filesVisible() && PolicyManager.effective().userMemoryMode() == UserMemoryMode.EDITABLE;
    }

    private ScopeChoice currentScopeChoice() {
        ScopeChoice choice = scopeCombo.getValue();
        return choice != null ? choice : scopeCombo.getItems().get(0);
    }

    private MemoryScope currentScope() {
        ScopeChoice choice = scopeCombo.getValue();
        return choice != null ? choice.scope() : MemoryScope.global();
    }

    private GlobalSettings settings() {
        return app != null && app.getGlobalSettingsManager() != null ? app.getGlobalSettingsManager().getSettings() : null;
    }

    /**
     * The warning for the selected scope when its file holds a possible secret: the first line and the kind of secret, never
     * the value. The warning goes once the file is saved clean, because every write checks the file again.
     */
    private void updateSecretNotice() {
        secretBox.getChildren().clear();
        List<AiSecretGuard.Finding> findings = filesVisible()
            ? AiSecretScanRegistry.shared().memoryFindings(currentScope().key())
            : List.of();
        if (!findings.isEmpty()) {
            AiSecretGuard.Finding first = findings.get(0);
            String text = I18n.get("settings.ai.memory.secret.banner", first.line(),
                AiMemoryMessages.categoryLabel(first.category()));
            if (findings.size() > 1) {
                text = text + " " + I18n.get("settings.ai.memory.secret.bannerMore", findings.size());
            }
            Label warning = new Label(text);
            warning.setWrapText(true);
            warning.setStyle(WARNING_STYLE);
            secretBox.getChildren().add(warning);
        }
        boolean shown = !secretBox.getChildren().isEmpty();
        secretBox.setVisible(shown);
        secretBox.setManaged(shown);
    }

    private static boolean killSwitchOff() {
        String value = System.getenv(MemoryService.KILL_SWITCH_VARIABLE);
        return value != null && "off".equalsIgnoreCase(value.strip());
    }

    private static Label banner(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle(BANNER_STYLE);
        return label;
    }

    private static Label label(String text) {
        return new Label(text);
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        label.setStyle(MutedTextStyle.HINT);
        return label;
    }
}
