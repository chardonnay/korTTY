package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SnippetManager;
import de.kortty.core.highlight.HighlightPreview;
import de.kortty.core.highlight.TerminalHighlightService;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import de.kortty.model.Snippet;
import javafx.animation.PauseTransition;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Window;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The highlight rule-set editor, opened from <i>View → Highlighting → Manage Rule Sets…</i>, the pane
 * context menu's Highlighting submenu and <i>Settings → Terminal → Edit Rules…</i>.
 *
 * <p>Left, the rule sets: the built-in ones (read-only; <b>Duplicate</b> makes an editable copy) and the
 * user's. Right, the selected set's name and rules — a table in priority order with each rule's look,
 * scope, hits in the test text and a short check — and the selected rule's details. Below, a test text
 * and its live preview, computed by {@link HighlightPreview} with the matcher and time budget a terminal
 * pane uses, so the preview, the hit counts and the slow-rule warnings are what a pane would do.
 *
 * <p>A rule can also be a trigger: <b>Action</b> makes it show a desktop notification when its pattern
 * appears in new output ({@link HighlightRule.Action#NOTIFY}), named by its <b>Rule name</b>; the
 * matched text only with <b>Include the matched text</b>. Or it runs the <b>Snippet</b> picked from the
 * library in the matching pane ({@link HighlightRule.Action#RUN_SNIPPET}; {@link HighlightSnippetTrigger}).
 * While the organization's policy forbids triggers the action controls are locked; while the user's
 * switch in Settings → Terminal is off a hint says the rule only highlights.
 *
 * <p>All editing happens on working copies ({@link HighlightRulesEditorModel}); OK stores them in
 * {@link GlobalSettings} and is disabled, with the first problem named in the footer, while any rule or
 * set is invalid. {@link #showAndSave} then writes {@code global-settings.xml} through
 * {@link GlobalSettingsManager} and reloads the {@link TerminalHighlightService}, so open panes and the
 * menus follow at once. Because the settings object is the one the Settings dialog applies its own
 * fields to, saving from inside the Settings dialog loses nothing on either side.
 */
public final class HighlightRulesDialog {

    private static final Logger logger = LoggerFactory.getLogger(HighlightRulesDialog.class);

    static final String TITLE_KEY = "highlight.editor.title";
    static final String SETS_KEY = "highlight.editor.sets";
    static final String NEW_SET_KEY = "highlight.editor.newSet";
    static final String DUPLICATE_KEY = "highlight.editor.duplicate";
    static final String DELETE_KEY = "highlight.editor.delete";
    static final String BUILTIN_TAG_KEY = "highlight.editor.builtinTag";
    static final String NAME_KEY = "highlight.editor.name";
    static final String BUILTIN_HINT_KEY = "highlight.editor.builtinHint";
    static final String RULES_KEY = "highlight.editor.rules";
    static final String NO_RULES_KEY = "highlight.editor.noRules";
    static final String COLUMN_ENABLED_KEY = "highlight.editor.column.enabled";
    static final String COLUMN_PATTERN_KEY = "highlight.editor.column.pattern";
    static final String COLUMN_SCOPE_KEY = "highlight.editor.column.scope";
    static final String COLUMN_HITS_KEY = "highlight.editor.column.hits";
    static final String COLUMN_CHECK_KEY = "highlight.editor.column.check";
    static final String ADD_RULE_KEY = "highlight.editor.addRule";
    static final String REMOVE_RULE_KEY = "highlight.editor.removeRule";
    static final String MOVE_UP_KEY = "highlight.editor.moveUp";
    static final String MOVE_DOWN_KEY = "highlight.editor.moveDown";
    static final String PRIORITY_HINT_KEY = "highlight.editor.priorityHint";
    static final String PATTERN_KEY = "highlight.editor.pattern";
    static final String REGEX_KEY = "highlight.editor.regex";
    static final String IGNORE_CASE_KEY = "highlight.editor.ignoreCase";
    static final String WHOLE_WORD_KEY = "highlight.editor.wholeWord";
    static final String SCOPE_KEY = "highlight.editor.scope";
    static final String SCOPE_MATCH_KEY = "highlight.editor.scope.match";
    static final String SCOPE_LINE_KEY = "highlight.editor.scope.line";
    static final String FOREGROUND_KEY = "highlight.editor.foreground";
    static final String BACKGROUND_KEY = "highlight.editor.background";
    static final String STYLE_KEY = "highlight.editor.style";
    static final String BOLD_KEY = "highlight.editor.bold";
    static final String ITALIC_KEY = "highlight.editor.italic";
    static final String UNDERLINE_KEY = "highlight.editor.underline";
    static final String SAMPLE_KEY = "highlight.editor.sample";
    static final String PREVIEW_KEY = "highlight.editor.preview";
    /** {0} is {@link HighlightPreview#MAX_LINES}. */
    static final String PREVIEW_TRUNCATED_KEY = "highlight.editor.previewTruncated";
    static final String PREVIEW_INCOMPLETE_KEY = "highlight.editor.previewIncomplete";
    static final String COLUMN_ACTION_KEY = "highlight.editor.column.action";
    static final String COLUMN_ACTION_NOTIFY_KEY = "highlight.editor.column.action.notify";
    static final String COLUMN_ACTION_RUN_SNIPPET_KEY = "highlight.editor.column.action.runSnippet";
    static final String ACTION_KEY = "highlight.editor.action";
    static final String ACTION_NONE_KEY = "highlight.editor.action.none";
    static final String ACTION_NOTIFY_KEY = "highlight.editor.action.notify";
    static final String ACTION_RUN_SNIPPET_KEY = "highlight.editor.action.runSnippet";
    static final String SNIPPET_KEY = "highlight.editor.snippet";
    static final String SNIPPET_PROMPT_KEY = "highlight.editor.snippet.prompt";
    static final String SNIPPET_TOOLTIP_KEY = "highlight.editor.snippet.tooltip";
    /** A rule's snippet that is no longer in the library. */
    static final String SNIPPET_MISSING_KEY = "highlight.editor.snippet.missing";
    static final String NOTIFY_WITH_TEXT_KEY = "highlight.editor.notifyWithText";
    static final String NOTIFY_WITH_TEXT_TOOLTIP_KEY = "highlight.editor.notifyWithText.tooltip";
    static final String RULE_NAME_KEY = "highlight.editor.ruleName";
    static final String RULE_NAME_PROMPT_KEY = "highlight.editor.ruleName.prompt";
    /** The hint below the action while the user's switch in Settings → Terminal is off. */
    static final String TRIGGERS_OFF_KEY = "highlight.editor.triggersOff";
    /** The hint below the action while the organization's policy forbids triggers. */
    static final String TRIGGERS_FORBIDDEN_KEY = "highlight.editor.triggersForbidden";

    /** Every key of the dialog's own texts, for the i18n coverage test. */
    static final List<String> KEYS = List.of(TITLE_KEY, SETS_KEY, NEW_SET_KEY, DUPLICATE_KEY, DELETE_KEY,
        BUILTIN_TAG_KEY, NAME_KEY, BUILTIN_HINT_KEY, RULES_KEY, NO_RULES_KEY, COLUMN_ENABLED_KEY, COLUMN_PATTERN_KEY,
        COLUMN_SCOPE_KEY, COLUMN_HITS_KEY, COLUMN_CHECK_KEY, ADD_RULE_KEY, REMOVE_RULE_KEY, MOVE_UP_KEY, MOVE_DOWN_KEY,
        PRIORITY_HINT_KEY, PATTERN_KEY, REGEX_KEY, IGNORE_CASE_KEY, WHOLE_WORD_KEY, SCOPE_KEY, SCOPE_MATCH_KEY,
        SCOPE_LINE_KEY, FOREGROUND_KEY, BACKGROUND_KEY, STYLE_KEY, BOLD_KEY, ITALIC_KEY, UNDERLINE_KEY, SAMPLE_KEY,
        PREVIEW_KEY, PREVIEW_TRUNCATED_KEY, PREVIEW_INCOMPLETE_KEY, COLUMN_ACTION_KEY, COLUMN_ACTION_NOTIFY_KEY,
        ACTION_KEY, ACTION_NONE_KEY, ACTION_NOTIFY_KEY, NOTIFY_WITH_TEXT_KEY, NOTIFY_WITH_TEXT_TOOLTIP_KEY,
        RULE_NAME_KEY, RULE_NAME_PROMPT_KEY, TRIGGERS_OFF_KEY, TRIGGERS_FORBIDDEN_KEY, COLUMN_ACTION_RUN_SNIPPET_KEY,
        ACTION_RUN_SNIPPET_KEY, SNIPPET_KEY, SNIPPET_PROMPT_KEY, SNIPPET_TOOLTIP_KEY, SNIPPET_MISSING_KEY);

    /**
     * A snippet the <b>Snippet</b> dropdown offers.
     *
     * @param id    the snippet's id, what the rule stores
     * @param label its name, with its folder in parentheses when it is in one
     */
    record SnippetChoice(String id, String label) {
    }

    /** Where the dialog's size and position are remembered. */
    static final String GEOMETRY_KEY = "highlight.rules";

    /** The content's preferred width. */
    static final double PREF_WIDTH = 980;

    /** The content's preferred height: the rule details hold the action, the snippet and the rule name too. */
    static final double PREF_HEIGHT = 820;

    /** What the title bar, the button bar and a margin take around the content on screen. */
    static final double DIALOG_CHROME_HEIGHT = 140;

    /** The shortest the content gets to fit a small screen; its split panes shrink instead of the buttons. */
    static final double MIN_FITTED_HEIGHT = 520;

    /**
     * The test text the preview starts with: log and network-device lines that the built-in sets and
     * typical rules hit. Documentation addresses only (RFC 5737, RFC 3849), no real host.
     */
    static final String DEFAULT_SAMPLE = String.join("\n",
        "Oct  3 09:14:02 web01 nginx[812]: GET /health HTTP/1.1 200 0.004s",
        "Oct  3 09:14:05 web01 nginx[812]: GET /api/orders HTTP/1.1 502 1.203s",
        "Oct  3 09:14:07 web01 cron[90]: WARNING: /etc/cron.d/backup uses a deprecated option",
        "Oct  3 09:14:09 web01 app[4711]: ERROR upstream timed out while connecting to 192.0.2.17:8080",
        "Traceback (most recent call last): ValueError: invalid literal for int()",
        "GigabitEthernet1/0/1 is up, line protocol is up; Gi1/0/2 is down (err-disabled)",
        "inet6 fe80::1%eth0/64  ether 00:1a:2b:3c:4d:5e  peer 2001:db8::10 established");

    /** How long typing has to pause before the preview is recomputed. */
    private static final Duration PREVIEW_DELAY = Duration.millis(150);

    private static final String MONOSPACE = "-fx-font-family: 'Monospaced';";

    private static final String HINT_STYLE = "-fx-text-fill: gray; -fx-font-size: 0.8462em;";

    private static final String WARNING_STYLE = "-fx-text-fill: #d97706;";

    /** The test text of this session, so reopening the editor keeps what the user typed. Not saved. */
    private static volatile String sessionSample = DEFAULT_SAMPLE;

    private final HighlightRulesEditorModel model;

    private final ConnectionSettings terminalColors;

    private final List<HighlightColorChoices.Choice> colorChoices = HighlightColorChoices.choices();

    private final ObservableList<HighlightRuleSet> sets = FXCollections.observableArrayList();

    private final ObservableList<HighlightRule> rules = FXCollections.observableArrayList();

    private final SimpleBooleanProperty invalid = new SimpleBooleanProperty();

    private final SimpleObjectProperty<HighlightPreview.Result> preview = new SimpleObjectProperty<>();

    private final ListView<HighlightRuleSet> setList = new ListView<>(sets);
    private final Button newSetButton = new Button(I18n.get(NEW_SET_KEY));
    private final Button duplicateButton = new Button(I18n.get(DUPLICATE_KEY));
    private final Button deleteButton = new Button(I18n.get(DELETE_KEY));

    private final TextField nameField = new TextField();
    private final Label builtinHint = new Label(I18n.get(BUILTIN_HINT_KEY));
    private final TableView<HighlightRule> ruleTable = new TableView<>(rules);
    private final Button addRuleButton = new Button(I18n.get(ADD_RULE_KEY));
    private final Button removeRuleButton = new Button(I18n.get(REMOVE_RULE_KEY));
    private final Button upButton = new Button("▲");
    private final Button downButton = new Button("▼");

    private final TextField patternField = new TextField();
    private final CheckBox regexCheck = new CheckBox(I18n.get(REGEX_KEY));
    private final CheckBox ignoreCaseCheck = new CheckBox(I18n.get(IGNORE_CASE_KEY));
    private final CheckBox wholeWordCheck = new CheckBox(I18n.get(WHOLE_WORD_KEY));
    private final ComboBox<HighlightRule.Scope> scopeCombo =
        new ComboBox<>(FXCollections.observableArrayList(HighlightRule.Scope.values()));
    private final ColorField foregroundField = new ColorField(spec -> editRule(rule -> rule.setForeground(spec)));
    private final ColorField backgroundField = new ColorField(spec -> editRule(rule -> rule.setBackground(spec)));
    private final CheckBox boldCheck = new CheckBox(I18n.get(BOLD_KEY));
    private final CheckBox italicCheck = new CheckBox(I18n.get(ITALIC_KEY));
    private final CheckBox underlineCheck = new CheckBox(I18n.get(UNDERLINE_KEY));
    private final ComboBox<HighlightRule.Action> actionCombo =
        new ComboBox<>(FXCollections.observableArrayList(HighlightRule.Action.values()));
    private final CheckBox notifyWithTextCheck = new CheckBox(I18n.get(NOTIFY_WITH_TEXT_KEY));
    private final ComboBox<SnippetChoice> snippetCombo = new ComboBox<>();
    private final TextField ruleNameField = new TextField();
    private final Label triggerHint = new Label();
    private final Label ruleMessage = new Label();
    private final GridPane details = new GridPane();

    private final TextArea sampleArea = new TextArea();
    private final VBox previewLines = new VBox();
    private final Label previewNotice = new Label();
    private final Label problemLabel = new Label();

    private final PauseTransition previewDelay = new PauseTransition(PREVIEW_DELAY);

    private HighlightRuleSet currentSet;

    private HighlightRule currentRule;

    /** True while controls are filled from the model, so their listeners do not write back. */
    private boolean loading;

    /** Whether the organization's policy forbids trigger actions: the action controls stay locked. */
    private final boolean triggersForbidden;

    /** Whether the user's switch for trigger actions (Settings → Terminal) is on. */
    private final boolean triggersEnabled;

    private HighlightRulesDialog(GlobalSettings settings) {
        this(settings, librarySnippets());
    }

    private HighlightRulesDialog(GlobalSettings settings, List<SnippetChoice> snippets) {
        this.model = new HighlightRulesEditorModel(settings.getHighlightRuleSets());
        this.terminalColors = settings.getDefaultTerminalSettings();
        this.triggersForbidden = !de.kortty.policy.PolicyManager.effective().terminalTriggersAllowed();
        this.triggersEnabled = settings.isTerminalTriggersEnabled();
        this.snippetCombo.getItems().setAll(snippets);
    }

    /** The snippets of the application's library, for the <b>Snippet</b> dropdown; none without one. */
    private static List<SnippetChoice> librarySnippets() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        SnippetManager manager = app != null ? app.getSnippetManager() : null;
        if (manager == null) {
            return List.of();
        }
        try {
            return snippetChoices(manager.getAllSnippets(), snippet -> manager.folderPath(snippet.getFolderId()));
        } catch (RuntimeException e) {
            logger.warn("Could not list the snippets for the highlight rule editor: {}", e.toString());
            return List.of();
        }
    }

    /**
     * The dropdown's entries for {@code snippets}: each snippet's name (the unnamed ones by
     * {@code snippets.insertTerminal.unnamed}), with its folder in parentheses, sorted by that label.
     *
     * @param folderPath the folder path of a snippet, blank for one at the top
     */
    static List<SnippetChoice> snippetChoices(List<Snippet> snippets, java.util.function.Function<Snippet, String> folderPath) {
        List<SnippetChoice> choices = new ArrayList<>();
        for (Snippet snippet : snippets) {
            if (snippet == null || snippet.getId() == null || snippet.getId().isBlank()) {
                continue;
            }
            String name = snippet.getName() != null && !snippet.getName().isBlank() ? snippet.getName().strip()
                : I18n.get(HighlightSnippetTrigger.UNNAMED_SNIPPET_KEY);
            String folder = folderPath != null ? folderPath.apply(snippet) : null;
            String label = folder != null && !folder.isBlank() ? name + " (" + folder.strip() + ")" : name;
            choices.add(new SnippetChoice(snippet.getId(), label));
        }
        choices.sort(Comparator.comparing((SnippetChoice choice) -> choice.label().toLowerCase(Locale.ROOT))
            .thenComparing(SnippetChoice::id));
        return List.copyOf(choices);
    }

    /**
     * Shows the editor and, on OK with changes, stores the user's sets in {@code settings}.
     *
     * @param initialSetId the set to select first (a pane's set, the default set), or {@code null}
     * @return whether {@code settings} changed and should be saved
     */
    public static boolean show(@Nullable Window owner, @NotNull GlobalSettings settings, @Nullable String initialSetId) {
        HighlightRulesDialog editor = new HighlightRulesDialog(settings);
        Dialog<ButtonType> dialog = editor.buildDialog(owner, initialSetId);
        fitToScreen(dialog, owner);
        boolean confirmed = dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
        sessionSample = editor.sampleArea.getText();
        if (!confirmed || !editor.model.isModified()) {
            return false;
        }
        editor.model.applyTo(settings);
        return true;
    }

    /**
     * Shows the editor for the application's settings and, when the user saved changes, writes them to
     * disk and moves every open pane to its updated set.
     *
     * @return whether rule sets were changed and saved
     */
    public static boolean showAndSave(@Nullable Window owner, @Nullable KorTTYApplication app,
                                      @Nullable String initialSetId) {
        GlobalSettingsManager manager = app != null ? app.getGlobalSettingsManager() : null;
        if (manager == null) {
            return false;
        }
        return showAndSave(owner, manager.getSettings(), manager, app.getTerminalHighlightService(), initialSetId);
    }

    /**
     * Shows the editor for {@code settings} and, when the user saved changes, writes them through
     * {@code manager} and reloads {@code service}. Either may be {@code null} (a headless capture).
     *
     * @return whether rule sets were changed
     */
    public static boolean showAndSave(@Nullable Window owner, @Nullable GlobalSettings settings,
                                      @Nullable GlobalSettingsManager manager,
                                      @Nullable TerminalHighlightService service, @Nullable String initialSetId) {
        if (settings == null || !show(owner, settings, initialSetId)) {
            return false;
        }
        saveAndReload(settings, manager, service);
        return true;
    }

    /**
     * Writes the settings file, then reloads the highlighting so open panes and the menus see the new
     * sets. Neither failure breaks the caller; the log names no pattern or set.
     */
    static void saveAndReload(@NotNull GlobalSettings settings, @Nullable GlobalSettingsManager manager,
                              @Nullable TerminalHighlightService service) {
        if (manager != null) {
            try {
                manager.save();
            } catch (Exception e) {
                logger.warn("Could not save the highlight rule sets: {}", e.toString());
            }
        }
        if (service != null && !service.isClosed()) {
            try {
                service.reload(settings);
            } catch (RuntimeException e) {
                logger.warn("Could not apply the highlight rule sets: {}", e.toString());
            }
        }
    }

    /**
     * The content's height on a screen whose usable height is {@code visualScreenHeight}: the preferred
     * height, lowered so the title and the OK button stay on a small screen (a 768-pixel laptop), but never
     * below {@link #MIN_FITTED_HEIGHT}. An unknown screen gets the preferred height.
     */
    static double fittedContentHeight(double visualScreenHeight) {
        if (!(visualScreenHeight > 0)) {
            return PREF_HEIGHT;
        }
        return Math.max(MIN_FITTED_HEIGHT, Math.min(PREF_HEIGHT, visualScreenHeight - DIALOG_CHROME_HEIGHT));
    }

    /**
     * Lowers the content's preferred height to the screen of {@code owner} (else the primary screen) before
     * the first show; a size the user gave the dialog before still wins ({@link DialogGeometrySupport}).
     */
    private static void fitToScreen(Dialog<?> dialog, @Nullable Window owner) {
        try {
            javafx.stage.Screen screen = javafx.stage.Screen.getPrimary();
            if (owner != null && owner.getWidth() > 0 && owner.getHeight() > 0) {
                List<javafx.stage.Screen> screens = javafx.stage.Screen.getScreensForRectangle(
                    owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight());
                if (!screens.isEmpty()) {
                    screen = screens.getFirst();
                }
            }
            if (dialog.getDialogPane().getContent() instanceof javafx.scene.layout.Region content) {
                content.setPrefHeight(fittedContentHeight(screen.getVisualBounds().getHeight()));
            }
        } catch (RuntimeException e) {
            logger.debug("Could not fit the highlight rule editor to the screen: {}", e.toString());
        }
    }

    /** The built, unshown dialog with a set and a rule selected — the manual's screenshot uses it. */
    static Dialog<ButtonType> buildForCapture(GlobalSettings settings, String setId, int ruleIndex, String sample) {
        return buildForCapture(settings, List.of(), setId, ruleIndex, sample);
    }

    /** As {@link #buildForCapture(GlobalSettings, String, int, String)}, with {@code snippets} to pick from. */
    static Dialog<ButtonType> buildForCapture(GlobalSettings settings, List<SnippetChoice> snippets, String setId,
                                              int ruleIndex, String sample) {
        HighlightRulesDialog editor = new HighlightRulesDialog(settings, snippets);
        editor.sampleArea.setText(sample);
        Dialog<ButtonType> dialog = editor.buildDialog(null, setId);
        if (ruleIndex >= 0 && ruleIndex < editor.rules.size()) {
            editor.ruleTable.getSelectionModel().select(ruleIndex);
        }
        editor.runPreview();
        return dialog;
    }

    // ==== layout ====

    private Dialog<ButtonType> buildDialog(@Nullable Window owner, @Nullable String initialSetId) {
        Dialog<ButtonType> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(I18n.get(TITLE_KEY));
        dialog.setResizable(true);
        DialogGeometrySupport.installAutomatic(dialog, GEOMETRY_KEY);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(invalid);

        SplitPane top = new SplitPane(buildSetsPane(), buildRulesPane());
        top.setDividerPositions(0.26);
        SplitPane bottom = new SplitPane(buildSamplePane(), buildPreviewPane());
        bottom.setDividerPositions(0.42);
        SplitPane split = new SplitPane(top, bottom);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.7);
        VBox.setVgrow(split, Priority.ALWAYS);

        problemLabel.setWrapText(true);
        problemLabel.setStyle(WARNING_STYLE);
        problemLabel.managedProperty().bind(problemLabel.textProperty().isNotEmpty());
        problemLabel.visibleProperty().bind(problemLabel.managedProperty());
        VBox content = new VBox(6, split, problemLabel);
        content.setPrefSize(PREF_WIDTH, PREF_HEIGHT);
        dialog.getDialogPane().setContent(content);

        if (sampleArea.getText() == null || sampleArea.getText().isEmpty()) {
            sampleArea.setText(sessionSample);
        }
        sampleArea.textProperty().addListener((obs, old, text) -> schedulePreview());
        previewDelay.setOnFinished(event -> runPreview());

        sets.setAll(model.allSets());
        HighlightRuleSet initial = model.find(initialSetId);
        if (initial == null) {
            initial = !model.userSets().isEmpty() ? model.userSets().getFirst() : sets.getFirst();
        }
        setList.getSelectionModel().select(initial);
        updateProblems();
        return dialog;
    }

    private VBox buildSetsPane() {
        setList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        setList.setCellFactory(view -> new SetCell());
        setList.getSelectionModel().selectedItemProperty().addListener((obs, old, set) -> showSet(set));

        newSetButton.setOnAction(event -> {
            HighlightRuleSet created = model.addSet();
            if (created != null) {
                sets.setAll(model.allSets());
                setList.getSelectionModel().select(created);
                patternField.requestFocus();
            }
        });
        duplicateButton.setOnAction(event -> {
            HighlightRuleSet source = setList.getSelectionModel().getSelectedItem();
            HighlightRuleSet copy = source != null ? model.duplicate(source) : null;
            if (copy != null) {
                sets.setAll(model.allSets());
                setList.getSelectionModel().select(copy);
                nameField.requestFocus();
                nameField.selectAll();
            }
        });
        deleteButton.setOnAction(event -> {
            HighlightRuleSet selected = setList.getSelectionModel().getSelectedItem();
            int index = setList.getSelectionModel().getSelectedIndex();
            if (model.delete(selected)) {
                sets.setAll(model.allSets());
                setList.getSelectionModel().select(Math.min(index, sets.size() - 1));
                updateProblems();
            }
        });

        Label title = new Label(I18n.get(SETS_KEY));
        title.setStyle("-fx-font-weight: bold;");
        // The buttons wrap rather than shrink to an ellipsis when the list is narrow.
        FlowPane buttons = new FlowPane(6, 6, newSetButton, duplicateButton, deleteButton);
        for (Button button : List.of(newSetButton, duplicateButton, deleteButton)) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        VBox box = new VBox(6, title, setList, buttons);
        box.setPadding(new Insets(8));
        box.setMinWidth(180);
        VBox.setVgrow(setList, Priority.ALWAYS);
        return box;
    }

    private VBox buildRulesPane() {
        nameField.textProperty().addListener((obs, old, text) -> {
            if (!loading && currentSet != null && !model.isReadOnly(currentSet)) {
                currentSet.setName(text);
                setList.refresh();
                updateProblems();
            }
        });
        HBox nameRow = new HBox(8, new Label(I18n.get(NAME_KEY)), nameField);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nameField, Priority.ALWAYS);

        builtinHint.setWrapText(true);
        builtinHint.setStyle(HINT_STYLE);
        builtinHint.managedProperty().bind(builtinHint.visibleProperty());

        buildRuleTable();

        addRuleButton.setOnAction(event -> {
            HighlightRule created = model.addRule(currentSet, ruleTable.getSelectionModel().getSelectedIndex());
            if (created != null) {
                reloadRules(created);
                patternField.requestFocus();
            }
        });
        removeRuleButton.setOnAction(event -> {
            int index = ruleTable.getSelectionModel().getSelectedIndex();
            if (model.removeRule(currentSet, ruleTable.getSelectionModel().getSelectedItem())) {
                List<HighlightRule> remaining = currentSet.getRules();
                reloadRules(remaining.isEmpty() ? null : remaining.get(Math.min(index, remaining.size() - 1)));
            }
        });
        upButton.setTooltip(new Tooltip(I18n.get(MOVE_UP_KEY)));
        upButton.setOnAction(event -> moveRule(-1));
        downButton.setTooltip(new Tooltip(I18n.get(MOVE_DOWN_KEY)));
        downButton.setOnAction(event -> moveRule(1));
        HBox ruleButtons = new HBox(6, addRuleButton, removeRuleButton, upButton, downButton);

        Label priorityHint = new Label(I18n.get(PRIORITY_HINT_KEY));
        priorityHint.setWrapText(true);
        priorityHint.setStyle(HINT_STYLE);

        buildDetails();

        Label rulesTitle = new Label(I18n.get(RULES_KEY));
        rulesTitle.setStyle("-fx-font-weight: bold;");
        VBox box = new VBox(6, nameRow, builtinHint, rulesTitle, ruleTable, ruleButtons, priorityHint, details,
            ruleMessage);
        box.setPadding(new Insets(8));
        VBox.setVgrow(ruleTable, Priority.ALWAYS);
        return box;
    }

    private void buildRuleTable() {
        ruleTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        ruleTable.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        ruleTable.setPlaceholder(new Label(I18n.get(NO_RULES_KEY)));
        ruleTable.setPrefHeight(170);
        ruleTable.setMinHeight(110);

        TableColumn<HighlightRule, Boolean> enabledColumn = new TableColumn<>(I18n.get(COLUMN_ENABLED_KEY));
        enabledColumn.setCellValueFactory(cell -> {
            SimpleBooleanProperty property = new SimpleBooleanProperty(cell.getValue().isEnabled());
            property.addListener((obs, old, value) -> {
                if (!model.isReadOnly(currentSet)) {
                    cell.getValue().setEnabled(value);
                    ruleEdited();
                }
            });
            return property;
        });
        enabledColumn.setCellFactory(CheckBoxTableCell.forTableColumn(enabledColumn));
        enabledColumn.setEditable(true);
        enabledColumn.setMinWidth(44);
        enabledColumn.setMaxWidth(60);

        TableColumn<HighlightRule, HighlightRule> patternColumn = new TableColumn<>(I18n.get(COLUMN_PATTERN_KEY));
        patternColumn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        patternColumn.setCellFactory(column -> new PatternCell());
        patternColumn.setMinWidth(200);
        patternColumn.setSortable(false);

        TableColumn<HighlightRule, String> scopeColumn = new TableColumn<>(I18n.get(COLUMN_SCOPE_KEY));
        scopeColumn.setCellValueFactory(cell -> new SimpleObjectProperty<>(scopeLabel(cell.getValue().getScope())));
        scopeColumn.setMinWidth(100);
        scopeColumn.setSortable(false);

        TableColumn<HighlightRule, HighlightRule> hitsColumn = new TableColumn<>(I18n.get(COLUMN_HITS_KEY));
        hitsColumn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        hitsColumn.setCellFactory(column -> new HitsCell());
        hitsColumn.setMinWidth(56);
        hitsColumn.setMaxWidth(80);
        hitsColumn.setSortable(false);

        TableColumn<HighlightRule, String> actionColumn = new TableColumn<>(I18n.get(COLUMN_ACTION_KEY));
        actionColumn.setCellValueFactory(cell -> new SimpleObjectProperty<>(switch (cell.getValue().getAction()) {
            case NOTIFY -> I18n.get(COLUMN_ACTION_NOTIFY_KEY);
            case RUN_SNIPPET -> I18n.get(COLUMN_ACTION_RUN_SNIPPET_KEY);
            case NONE -> "";
        }));
        actionColumn.setMinWidth(80);
        actionColumn.setSortable(false);

        TableColumn<HighlightRule, HighlightRule> checkColumn = new TableColumn<>(I18n.get(COLUMN_CHECK_KEY));
        checkColumn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        checkColumn.setCellFactory(column -> new CheckCell());
        checkColumn.setMinWidth(90);
        checkColumn.setSortable(false);

        ruleTable.getColumns().addAll(List.of(enabledColumn, patternColumn, scopeColumn, actionColumn, hitsColumn,
            checkColumn));
        ruleTable.getSelectionModel().selectedItemProperty().addListener((obs, old, rule) -> showRule(rule));
        enabledColumn.setSortable(false);
    }

    private void buildDetails() {
        patternField.setStyle(MONOSPACE);
        patternField.textProperty().addListener((obs, old, text) -> editRule(rule -> rule.setPattern(text)));
        regexCheck.selectedProperty().addListener((obs, old, value) -> editRule(rule -> rule.setRegex(value)));
        ignoreCaseCheck.selectedProperty().addListener((obs, old, value) -> editRule(rule -> rule.setIgnoreCase(value)));
        wholeWordCheck.selectedProperty().addListener((obs, old, value) -> editRule(rule -> rule.setWholeWord(value)));
        scopeCombo.setCellFactory(view -> new ScopeCell());
        scopeCombo.setButtonCell(new ScopeCell());
        scopeCombo.valueProperty().addListener((obs, old, value) -> {
            if (value != null) {
                editRule(rule -> rule.setScope(value));
            }
        });
        boldCheck.selectedProperty().addListener((obs, old, value) -> editRule(rule -> rule.setBold(value)));
        italicCheck.selectedProperty().addListener((obs, old, value) -> editRule(rule -> rule.setItalic(value)));
        underlineCheck.selectedProperty().addListener((obs, old, value) -> editRule(rule -> rule.setUnderline(value)));
        actionCombo.setCellFactory(view -> new ActionCell());
        actionCombo.setButtonCell(new ActionCell());
        actionCombo.valueProperty().addListener((obs, old, value) -> {
            if (value != null) {
                editRule(rule -> rule.setAction(value));
                updateTriggerControls();
            }
        });
        notifyWithTextCheck.setTooltip(new Tooltip(I18n.get(NOTIFY_WITH_TEXT_TOOLTIP_KEY)));
        notifyWithTextCheck.selectedProperty().addListener(
            (obs, old, value) -> editRule(rule -> rule.setNotifyWithText(value)));
        snippetCombo.setCellFactory(view -> new SnippetCell(false));
        snippetCombo.setButtonCell(new SnippetCell(true));
        snippetCombo.setPromptText(I18n.get(SNIPPET_PROMPT_KEY));
        snippetCombo.setTooltip(new Tooltip(I18n.get(SNIPPET_TOOLTIP_KEY)));
        snippetCombo.setMaxWidth(Double.MAX_VALUE);
        snippetCombo.valueProperty().addListener(
            (obs, old, value) -> editRule(rule -> rule.setSnippetId(value != null ? value.id() : null)));
        ruleNameField.setPromptText(I18n.get(RULE_NAME_PROMPT_KEY));
        ruleNameField.textProperty().addListener((obs, old, text) -> editRule(rule -> rule.setName(text)));
        triggerHint.setWrapText(true);
        triggerHint.setStyle(HINT_STYLE);
        triggerHint.managedProperty().bind(triggerHint.textProperty().isNotEmpty());
        triggerHint.visibleProperty().bind(triggerHint.managedProperty());

        details.setHgap(8);
        details.setVgap(6);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        details.getColumnConstraints().addAll(labels, fields);
        int row = 0;
        details.add(new Label(I18n.get(PATTERN_KEY)), 0, row);
        details.add(patternField, 1, row++);
        details.add(new HBox(14, regexCheck, ignoreCaseCheck, wholeWordCheck), 1, row++);
        details.add(new Label(I18n.get(SCOPE_KEY)), 0, row);
        details.add(scopeCombo, 1, row++);
        details.add(new Label(I18n.get(FOREGROUND_KEY)), 0, row);
        details.add(foregroundField.box, 1, row++);
        details.add(new Label(I18n.get(BACKGROUND_KEY)), 0, row);
        details.add(backgroundField.box, 1, row++);
        details.add(new Label(I18n.get(STYLE_KEY)), 0, row);
        details.add(new HBox(14, boldCheck, italicCheck, underlineCheck), 1, row++);
        details.add(new Label(I18n.get(ACTION_KEY)), 0, row);
        HBox actionRow = new HBox(14, actionCombo, notifyWithTextCheck);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        details.add(actionRow, 1, row++);
        details.add(new Label(I18n.get(SNIPPET_KEY)), 0, row);
        details.add(snippetCombo, 1, row++);
        details.add(new Label(I18n.get(RULE_NAME_KEY)), 0, row);
        details.add(ruleNameField, 1, row++);
        details.add(triggerHint, 1, row);

        ruleMessage.setWrapText(true);
        ruleMessage.setStyle(WARNING_STYLE);
        ruleMessage.managedProperty().bind(ruleMessage.textProperty().isNotEmpty());
        ruleMessage.visibleProperty().bind(ruleMessage.managedProperty());
    }

    private VBox buildSamplePane() {
        sampleArea.setStyle(MONOSPACE);
        sampleArea.setWrapText(false);
        sampleArea.setPrefRowCount(6);
        VBox box = new VBox(6, new Label(I18n.get(SAMPLE_KEY)), sampleArea);
        box.setPadding(new Insets(8));
        VBox.setVgrow(sampleArea, Priority.ALWAYS);
        return box;
    }

    private VBox buildPreviewPane() {
        previewLines.setStyle("-fx-background-color: " + HighlightColorChoices.terminalBackground(terminalColors)
            + "; -fx-padding: 6;");
        String background = HighlightColorChoices.terminalBackground(terminalColors);
        ScrollPane scroll = new ScrollPane(previewLines);
        scroll.setFitToHeight(true);
        scroll.setStyle("-fx-background: " + background + "; -fx-background-color: " + background + ";");
        // The square between the two scroll bars would otherwise stay light on the terminal's background.
        scroll.skinProperty().addListener((obs, old, skin) -> {
            Node corner = skin != null ? scroll.lookup(".corner") : null;
            if (corner != null) {
                corner.setStyle("-fx-background-color: " + background + ";");
            }
        });
        previewNotice.setWrapText(true);
        previewNotice.setStyle(HINT_STYLE);
        previewNotice.managedProperty().bind(previewNotice.textProperty().isNotEmpty());
        previewNotice.visibleProperty().bind(previewNotice.managedProperty());
        VBox box = new VBox(6, new Label(I18n.get(PREVIEW_KEY)), scroll, previewNotice);
        box.setPadding(new Insets(8));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return box;
    }

    // ==== selection ====

    private void showSet(@Nullable HighlightRuleSet set) {
        currentSet = set;
        boolean readOnly = model.isReadOnly(set);
        loading = true;
        try {
            nameField.setText(set == null ? "" : readOnly ? HighlightRulesEditorModel.displayName(set) : set.getName());
        } finally {
            loading = false;
        }
        nameField.setEditable(!readOnly);
        nameField.setDisable(set == null);
        builtinHint.setVisible(set != null && readOnly);
        ruleTable.setEditable(!readOnly);
        preview.set(null);
        reloadRules(set != null && !set.getRules().isEmpty() ? set.getRules().getFirst() : null);
        runPreview();
    }

    private void reloadRules(@Nullable HighlightRule select) {
        rules.setAll(currentSet != null ? currentSet.getRules() : List.of());
        if (select != null) {
            ruleTable.getSelectionModel().select(select);
            ruleTable.scrollTo(select);
        } else {
            ruleTable.getSelectionModel().clearSelection();
            showRule(null);
        }
        ruleEdited();
    }

    private void moveRule(int delta) {
        int target = model.moveRule(currentSet, ruleTable.getSelectionModel().getSelectedIndex(), delta);
        if (target >= 0) {
            reloadRules(currentSet.getRules().get(target));
        }
    }

    private void showRule(@Nullable HighlightRule rule) {
        currentRule = rule;
        boolean editable = rule != null && !model.isReadOnly(currentSet);
        loading = true;
        try {
            patternField.setText(rule != null && rule.getPattern() != null ? rule.getPattern() : "");
            regexCheck.setSelected(rule != null && rule.isRegex());
            ignoreCaseCheck.setSelected(rule != null && rule.isIgnoreCase());
            wholeWordCheck.setSelected(rule != null && rule.isWholeWord());
            scopeCombo.setValue(rule != null ? rule.getScope() : HighlightRule.Scope.MATCH);
            foregroundField.show(rule != null ? rule.getForeground() : null);
            backgroundField.show(rule != null ? rule.getBackground() : null);
            boldCheck.setSelected(rule != null && rule.isBold());
            italicCheck.setSelected(rule != null && rule.isItalic());
            underlineCheck.setSelected(rule != null && rule.isUnderline());
            actionCombo.setValue(rule != null ? rule.getAction() : HighlightRule.Action.NONE);
            notifyWithTextCheck.setSelected(rule != null && rule.isNotifyWithText());
            snippetCombo.setValue(snippetChoice(rule != null ? rule.getSnippetId() : null));
            ruleNameField.setText(rule != null && rule.getName() != null ? rule.getName() : "");
        } finally {
            loading = false;
        }
        // A built-in rule stays readable — the patterns are a good starting point — but cannot change.
        patternField.setEditable(editable);
        for (Node control : List.of(regexCheck, ignoreCaseCheck, wholeWordCheck, scopeCombo, foregroundField.box,
                backgroundField.box, boldCheck, italicCheck, underlineCheck)) {
            control.setDisable(!editable);
        }
        patternField.setDisable(rule == null);
        updateTriggerControls();
        updateRuleMessage();
        updateButtons();
    }

    /** The dropdown entry of {@code snippetId}: its library entry, else one that says it is missing. */
    private @Nullable SnippetChoice snippetChoice(@Nullable String snippetId) {
        if (snippetId == null) {
            return null;
        }
        for (SnippetChoice choice : snippetCombo.getItems()) {
            if (choice.id().equals(snippetId)) {
                return choice;
            }
        }
        return new SnippetChoice(snippetId, I18n.get(SNIPPET_MISSING_KEY));
    }

    /**
     * The action controls: locked while the policy forbids triggers (with the organization's hint),
     * otherwise editable for a rule of the user's; the matched-text option only matters for a rule that
     * notifies, the snippet only for one that runs a snippet, and the name for a rule with an action.
     */
    private void updateTriggerControls() {
        boolean editable = currentRule != null && !model.isReadOnly(currentSet);
        boolean acting = currentRule != null && currentRule.hasAction();
        HighlightRule.Action action = currentRule != null ? currentRule.getAction() : HighlightRule.Action.NONE;
        actionCombo.setDisable(!editable || (triggersForbidden && !acting));
        notifyWithTextCheck.setDisable(!editable || action != HighlightRule.Action.NOTIFY || triggersForbidden);
        snippetCombo.setDisable(!editable || action != HighlightRule.Action.RUN_SNIPPET || triggersForbidden);
        ruleNameField.setEditable(editable);
        ruleNameField.setDisable(currentRule == null);
        if (triggersForbidden) {
            actionCombo.setTooltip(new Tooltip(de.kortty.policy.PolicyUiSupport.managedByOrganizationText()));
        }
        String hint = "";
        if (acting && triggersForbidden) {
            hint = I18n.get(TRIGGERS_FORBIDDEN_KEY);
        } else if (acting && !triggersEnabled) {
            hint = I18n.get(TRIGGERS_OFF_KEY);
        }
        triggerHint.setText(hint);
    }

    // ==== editing ====

    /** Applies an edit of a detail control to the selected rule of a user set. */
    private void editRule(Consumer<HighlightRule> edit) {
        if (loading || currentRule == null || model.isReadOnly(currentSet)) {
            return;
        }
        edit.accept(currentRule);
        ruleEdited();
    }

    /** After any change of a rule: redraw the table, re-check, and recompute the preview shortly. */
    private void ruleEdited() {
        ruleTable.refresh();
        updateProblems();
        updateRuleMessage();
        updateButtons();
        schedulePreview();
    }

    private void updateProblems() {
        String problem = model.firstProblem().map(HighlightRulesEditorModel.Problem::message).orElse("");
        problemLabel.setText(problem);
        invalid.set(!problem.isEmpty());
    }

    private void updateRuleMessage() {
        ruleMessage.setText(String.join(" ",
            HighlightRulesEditorModel.ruleMessages(currentRule, statsOf(currentRule))));
    }

    private void updateButtons() {
        HighlightRuleSet set = setList.getSelectionModel().getSelectedItem();
        boolean readOnly = model.isReadOnly(set);
        int index = ruleTable.getSelectionModel().getSelectedIndex();
        newSetButton.setDisable(!model.canAddSet());
        duplicateButton.setDisable(set == null || !model.canAddSet());
        deleteButton.setDisable(set == null || readOnly);
        addRuleButton.setDisable(!model.canAddRule(set));
        removeRuleButton.setDisable(readOnly || index < 0);
        upButton.setDisable(readOnly || index <= 0);
        downButton.setDisable(readOnly || index < 0 || index >= rules.size() - 1);
    }

    // ==== preview ====

    private void schedulePreview() {
        previewDelay.playFromStart();
    }

    private void runPreview() {
        previewDelay.stop();
        HighlightPreview.Result result = currentSet != null
            ? HighlightPreview.evaluate(currentSet, sampleArea.getText()) : null;
        preview.set(result);
        renderPreview(result);
        ruleTable.refresh();
        updateRuleMessage();
    }

    private void renderPreview(@Nullable HighlightPreview.Result result) {
        previewLines.getChildren().clear();
        List<String> notices = new ArrayList<>(2);
        if (result != null) {
            List<HighlightRule> setRules = currentSet.getRules();
            for (HighlightPreview.Line line : result.lines()) {
                HBox row = new HBox();
                if (line.spans().isEmpty()) {
                    row.getChildren().add(previewLabel(" ", null));
                }
                for (HighlightPreview.Span span : line.spans()) {
                    HighlightRule owner = span.highlighted() && span.ruleIndex() < setRules.size()
                        ? setRules.get(span.ruleIndex()) : null;
                    row.getChildren().add(previewLabel(line.text().substring(span.start(), span.end()), owner));
                }
                previewLines.getChildren().add(row);
            }
            if (result.truncated()) {
                notices.add(I18n.get(PREVIEW_TRUNCATED_KEY, HighlightPreview.MAX_LINES));
            }
            if (!result.complete()) {
                notices.add(I18n.get(PREVIEW_INCOMPLETE_KEY));
            }
        }
        previewNotice.setText(String.join(" ", notices));
    }

    private Label previewLabel(String text, @Nullable HighlightRule rule) {
        Label label = new Label(text.replace("\t", "    "));
        label.setStyle(MONOSPACE + " -fx-padding: 0; " + HighlightColorChoices.lookStyle(rule, terminalColors));
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    private @Nullable HighlightPreview.RuleStats statsOf(@Nullable HighlightRule rule) {
        HighlightPreview.Result result = preview.get();
        if (rule == null || result == null || currentSet == null) {
            return null;
        }
        List<HighlightRule> setRules = currentSet.getRules();
        for (int i = 0; i < setRules.size() && i < result.rules().size(); i++) {
            if (setRules.get(i) == rule) {
                return result.rule(i);
            }
        }
        return null;
    }

    private static String scopeLabel(HighlightRule.Scope scope) {
        return I18n.get(scope == HighlightRule.Scope.LINE ? SCOPE_LINE_KEY : SCOPE_MATCH_KEY);
    }

    // ==== cells ====

    /** A set's name; built-in sets are tagged so it is clear why they cannot be edited. */
    private final class SetCell extends ListCell<HighlightRuleSet> {

        @Override
        protected void updateItem(HighlightRuleSet set, boolean empty) {
            super.updateItem(set, empty);
            if (empty || set == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            String name = HighlightRulesEditorModel.displayName(set);
            setText(model.isReadOnly(set) ? name + "  ·  " + I18n.get(BUILTIN_TAG_KEY) : name);
        }
    }

    /** The pattern, drawn the way the rule highlights it on the terminal's colors. */
    private final class PatternCell extends TableCell<HighlightRule, HighlightRule> {

        @Override
        protected void updateItem(HighlightRule rule, boolean empty) {
            super.updateItem(rule, empty);
            if (empty || rule == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            String pattern = rule.getPattern() != null ? rule.getPattern() : "";
            Label look = new Label(pattern.isEmpty() ? " " : pattern);
            look.setStyle(MONOSPACE + " -fx-padding: 1 4 1 4; " + HighlightColorChoices.lookStyle(rule, terminalColors));
            // A switched-off rule keeps its look, faded, so it reads as present but inactive.
            look.setOpacity(rule.isEnabled() ? 1.0 : 0.5);
            setText(null);
            setGraphic(look);
        }
    }

    private final class HitsCell extends TableCell<HighlightRule, HighlightRule> {

        @Override
        protected void updateItem(HighlightRule rule, boolean empty) {
            super.updateItem(rule, empty);
            setText(empty || rule == null ? null : HighlightRulesEditorModel.hitsText(statsOf(rule)));
        }
    }

    /** "Invalid", "Off", "Slow" or "Too slow", with the full explanation as a tooltip. */
    private final class CheckCell extends TableCell<HighlightRule, HighlightRule> {

        @Override
        protected void updateItem(HighlightRule rule, boolean empty) {
            super.updateItem(rule, empty);
            HighlightPreview.RuleStats stats = empty || rule == null ? null : statsOf(rule);
            String key = empty || rule == null ? null : HighlightRulesEditorModel.statusKey(rule, stats);
            if (key == null) {
                setText(null);
                setTooltip(null);
                setStyle(null);
                return;
            }
            setText(I18n.get(key));
            List<String> messages = HighlightRulesEditorModel.ruleMessages(rule, stats);
            setTooltip(messages.isEmpty() ? null : new Tooltip(String.join("\n", messages)));
            setStyle(HighlightRulesEditorModel.STATUS_OFF_KEY.equals(key) ? null : WARNING_STYLE);
        }
    }

    private static final class ActionCell extends ListCell<HighlightRule.Action> {

        @Override
        protected void updateItem(HighlightRule.Action action, boolean empty) {
            super.updateItem(action, empty);
            setText(empty || action == null ? null : I18n.get(switch (action) {
                case NOTIFY -> ACTION_NOTIFY_KEY;
                case RUN_SNIPPET -> ACTION_RUN_SNIPPET_KEY;
                case NONE -> ACTION_NONE_KEY;
            }));
        }
    }

    /** A snippet entry; the button cell shows the prompt while no snippet is picked. */
    private final class SnippetCell extends ListCell<SnippetChoice> {

        private final boolean button;

        private SnippetCell(boolean button) {
            this.button = button;
        }

        @Override
        protected void updateItem(SnippetChoice choice, boolean empty) {
            super.updateItem(choice, empty);
            if (empty || choice == null) {
                setText(button ? snippetCombo.getPromptText() : null);
            } else {
                setText(choice.label());
            }
        }
    }

    private static final class ScopeCell extends ListCell<HighlightRule.Scope> {

        @Override
        protected void updateItem(HighlightRule.Scope scope, boolean empty) {
            super.updateItem(scope, empty);
            setText(empty || scope == null ? null : scopeLabel(scope));
        }
    }

    /** A theme color with its swatch in the terminal palette; Keep and Custom without one. */
    private final class ColorChoiceCell extends ListCell<HighlightColorChoices.Choice> {

        @Override
        protected void updateItem(HighlightColorChoices.Choice choice, boolean empty) {
            super.updateItem(choice, empty);
            if (empty || choice == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(choice.label());
            String hex = choice.kind() == HighlightColorChoices.Kind.THEME
                ? HighlightColorChoices.hexFor(choice.spec(), terminalColors) : null;
            if (hex == null) {
                setGraphic(null);
                return;
            }
            Rectangle swatch = new Rectangle(12, 12, Color.web(hex));
            swatch.setArcWidth(3);
            swatch.setArcHeight(3);
            swatch.setStroke(Color.gray(0.5));
            setGraphic(swatch);
        }
    }

    /**
     * A rule color: Keep, a theme color or Custom, with a color picker next to the dropdown while Custom
     * is chosen. Reports the stored text of every change.
     */
    private final class ColorField {

        private final ComboBox<HighlightColorChoices.Choice> combo =
            new ComboBox<>(FXCollections.observableArrayList(colorChoices));
        private final ColorPicker picker = new ColorPicker(Color.WHITE);
        private final HBox box = new HBox(6, combo, picker);
        private final Consumer<String> onChange;

        ColorField(Consumer<String> onChange) {
            this.onChange = onChange;
            box.setAlignment(Pos.CENTER_LEFT);
            combo.setCellFactory(view -> new ColorChoiceCell());
            combo.setButtonCell(new ColorChoiceCell());
            combo.setVisibleRowCount(10);
            picker.managedProperty().bind(picker.visibleProperty());
            combo.valueProperty().addListener((obs, old, choice) -> {
                boolean custom = choice != null && choice.kind() == HighlightColorChoices.Kind.CUSTOM;
                if (custom && !loading && old != null) {
                    // Start the picker from the color shown so far, so Custom tweaks rather than resets it.
                    String previous = old.kind() == HighlightColorChoices.Kind.THEME
                        ? HighlightColorChoices.hexFor(old.spec(), terminalColors) : null;
                    if (previous != null) {
                        picker.setValue(Color.web(previous));
                    }
                }
                picker.setVisible(custom);
                report();
            });
            picker.valueProperty().addListener((obs, old, color) -> report());
        }

        /** Shows a stored color without reporting it back. */
        void show(@Nullable String spec) {
            HighlightColorChoices.Choice choice = HighlightColorChoices.choiceFor(spec, colorChoices);
            if (choice.kind() == HighlightColorChoices.Kind.CUSTOM) {
                String hex = HighlightColorChoices.hexFor(spec, terminalColors);
                picker.setValue(hex != null ? Color.web(hex) : Color.WHITE);
            }
            combo.setValue(choice);
            picker.setVisible(choice.kind() == HighlightColorChoices.Kind.CUSTOM);
        }

        private void report() {
            if (loading) {
                return;
            }
            HighlightColorChoices.Choice choice = combo.getValue();
            if (choice == null) {
                return;
            }
            onChange.accept(switch (choice.kind()) {
                case KEEP -> null;
                case THEME -> choice.spec();
                case CUSTOM -> HighlightColorChoices.customSpec(picker.getValue());
            });
        }
    }
}
