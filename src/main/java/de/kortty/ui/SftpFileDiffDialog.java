package de.kortty.ui;

import de.kortty.core.AiCancellation;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiFileComparison;
import de.kortty.core.AiOutboundRedaction;
import de.kortty.core.AiRequest;
import de.kortty.core.AiService;
import de.kortty.core.LanguageManager;
import de.kortty.core.RedactionResult;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.core.TextDiff;
import de.kortty.model.AiProfile;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Two text files side by side, as the SFTP manager's "Compare" shows them: Monaco's read-only diff,
 * like the snippet manager's comparison, with a summary of the changed lines. With the AI features
 * on, "Ask AI" opens a panel beside the diff to ask where the files differ, what the differences
 * do, or what to improve; both files and their unified diff go to the chosen profile, with known
 * secrets masked first. In its "New file" mode the AI writes a third file from the two as the user
 * describes it; the diff then shows it against either file, and it can be saved on the server or
 * this computer ({@link NewFileTarget}) or kept in the Snippet Manager. Non-modal, so the SFTP
 * manager stays usable while it is open.
 */
final class SftpFileDiffDialog extends ThemeAwareDialog<Void> {

    /** One compared file: where it is ("This computer", "Server demo@host"), its path and text. */
    record Side(String place, String path, String text) {
        Side {
            Objects.requireNonNull(place, "place");
            Objects.requireNonNull(path, "path");
            text = Objects.requireNonNullElse(text, "");
        }

        String label() {
            return place + ": " + path;
        }

        String name() {
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }

    /** What asking the AI needs from the SFTP manager; the dialog never reaches into the main window. */
    interface AiAccess {
        /** Why the AI cannot be asked (features off, a policy, no profile); empty when it can. */
        Optional<String> unavailableReason();

        List<AiProfile> profiles();

        /** The profile the AI settings route this kind of request to. */
        AiProfile defaultProfile();

        AiService service(AiProfile profile);

        void recordUsage(AiProfile profile, AiRequest request, AiExecutionResult result);

        /** The connection the comparison belongs to, named in the request; may be null. */
        String connectionName();
    }

    /** Where a new file can go; the SFTP manager knows the server, the panels and the policies. */
    interface NewFileTarget {
        /**
         * Asks where to save {@code content}, proposing the right file's place, writes it there off
         * the FX thread and reports the outcome to {@code done} on the FX thread.
         */
        void save(Window owner, String content, Consumer<Saved> done);

        /**
         * Opens {@code content} in the snippet editor, where it can be edited, kept in the Snippet
         * Manager or saved as a file; every file saved there is reported to {@code saved} on the FX
         * thread.
         */
        void edit(Window owner, String name, String content, String description, Consumer<Saved> saved);
    }

    /**
     * How a save went: where the file went ({@code place} and {@code path} as a {@link Side} names
     * them) and what it holds now, or why it failed; all {@code null} when the user cancelled.
     */
    record Saved(String place, String path, String content, String failure) {
        static Saved cancelled() {
            return new Saved(null, null, null, null);
        }

        static Saved failed(String failure) {
            return new Saved(null, null, null, failure);
        }

        boolean done() {
            return place != null && path != null;
        }

        String label() {
            return place + ": " + path;
        }
    }

    /** What the AI panel does: answer a question about the two files, or write a third one. */
    enum Mode {
        ASK, NEW_FILE
    }

    /** Which two files the diff shows; the views with the new file appear once the AI wrote one. */
    enum View {
        LEFT_RIGHT("sftp.diff.view.leftRight"),
        RIGHT_NEW("sftp.diff.view.rightNew"),
        LEFT_NEW("sftp.diff.view.leftNew");

        private final String key;

        View(String key) {
            this.key = key;
        }

        String label() {
            return I18n.get(key);
        }
    }

    /** The prepared questions; the last one leaves the question to the user. */
    static final List<String> QUESTION_KEYS = List.of(
        "sftp.diff.ai.question.differences",
        "sftp.diff.ai.question.effects",
        "sftp.diff.ai.question.improvements",
        "sftp.diff.ai.question.own");

    /** The prepared instructions for a new file; the last one leaves the instruction to the user. */
    static final List<String> NEW_FILE_KEYS = List.of(
        "sftp.diff.ai.newFile.best",
        "sftp.diff.ai.newFile.fixRight",
        "sftp.diff.ai.newFile.leftIntoRight",
        "sftp.diff.ai.newFile.own");

    /** The AI panel's font size (question and answers) unless one was chosen; kept in the settings. */
    static final int DEFAULT_AI_FONT_SIZE = 13;
    static final int MIN_AI_FONT_SIZE = 9;
    static final int MAX_AI_FONT_SIZE = 32;
    /** How much wider the window gets for the AI panel, so the diff keeps its two columns. */
    static final double AI_PANEL_WIDTH = 460;

    /** Replaced when a new file is saved over one of the two, so the diff shows what is there now. */
    private Side left;
    private Side right;
    private final AiAccess ai;
    private final NewFileTarget target;
    private TextDiff.Result diff;
    private final MonacoDiffPane diffPane = new MonacoDiffPane();
    private final SplitPane body = new SplitPane();
    private final ToggleButton askToggle = new ToggleButton(I18n.get("sftp.diff.ai.toggle"));
    private final Label leftLabel = sideLabel();
    private final Label rightLabel = sideLabel();
    private final Label summary = new Label();
    private final ComboBox<View> viewChoice = new ComboBox<>();
    private View view = View.LEFT_RIGHT;
    private VBox aiPanel;
    private Label aiTitle;
    private ComboBox<AiProfile> profileChoice;
    private final ToggleGroup modes = new ToggleGroup();
    private ToggleButton askMode;
    private ToggleButton newFileMode;
    private Mode mode = Mode.ASK;
    /** What the text area held in each mode, so switching modes and back keeps it. */
    private final Map<Mode, String> drafts = new EnumMap<>(Mode.class);
    private ComboBox<String> presets;
    private TextArea question;
    private Label privacyNote;
    private Label aiStatus;
    private Button askButton;
    private Button stopButton;
    private VBox answerBox;
    private VBox newFileBar;
    private Label newFileSummary;
    private Button showNewFileButton;
    private Button saveNewFileButton;
    private Button snippetButton;
    private AiChatStreamingView streaming;
    private Task<AiExecutionResult> running;
    /** What opening the AI panel added to the window's width, given back when it closes. */
    private double widenedBy;
    /** The file the AI wrote, with the right file's line breaks; null until it wrote one. */
    private String newFile;
    /** Who wrote the new file, or where it was saved last, as its header shows it. */
    private String newFileOrigin;
    private int aiFontSize = clampAiFontSize(loadAiFontSize());
    private Label aiFontSizeLabel;
    /** The last question and its rendered answer (Markdown), drawn again when the font size changes. */
    private String lastAsked;
    private String lastAnswer;

    SftpFileDiffDialog(Window owner, Side left, Side right, AiAccess ai) {
        this(owner, left, right, ai, null);
    }

    /** @param target where a new file the AI wrote can be saved; {@code null} offers no saving */
    SftpFileDiffDialog(Window owner, Side left, Side right, AiAccess ai, NewFileTarget target) {
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
        this.ai = ai;
        this.target = target;
        this.diff = TextDiff.unified(left.label(), right.label(), left.text(), right.text(), 3);

        setTitle(I18n.get("sftp.diff.title", left.name(), right.name()));
        setResizable(true);
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }

        EditorSettingsHelper.Settings settings = EditorSettingsHelper.loadSnippetSettings();
        diffPane.setFont(settings.fontFamily(), settings.fontSize());
        diffPane.setThemeColors(settings.foregroundColor(), settings.backgroundColor());
        diffPane.setStyle("-fx-background-color: " + settings.backgroundColor() + ";");

        body.setOrientation(Orientation.HORIZONTAL);
        body.getItems().add(diffPane);
        VBox root = new VBox(10, header(), body);
        root.setPadding(new Insets(14));
        VBox.setVgrow(body, Priority.ALWAYS);

        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(1240);
        getDialogPane().setPrefHeight(760);
        setOnHidden(event -> {
            if (running != null) {
                running.cancel();
            }
            diffPane.dispose();
        });
        setResultConverter(buttonType -> null);
        showView(View.LEFT_RIGHT);
    }

    /** The unified diff behind the summary and the AI request; for tests. */
    TextDiff.Result diff() {
        return diff;
    }

    private VBox header() {
        GridPane sides = new GridPane();
        sides.setHgap(12);
        sides.setVgap(2);
        sides.addRow(0, leftLabel, rightLabel);
        javafx.scene.layout.ColumnConstraints half = new javafx.scene.layout.ColumnConstraints();
        half.setPercentWidth(50);
        sides.getColumnConstraints().addAll(half, half);

        summary.getStyleClass().add("sftp-diff-summary");
        summary.setStyle("-fx-opacity: 0.8;");

        viewChoice.getItems().setAll(View.values());
        viewChoice.setConverter(new StringConverter<>() {
            @Override
            public String toString(View shownView) {
                return shownView == null ? "" : shownView.label();
            }

            @Override
            public View fromString(String text) {
                return null;
            }
        });
        viewChoice.getSelectionModel().select(View.LEFT_RIGHT);
        viewChoice.setTooltip(new Tooltip(I18n.get("sftp.diff.view.tooltip")));
        viewChoice.setOnAction(event -> {
            View chosen = viewChoice.getValue();
            if (chosen != null && chosen != view) {
                showView(chosen);
            }
        });
        // Only once the AI wrote a new file is there more than one pair to show.
        viewChoice.setVisible(false);
        viewChoice.setManaged(false);

        askToggle.getStyleClass().add("sftp-diff-ask");
        Optional<String> unavailable = ai == null
            ? Optional.of(I18n.get("sftp.diff.ai.disabled"))
            : ai.unavailableReason();
        if (unavailable.isPresent()) {
            askToggle.setDisable(true);
            // A disabled control shows no tooltip of its own; the wrapper carries the reason.
            Tooltip.install(askToggle, new Tooltip(unavailable.get()));
        } else {
            askToggle.setTooltip(new Tooltip(I18n.get("sftp.diff.ai.toggleTooltip")));
        }
        askToggle.setOnAction(event -> showAiPanel(askToggle.isSelected()));
        HBox askWrapper = new HBox(askToggle);
        if (unavailable.isPresent()) {
            Tooltip.install(askWrapper, new Tooltip(unavailable.get()));
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, viewChoice, summary, spacer, askWrapper);
        bar.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, sides, bar);
    }

    private static Label sideLabel() {
        Label label = new Label();
        label.setStyle("-fx-font-weight: bold;");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setTooltip(new Tooltip());
        return label;
    }

    private static void describe(Label label, String which, String where) {
        label.setText(which + "  " + where);
        label.getTooltip().setText(where);
    }

    /** Shows {@code shownView}'s two files in the diff, with their names and the changed lines. */
    private void showView(View shownView) {
        View effective = newFile == null ? View.LEFT_RIGHT : shownView;
        view = effective;
        if (viewChoice.getValue() != effective) {
            viewChoice.getSelectionModel().select(effective);
        }
        String newWhere = Objects.requireNonNullElse(newFileOrigin, "");
        String original;
        String modified;
        String originalName;
        TextDiff.Stats stats;
        switch (effective) {
            case RIGHT_NEW -> {
                describe(leftLabel, I18n.get("sftp.diff.right"), right.label());
                describe(rightLabel, I18n.get("sftp.diff.newFile.which"), newWhere);
                original = right.text();
                originalName = right.name();
                modified = newFile;
                stats = TextDiff.unified("", "", right.text(), newFile, 0).stats();
            }
            case LEFT_NEW -> {
                describe(leftLabel, I18n.get("sftp.diff.left"), left.label());
                describe(rightLabel, I18n.get("sftp.diff.newFile.which"), newWhere);
                original = left.text();
                originalName = left.name();
                modified = newFile;
                stats = TextDiff.unified("", "", left.text(), newFile, 0).stats();
            }
            default -> {
                describe(leftLabel, I18n.get("sftp.diff.left"), left.label());
                describe(rightLabel, I18n.get("sftp.diff.right"), right.label());
                original = left.text();
                originalName = left.name();
                modified = right.text();
                stats = diff.stats();
            }
        }
        summary.setText(stats.identical()
            ? I18n.get("sftp.diff.identical")
            : I18n.get("sftp.diff.summary", stats.added(), stats.removed()));
        diffPane.setComparison(original, modified,
            SnippetLanguageSupport.detectFileLanguage(originalName, original),
            SnippetLanguageSupport.detectFileLanguage(right.name(), modified));
    }

    /**
     * Opens or closes the AI panel beside the diff. The window grows by the panel's width as far as
     * its screen allows, so the diff keeps its width: Monaco shows a narrow diff in one column only.
     */
    private void showAiPanel(boolean show) {
        javafx.stage.Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
        if (show) {
            if (aiPanel == null) {
                aiPanel = createAiPanel();
                SplitPane.setResizableWithParent(aiPanel, false);
            }
            if (!body.getItems().contains(aiPanel)) {
                double diffWidth = diffPane.getWidth();
                widenedBy = window == null ? 0 : widen(window, AI_PANEL_WIDTH);
                body.getItems().add(aiPanel);
                double total = body.getWidth() + widenedBy;
                double position = diffWidth > 0 && total > 0
                    ? Math.min(0.75, Math.max(0.45, diffWidth / total))
                    : 0.62;
                body.setDividerPositions(position);
                javafx.application.Platform.runLater(() -> body.setDividerPositions(position));
            }
            question.requestFocus();
        } else {
            body.getItems().remove(aiPanel);
            if (window != null && widenedBy > 0) {
                window.setWidth(window.getWidth() - widenedBy);
            }
            widenedBy = 0;
        }
    }

    /** Widens {@code window} by up to {@code extra}, keeping it on its screen; returns what it grew by. */
    private static double widen(javafx.stage.Window window, double extra) {
        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getScreensForRectangle(
                window.getX(), window.getY(), window.getWidth(), window.getHeight()).stream()
            .findFirst()
            .orElse(javafx.stage.Screen.getPrimary())
            .getVisualBounds();
        double width = Math.min(window.getWidth() + extra, screen.getWidth());
        double grown = Math.max(0, width - window.getWidth());
        if (grown == 0) {
            return 0;
        }
        window.setWidth(width);
        if (window.getX() + width > screen.getMaxX()) {
            window.setX(Math.max(screen.getMinX(), screen.getMaxX() - width));
        }
        return grown;
    }

    private VBox createAiPanel() {
        aiTitle = new Label(I18n.get("sftp.diff.ai.title"));
        aiTitle.setStyle("-fx-font-weight: bold;");
        aiTitle.setWrapText(true);

        VBox panel = new VBox(8);
        panel.getStyleClass().add("sftp-diff-ai-panel");
        panel.setPadding(new Insets(0, 0, 0, 10));
        panel.setMinWidth(320);

        askMode = new ToggleButton(I18n.get("sftp.diff.ai.mode.ask"));
        askMode.setTooltip(new Tooltip(I18n.get("sftp.diff.ai.mode.askTooltip")));
        askMode.setUserData(Mode.ASK);
        newFileMode = new ToggleButton(I18n.get("sftp.diff.ai.mode.newFile"));
        newFileMode.setTooltip(new Tooltip(I18n.get("sftp.diff.ai.mode.newFileTooltip")));
        newFileMode.setUserData(Mode.NEW_FILE);
        for (ToggleButton modeButton : List.of(askMode, newFileMode)) {
            modeButton.setToggleGroup(modes);
            modeButton.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(modeButton, Priority.ALWAYS);
        }
        askMode.setSelected(true);
        markSelectedMode();
        // One mode is always on: clicking the selected one again does not turn it off.
        modes.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                modes.selectToggle(old);
            } else {
                switchMode((Mode) toggle.getUserData());
                markSelectedMode();
            }
        });
        // Hides the panel when it is in the way; "Ask AI" brings it back with the last answer.
        Button hide = new Button("\u2715");
        hide.getStyleClass().add("sftp-diff-ai-hide");
        hide.setTooltip(new Tooltip(I18n.get("sftp.diff.ai.hide")));
        hide.setAccessibleText(I18n.get("sftp.diff.ai.hide"));
        hide.setOnAction(event -> {
            askToggle.setSelected(false);
            showAiPanel(false);
        });
        HBox modeRow = new HBox(0, askMode, newFileMode);
        modeRow.getStyleClass().add("sftp-diff-ai-modes");
        HBox.setHgrow(modeRow, Priority.ALWAYS);
        HBox topRow = new HBox(6, modeRow, hide);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Button smaller = new Button(I18n.get("editor.zoomOut"));
        smaller.setTooltip(new Tooltip(I18n.get("menu.view.zoomOut")));
        smaller.setOnAction(event -> changeAiFontSize(-1));
        Button larger = new Button(I18n.get("editor.zoomIn"));
        larger.setTooltip(new Tooltip(I18n.get("menu.view.zoomIn")));
        larger.setOnAction(event -> changeAiFontSize(1));
        smaller.getStyleClass().add("sftp-diff-ai-smaller");
        larger.getStyleClass().add("sftp-diff-ai-larger");
        aiFontSizeLabel = new Label();
        aiFontSizeLabel.setStyle("-fx-opacity: 0.75;");
        aiTitle.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(aiTitle, Priority.ALWAYS);
        HBox titleRow = new HBox(6, aiTitle, smaller, aiFontSizeLabel, larger);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        panel.getChildren().addAll(topRow, titleRow);
        // Cmd/Ctrl with + and - size the panel's text, as in the other AI windows.
        panel.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (!event.isShortcutDown()) {
                return;
            }
            javafx.scene.input.KeyCode code = event.getCode();
            if (code == javafx.scene.input.KeyCode.PLUS || code == javafx.scene.input.KeyCode.ADD
                || code == javafx.scene.input.KeyCode.EQUALS) {
                changeAiFontSize(1);
                event.consume();
            } else if (code == javafx.scene.input.KeyCode.MINUS || code == javafx.scene.input.KeyCode.SUBTRACT) {
                changeAiFontSize(-1);
                event.consume();
            }
        });

        List<AiProfile> profiles = ai.profiles();
        AiProfile chosen = ai.defaultProfile() != null ? ai.defaultProfile() : profiles.get(0);
        profileChoice = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(profiles));
        profileChoice.setConverter(new StringConverter<>() {
            @Override
            public String toString(AiProfile profile) {
                return profile == null ? "" : profile.getName();
            }

            @Override
            public AiProfile fromString(String text) {
                return null;
            }
        });
        profileChoice.getSelectionModel().select(chosen);
        profileChoice.setMaxWidth(Double.MAX_VALUE);
        profileChoice.valueProperty().addListener((obs, old, profile) -> updatePrivacyNote());
        if (profiles.size() > 1) {
            HBox profileRow = new HBox(8, new Label(I18n.get("sftp.diff.ai.profile")), profileChoice);
            profileRow.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(profileChoice, Priority.ALWAYS);
            panel.getChildren().add(profileRow);
        }

        presets = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(labels(QUESTION_KEYS)));
        presets.setMaxWidth(Double.MAX_VALUE);
        question = new TextArea(I18n.get(QUESTION_KEYS.get(0)));
        question.setWrapText(true);
        question.setPrefRowCount(3);
        presets.getSelectionModel().select(0);
        presets.setOnAction(event -> {
            int index = presets.getSelectionModel().getSelectedIndex();
            List<String> keys = presetKeys(mode);
            if (index >= 0) {
                question.setText(index == keys.size() - 1 ? "" : I18n.get(keys.get(index)));
                question.requestFocus();
                question.end();
            }
        });
        drafts.put(Mode.NEW_FILE, I18n.get(NEW_FILE_KEYS.get(0)));

        privacyNote = new Label();
        privacyNote.setWrapText(true);
        privacyNote.setStyle("-fx-font-size: 0.8462em; -fx-opacity: 0.75;");
        updatePrivacyNote();

        askButton = new Button(I18n.get("sftp.diff.ai.ask"));
        askButton.setDefaultButton(false);
        askButton.setOnAction(event -> ask());
        stopButton = new Button(I18n.get("sftp.compare.stop"));
        stopButton.setDisable(true);
        stopButton.setOnAction(event -> {
            if (running != null) {
                running.cancel();
            }
        });
        HBox buttons = new HBox(8, askButton, stopButton);

        aiStatus = new Label();
        aiStatus.setWrapText(true);
        aiStatus.setStyle("-fx-font-size: 0.8462em; -fx-opacity: 0.8;");

        answerBox = new VBox(8);
        answerBox.getStyleClass().add("sftp-diff-ai-answer");
        answerBox.setPadding(new Insets(6));
        ScrollPane answers = new ScrollPane(answerBox);
        answers.setFitToWidth(true);
        VBox.setVgrow(answers, Priority.ALWAYS);
        streaming = new AiChatStreamingView(answerBox, () -> aiFontSize,
            () -> profileChoice.getValue() != null ? profileChoice.getValue().getName() : "",
            () -> aiStatus.setText(I18n.get("sftp.diff.ai.receiving")));

        panel.getChildren().addAll(presets, question, privacyNote, buttons, aiStatus, answers, createNewFileBar());
        applyAiFontSize();
        return panel;
    }

    /** Makes the AI panel's text {@code delta} larger or smaller and remembers the size. */
    private void changeAiFontSize(int delta) {
        int next = clampAiFontSize(aiFontSize + delta);
        if (next == aiFontSize) {
            return;
        }
        aiFontSize = next;
        applyAiFontSize();
        saveAiFontSize(next);
    }

    /** Sizes the question and draws the shown answer again; a streaming answer picks it up itself. */
    private void applyAiFontSize() {
        aiFontSizeLabel.setText(aiFontSize + "pt");
        question.setStyle("-fx-font-size: " + aiFontSize + "px;");
        if (lastAsked != null && (streaming == null || !streaming.isActive())) {
            renderAnswer();
        } else if (!answerBox.getChildren().isEmpty() && answerBox.getChildren().get(0) instanceof Label asked) {
            styleAsked(asked);
        }
    }

    /** The question asked last and, once there is one, its answer, at the current size. */
    private void renderAnswer() {
        Label asked = new Label(lastAsked);
        asked.setWrapText(true);
        styleAsked(asked);
        answerBox.getChildren().setAll(asked);
        if (lastAnswer != null) {
            AiChatRenderSupport.renderInto(answerBox, true, lastAnswer, aiFontSize);
        }
    }

    private void styleAsked(Label asked) {
        asked.setStyle("-fx-font-style: italic; -fx-opacity: 0.8; -fx-font-size: " + aiFontSize + "px;");
    }

    static int clampAiFontSize(int size) {
        return Math.max(MIN_AI_FONT_SIZE, Math.min(MAX_AI_FONT_SIZE, size));
    }

    private static int loadAiFontSize() {
        de.kortty.model.GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        return settings != null && settings.getSftpDiffAiFontSize() != null
            ? settings.getSftpDiffAiFontSize()
            : DEFAULT_AI_FONT_SIZE;
    }

    private static void saveAiFontSize(int size) {
        try {
            de.kortty.core.GlobalSettingsManager manager =
                de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            de.kortty.model.GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setSftpDiffAiFontSize(size);
                manager.save();
            }
        } catch (Exception e) {
            // The size still applies to this window; it is only not remembered.
            LoggerHolder.LOGGER.warn("Could not save the AI panel's font size", e);
        }
    }

    private static final class LoggerHolder {
        private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(SftpFileDiffDialog.class);
    }

    /** What to do with the new file: show it in the diff, save it, keep it as a snippet. Hidden until there is one. */
    private VBox createNewFileBar() {
        Label caption = new Label(I18n.get("sftp.diff.newFile.title"));
        caption.setStyle("-fx-font-weight: bold;");
        newFileSummary = new Label();
        newFileSummary.setWrapText(true);
        newFileSummary.setStyle("-fx-font-size: 0.8462em; -fx-opacity: 0.8;");
        showNewFileButton = new Button(I18n.get("sftp.diff.newFile.show"));
        showNewFileButton.setTooltip(new Tooltip(I18n.get("sftp.diff.newFile.showTooltip")));
        showNewFileButton.setOnAction(event -> showView(View.RIGHT_NEW));
        saveNewFileButton = new Button(I18n.get("sftp.diff.newFile.save"));
        saveNewFileButton.setTooltip(new Tooltip(I18n.get("sftp.diff.newFile.saveTooltip")));
        saveNewFileButton.setOnAction(event -> saveNewFile());
        snippetButton = new Button(I18n.get("sftp.diff.newFile.snippet"));
        snippetButton.setTooltip(new Tooltip(I18n.get("sftp.diff.newFile.snippetTooltip")));
        snippetButton.setOnAction(event -> saveNewFileAsSnippet());
        if (target == null) {
            saveNewFileButton.setDisable(true);
            snippetButton.setDisable(true);
        }
        javafx.scene.layout.FlowPane actions = new javafx.scene.layout.FlowPane(8, 6,
            showNewFileButton, saveNewFileButton, snippetButton);
        newFileBar = new VBox(6, caption, newFileSummary, actions);
        newFileBar.getStyleClass().add("sftp-diff-new-file");
        newFileBar.setPadding(new Insets(8, 0, 0, 0));
        newFileBar.setVisible(false);
        newFileBar.setManaged(false);
        return newFileBar;
    }

    /** The chosen mode also reads bold: some designs set a pressed toggle apart only faintly. */
    private void markSelectedMode() {
        for (ToggleButton modeButton : List.of(askMode, newFileMode)) {
            modeButton.setStyle(modeButton.isSelected() ? "-fx-font-weight: bold;" : "");
        }
    }

    private static List<String> presetKeys(Mode shownMode) {
        return shownMode == Mode.NEW_FILE ? NEW_FILE_KEYS : QUESTION_KEYS;
    }

    private static List<String> labels(List<String> keys) {
        return keys.stream().map(key -> I18n.get(key + ".label")).toList();
    }

    /** Puts the panel in {@code next} mode: its title, prepared texts and button; each mode keeps its own text. */
    private void switchMode(Mode next) {
        if (next == mode) {
            return;
        }
        drafts.put(mode, question.getText());
        mode = next;
        boolean newFileShown = next == Mode.NEW_FILE;
        aiTitle.setText(I18n.get(newFileShown ? "sftp.diff.ai.newFile.title" : "sftp.diff.ai.title"));
        askButton.setText(I18n.get(newFileShown ? "sftp.diff.ai.create" : "sftp.diff.ai.ask"));
        presets.getItems().setAll(labels(presetKeys(next)));
        String draft = drafts.getOrDefault(next, "");
        question.setText(draft);
        List<String> keys = presetKeys(next);
        int preset = keys.size() - 1;
        for (int i = 0; i < keys.size() - 1; i++) {
            if (I18n.get(keys.get(i)).equals(draft)) {
                preset = i;
            }
        }
        presets.getSelectionModel().select(preset);
        question.requestFocus();
        question.end();
    }

    private void updatePrivacyNote() {
        AiProfile profile = profileChoice.getValue();
        String name = profile == null ? "" : profile.getName();
        privacyNote.setText(AiOutboundRedaction.appliesTo(profile)
            ? I18n.get("sftp.diff.ai.privacyMasked", name)
            : I18n.get("sftp.diff.ai.privacyLocal", name));
    }

    /** Sends both files, their diff and the question; the answer streams into the panel. */
    private void ask() {
        AiProfile profile = profileChoice.getValue();
        String text = question.getText() == null ? "" : question.getText().trim();
        Mode asked = mode;
        if (profile == null || text.isEmpty()) {
            aiStatus.setText(I18n.get(asked == Mode.NEW_FILE ? "sftp.diff.ai.noInstruction" : "sftp.diff.ai.noQuestion"));
            return;
        }
        String material = AiFileComparison.material(left.label(), left.text(), right.label(), right.text(),
            diff.unified());
        RedactionResult masked = AiOutboundRedaction.redactFor(profile, material, null);
        int limit = profile.getMaxSelectionChars() != null && profile.getMaxSelectionChars() > 0
            ? profile.getMaxSelectionChars()
            : AiProfile.DEFAULT_MAX_SELECTION_CHARS;
        if (masked.text().length() > limit) {
            aiStatus.setText(I18n.get("sftp.diff.ai.tooLarge", masked.text().length(), limit));
            return;
        }
        String languageCode = LanguageManager.getInstance().getCurrentLanguageCode();
        AiRequest request = asked == Mode.NEW_FILE
            ? AiFileComparison.newFileRequest(masked.text(), text, ai.connectionName(), languageCode)
            : AiFileComparison.request(masked.text(), text, ai.connectionName(), languageCode);
        AiService service = ai.service(profile);
        lastAsked = text;
        lastAnswer = null;
        renderAnswer();
        AiRequest streamed = request.withStreamListener(streaming.begin());
        Task<AiExecutionResult> task = new Task<>() {
            @Override
            protected AiExecutionResult call() throws Exception {
                return service.execute(streamed);
            }
        };
        running = task;
        askButton.setDisable(true);
        stopButton.setDisable(false);
        aiStatus.setText(masked.count() > 0
            ? I18n.get("sftp.diff.ai.sendingMasked", profile.getName(), masked.count())
            : I18n.get("sftp.diff.ai.sending", profile.getName()));
        task.setOnSucceeded(event -> {
            finish(task);
            AiExecutionResult result = task.getValue();
            String content = result == null ? null : result.content();
            if (content == null || content.isBlank()) {
                aiStatus.setText(I18n.get("sftp.diff.ai.noAnswer"));
            } else if (asked == Mode.NEW_FILE) {
                Optional<AiFileComparison.NewFile> written = AiFileComparison.newFile(content);
                if (written.isPresent()) {
                    showAnswer(written.get().explanation());
                    takeNewFile(written.get().content(), profile.getName());
                    aiStatus.setText(I18n.get("sftp.diff.ai.created", profile.getName()));
                } else {
                    showAnswer(content);
                    aiStatus.setText(I18n.get("sftp.diff.ai.noFile"));
                }
            } else {
                showAnswer(content);
                aiStatus.setText(I18n.get("sftp.diff.ai.done", profile.getName()));
            }
            ai.recordUsage(profile, request, result);
        });
        task.setOnCancelled(event -> {
            finish(task);
            aiStatus.setText(I18n.get("sftp.diff.ai.cancelled"));
        });
        task.setOnFailed(event -> {
            finish(task);
            Throwable error = task.getException();
            aiStatus.setText(AiCancellation.isCancellation(error)
                ? I18n.get("sftp.diff.ai.cancelled")
                : I18n.get("sftp.diff.ai.failed",
                    error != null && error.getMessage() != null ? error.getMessage() : String.valueOf(error)));
        });
        AiTaskRunner.start(task, "sftp-diff-ai");
    }

    private void showAnswer(String markdown) {
        lastAnswer = markdown;
        renderAnswer();
    }

    private void finish(Task<AiExecutionResult> task) {
        if (running == task) {
            running = null;
        }
        streaming.end();
        askButton.setDisable(false);
        stopButton.setDisable(true);
    }

    /**
     * Keeps the file the AI wrote, with the right file's line breaks, and shows it against the
     * right file, the one it is meant to replace. A later one replaces it.
     */
    private void takeNewFile(String content, String profileName) {
        newFile = AiFileComparison.withLineEndingsOf(content, right.text());
        newFileOrigin = I18n.get("sftp.diff.newFile.writtenBy", profileName);
        viewChoice.setVisible(true);
        viewChoice.setManaged(true);
        newFileBar.setVisible(true);
        newFileBar.setManaged(true);
        updateNewFileSummary();
        showView(View.RIGHT_NEW);
    }

    private void updateNewFileSummary() {
        TextDiff.Stats stats = TextDiff.unified("", "", right.text(), newFile, 0).stats();
        int lines = TextDiff.lineCount(newFile);
        newFileSummary.setText(stats.identical()
            ? I18n.get("sftp.diff.newFile.sameAsRight", lines, newFileOrigin)
            : I18n.get("sftp.diff.newFile.summary", lines, stats.added(), stats.removed(), newFileOrigin));
    }

    private Window window() {
        return getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
    }

    private void saveNewFile() {
        if (newFile == null || target == null) {
            return;
        }
        String content = newFile;
        saveNewFileButton.setDisable(true);
        aiStatus.setText(I18n.get("sftp.diff.newFile.saving"));
        target.save(window(), content, saved -> {
            saveNewFileButton.setDisable(false);
            if (saved == null || (!saved.done() && saved.failure() == null)) {
                aiStatus.setText("");
            } else if (!saved.done()) {
                aiStatus.setText(I18n.get("sftp.diff.newFile.saveFailed", saved.failure()));
            } else {
                applySaved(saved);
            }
        });
    }

    /** Opens the new file in the snippet editor, to keep it in the Snippet Manager or edit it first. */
    private void saveNewFileAsSnippet() {
        if (newFile == null || target == null) {
            return;
        }
        target.edit(window(), right.name(), newFile,
            I18n.get("sftp.diff.newFile.snippetDescription", left.label(), right.label()), saved -> {
                if (saved != null && saved.done()) {
                    applySaved(saved);
                }
            });
    }

    /**
     * Takes note of a saved file: a save over one of the two compared files is what that file
     * holds now, so the diff compares against it.
     */
    private void applySaved(Saved saved) {
        aiStatus.setText(I18n.get("sftp.diff.newFile.saved", saved.label()));
        String content = Objects.requireNonNullElse(saved.content(), "");
        if (content.equals(newFile)) {
            newFileOrigin = I18n.get("sftp.diff.newFile.savedAt", saved.label());
        }
        if (saved.label().equals(left.label())) {
            left = new Side(left.place(), left.path(), content);
        }
        if (saved.label().equals(right.label())) {
            right = new Side(right.place(), right.path(), content);
        }
        diff = TextDiff.unified(left.label(), right.label(), left.text(), right.text(), 3);
        updateNewFileSummary();
        showView(view);
    }

    // ---- for SftpManagerScreenshotGenerator ------------------------------------------------------

    ToggleButton askToggle() {
        return askToggle;
    }

    TextArea question() {
        return question;
    }

    Button askButton() {
        return askButton;
    }

    VBox answerBox() {
        return answerBox;
    }

    Label aiStatus() {
        return aiStatus;
    }

    ToggleButton newFileMode() {
        return newFileMode;
    }

    int aiFontSize() {
        return aiFontSize;
    }

    VBox newFileBar() {
        return newFileBar;
    }

    Button saveNewFileButton() {
        return saveNewFileButton;
    }

    Button snippetButton() {
        return snippetButton;
    }

    ComboBox<View> viewChoice() {
        return viewChoice;
    }

    Label summary() {
        return summary;
    }

    Label rightLabel() {
        return rightLabel;
    }

    String newFile() {
        return newFile;
    }

    /** The two compared files as they are now (a save over one of them replaces it); for tests. */
    Side leftSide() {
        return left;
    }

    Side rightSide() {
        return right;
    }
}
