package de.kortty.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.model.GlobalSettings;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The embeddable "review AI change" view: the summary split, the syntax/zoom/copy toolbar, Monaco's
 * side-by-side diff, the per-finding focus picker and the "why these parts changed" cards. Hosted by
 * {@link SnippetAiDiffDialog} in its own window, or directly inside an editor.
 *
 * <p>An embedded host asks for the optional decision bar ({@code [Review later] [Reject]
 * [Accept & apply]}, Shortcut+Enter accepts) and receives the choice through
 * {@link #setOnDecision(Consumer)}; the pane itself never opens a window or blocks.</p>
 */
public class SnippetAiDiffPane extends VBox {

    /** What the reviewer decided in the decision bar. */
    public enum Decision { REVIEW_LATER, REJECT, ACCEPT }

    private static final int MIN_PREVIEW_FONT_SIZE = 8;
    private static final int MAX_PREVIEW_FONT_SIZE = 72;
    private static final int PREVIEW_FONT_STEP = 1;
    private static final double SUMMARY_MIN_HEIGHT = 52;
    private static final double SUMMARY_PREF_HEIGHT = 116;
    /** A staged apply's summary can run to a dozen paragraphs; never let it take the diff's room. */
    private static final double SUMMARY_MAX_SHARE = 0.34;
    /** Governs until the pane is shown and {@link #fitSummaryHeight()} measures the real content. */
    private static final double SUMMARY_INITIAL_SPLIT = 0.16;
    private static final String ACCENT = "#3b82f6";
    private static final String FALLBACK_BG = "#1e1e1e";
    private static final String FALLBACK_FG = "#d6d6d6";
    private static final List<String> HIGHLIGHT_LANGUAGES = List.of(
        "plain", "bash", "shell", "python", "perl", "ruby", "java", "javascript", "groovy",
        "powershell", "sql", "xml", "json", "yaml", "yml", "toml", "properties", "ini", "html",
        "markdown", "dockerfile");

    private final MonacoDiffPane diffPane;
    private final ComboBox<String> syntaxCombo;
    private final Label fontSizeLabel;
    private final WebView explanationsView;
    private final HBox toolbar;
    private final HBox findingFilterBar;
    private final SplitPane summarySplit;
    private final Region summaryContent;
    private final Label blockingNotice = new Label();
    private final Button blockingActionButton = new Button();
    private final HBox blockingNoticeRow = new HBox(8);
    private final Button acceptButton;
    private ComboBox<String> findingFilterCombo;
    private Button previousFindingButton;
    private Button nextFindingButton;
    private String activeFindingFilter;
    private Double appliedSummaryDivider;
    private final String originalText;
    private final String replacementText;
    private final EditorSettingsHelper.Settings previewSettings;
    private int fontSize;
    private String lastReasonsJson;
    private List<SnippetAiResponseSupport.SecurityChange> lastChanges;
    private Map<Integer, int[]> lastReasonRanges = Map.of();
    private Consumer<Decision> onDecision;
    private boolean disposed;

    /**
     * @param withDecisionBar whether to show the {@code [Review later] [Reject] [Accept & apply]}
     *     bar; a window host brings its own buttons and passes {@code false}.
     */
    public SnippetAiDiffPane(
        String summary,
        String originalText,
        String replacementText,
        String snippetLanguage,
        EditorSettingsHelper.Settings editorSettings,
        boolean withDecisionBar) {

        setId("snippet-ai-diff-pane");
        this.previewSettings = editorSettings != null ? editorSettings : EditorSettingsHelper.loadSnippetSettings();
        this.originalText = originalText != null ? originalText : "";
        this.replacementText = replacementText != null ? replacementText : "";
        this.fontSize = clampFontSize(loadPersistedFontSize(previewSettings.fontSize()));

        ScrollPane summaryBanner = buildSummaryBanner(summary);
        summaryContent = (Region) summaryBanner.getContent();
        findingFilterBar = buildFindingFilterBar();

        String detectedLanguage = SnippetLanguageSupport.detectSnippetLanguage(
            snippetLanguage,
            nonBlank(this.replacementText) ? this.replacementText : this.originalText);
        syntaxCombo = new ComboBox<>();
        syntaxCombo.getItems().addAll(HIGHLIGHT_LANGUAGES);
        syntaxCombo.setValue(HIGHLIGHT_LANGUAGES.contains(detectedLanguage) ? detectedLanguage : "plain");
        syntaxCombo.valueProperty().addListener((obs, oldValue, newValue) -> applyComparison());

        Button zoomOutButton = new Button(I18n.get("editor.zoomOut"));
        zoomOutButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomOut")));
        zoomOutButton.setOnAction(event -> changePreviewFontSize(-PREVIEW_FONT_STEP));
        Button zoomInButton = new Button(I18n.get("editor.zoomIn"));
        zoomInButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomIn")));
        zoomInButton.setOnAction(event -> changePreviewFontSize(PREVIEW_FONT_STEP));
        fontSizeLabel = new Label();
        updateFontSizeLabel();

        Button copyButton = new Button("⧉");
        copyButton.setTooltip(new Tooltip(I18n.get("snippets.copyClipboard")));
        copyButton.setOnAction(event -> copyReplacementText());

        Region spacer = new Region();
        toolbar = new HBox(
            8,
            new Label(I18n.get("snippets.ai.diff.syntax")),
            syntaxCombo,
            spacer,
            zoomOutButton,
            fontSizeLabel,
            zoomInButton,
            copyButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(spacer, Priority.ALWAYS);

        diffPane = new MonacoDiffPane();
        diffPane.setThemeColors(previewSettings.foregroundColor(), previewSettings.backgroundColor());
        diffPane.setStyle("-fx-background-color: " + previewSettings.backgroundColor() + ";");
        applyFont();
        applyComparison();

        explanationsView = new WebView();
        explanationsView.setContextMenuEnabled(false);
        explanationsView.setManaged(false);
        explanationsView.setVisible(false);
        explanationsView.setPrefHeight(132);
        explanationsView.setMinHeight(60);
        explanationsView.setMaxHeight(210);

        VBox reviewArea = new VBox(10, toolbar, diffPane, findingFilterBar, explanationsView);
        VBox.setVgrow(diffPane, Priority.ALWAYS);
        // Split so the summary's share of the pane is the reviewer's choice: a staged apply writes
        // one paragraph per stage, a single-stage fix one line.
        summarySplit = new SplitPane(summaryBanner, reviewArea);
        summarySplit.setOrientation(Orientation.VERTICAL);
        summarySplit.setDividerPositions(SUMMARY_INITIAL_SPLIT);
        SplitPane.setResizableWithParent(summaryBanner, Boolean.FALSE);
        summarySplit.setStyle("-fx-background-color: transparent; -fx-padding: 0;");

        setSpacing(10);
        setPadding(new Insets(14));
        getChildren().add(summarySplit);
        VBox.setVgrow(summarySplit, Priority.ALWAYS);

        blockingNotice.setId("snippet-ai-diff-blocking-notice");
        blockingNotice.setWrapText(true);
        blockingNotice.setMaxWidth(Double.MAX_VALUE);
        blockingNotice.setStyle("-fx-background-color: rgba(245,158,11,0.16); -fx-background-radius: 6;"
            + " -fx-padding: 8 10 8 10;");
        blockingActionButton.setId("snippet-ai-diff-blocking-action");
        HBox.setHgrow(blockingNotice, Priority.ALWAYS);
        blockingNoticeRow.getChildren().setAll(blockingNotice, blockingActionButton);
        blockingNoticeRow.setAlignment(Pos.CENTER_LEFT);
        setVisibleManaged(blockingActionButton, false);
        setVisibleManaged(blockingNoticeRow, false);

        if (withDecisionBar) {
            Button laterButton = new Button(I18n.get("snippets.ai.diff.decision.later"));
            laterButton.setId("snippet-ai-diff-later");
            laterButton.setOnAction(event -> decide(Decision.REVIEW_LATER));
            Button rejectButton = new Button(I18n.get("snippets.ai.diff.decision.reject"));
            rejectButton.setId("snippet-ai-diff-reject");
            rejectButton.setOnAction(event -> decide(Decision.REJECT));
            acceptButton = new Button(I18n.get("snippets.ai.diff.decision.accept"));
            acceptButton.setId("snippet-ai-diff-accept");
            acceptButton.setDefaultButton(false);
            acceptButton.setOnAction(event -> decide(Decision.ACCEPT));
            Region decisionSpacer = new Region();
            HBox.setHgrow(decisionSpacer, Priority.ALWAYS);
            HBox decisionBar = new HBox(8, decisionSpacer, laterButton, rejectButton, acceptButton);
            decisionBar.setId("snippet-ai-diff-decision-bar");
            decisionBar.setAlignment(Pos.CENTER_RIGHT);
            getChildren().addAll(blockingNoticeRow, decisionBar);
            addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboardShortcut);
        } else {
            acceptButton = null;
            getChildren().add(blockingNoticeRow);
        }
    }

    /** Receives the decision-bar choice. Only called when the pane was built with the bar. */
    public void setOnDecision(Consumer<Decision> onDecision) {
        this.onDecision = onDecision;
    }

    /**
     * Explains why the change can no longer be accepted as it stands (typically: the content changed
     * since the run started) and disables Accept; {@code null} or blank clears the notice again.
     */
    public void showBlockingNotice(String message) {
        showBlockingNotice(message, null, null);
    }

    /**
     * Like {@link #showBlockingNotice(String)}, with a way forward next to the notice (e.g. "re-plan
     * on the current content"); a {@code null} action shows the notice alone.
     */
    public void showBlockingNotice(String message, String actionLabel, Runnable action) {
        boolean visible = message != null && !message.isBlank();
        blockingNotice.setText(visible ? message : "");
        setVisibleManaged(blockingNoticeRow, visible);
        boolean withAction = visible && action != null && actionLabel != null && !actionLabel.isBlank();
        blockingActionButton.setText(withAction ? actionLabel : "");
        blockingActionButton.setOnAction(withAction ? event -> action.run() : null);
        setVisibleManaged(blockingActionButton, withAction);
        if (acceptButton != null) {
            acceptButton.setDisable(visible);
        }
    }

    /** Whether a blocking notice currently prevents Accept. */
    public boolean isAcceptBlocked() {
        return blockingNoticeRow.isVisible();
    }

    public String originalText() {
        return originalText;
    }

    public String replacementText() {
        return replacementText;
    }

    private void decide(Decision decision) {
        if (decision == Decision.ACCEPT && isAcceptBlocked()) {
            return;
        }
        Consumer<Decision> handler = onDecision;
        if (handler != null) {
            handler.accept(decision);
        }
    }

    /**
     * Starts the divider where the reviewer last put it. Without a stored position it follows the
     * summary's own height instead of a fixed share: a one-line security-fix summary gets a strip, a
     * staged apply's multi-paragraph summary gets up to a third of the pane and scrolls beyond
     * that. Dragging it afterwards is what makes the position stored.
     */
    void fitSummaryHeight() {
        double available = summarySplit.getHeight();
        if (available <= 0) {
            return;
        }
        Double stored = storedSummaryDividerPosition();
        double position;
        if (stored != null) {
            position = Math.min(Math.max(stored, 0.02), 0.9);
        } else {
            double width = summaryContent.getWidth() > 0 ? summaryContent.getWidth() : getWidth();
            double needed = summaryContent.prefHeight(width) + 4;
            position = Math.min(Math.max(needed, SUMMARY_MIN_HEIGHT), available * SUMMARY_MAX_SHARE) / available;
        }
        summarySplit.setDividerPositions(position);
        appliedSummaryDivider = position;
    }

    private static Double storedSummaryDividerPosition() {
        try {
            GlobalSettings settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            return settings != null ? settings.getAiDiffDialogSummaryDividerPosition() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * The summary divider position to store, but only once the reviewer moved it themselves, so a
     * pane that was merely resized keeps following the summary's height on the next review.
     */
    Double movedSummaryDividerPosition() {
        double[] positions = summarySplit.getDividerPositions();
        return positions.length == 0
            ? null
            : movedDividerPosition(positions[0], appliedSummaryDivider);
    }

    /**
     * The divider position to store: the current one when the reviewer moved it away from what this
     * pane applied on opening, otherwise {@code null} so the content fit keeps applying. The
     * tolerance absorbs the rounding a layout pass introduces without any user interaction.
     */
    static Double movedDividerPosition(double current, Double applied) {
        if (applied == null) {
            return null;
        }
        return Math.abs(current - applied) > 0.01 ? current : null;
    }

    /**
     * Attaches per-change explanations (security-fix flow): hover annotations on the modified side plus
     * a themed HTML card panel below the diff, so the "why" is understandable even if a hover anchor
     * does not match.
     */
    public void setChangeExplanations(List<SnippetAiResponseSupport.SecurityChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        lastChanges = changes;
        lastReasonsJson = toReasonsJson(changes);
        populateFindingFilter(changes);
        // The diff host reports each reason's resolved line range (modified side) once Monaco has
        // computed the diff; re-render the cards so they carry a "Lines 23-40" chip.
        diffPane.setChangeReasonRangesHandler(this::applyReasonRanges);
        diffPane.setChangeReasons(lastReasonsJson);
        renderExplanations();
    }

    /**
     * Enables the transient AI-profile picker and a re-run button so this adjustment can be repeated
     * with a different profile. No-op when {@code onRerun} is {@code null}.
     *
     * @param beforeRerun runs before {@code onRerun} (a window host closes itself); may be {@code null}
     */
    public void setRerunHandler(String activeProfileId, Consumer<String> onRerun, Runnable beforeRerun) {
        if (onRerun == null) {
            return;
        }
        ComboBox<SnippetAiDialogSupport.ProfileChoice> profileCombo =
            SnippetAiDialogSupport.buildProfileCombo(activeProfileId);
        Button rerunButton = SnippetAiDialogSupport.buildRerunButton(
            () -> SnippetAiDialogSupport.selectedProfileId(profileCombo), onRerun, beforeRerun);
        toolbar.getChildren().addAll(0, List.of(
            SnippetAiDialogSupport.profileLabel(), profileCombo, rerunButton));
    }

    /** Whether the side-by-side diff editor has booted (tests; a human needs longer anyway). */
    boolean isDiffReady() {
        return diffPane.isReady();
    }

    /** Releases the diff editor and the explanations page. Safe to call more than once. */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        diffPane.dispose();
        explanationsView.getEngine().loadContent("");
    }

    private void populateFindingFilter(List<SnippetAiResponseSupport.SecurityChange> changes) {
        List<String> choices = findingFilterChoices(changes);
        if (choices.size() < 2) {
            return;
        }
        String allLabel = I18n.get("snippets.ai.diff.focus.all");
        findingFilterCombo.getItems().setAll(allLabel);
        findingFilterCombo.getItems().addAll(choices);
        findingFilterCombo.setValue(allLabel);
        findingFilterBar.setManaged(true);
        findingFilterBar.setVisible(true);
    }

    private void renderExplanations() {
        if (lastChanges == null) {
            return;
        }
        String html = buildExplanationsHtml(lastChanges, lastReasonRanges);
        if (html == null) {
            return;
        }
        explanationsView.getEngine().loadContent(html);
        explanationsView.setManaged(true);
        explanationsView.setVisible(true);
    }

    /** Consumes the {@code [{idx,start,end}]} ranges reported by the diff host (see MonacoDiffPane). */
    private void applyReasonRanges(String rangesJson) {
        Map<Integer, int[]> ranges = new HashMap<>();
        try {
            JsonElement parsed = JsonParser.parseString(rangesJson != null ? rangesJson : "[]");
            if (parsed.isJsonArray()) {
                for (JsonElement element : parsed.getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject object = element.getAsJsonObject();
                    if (object.has("idx") && object.has("start") && object.has("end")) {
                        ranges.put(object.get("idx").getAsInt(),
                            new int[]{object.get("start").getAsInt(), object.get("end").getAsInt()});
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return; // Malformed range report: keep the cards without line chips.
        }
        if (Objects.equals(rangesToKey(ranges), rangesToKey(lastReasonRanges))) {
            return; // Monaco re-applies decorations on every diff update; avoid redundant reloads.
        }
        lastReasonRanges = ranges;
        renderExplanations();
    }

    private static String rangesToKey(Map<Integer, int[]> ranges) {
        StringBuilder key = new StringBuilder();
        ranges.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> key.append(entry.getKey()).append(':')
                .append(entry.getValue()[0]).append('-').append(entry.getValue()[1]).append(';'));
        return key.toString();
    }

    /**
     * The summary of a staged apply concatenates one paragraph per stage and can outgrow the pane,
     * so it scrolls inside its own pane and the split divider below it lets the reviewer trade its
     * height against the diff.
     */
    private ScrollPane buildSummaryBanner(String summary) {
        Region accentBar = new Region();
        accentBar.setMinWidth(3);
        accentBar.setStyle("-fx-background-color: " + ACCENT + "; -fx-background-radius: 3;");

        Label summaryLabel = new Label(summary != null && !summary.isBlank()
            ? summary
            : I18n.get("snippets.ai.diff.summary.empty"));
        summaryLabel.setWrapText(true);
        summaryLabel.setStyle("-fx-font-size: 0.9615em;");
        HBox.setHgrow(summaryLabel, Priority.ALWAYS);

        HBox banner = new HBox(10, accentBar, summaryLabel);
        banner.setAlignment(Pos.TOP_LEFT);
        banner.setStyle("-fx-background-color: rgba(127,127,127,0.09); -fx-background-radius: 8; -fx-padding: 10 12 10 12;");

        ScrollPane scroll = new ScrollPane(banner);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setMinHeight(SUMMARY_MIN_HEIGHT);
        scroll.setPrefHeight(SUMMARY_PREF_HEIGHT);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return scroll;
    }

    /**
     * Lets the reviewer narrow the diff annotations to one finding. Hidden while fewer than two
     * findings carry a reason, where filtering could only ever hide work.
     */
    private HBox buildFindingFilterBar() {
        findingFilterCombo = new ComboBox<>();
        findingFilterCombo.setVisibleRowCount(12);
        findingFilterCombo.valueProperty().addListener((obs, oldValue, newValue) -> applyFindingFilter(newValue));

        previousFindingButton = new Button("◀");
        previousFindingButton.setTooltip(new Tooltip(I18n.get("snippets.ai.diff.focus.previous")));
        previousFindingButton.setOnAction(event -> stepFinding(-1));
        nextFindingButton = new Button("▶");
        nextFindingButton.setTooltip(new Tooltip(I18n.get("snippets.ai.diff.focus.next")));
        nextFindingButton.setOnAction(event -> stepFinding(1));

        HBox bar = new HBox(8,
            new Label(I18n.get("snippets.ai.diff.focus")),
            findingFilterCombo,
            previousFindingButton,
            nextFindingButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setManaged(false);
        bar.setVisible(false);
        return bar;
    }

    /**
     * Walks the picker one finding at a time, which re-filters the diff and scrolls to that
     * finding's first place. Also the way out of "all changes": from there the arrows step to the
     * first or last finding.
     */
    private void stepFinding(int step) {
        int next = steppedFindingIndex(
            findingFilterCombo.getSelectionModel().getSelectedIndex(),
            findingFilterCombo.getItems().size(),
            step);
        if (next >= 0 && next < findingFilterCombo.getItems().size()) {
            findingFilterCombo.getSelectionModel().select(next);
        }
    }

    /**
     * The picker index the arrows move to. Item 0 is "all changes" and is skipped: stepping forward
     * from it selects the first finding, backward the last, and the ends wrap into each other.
     * Returns {@code currentIndex} when there is no finding to move to.
     */
    static int steppedFindingIndex(int currentIndex, int itemCount, int step) {
        int findings = itemCount - 1;
        if (findings <= 0) {
            return currentIndex;
        }
        int position = currentIndex <= 0 ? (step > 0 ? -1 : 0) : currentIndex - 1;
        return Math.floorMod(position + (step > 0 ? 1 : -1), findings) + 1;
    }

    /** Distinct finding ids that actually carry a reason, in the order the model reported them. */
    static List<String> findingFilterChoices(List<SnippetAiResponseSupport.SecurityChange> changes) {
        if (changes == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (SnippetAiResponseSupport.SecurityChange change : changes) {
            if (change == null || change.reason().isBlank()) {
                continue;
            }
            String finding = change.finding().trim();
            if (!finding.isEmpty() && !ids.contains(finding)) {
                ids.add(finding);
            }
        }
        return List.copyOf(ids);
    }

    /** A blank or absent filter keeps every change; otherwise the finding id must match exactly. */
    static boolean matchesFindingFilter(SnippetAiResponseSupport.SecurityChange change, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return change != null && change.finding().trim().equals(filter.trim());
    }

    private void applyFindingFilter(String selected) {
        // Index 0 is the "all changes" entry; every later item is a finding id. Going by position
        // rather than by label keeps a finding that happens to read like the label out of the way.
        boolean showAll = selected == null || findingFilterCombo.getSelectionModel().getSelectedIndex() <= 0;
        activeFindingFilter = showAll ? null : selected;
        diffPane.setReasonFilter(activeFindingFilter);
        renderExplanations();
    }

    private void applyComparison() {
        String language = syntaxCombo.getValue();
        diffPane.setComparison(originalText, replacementText, language, language);
        // setComparison clears prior decorations, so re-apply any reasons after re-highlighting.
        if (lastReasonsJson != null) {
            diffPane.setChangeReasons(lastReasonsJson);
        }
    }

    private void applyFont() {
        diffPane.setFont(previewSettings.fontFamily(), fontSize);
    }

    /**
     * Zoom shortcuts, plus Shortcut+Enter for Accept when the decision bar is shown. A window host
     * installs this on its own root so the shortcuts also work while its buttons have focus.
     */
    void handleKeyboardShortcut(KeyEvent event) {
        if (!event.isShortcutDown() && !event.isControlDown()) {
            return;
        }
        KeyCode code = event.getCode();
        if (code == KeyCode.PLUS || code == KeyCode.ADD || code == KeyCode.EQUALS) {
            changePreviewFontSize(PREVIEW_FONT_STEP);
            event.consume();
        } else if (code == KeyCode.MINUS || code == KeyCode.SUBTRACT) {
            changePreviewFontSize(-PREVIEW_FONT_STEP);
            event.consume();
        } else if (code == KeyCode.ENTER && acceptButton != null && event.isShortcutDown()) {
            decide(Decision.ACCEPT);
            event.consume();
        }
    }

    private void changePreviewFontSize(int delta) {
        int next = clampFontSize(fontSize + delta);
        if (next == fontSize) {
            updateFontSizeLabel();
            return;
        }
        fontSize = next;
        applyFont();
        persistFontSize();
        updateFontSizeLabel();
    }

    private void updateFontSizeLabel() {
        if (fontSizeLabel != null) {
            fontSizeLabel.setText(fontSize + "pt");
        }
    }

    private void copyReplacementText() {
        de.kortty.core.KorttyClipboard.setText(replacementText);
    }

    // ---- Explanations panel (themed HTML) -------------------------------------------------------

    private String buildExplanationsHtml(
        List<SnippetAiResponseSupport.SecurityChange> changes,
        Map<Integer, int[]> reasonRanges) {

        StringBuilder items = new StringBuilder();
        int count = 0;
        for (int index = 0; index < changes.size(); index++) {
            SnippetAiResponseSupport.SecurityChange change = changes.get(index);
            if (change == null || change.reason().isBlank()
                || !matchesFindingFilter(change, activeFindingFilter)) {
                continue;
            }
            count++;
            String category = SnippetAiDialogSupport.categoryForFindingId(change.finding());
            String color = category != null ? SnippetAiDialogSupport.sectionColor(category) : ACCENT;
            String badge = !change.finding().isBlank() ? escapeHtml(change.finding()) : "•";
            items.append("<div class=\"item\" style=\"border-left-color:").append(color).append(";\">")
                .append("<span class=\"badge\" style=\"background:").append(color).append(";\">")
                .append(badge).append("</span>");
            if (category != null) {
                // Category glyph (shield/bolt/drop/box) so the section is recognizable at a glance,
                // matching the icons in the analysis window's section titles.
                items.append("<span class=\"cat\" style=\"color:").append(color).append(";\">")
                    .append(SnippetAiDialogSupport.sectionIconSvg(category)).append("</span>");
            }
            int[] range = reasonRanges.get(index);
            if (range != null) {
                items.append("<span class=\"lines\">").append(escapeHtml(formatLineRange(range[0], range[1])))
                    .append("</span>");
            }
            items.append("<span class=\"reason\">").append(escapeHtml(change.reason())).append("</span></div>");
        }
        if (count == 0) {
            return null;
        }

        ThemeCssSupport.ThemeColors colors = resolveThemeColors();
        String background = colors != null ? colors.backgroundColor() : FALLBACK_BG;
        String foreground = colors != null ? colors.foregroundColor() : FALLBACK_FG;
        String header = escapeHtml(I18n.get("snippets.ai.diff.reasons.title"));

        return "<!doctype html><html><head><meta charset=\"UTF-8\"><style>"
            + ":root{color-scheme:dark;}*{box-sizing:border-box;}"
            + "body{margin:0;padding:12px 14px;background:" + background + ";color:" + foreground + ";"
            + "font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:12.5px;line-height:1.45;}"
            + ".head{font-weight:700;opacity:.9;margin:0 0 8px;display:flex;align-items:center;gap:7px;}"
            + ".head .dot{width:7px;height:7px;border-radius:50%;background:" + ACCENT + ";display:inline-block;}"
            + ".item{display:flex;gap:9px;align-items:flex-start;padding:8px 10px;margin-bottom:7px;border-radius:8px;"
            + "background:rgba(127,127,127,0.08);border-left:3px solid " + ACCENT + ";}"
            + ".badge{flex:0 0 auto;font-family:'SF Mono',Menlo,Consolas,monospace;font-weight:700;font-size:0.82em;"
            + "padding:1px 8px;border-radius:6px;background:" + ACCENT + ";color:#fff;}"
            + ".cat{flex:0 0 auto;line-height:1;}"
            + ".cat .sec-ic{width:1.05em;height:1.05em;fill:currentColor;vertical-align:-.12em;}"
            + ".lines{flex:0 0 auto;font-family:'SF Mono',Menlo,Consolas,monospace;font-size:0.82em;opacity:.65;"
            + "white-space:nowrap;padding-top:1px;}"
            + ".reason{opacity:.96;}"
            + "</style></head><body>"
            + "<div class=\"head\"><span class=\"dot\"></span>" + header + "</div>"
            + items
            + "</body></html>";
    }

    /** "Line 23" for a single line, "Lines 23-40" for a block (modified/right side of the diff). */
    private static String formatLineRange(int start, int end) {
        if (end <= start) {
            return I18n.get("common.line") + " " + start;
        }
        return I18n.get("snippets.ai.diff.reasons.lines", start, end);
    }

    private ThemeCssSupport.ThemeColors resolveThemeColors() {
        try {
            return ThemeCssSupport.resolveThemeColors(KorTTYApplication.getInstance());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String toReasonsJson(List<SnippetAiResponseSupport.SecurityChange> changes) {
        JsonArray array = new JsonArray();
        for (int index = 0; index < changes.size(); index++) {
            SnippetAiResponseSupport.SecurityChange change = changes.get(index);
            if (change == null) {
                continue;
            }
            JsonObject object = new JsonObject();
            // The list index keys the range report back to its explanation card (see applyReasonRanges).
            object.addProperty("idx", index);
            object.addProperty("finding", change.finding());
            object.addProperty("anchor", change.anchor());
            object.addProperty("reason", change.reason());
            array.add(object);
        }
        return array.toString();
    }

    private static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static int clampFontSize(int size) {
        return Math.max(MIN_PREVIEW_FONT_SIZE, Math.min(MAX_PREVIEW_FONT_SIZE, size));
    }

    private static int loadPersistedFontSize(int fallback) {
        try {
            GlobalSettings settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            if (settings != null && settings.getAiDiffFontSize() != null) {
                return settings.getAiDiffFontSize();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private void persistFontSize() {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setAiDiffFontSize(fontSize);
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }

    private static void setVisibleManaged(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static boolean nonBlank(String text) {
        return text != null && !text.isBlank();
    }
}
